package com.limber.asistente

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Puente de Alexa dentro de Monki (antes corría en la Mac con alexa-remote2): órdenes de texto al Echo, qué suena,
 * controles, volumen y alarmas, con la API web de Alexa. Usa la sesión de Amazon copiada a la consola
 * (herramientas/alexa-a-consola.py) y la renueva sola con el refresh token cuando vence.
 */
class AlexaClient(private val prefs: Prefs) {

    val configured get() = prefs.alexaCookie.isNotBlank() && prefs.alexaCsrf.isNotBlank()

    class Device(val serial: String, val type: String, val name: String, val customerId: String, val family: String, val online: Boolean)

    private var devicesCache: List<Device> = emptyList()
    private var devicesAt = 0L
    /** Último volumen puesto en cada Echo: /api/media/state tarda en reflejarlo. */
    private val volumes = mutableMapOf<String, Pair<Int, Long>>()

    // ---------- HTTP ----------

    private fun request(path: String, method: String = "GET", body: String? = null, retry: Boolean = true): String {
        val conn = (URL("https://$HOST$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 8_000; readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json; charset=utf-8")
            setRequestProperty("Accept-Language", "es-MX")
            setRequestProperty("Referer", "https://alexa.$PAGE/spa/index.html")
            setRequestProperty("Origin", "https://alexa.$PAGE")
            setRequestProperty("csrf", prefs.alexaCsrf)
            setRequestProperty("Cookie", prefs.alexaCookie)
            setRequestProperty("Accept-Encoding", "gzip")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        body?.let { b -> conn.outputStream.use { it.write(b.toByteArray()) } }
        val code = conn.responseCode
        val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
        val text = stream?.let { s -> (if (conn.contentEncoding == "gzip") GZIPInputStream(s) else s).bufferedReader().use { it.readText() } } ?: ""
        conn.disconnect()
        if ((code == 401 || code == 403) && retry && refreshSession()) return request(path, method, body, retry = false)
        if (code !in 200..299) {
            Log.w("Asistente", "Alexa $method $path → $code ${text.take(150)}")
            throw RuntimeException("Alexa respondió $code")
        }
        return text.ifBlank { "{}" }
    }

    /**
     * Renueva la sesión: cambia el refresh token por cookies nuevas de amazon.com.mx y pide el csrf.
     * Mismo proceso que alexa-cookie2 (refreshAlexaCookie → exchangetoken/cookies).
     */
    @Synchronized
    fun refreshSession(): Boolean = runCatching {
        if (prefs.alexaRefresh.isBlank()) return false
        val params = mapOf(
            "di.os.name" to "iOS", "app_version" to "2.2.595606.0", "domain" to ".$PAGE", "source_token" to prefs.alexaRefresh,
            "requested_token_type" to "auth_cookies", "source_token_type" to "refresh_token", "di.hw.version" to "iPhone",
            "di.sdk.version" to "6.12.4", "app_name" to prefs.alexaAppName.ifBlank { "ioBroker Alexa2" }, "di.os.version" to "16.6",
        ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL("https://www.$PAGE/ap/exchangetoken/cookies").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept-Language", "es-MX")
            setRequestProperty("x-amzn-identity-auth-domain", "api.$PAGE")
            if (prefs.alexaLoginCookie.isNotBlank()) setRequestProperty("Cookie", prefs.alexaLoginCookie)
        }
        conn.outputStream.use { it.write(params.toByteArray()) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val cookies = JSONObject(body).getJSONObject("response").getJSONObject("tokens").getJSONObject("cookies")
        val list = cookies.optJSONArray(".$PAGE") ?: cookies.getJSONArray(cookies.keys().next())
        val cookie = (0 until list.length()).map { list.getJSONObject(it) }.joinToString("; ") { "${it.getString("Name")}=${it.getString("Value")}" }
        // El csrf llega como cookie al pedir cualquier página de la API
        val c2 = (URL("https://$HOST/api/language").openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Cookie", cookie)
        }
        c2.responseCode
        val csrf = c2.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
            .firstOrNull { it.startsWith("csrf=") }?.substringAfter("csrf=")?.substringBefore(";")
        c2.disconnect()
        prefs.alexaCookie = if (csrf != null) "$cookie; csrf=$csrf" else cookie
        if (csrf != null) prefs.alexaCsrf = csrf
        Log.i("Asistente", "Alexa: sesión renovada")
        true
    }.getOrElse { Log.w("Asistente", "Alexa: no pude renovar la sesión: ${it.message}"); false }

    // ---------- Equipos ----------

    fun devices(): List<Device> {
        if (devicesCache.isNotEmpty() && System.currentTimeMillis() - devicesAt < 10 * 60_000) return devicesCache
        val arr = JSONObject(request("/api/devices-v2/device?cached=true")).optJSONArray("devices") ?: JSONArray()
        devicesCache = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { d -> d.optJSONArray("capabilities")?.let { c -> (0 until c.length()).any { c.getString(it) == "VOLUME_SETTING" } } == true }
            .map { Device(it.optString("serialNumber"), it.optString("deviceType"), it.optString("accountName"),
                it.optString("deviceOwnerCustomerId"), it.optString("deviceFamily"), it.optBoolean("online")) }
        devicesAt = System.currentTimeMillis()
        return devicesCache
    }

    /** El Echo pedido o, si no dice, el principal (o un Echo en línea; nunca un grupo ni la app del celular). */
    fun pick(name: String? = null): Device? {
        val all = devices()
        if (!name.isNullOrBlank()) {
            val n = name.lowercase()
            all.firstOrNull { it.name.equals(name, true) }?.let { return it }
            all.firstOrNull { it.name.lowercase().contains(n) }?.let { return it }
        }
        return all.firstOrNull { it.online && it.name.equals(prefs.alexaPrincipal, true) }
            ?: all.firstOrNull { it.online && it.family == "ECHO" } ?: all.firstOrNull { it.online } ?: all.firstOrNull()
    }

    // ---------- Órdenes ----------

    private fun sequence(d: Device, type: String, skill: String, payload: JSONObject) {
        payload.put("deviceType", d.type).put("deviceSerialNumber", d.serial).put("locale", "es-MX").put("customerId", d.customerId)
        val node = JSONObject().put("@type", "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode")
            .put("type", type).put("skillId", skill).put("operationPayload", payload)
        val seq = JSONObject().put("@type", "com.amazon.alexa.behaviors.model.Sequence").put("startNode", node)
        request("/api/behaviors/preview", "POST",
            JSONObject().put("behaviorId", "PREVIEW").put("sequenceJson", seq.toString()).put("status", "ENABLED").toString())
    }

    /** Le dice al Echo un texto como si le hablaras. Devuelve el nombre del Echo. */
    fun textCommand(text: String, name: String? = null): String {
        val d = pick(name) ?: throw RuntimeException("No encontré ningún Echo en la cuenta.")
        sequence(d, "Alexa.TextCommand", "amzn1.ask.1p.tellalexa", JSONObject().put("text", text.lowercase()))
        return d.name
    }

    fun speak(text: String, name: String? = null): String {
        val d = pick(name) ?: throw RuntimeException("No encontré ningún Echo en la cuenta.")
        sequence(d, "Alexa.Speak", "amzn1.ask.1p.saysomething", JSONObject().put("textToSpeak", text))
        return d.name
    }

    fun setVolume(level: Int, name: String? = null): String {
        val d = pick(name) ?: throw RuntimeException("No encontré ningún Echo en la cuenta.")
        sequence(d, "Alexa.DeviceControls.Volume", "amzn1.ask.1p.alexadevicecontrols", JSONObject().put("value", level.coerceIn(0, 100)))
        volumes[d.serial] = level.coerceIn(0, 100) to System.currentTimeMillis()
        return d.name
    }

    /** Sube o baja el volumen (cambio en puntos) del Echo que suena o del pedido. Devuelve el nivel nuevo. */
    fun changeVolume(change: Int, name: String? = null): Int {
        val d = (if (name.isNullOrBlank()) nowPlaying()?.optString("dispositivo") else name)?.let { pick(it) } ?: pick()
            ?: throw RuntimeException("No encontré ningún Echo en la cuenta.")
        val recent = volumes[d.serial]?.takeIf { System.currentTimeMillis() - it.second < 60_000 }?.first
        val now = recent ?: runCatching {
            JSONObject(request("/api/media/state?deviceSerialNumber=${d.serial}&deviceType=${d.type}")).optInt("volume", 30)
        }.getOrDefault(30)
        val level = (now + change).coerceIn(0, 100)
        setVolume(level, d.name)
        return level
    }

    // ---------- Qué suena ----------

    private fun infoOf(d: Device): JSONObject? {
        val r = JSONObject(request("/api/np/list-media-sessions?deviceSerialNumber=${d.serial}&deviceType=${d.type}"))
        val list = r.optJSONArray("mediaSessionList") ?: return null
        val s = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optJSONObject("nowPlayingData")?.optJSONObject("infoText") != null }
            ?: return null
        val p = s.getJSONObject("nowPlayingData")
        val info = p.getJSONObject("infoText")
        val art = p.optJSONObject("mainArt") ?: JSONObject()
        val listaId = runCatching { JSONObject(p.optJSONObject("mediaReference")?.optString("value") ?: "{}").optString("contentId") }.getOrDefault("")
        return JSONObject()
            .put("dispositivo", d.name)
            .put("estado", when (p.optString("playerState")) { "PLAYING" -> "sonando"; "PAUSED" -> "pausa"; else -> p.optString("playerState").lowercase() })
            .put("titulo", info.optString("title")).put("artista", info.optString("subText1")).put("album", info.optString("subText2"))
            .put("portada", art.optString("largeUrl").ifBlank { art.optString("mediumUrl") }.ifBlank { art.optString("fullUrl") })
            .put("app", p.optJSONObject("provider")?.optString("providerName").orEmpty())
            .put("progreso", p.optJSONObject("progress")?.optLong("mediaProgress") ?: 0)
            .put("duracion", p.optJSONObject("progress")?.optLong("mediaLength") ?: 0)
            .put("lista", listaId.ifBlank { p.optString("queueId") })
            .put("cancion", p.optString("mediaId"))
    }

    /** Lo que suena en el Echo pedido o en el primero que esté sonando (empezando por el principal). */
    fun nowPlaying(name: String? = null): JSONObject? {
        if (!name.isNullOrBlank()) return pick(name)?.let { infoOf(it) }
        val all = devices().filter { it.online }.sortedByDescending { it.name.equals(prefs.alexaPrincipal, true) }
        var paused: JSONObject? = null
        for (d in all) {
            val i = runCatching { infoOf(d) }.getOrNull() ?: continue
            if (i.optString("estado") == "sonando") return i
            if (paused == null) paused = i
        }
        return paused
    }

    /** play, pausa, siguiente, anterior: como orden de texto (la API de comandos no controla las sesiones de Spotify). */
    fun control(action: String, name: String? = null): String {
        val text = mapOf("play" to "continúa la música", "pausa" to "pausa", "siguiente" to "siguiente canción", "anterior" to "canción anterior")[action]
            ?: throw RuntimeException("Acción no válida: play, pausa, siguiente o anterior.")
        return textCommand(text, name ?: nowPlaying()?.optString("dispositivo"))
    }

    /** Salta al segundo pedido de la canción que suena (SeekCommand va en SEGUNDOS). */
    fun seek(second: Int, name: String? = null) {
        val target = name ?: nowPlaying()?.optString("dispositivo")
        val d = pick(target) ?: throw RuntimeException("No hay música sonando.")
        request("/api/np/command?deviceSerialNumber=${d.serial}&deviceType=${d.type}", "POST",
            JSONObject().put("type", "SeekCommand").put("mediaPosition", second.coerceAtLeast(0)).put("contentFocusClientId", JSONObject.NULL).toString())
    }

    // ---------- Alarmas ----------

    private fun notifications(): List<JSONObject> {
        val arr = JSONObject(request("/api/notifications?cached=false")).optJSONArray("notifications") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Alarmas activas (también las de música), la más próxima primero, con la misma forma que alexa_alarmas. */
    fun alarms(): JSONArray {
        val names = devices().associate { it.serial to it.name }
        return JSONArray(notifications().filter { Regex("alarm", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("type")) && it.optString("status") == "ON" }
            .sortedBy { it.optLong("alarmTime") }
            .map {
                JSONObject().put("id", it.optString("notificationIndex").ifBlank { it.optString("id") })
                    .put("dispositivo", names[it.optString("deviceSerialNumber")] ?: it.optString("deviceSerialNumber"))
                    .put("fecha", it.optString("originalDate")).put("hora", it.optString("originalTime").take(5))
                    .put("musica", it.optString("musicEntity").takeIf { s -> s != "null" }.orEmpty()).put("tipo", it.optString("type"))
            })
    }

    /** Alarma en el Echo (fecha AAAA-MM-DD, hora HH:MM); con música, MusicAlarm de Spotify en formato clásico. */
    fun createAlarm(date: String, time: String, music: String, name: String? = null): String {
        val d = pick(name) ?: throw RuntimeException("No encontré ningún Echo en la cuenta.")
        require(Regex("""\d{4}-\d{2}-\d{2}""").matches(date) && Regex("""\d{1,2}:\d{2}""").matches(time)) { "Fecha u hora inválida." }
        val (h, m) = time.split(":").map { it.toInt() }
        val hhmm = "%02d:%02d".format(h, m)
        val whenMs = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse("$date $hhmm")!!.time
        val n = JSONObject()
            .put("type", if (music.isBlank()) "Alarm" else "MusicAlarm").put("status", "ON")
            .put("alarmTime", whenMs).put("createdDate", System.currentTimeMillis())
            .put("deviceSerialNumber", d.serial).put("deviceType", d.type)
            .put("originalDate", date).put("originalTime", "$hhmm:00.000")
            .put("id", JSONObject.NULL).put("isRecurring", false).put("recurringPattern", JSONObject.NULL)
            .put("reminderLabel", JSONObject.NULL).put("sound", JSONObject.NULL).put("isSaveInFlight", true)
            .put("timeZoneId", JSONObject.NULL).put("reminderIndex", JSONObject.NULL)
        if (music.isNotBlank()) n.put("musicEntity", music).put("provider", "Spotify").put("musicAlarmId", JSONObject.NULL)
        request("/api/notifications/null", "PUT", n.toString())
        return "Alarma puesta en ${d.name} el $date a las $hhmm" + if (music.isNotBlank()) " con $music en Spotify." else "."
    }

    fun deleteAlarm(id: String): String {
        val n = notifications().firstOrNull { (it.optString("notificationIndex").ifBlank { it.optString("id") }) == id }
            ?: throw RuntimeException("No encontré esa alarma.")
        request("/api/notifications/${URLEncoder.encode(n.getString("id"), "UTF-8")}", "DELETE", n.toString())
        return "Alarma borrada."
    }

    /**
     * Lo mismo que las herramientas alexa_* de casa, pero desde la consola. Devuelve el texto de la herramienta.
     * Se usa para el reproductor, el volumen, las alarmas del widget y el cambio de salida de sonido.
     */
    fun call(tool: String, a: JSONObject): String = when (tool) {
        "alexa_comando" -> "Alexa (${textCommand(a.optString("texto"), a.optString("dispositivo"))}) recibió: ${a.optString("texto")}"
        "alexa_decir" -> { speak(a.optString("texto"), a.optString("dispositivo")); "Lo dijo el Echo." }
        "alexa_dispositivos" -> JSONArray(devices().map { JSONObject().put("nombre", it.name).put("en_linea", it.online).put("tipo", it.family) }).toString()
        "alexa_reproduciendo" -> (nowPlaying(a.optString("dispositivo")) ?: JSONObject().put("estado", "nada")).toString()
        "alexa_control" -> { control(a.optString("accion"), a.optString("dispositivo").ifBlank { null }); "Listo: ${a.optString("accion")}." }
        "alexa_saltar" -> { seek(a.optInt("segundo"), a.optString("dispositivo").ifBlank { null }); "Listo, en el segundo ${a.optInt("segundo")}." }
        "alexa_volumen" -> "Volumen ${changeVolume(a.optInt("cambio"), a.optString("dispositivo").ifBlank { null })}."
        "alexa_alarma" -> createAlarm(a.optString("fecha"), a.optString("hora"), a.optString("musica"), a.optString("dispositivo").ifBlank { null })
        "alexa_alarmas" -> alarms().toString()
        "alexa_borrar_alarma" -> deleteAlarm(a.optString("id"))
        else -> throw RuntimeException("Herramienta desconocida: $tool")
    }

    /** Herramientas para el agente (mismos nombres y descripciones que las de casa). */
    fun tools(): List<Tool> {
        fun t(name: String, desc: String, vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
            Tool(name, desc, schema(*props, required = required)) { args ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ToolResult(runCatching { call(name, args) }.getOrElse { "Error: ${it.message}" })
                }
            }
        val dev = "dispositivo" to prop("string", "Echo por nombre; vacío = el principal")
        return listOf(
            t("alexa_comando", "Manda al Echo una orden como si le hablaras (música en Spotify, luces, rutinas…).",
                "texto" to prop("string", "la orden"), dev, required = listOf("texto")),
            t("alexa_decir", "Hace que un Echo diga un texto en voz alta.", "texto" to prop("string", ""), dev, required = listOf("texto")),
            t("alexa_dispositivos", "Lista los Echos de la cuenta y si están en línea."),
            t("alexa_reproduciendo", "Qué está sonando en los Echos (canción, artista, Spotify…), en JSON.", dev),
            t("alexa_control", "Controla la música del Echo que está sonando: play, pausa, siguiente o anterior.",
                "accion" to prop("string", "play | pausa | siguiente | anterior"), dev, required = listOf("accion")),
            t("alexa_saltar", "Mueve la canción que suena a ese segundo (adelantar o regresar).",
                "segundo" to prop("integer", ""), dev, required = listOf("segundo")),
            t("alexa_volumen", "Sube (cambio positivo) o baja (negativo) el volumen del Echo que está sonando.",
                "cambio" to prop("integer", "p. ej. 10 o -10"), dev, required = listOf("cambio")),
            t("alexa_alarma", "Pone una alarma en el Echo. fecha AAAA-MM-DD, hora HH:MM (24 h). musica: lo que debe sonar en Spotify; vacío = tono normal.",
                "fecha" to prop("string", ""), "hora" to prop("string", ""), "musica" to prop("string", ""), dev, required = listOf("fecha", "hora")),
            t("alexa_alarmas", "Alarmas activas de los Echo (JSON: id, dispositivo, fecha, hora, música), la más próxima primero."),
            t("alexa_borrar_alarma", "Borra una alarma del Echo por su id (de alexa_alarmas).", "id" to prop("string", ""), required = listOf("id")),
        )
    }

    companion object {
        private const val PAGE = "amazon.com.mx"
        private const val HOST = "pitangui.amazon.com"
        private const val USER_AGENT = "AppleWebKit PitanguiBridge/2.2.595606.0-[HARDWARE=iPhone14_7][SOFTWARE=17.4.1][DEVICE=iPhone]"
    }
}
