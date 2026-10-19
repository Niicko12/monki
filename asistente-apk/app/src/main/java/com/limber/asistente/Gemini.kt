package com.limber.asistente

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cliente mínimo de la API REST de Gemini. Llamar siempre fuera del hilo principal. */
class Gemini(private val apiKey: String) {

    class QuotaException(msg: String) : RuntimeException(msg)

    /** Prueba los modelos en orden y salta al siguiente si uno se quedó sin cuota (429). */
    fun generateWithFallback(models: List<String>, body: JSONObject): JSONObject {
        var last: Exception? = null
        for (m in models.distinct()) {
            try { return generate(m, body) } catch (e: QuotaException) { last = e }
        }
        throw RuntimeException("Se acabó la cuota gratis de Gemini en todos los modelos. Vuelve a intentar más tarde.", last)
    }

    fun generate(model: String, body: JSONObject): JSONObject {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                .getOrDefault(text.take(200))
            if (code == 429) throw QuotaException("$model sin cuota")
            throw RuntimeException("Gemini respondió $code: $msg")
        }
        return JSONObject(text)
    }

    /** Transcribe un audio WAV a texto (español). Devuelve "" si no se entiende nada. */
    fun transcribe(models: List<String>, wav: ByteArray): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
            .put(JSONObject().put("text", "Transcribe exactamente lo que dice la persona en este audio, en español. " +
                "Responde solo con la transcripción. Si no se entiende nada, responde exactamente: (nada)")))))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val res = generateWithFallback(models, body)
        val text = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().trim()
        return if (text.contains("(nada)")) "" else text
    }

    /** Convierte texto en voz. Devuelve un WAV (o null si falla). */
    fun speech(ttsModel: String, text: String, voice: String = "Charon", style: String = ""): ByteArray? {
        for (m in listOf(ttsModel, "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview").distinct()) {
            try { return speechWith(m, text, voice, style) } catch (_: QuotaException) {}
        }
        return null
    }

    private fun speechWith(ttsModel: String, text: String, voice: String, style: String): ByteArray? {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "${style.ifBlank { "Tono amigable y natural, en español latino" }}. Di: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val res = generate(ttsModel, body)
        val part = res.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optJSONObject("inlineData") ?: return null
        val bytes = Base64.decode(part.getString("data"), Base64.DEFAULT)
        val mime = part.optString("mimeType")
        return if (mime.contains("wav") || (bytes.size > 4 && String(bytes, 0, 4) == "RIFF")) bytes
        else Wav.fromPcm16(bytes, 24_000)
    }
}
