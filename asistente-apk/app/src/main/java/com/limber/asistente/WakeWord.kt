package com.limber.asistente

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.concurrent.thread

/**
 * Palabra de activación sin internet (como la de xiaozhi), con Vosk en español.
 * Escucha "Hey Monki" todo el tiempo con el micrófono amplificado (los audífonos graban bajito).
 */
class WakeWord(private val context: Context, private val onWake: () -> Unit) {

    private var model: Model? = null
    @Volatile private var running = false
    /** Cuándo oyó "hey" al final de una frase: con una pausa, el nombre llega en la frase siguiente. */
    private var heyAt = 0L
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /** Palabra del vocabulario que suena como el nombre ("monkey" ≈ "Monki"). */
    var word: String = "monkey"

    /**
     * Mientras Monki habla su propia voz entra al micrófono y a veces suena a "mon…".
     * En modo estricto el nombre solo ya no basta: tiene que ir después de un "hey" (Monki casi nunca dice "hey").
     * Sigue estricto un momento después (eco).
     */
    @Volatile var strict = false
        set(v) { if (field && !v) strictUntil = System.currentTimeMillis() + STRICT_TAIL_MS; field = v }
    @Volatile private var strictUntil = 0L
    val isListening get() = running

    fun load(onReady: (Boolean) -> Unit) {
        if (model != null) return onReady(true)
        StorageService.unpack(context, "model-es", "model",
            { m -> model = m; onReady(true) },
            { e -> Log.w(TAG, "No pude cargar el modelo de voz", e); onReady(false) })
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val m = model ?: return
        if (running) return
        val w = word.lowercase().trim()
        val names = listOf(w) + (ALIASES[w] ?: emptyList())
        val grammar = JSONArray(names.flatMap { listOf("hey $it", it) } + listOf("hey", "[unk]")).toString()
        val rec = runCatching { Recognizer(m, RATE.toFloat(), grammar).apply { setWords(true) } }.getOrElse {
            Log.w(TAG, "No pude crear el reconocedor", it); return
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        }.getOrNull()
        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) { audio?.release(); rec.close(); return }
        running = true
        worker = thread(name = "wake") {
            val buf = ShortArray(2048)
            val last = ShortArray(RATE * 4)   // últimos 4 s, para depurar si no reconoce
            var lastPos = 0
            var gain = GAIN.toDouble()
            var peak = 0.0
            var lastLog = 0L
            audio.startRecording()
            try {
                while (running) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Ganancia automática: sube lo bajito (audífonos) sin saturar la voz fuerte
                    var max = 1
                    for (i in 0 until n) max = maxOf(max, Math.abs(buf[i].toInt()))
                    gain = minOf(GAIN.toDouble(), maxOf(1.0, minOf(gain * 1.02, 28000.0 / max)))
                    for (i in 0 until n) buf[i] = (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                    for (i in 0 until n) { last[lastPos] = buf[i]; lastPos = (lastPos + 1) % last.size }
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    peak = maxOf(peak, Math.sqrt(sum / n))
                    if (System.currentTimeMillis() - lastLog > 1000) {
                        Log.d(TAG, "nivel ${peak.toInt()}"); peak = 0.0; lastLog = System.currentTimeMillis()
                    }
                    if (rec.acceptWaveForm(buf, n) && heard(rec.result.also {
                            Log.d(TAG, "frase: $it")
                            if (it.contains("\"word\"")) saveDebug(last, lastPos)
                        })) {
                        running = false
                        main.post { onWake() }
                    }
                }
            } finally {
                runCatching { audio.stop() }; audio.release(); rec.close()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(600)
        worker = null
    }

    /** "Hey" + nombre (o variante como "mon qui"), o el nombre solo si es casi seguro. */
    private fun heard(json: String?): Boolean {
        val arr = runCatching { JSONObject(json ?: "{}").optJSONArray("result") }.getOrNull() ?: return false
        val words = (0 until arr.length()).map { arr.getJSONObject(it) }
        val w = word.lowercase().trim()
        val names = (listOf(w) + (ALIASES[w] ?: emptyList())).map { it.split(" ") }
        for (i in words.indices) for (name in names) {
            if (i + name.size > words.size) continue
            val part = words.subList(i, i + name.size)
            if (part.map { it.optString("word") } != name) continue
            val conf = part.map { it.optDouble("conf") }.average()   // "qui" suele salir dudoso tras un "mon" claro
            val prev = if (i > 0) words[i - 1] else null
            val withHey = if (prev != null) prev.optString("word") == "hey" && prev.optDouble("conf") >= 0.3
                else System.currentTimeMillis() - heyAt < HEY_WINDOW_MS
            Log.d(TAG, "oí: ${if (withHey) "hey " else ""}${name.joinToString(" ")} ($conf)")
            val alone = ALONE_MIN[name.joinToString(" ")] ?: ALONE_DEFAULT
            if (strict || System.currentTimeMillis() < strictUntil) {
                // Tu "hey monkey" real llega con ~0.45 de confianza en el nombre; "mon" solo es demasiado común
                val heyClear = prev != null && prev.optString("word") == "hey" && prev.optDouble("conf") >= 0.5
                if (heyClear && conf >= 0.35 && name.joinToString(" ") != "mon") return true
                continue
            }
            if ((withHey && conf >= 0.3) || conf >= alone) return true
        }
        val tail = words.lastOrNull() ?: return false   // frase vacía (la pausa): conserva el "hey"
        heyAt = if (tail.optString("word") == "hey" && tail.optDouble("conf") >= 0.3) System.currentTimeMillis() else 0L
        return false
    }

    /** Guarda los últimos segundos (ya amplificados) en wake-debug.wav para revisarlos en la Mac. */
    private fun saveDebug(ring: ShortArray, pos: Int) = runCatching {
        val pcm = java.nio.ByteBuffer.allocate(ring.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in ring.indices) pcm.putShort(ring[(pos + i) % ring.size])
        val data = pcm.array()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.size)
        java.io.File(context.getExternalFilesDir(null), "wake-debug.wav").writeBytes(h.array() + data)
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val RATE = 16_000
        private const val GAIN = 4
        private const val HEY_WINDOW_MS = 3_000L
        private const val STRICT_TAIL_MS = 1_500L
        /** Cómo entiende el modelo en español la palabra dicha a la latina ("Monki" ≈ "mon qui"). */
        private val ALIASES = mapOf("monkey" to listOf("mon qui", "mon ki", "moni", "qui", "mon"))
        /** Seguridad mínima sin "hey" delante. "qui" y "moni" sueltos salen del ruido del juego: solo cuentan tras "hey". */
        private const val ALONE_DEFAULT = 0.7
        private val ALONE_MIN = mapOf("mon qui" to 0.5, "mon ki" to 0.5, "qui" to 2.0, "moni" to 2.0)
    }
}
