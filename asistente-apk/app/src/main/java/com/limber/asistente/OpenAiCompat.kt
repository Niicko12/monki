package com.limber.asistente

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Respaldo gratis cuando Gemini se queda sin cuota: APIs con formato OpenAI (Groq, NVIDIA, OpenRouter).
 * El historial sigue en formato Gemini: aquí se traduce de ida (contents → messages) y de vuelta (message → content).
 */
class OpenAiCompat(val name: String, private val baseUrl: String, private val key: String, val models: List<String>) {

    class LimitException(msg: String) : RuntimeException(msg)

    /** Igual que Gemini.generate pero con este proveedor; devuelve una respuesta con forma de Gemini. */
    fun generate(model: String, geminiBody: JSONObject): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", toMessages(geminiBody))
            .put("temperature", 0.6)
            // Groq reserva los tokens de respuesta contra su límite por minuto: con el máximo por defecto da 413
            .put("max_tokens", 700)
        geminiBody.optJSONArray("tools")?.let { t -> toTools(t).takeIf { it.length() > 0 }?.let { body.put("tools", it) } }
        Log.i("Tiempos", "$name tamaño: mensajes ${body.optJSONArray("messages")?.toString()?.length} letras, herramientas ${body.optJSONArray("tools")?.toString()?.length} (${body.optJSONArray("tools")?.length()})")
        val res = post("/chat/completions", body)
        res.optJSONObject("usage")?.let { Log.i("Tiempos", "$name/$model tokens: ${it.optInt("prompt_tokens")} + ${it.optInt("completion_tokens")}") }
        val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", toContent(msg))))
    }

    /** Pregunta corta sin herramientas ni historial (por ejemplo, clasificar una canción). */
    fun quick(prompt: String, model: String = models.last()): String {
        val body = JSONObject().put("model", model).put("max_tokens", 300).put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
        return post("/chat/completions", body).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty().trim()
    }

    fun generateWithFallback(geminiBody: JSONObject): JSONObject {
        var last: Exception? = null
        // Cualquier falla (sin cuota, modelo retirado, red lenta) pasa al siguiente modelo
        for (m in models) {
            try { return generate(m, geminiBody) } catch (e: Exception) { last = e; Log.w("Asistente", "$name/$m: ${e.message}") }
        }
        throw LimitException("$name sin cuota${last?.message?.let { ": $it" } ?: ""}")
    }

    /** Voz a texto con Whisper (Groq). Devuelve "" si no se entiende nada. */
    fun transcribe(wav: ByteArray, model: String = "whisper-large-v3-turbo"): String {
        val boundary = "----monki${System.currentTimeMillis()}"
        val out = java.io.ByteArrayOutputStream()
        fun field(n: String, v: String) = out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
        field("model", model); field("language", "es"); field("temperature", "0"); field("response_format", "json")
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voz.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav); out.write("\r\n--$boundary--\r\n".toByteArray())
        // Límite corto: si Whisper se atora, mejor pasar a Gemini que dejar al usuario esperando
        val t0 = System.currentTimeMillis()
        val res = request("/audio/transcriptions", out.toByteArray(), "multipart/form-data; boundary=$boundary", readMs = 8_000)
        Log.i("Tiempos", "Whisper ${System.currentTimeMillis() - t0} ms")
        val text = res.optString("text").trim()
        // Whisper "inventa" frases de subtítulos cuando solo hay ruido
        val junk = listOf("subtítulos", "gracias por ver", "suscríbete", "amara.org")
        return if (text.length < 2 || junk.any { text.lowercase().contains(it) }) "" else text
    }

    private fun post(path: String, body: JSONObject) = request(path, body.toString().toByteArray(), "application/json; charset=utf-8")

    private fun request(path: String, payload: ByteArray, type: String, readMs: Int = 45_000): JSONObject {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = minOf(15_000, readMs); readTimeout = readMs; doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", type)
            setRequestProperty("Authorization", "Bearer $key")
        }
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault(text.take(200))
            // 429 = sin cuota; 413 = petición más grande que el límite por minuto; 404/410 = modelo que ya no existe
            if (code == 429 || code == 413 || code == 404 || code == 410 || code >= 500) throw LimitException("$name $code: ${msg.take(120)}")
            throw RuntimeException("$name respondió $code: $msg")
        }
        return JSONObject(text)
    }

    // ---------- traducción Gemini ⇄ OpenAI ----------

    private fun toTools(geminiTools: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until geminiTools.length()) {
            val decls = geminiTools.getJSONObject(i).optJSONArray("functionDeclarations") ?: continue
            for (j in 0 until decls.length()) {
                val d = decls.getJSONObject(j)
                val params = d.optJSONObject("parameters")?.let { fromGeminiSchema(it) }
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", safeName(d.getString("name"))).put("description", d.optString("description").take(TOOL_DESC)).put("parameters", params)))
            }
        }
    }

    /** Los tipos de Gemini van en mayúsculas (OBJECT, STRING…); OpenAI los quiere en minúsculas. */
    private fun fromGeminiSchema(s: JSONObject): JSONObject {
        val out = JSONObject()
        s.keys().forEach { k ->
            when (val v = s.get(k)) {
                is JSONObject -> out.put(k, if (k == "properties") JSONObject().apply { v.keys().forEach { p -> put(p, fromGeminiSchema(v.getJSONObject(p))) } } else fromGeminiSchema(v))
                is String -> out.put(k, when (k) { "type" -> v.lowercase(); "description" -> v.take(PARAM_DESC); else -> v })
                else -> out.put(k, v)
            }
        }
        return out
    }

    private fun toMessages(g: JSONObject): JSONArray {
        val msgs = JSONArray()
        g.optJSONObject("systemInstruction")?.optJSONArray("parts")?.let { msgs.put(JSONObject().put("role", "system").put("content", texts(it))) }
        val contents = g.optJSONArray("contents") ?: JSONArray()
        var pendingIds = ArrayDeque<String>()
        // Groq da 8K tokens por minuto: solo los últimos mensajes, empezando en un pedido del usuario (no en una respuesta de herramienta)
        var start = maxOf(0, contents.length() - MAX_CONTENTS)
        while (start > 0 && !isUserText(contents.getJSONObject(start))) start--
        if (contents.length() - start > MAX_CONTENTS * 2) start = (start until contents.length()).firstOrNull { it >= contents.length() - MAX_CONTENTS && isUserText(contents.getJSONObject(it)) } ?: start
        for (i in start until contents.length()) {
            val c = contents.getJSONObject(i)
            val parts = c.optJSONArray("parts") ?: JSONArray()
            if (c.optString("role") == "model") {
                val calls = JSONArray()
                pendingIds = ArrayDeque()
                for (j in 0 until parts.length()) {
                    val fc = parts.getJSONObject(j).optJSONObject("functionCall") ?: continue
                    val id = "call_${i}_$j"
                    pendingIds.add(id)
                    calls.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
                        .put("name", safeName(fc.getString("name"))).put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString())))
                }
                val m = JSONObject().put("role", "assistant").put("content", texts(parts))
                if (calls.length() > 0) m.put("tool_calls", calls)
                msgs.put(m)
            } else {
                val extra = StringBuilder()
                for (j in 0 until parts.length()) {
                    val p = parts.getJSONObject(j)
                    val fr = p.optJSONObject("functionResponse")
                    when {
                        fr != null -> {
                            val r = fr.optJSONObject("response")?.optString("resultado") ?: fr.optJSONObject("response")?.toString() ?: ""
                            // Resultados viejos (búsquedas, páginas) recortados; el del paso actual completo
                            val old = i < contents.length() - 2
                            msgs.put(JSONObject().put("role", "tool")
                                .put("tool_call_id", pendingIds.removeFirstOrNull() ?: "call_x")
                                .put("content", if (old && r.length > OLD_RESULT) r.take(OLD_RESULT) + "…" else r.take(MAX_RESULT)))
                        }
                        p.has("text") -> extra.append(p.getString("text")).append('\n')
                        p.has("inlineData") -> extra.append(
                            if (p.getJSONObject("inlineData").optString("mimeType").startsWith("image/"))
                                "[Captura de pantalla no disponible con este modelo: usa self.screen.read para leer la pantalla]\n"
                            else "[audio omitido]\n")
                    }
                }
                if (extra.isNotBlank()) msgs.put(JSONObject().put("role", "user").put("content", extra.toString().trim()))
            }
        }
        return msgs
    }

    /** Algunos proveedores no aceptan puntos en los nombres de herramientas: "internet.clima" ⇄ "internet__clima". */
    private fun safeName(n: String) = n.replace(".", "__").replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun realName(n: String) = n.replace("__", ".")

    private fun isUserText(c: JSONObject): Boolean {
        if (c.optString("role") != "user") return false
        val p = c.optJSONArray("parts") ?: return false
        return (0 until p.length()).none { p.getJSONObject(it).has("functionResponse") }
    }

    private fun texts(parts: JSONArray) = (0 until parts.length()).map { parts.getJSONObject(it) }
        .filter { it.has("text") && !it.optBoolean("thought") }.joinToString("\n") { it.getString("text") }

    private fun toContent(msg: JSONObject): JSONObject {
        val parts = JSONArray()
        msg.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let { parts.put(JSONObject().put("text", it)) }
        msg.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val f = calls.getJSONObject(i).getJSONObject("function")
                val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                // Gemini 3 exige firma de pensamiento en llamadas del historial; esta firma especial le dice que no la valide
                parts.put(JSONObject().put("functionCall", JSONObject().put("name", realName(f.getString("name"))).put("args", args))
                    .put("thoughtSignature", "skip_thought_signature_validator"))
            }
        }
        return JSONObject().put("role", "model").put("parts", parts)
    }

    companion object {
        /** Cuántos mensajes recientes de la conversación se mandan a estos proveedores. */
        private const val MAX_CONTENTS = 8
        private const val OLD_RESULT = 300
        private const val MAX_RESULT = 6000
        private const val TOOL_DESC = 120
        private const val PARAM_DESC = 60

        /** Proveedores configurados, en orden: Groq (rápido), NVIDIA (más cupo diario), OpenRouter (último recurso). */
        fun providers(p: Prefs): List<OpenAiCompat> = buildList {
            if (p.groqKey.isNotBlank()) add(OpenAiCompat("Groq", "https://api.groq.com/openai/v1", p.groqKey,
                listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")))
            if (p.nvidiaKey.isNotBlank()) add(OpenAiCompat("NVIDIA", "https://integrate.api.nvidia.com/v1", p.nvidiaKey,
                listOf("nvidia/nemotron-3.5-lightning-30b-a3b", "nvidia/nemotron-3-super-120b-a12b", "openai/gpt-oss-20b")))
            if (p.openRouterKey.isNotBlank()) add(OpenAiCompat("OpenRouter", "https://openrouter.ai/api/v1", p.openRouterKey,
                listOf("nvidia/nemotron-3-super-120b-a12b:free", "google/gemma-4-31b-it:free")))
        }

        fun groq(p: Prefs) = if (p.groqKey.isBlank()) null else providers(p).first()
    }
}
