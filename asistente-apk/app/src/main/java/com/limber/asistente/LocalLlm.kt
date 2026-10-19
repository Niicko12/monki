package com.limber.asistente

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Modelo de lenguaje dentro de la consola (llama.cpp), sin internet ni límites: el último respaldo cuando
 * Groq, NVIDIA y Gemini están limitados o no hay conexión. Es chico (2 GB de RAM en la consola): conversa,
 * pero no usa herramientas. Se carga al necesitarlo y se descarga tras unos minutos sin uso.
 * El modelo va en la memoria interna de la app, files/llm.gguf (por adb: push a /data/local/tmp y "run-as … cp").
 */
object LocalLlm {
    private var ptr = 0L
    private var lastUse = 0L
    private var dir: File? = null

    private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
    private external fun nativeChat(ptr: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int): String
    private external fun nativeStop()
    private external fun nativeFree(ptr: Long)

    private val loaded by lazy { runCatching { System.loadLibrary("monki_llm"); true }.getOrDefault(false) }

    fun setup(context: Context) { dir = context.filesDir }

    private fun modelFile() = dir?.let { File(it, "llm.gguf") }?.takeIf { it.length() > 10_000_000 }

    val available get() = modelFile() != null && loaded

    @Synchronized
    private fun engine(): Long {
        if (ptr != 0L) return ptr
        val f = modelFile() ?: return 0L
        val t0 = System.currentTimeMillis()
        ptr = nativeLoad(f.path, N_CTX, THREADS)
        Log.i("Tiempos", "modelo local cargado en ${System.currentTimeMillis() - t0} ms")
        return ptr
    }

    /** Responde con forma de respuesta de Gemini, a partir del historial en formato Gemini. */
    @Synchronized
    fun generate(geminiBody: JSONObject, persona: String): JSONObject {
        if (!available) throw IllegalStateException("No hay modelo local")
        val e = engine()
        if (e == 0L) throw IllegalStateException("No pude cargar el modelo local")
        lastUse = System.currentTimeMillis()
        val roles = mutableListOf("system")
        val contents = mutableListOf(
            "$persona\nAhora estás en modo sin conexión: no puedes usar herramientas, internet ni controlar nada. " +
                "Responde en español, en 1 o 2 frases cortas, empezando con tu emoción entre corchetes, por ejemplo \"[feliz] Claro.\". " +
                "Si piden algo que necesita internet o controlar dispositivos, di con amabilidad que por ahora no puedes."
        )
        // Solo los últimos textos (sin resultados de herramientas): el modelo es chico
        val hist = geminiBody.optJSONArray("contents") ?: JSONArray()
        val turns = mutableListOf<Pair<String, String>>()
        for (i in 0 until hist.length()) {
            val c = hist.getJSONObject(i)
            val parts = c.optJSONArray("parts") ?: continue
            val text = (0 until parts.length()).map { parts.getJSONObject(it) }
                .filter { it.has("text") && !it.optBoolean("thought") }.joinToString(" ") { it.getString("text") }
                .replace(Regex("\\[App en pantalla:[^]]*]"), "").trim()
            if (text.isBlank()) continue
            val role = if (c.optString("role") == "model") "assistant" else "user"
            if (turns.isNotEmpty() && turns.last().first == role) turns[turns.size - 1] = role to (turns.last().second + "\n" + text)
            else turns += role to text
        }
        turns.takeLast(5).dropWhile { it.first != "user" }.forEach { roles += it.first; contents += it.second.take(600) }
        val t0 = System.currentTimeMillis()
        // Qwen a veces "piensa" en voz alta entre <think>…</think>: eso no se dice
        val out = nativeChat(e, roles.toTypedArray(), contents.toTypedArray(), MAX_TOKENS)
            .replace(Regex("(?s)<think>.*?(</think>|$)"), "").trim()
        Log.i("Tiempos", "modelo local respondió en ${System.currentTimeMillis() - t0} ms")
        lastUse = System.currentTimeMillis()
        val text = out.trim('"', '“', '”', ' ').ifBlank { "[confundido] Perdón, no pude pensar una respuesta." }
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("content",
            JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))))
    }

    fun stop() { if (loaded) nativeStop() }

    /** Libera la RAM si lleva un rato sin usarse (la consola solo tiene 2 GB). */
    @Synchronized
    fun releaseIfIdle() {
        if (ptr != 0L && System.currentTimeMillis() - lastUse > IDLE_MS) { nativeFree(ptr); ptr = 0L; Log.i("Asistente", "Modelo local liberado") }
    }

    private const val N_CTX = 2048
    private const val THREADS = 4
    private const val MAX_TOKENS = 120
    private const val IDLE_MS = 5 * 60_000L
}
