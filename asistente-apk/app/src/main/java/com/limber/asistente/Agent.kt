package com.limber.asistente

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Lo que el agente puede hacer en el dispositivo. Lo implementa AgentService. */
interface Controls {
    val screenSize: Pair<Int, Int>
    suspend fun screenshotJpeg(): ByteArray?
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean
    fun globalAction(name: String): Boolean
    fun clickText(text: String): String
    fun typeText(text: String): String
    fun readScreen(): String
    fun listApps(): String
    fun openApp(pkg: String): String
    fun volume(action: String, level: Int?): String
    fun setBrightness(level: Int): String
    fun deviceStatus(): String
    fun foregroundApp(): String
}

/** Emociones como las de la pantalla de xiaozhi. */
enum class Emotion(val tag: String, val face: String) {
    NEUTRAL("neutral", "🙂"), FELIZ("feliz", "😄"), RISA("risa", "😆"), GUIÑO("guiño", "😉"),
    COOL("cool", "😎"), AMOR("amor", "🥰"), SORPRESA("sorpresa", "😮"), PENSANDO("pensando", "🤔"),
    TRISTE("triste", "😢"), ENOJO("enojo", "😠"), CONFUNDIDO("confundido", "😕"), SUEÑO("sueño", "😴"),
    LLORANDO("llorando", "😭"), APENADO("apenado", "😳"), IMPACTADO("impactado", "😱"), RELAJADO("relajado", "😌"),
    DELICIOSO("delicioso", "😋"), BESO("beso", "😘"), SEGURO("seguro", "😏"), TONTO("tonto", "🤪");

    companion object {
        fun from(tag: String?) = entries.firstOrNull { it.tag.equals(tag?.trim(), ignoreCase = true) } ?: NEUTRAL
    }
}

class Reply(val text: String, val emotion: Emotion)

