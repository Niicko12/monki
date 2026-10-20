package com.limber.asistente

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify Web API desde la consola (sin la Mac): pasar la reproducción entre el Echo y Spotify de la consola
 * (Spotify Connect). El permiso se da una vez con herramientas/spotify-autorizar.py (PKCE: sin client secret).
 */
class Spotify(private val prefs: Prefs) {
    private var access = ""
    private var expires = 0L

    val configured get() = prefs.spotifyClientId.isNotBlank() && prefs.spotifyRefresh.isNotBlank()

    class Device(val id: String, val name: String, val type: String, val active: Boolean)

    private fun token(): String {
        if (access.isNotBlank() && System.currentTimeMillis() < expires) return access
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(prefs.spotifyRefresh, "UTF-8") +
            "&client_id=" + URLEncoder.encode(prefs.spotifyClientId, "UTF-8")
        val r = request("https://accounts.spotify.com/api/token", "POST", body, "application/x-www-form-urlencoded", auth = false)
        val j = JSONObject(r)
        access = j.getString("access_token")
        expires = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        // Spotify a veces entrega un permiso nuevo y el anterior deja de servir
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.spotifyRefresh = it }
        return access
    }

    fun devices(): List<Device> {
        val arr = JSONObject(request("https://api.spotify.com/v1/me/player/devices", "GET")).optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Device(it.optString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active"))
        }
    }

    /** Pasa lo que está sonando a ese dispositivo (misma canción y posición) y sigue reproduciendo. */
    fun transfer(deviceId: String) {
        request("https://api.spotify.com/v1/me/player", "PUT", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", true).toString())
    }

    /**
     * Lo que suena ahora en cualquier equipo, con la misma forma que alexa_reproduciendo (para el reproductor de la
     * pantalla). null si no hay nada.
     */
    fun nowPlaying(): JSONObject? {
        val raw = request("https://api.spotify.com/v1/me/player?additional_types=track,episode", "GET")
        val j = JSONObject(raw)
        val item = j.optJSONObject("item") ?: return null
        val images = item.optJSONObject("album")?.optJSONArray("images") ?: item.optJSONArray("images") ?: JSONArray()
        val art = (0 until images.length()).map { images.getJSONObject(it) }.sortedBy { kotlin.math.abs(it.optInt("width") - 300) }
            .firstOrNull()?.optString("url").orEmpty()
        val artists = item.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }
            ?: item.optJSONObject("show")?.optString("name").orEmpty()
        val device = j.optJSONObject("device")
        return JSONObject()
            .put("estado", if (j.optBoolean("is_playing")) "sonando" else "pausa")
            .put("titulo", item.optString("name"))
            .put("artista", artists)
            .put("album", item.optJSONObject("album")?.optString("name").orEmpty())
            .put("portada", art)
            .put("progreso", j.optLong("progress_ms"))
            .put("duracion", item.optLong("duration_ms"))
            .put("dispositivo", device?.optString("name").orEmpty())
            .put("tipo_dispositivo", device?.optString("type").orEmpty())
            .put("volumen", device?.optInt("volume_percent", -1) ?: -1)
            .put("lista", j.optJSONObject("context")?.optString("uri").orEmpty())
            .put("cancion", item.optString("uri"))
            .put("fuente", "spotify")
    }

    /** Toca una canción dentro de su lista (sigue la lista después). */
    fun playInContext(context: String, track: String) {
        request("https://api.spotify.com/v1/me/player/play", "PUT",
            JSONObject().put("context_uri", context).put("offset", JSONObject().put("uri", track)).toString())
    }

    fun play() { request("https://api.spotify.com/v1/me/player/play", "PUT", "{}") }
    fun pause() { request("https://api.spotify.com/v1/me/player/pause", "PUT", "{}") }
    fun next() { request("https://api.spotify.com/v1/me/player/next", "POST", "{}") }
    fun previous() { request("https://api.spotify.com/v1/me/player/previous", "POST", "{}") }
    fun seek(ms: Long) { request("https://api.spotify.com/v1/me/player/seek?position_ms=$ms", "PUT", "{}") }
    fun volume(percent: Int) { request("https://api.spotify.com/v1/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}", "PUT", "{}") }

    companion object {
        /**
         * Canciones de una lista, álbum o artista ("spotify:playlist:ID"…) leyendo la página pública de Spotify para
         * insertar (no necesita permiso). Misma forma que spotify_lista de casa: {nombre, canciones:[{titulo, artista, duracion, uri}]}.
         */
        fun playlist(uri: String): JSONObject {
            val m = Regex("spotify:(playlist|album|artist|show):([A-Za-z0-9]{10,40})").matchEntire(uri.trim()) ?: throw RuntimeException("lista no válida")
            val conn = (URL("https://open.spotify.com/embed/${m.groupValues[1]}/${m.groupValues[2]}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 12_000; setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                ?: throw RuntimeException("Spotify no devolvió la lista")
            val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
                ?.optJSONObject("data")?.optJSONObject("entity") ?: JSONObject()
            val list = entity.optJSONArray("trackList") ?: JSONArray()
            val songs = JSONArray((0 until minOf(list.length(), 60)).map { list.getJSONObject(it) }.map {
                JSONObject().put("titulo", it.optString("title")).put("artista", it.optString("subtitle").replace('\u00a0', ' '))
                    .put("duracion", it.optLong("duration")).put("uri", it.optString("uri"))
            })
            return JSONObject().put("nombre", entity.optString("name").ifBlank { entity.optString("title") }).put("canciones", songs)
        }
    }

    private fun request(url: String, method: String, body: String? = null, type: String = "application/json", auth: Boolean = true): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 8_000; readTimeout = 12_000
            if (auth) setRequestProperty("Authorization", "Bearer ${token()}")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", type) }
        }
        body?.let { b -> conn.outputStream.use { it.write(b.toByteArray()) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            Log.w("Asistente", "Spotify $method $url → $code ${text.take(200)}")
            throw RuntimeException("Spotify respondió $code")
        }
        return text.ifBlank { "{}" }
    }
}