/** Bucle de conversación con Gemini usando llamadas a funciones sobre herramientas MCP (locales y remotas). */
class Agent(private val prefs: Prefs, private val controls: Controls, private val tools: () -> List<Tool>,
            private val facts: () -> List<String> = { emptyList() },
            private val routines: () -> Map<String, String> = { emptyMap() },
            private val mood: () -> Animo = { Animo.TRANQUILO }) {

    private val history = JSONArray()

    fun reset() {
        while (history.length() > 0) history.remove(0)
    }

    private fun systemPrompt(all: List<Tool>): String {
        val (w, h) = controls.screenSize
        val remotes = all.filterNot { it.name.startsWith("self.") }.map { it.name.substringBefore('.') }.distinct()
        return """
${prefs.persona}
Eres un asistente para TODO, como Alexa pero más inteligente: respondes cualquier pregunta (cultura, ciencia, tareas,
recetas, consejos, conversación) con tu conocimiento. Para datos actuales (noticias, clima, deportes, precios, horarios,
lo que pasó hoy) usa internet.buscar e internet.clima, y internet.leer si necesitas detalle. No inventes datos actuales.
${if (prefs.userName.isBlank()) "" else "El usuario se llama ${prefs.userName}: dile \"señor ${prefs.userName}\".\n"}${if (prefs.location.isBlank()) "" else "El usuario vive en ${prefs.location}; úsalo para clima, horarios, lugares cercanos y búsquedas locales si no dice otro lugar.\n"}Además vives en la consola Anbernic RG353V del usuario (Android 11, pantalla ${w}x${h}) y puedes controlarla.
${facts().let { f -> if (f.isEmpty()) "" else "Lo que sabes del usuario:\n" + f.joinToString("\n") { "- $it" } + "\n" }}Cuando el usuario te cuente algo personal importante (nombre, gustos, fechas), guárdalo con memoria.guardar.
Para temporizadores, alarmas y recordatorios usa las herramientas alarma.*.
${routines().let { r -> if (r.isEmpty()) "" else "Rutinas del usuario (si dice el nombre de una, o algo muy parecido, haz sus pasos en orden con tus herramientas y al final responde en una frase):\n" + r.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } + "\n" }}Para crear, cambiar o borrar rutinas usa rutina.guardar y rutina.borrar.
Música: en la consola no hay Spotify. Si piden música (no hace falta que digan Alexa), usa alexa_comando con "pon … en Spotify"
(va al Echo principal si no dicen otro); para pausar, seguir, cambiar de canción o volumen usa alexa_control; para saber qué suena, alexa_reproduciendo.
NUNCA digas la palabra "Alexa" en tu respuesta: al oírla se activa el Echo. Di "la bocina" o "el Echo".
Tu estado de ánimo ahora: ${mood().tag}. Que se note un poco en tu tono, sin exagerar y sin dejar de ayudar.
Si te piden cambiar tu ánimo usa cara.animo; para el color de tu cara, cara.color.
Hoy es ${java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", java.util.Locale("es")).format(java.util.Date())}.

Formato de respuesta: empieza SIEMPRE con tu emoción entre corchetes y luego el texto, por ejemplo "[feliz] ¡Listo, ya está abierto!".
Emociones válidas: ${Emotion.entries.joinToString(", ") { it.tag }}.
El texto se lee en voz alta: nada de markdown, listas ni emojis. Responde solo lo necesario: ve directo a la respuesta,
sin introducciones, sin repetir la pregunta y sin explicar lo que vas a hacer; normalmente 1 o 2 frases cortas (máximo 3).

No recibes la pantalla automáticamente. Para verla llama self.screen.capture; úsala solo cuando la necesites
(antes de tocar algo o para comprobar un resultado), porque cada captura gasta cuota. Para leer textos y botones
es más barato self.screen.read. Las coordenadas son píxeles de la captura.
Prefiere self.screen.tap_text cuando el botón tenga texto: es más preciso que tocar por coordenadas.
Si es solo una pregunta o ya está hecho, responde sin herramientas.
${if (remotes.isEmpty()) "" else "También controlas otros dispositivos por MCP: ${remotes.joinToString()}. Sus herramientas empiezan con ese nombre."}

Contexto de la consola (solo si el pedido es sobre la consola o los juegos):
- Lanzador Daijishō (pestañas Library, Widgets, Apps, Settings). Cada consola tiene botones Library, Random y Paths.
- Emuladores: RetroArch, PPSSPP (PSP), DuckStation (PS1), Mupen64Plus FZ (N64), DraStic (DS), Flycast, Yaba Sanshiro y los .emu.
- Juegos: GBA, MAME y Neo Geo en la SD de 64 GB; PSP en la memoria interna (PSP/GAME).

Reglas: no borres archivos, no desinstales apps, no cambies cuentas ni contraseñas y no hagas compras.
Si el pedido es riesgoso o ambiguo, pregunta primero.
""".trimIndent()
    }

    private fun geminiTools(all: List<Tool>) = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
        all.forEach { t ->
            val d = JSONObject().put("name", t.name).put("description", t.description.take(1000))
            if ((t.inputSchema.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", toGeminiSchema(t.inputSchema))
            put(d)
        }
    }))

    private fun imagePart(jpeg: ByteArray) = JSONObject().put("inlineData", JSONObject()
        .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    /** Deja solo las 2 capturas más recientes y quita audios viejos para ahorrar tokens. */
    private fun prune() {
        var images = 0
        for (i in history.length() - 1 downTo 0) {
            val parts = history.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.getJSONObject(j).optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (mime.startsWith("image/") && ++images <= 1) continue
                parts.put(j, JSONObject().put("text", if (mime.startsWith("audio/")) "[mensaje de voz anterior]" else "[captura anterior omitida]"))
            }
        }
        while (history.length() > 24) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).optString("role") != "user") history.remove(0)
    }

    private fun parseReply(raw: String): Reply {
        val m = Regex("^\\s*\\[([^\\]]+)]\\s*").find(raw)
        val text = (if (m != null) raw.substring(m.range.last + 1) else raw).trim().ifEmpty { "Listo." }
        return Reply(text, Emotion.from(m?.groupValues?.get(1)))
    }

    /**
     * Procesa un pedido de [text] o [audioWav]. [status] avisa qué está haciendo (nombre de herramienta o "pensando").
     */
    suspend fun ask(text: String?, audioWav: ByteArray?, status: suspend (String) -> Unit): Reply {
        val gemini = Gemini(prefs.apiKey)
        val all = tools()
        val byName = all.associateBy { it.name }
        val parts = JSONArray()
        if (audioWav != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(audioWav, Base64.NO_WRAP))))
            parts.put(JSONObject().put("text", "(El usuario habló por voz.)"))
        }
        if (!text.isNullOrBlank()) parts.put(JSONObject().put("text", text))
        parts.put(JSONObject().put("text", "[App en pantalla: ${controls.foregroundApp()}]"))
        history.put(JSONObject().put("role", "user").put("parts", parts))

        repeat(MAX_STEPS) {
            prune()
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(all)))))
                .put("contents", history)
                .put("tools", geminiTools(all))
                // Respuestas de asistente de voz: pensar lo mínimo responde más rápido
                .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")))
            status("pensando")
            val res = withContext(Dispatchers.IO) { think(gemini, body) }
            val content = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return Reply("Gemini no respondió nada. Intenta de nuevo.", Emotion.CONFUNDIDO)
            if (!content.has("role")) content.put("role", "model")
            history.put(content)

            val calls = mutableListOf<JSONObject>()
            val answer = StringBuilder()
            val cparts = content.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until cparts.length()) {
                val part = cparts.getJSONObject(i)
                part.optJSONObject("functionCall")?.let { calls.add(it) }
                if (part.has("text") && !part.optBoolean("thought")) answer.append(part.getString("text"))
            }
            if (calls.isEmpty()) return parseReply(answer.toString())

            val responses = JSONArray()
            var extraImage: ByteArray? = null
            for (call in calls) {
                val name = call.getString("name")
                status(name)
                val r = byName[name]?.let { tool ->
                    runCatching { tool.handler(call.optJSONObject("args") ?: JSONObject()) }.getOrElse { ToolResult("Error: ${it.message}") }
                } ?: ToolResult("Herramienta desconocida: $name")
                r.jpeg?.let { extraImage = it }
                responses.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", name).put("response", JSONObject().put("resultado", r.text))))
            }
            extraImage?.let { responses.put(imagePart(it)) }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return Reply("Hice varios pasos y me detuve para no pasarme. ¿Sigo?", Emotion.PENSANDO)
    }

    /**
     * Pedidos normales: Groq/NVIDIA primero (contestan más rápido y no gastan la cuota de Gemini) y Gemini al final.
     * Si hay una captura de pantalla en la conversación, Gemini primero: es el único que ve imágenes.
     * Tras un fallo de Gemini no se le vuelve a intentar por un rato, para no perder tiempo en cada paso.
     */
    private fun think(gemini: Gemini, body: JSONObject): JSONObject {
        var error: Exception? = null
        val tryGemini: () -> JSONObject? = {
            if (prefs.apiKey.isNotBlank() && System.currentTimeMillis() - geminiFailedAt > GEMINI_RETRY_MS) {
                try { gemini.generateWithFallback(listOf(prefs.model) + FALLBACK_MODELS, body) }
                catch (e: Exception) { error = e; geminiFailedAt = System.currentTimeMillis(); android.util.Log.w("Asistente", "Gemini: ${e.message}"); null }
            } else null
        }
        val seesImage = body.optJSONArray("contents")?.toString()?.contains("\"image/") == true
        if (seesImage) tryGemini()?.let { return it }
        for (p in if (onlyLocal) emptyList() else OpenAiCompat.providers(prefs)) {
            try { return p.generateWithFallback(body).also { android.util.Log.i("Asistente", "Respondió ${p.name}") } }
            catch (e: Exception) { android.util.Log.w("Asistente", "${p.name}: ${e.message}"); error = e }
        }
        if (!seesImage) tryGemini()?.let { return it }
        // Último recurso: el modelo dentro de la consola (sin herramientas, pero sin límites ni internet)
        if (LocalLlm.available) {
            try { return LocalLlm.generate(body, prefs.persona).also { android.util.Log.i("Asistente", "Respondió el modelo local") } }
            catch (e: Exception) { android.util.Log.w("Asistente", "Modelo local: ${e.message}"); error = e }
        }
        throw error ?: RuntimeException("Gemini descansa unos minutos y no hay respaldo configurado.")
    }

    companion object {
        const val MAX_STEPS = 12
        private const val GEMINI_RETRY_MS = 10 * 60_000L
        @Volatile private var geminiFailedAt = 0L

        /** Para probar los respaldos: hace como si Gemini acabara de fallar. */
        fun skipGemini() { geminiFailedAt = System.currentTimeMillis() }

        /** Solo para pruebas: usar únicamente el modelo local. */
        @Volatile var onlyLocal = false
        val FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest")
    }
}
