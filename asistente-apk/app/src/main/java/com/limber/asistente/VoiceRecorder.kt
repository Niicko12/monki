package com.limber.asistente

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Graba del micrófono (Bluetooth o USB si hay) a WAV 16 kHz mono. */
class VoiceRecorder(private val onSilence: () -> Unit = {}) {
    private var record: AudioRecord? = null
    private var buffer = ByteArrayOutputStream()
    @Volatile var isRecording = false
        private set
    @Volatile var level = 0.0
        private set
    @Volatile var heardVoice = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4)
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        buffer = ByteArrayOutputStream()
        record = r
        isRecording = true
        r.startRecording()
        thread(name = "mic") {
            val chunk = ByteArray(min)
            heardVoice = false
            var quietMs = 0L
            var voiceMs = 0L
            var elapsed = 0L
            var floor = 0.0
            val hum = HighPass(300.0)
            while (isRecording) {
                val n = r.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                    // Detección de voz: energía del bloque sin el zumbido grave del micrófono (60/180 Hz),
                    // que es casi tan fuerte como la voz y no dejaba notar cuándo terminaba de hablar
                    var sum = 0.0
                    var raw = 0.0
                    for (i in 0 until n - 1 step 2) {
                        val v = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toDouble()
                        raw += v * v
                        val f = hum.next(v)
                        sum += f * f
                    }
                    level = Math.sqrt(raw / (n / 2).coerceAtLeast(1))   // para las barritas de la pantalla
                    val voice = Math.sqrt(sum / (n / 2).coerceAtLeast(1))
                    val ms = n * 1000L / (RATE * 2)
                    elapsed += ms
                    if (elapsed <= SKIP_MS) continue                      // el "clic" al abrir el micrófono
                    // Ruido de fondo adaptable: baja rápido, sube lento (los audífonos cambian su ruido solos)
                    floor = when {
                        floor == 0.0 -> voice
                        voice < floor -> floor * 0.6 + voice * 0.4
                        else -> floor * 0.995 + voice * 0.005
                    }
                    if (elapsed <= SKIP_MS + CALIBRATE_MS) continue
                    val threshold = maxOf(VOICE_RMS, floor * 2.2)
                    if (voice > threshold) { voiceMs += ms; quietMs = 0; if (voiceMs >= MIN_VOICE_MS) heardVoice = true }
                    else { quietMs += ms; if (!heardVoice && quietMs > 400) voiceMs = 0 }  // suma trozos de voz cercanos
                    if (heardVoice && quietMs > SILENCE_MS) { onSilence(); break }
                    if (!heardVoice && buffer.size() > RATE * 2 * 8) { onSilence(); break } // 8 s sin hablar
                }
                if (buffer.size() > RATE * 2 * 30) { onSilence(); break } // máximo 30 s
            }
        }
        return true
    }

    fun stop(): ByteArray? {
        if (record == null) return null
        isRecording = false
        Thread.sleep(80)
        record?.run { runCatching { stop() }; release() }
        record = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return if (pcm.isEmpty()) null else Wav.fromPcm16(normalize(pcm), RATE)
    }

    /** Sube el volumen para que el pico quede cerca del máximo (micrófonos muy bajitos). */
    private fun normalize(pcm: ByteArray): ByteArray {
        val skip = (RATE * 2 * SKIP_MS / 1000).toInt().coerceAtMost(pcm.size) and 1.inv()
        var peak = 1
        for (i in skip until pcm.size - 1 step 2) {
            val v = kotlin.math.abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt())
            if (v > peak) peak = v
        }
        val gain = (24000.0 / peak).coerceIn(1.0, 12.0)
        if (gain <= 1.05) return pcm
        val out = pcm.copyOf()
        for (i in 0 until out.size - 1 step 2) {
            if (i < skip) { out[i] = 0; out[i + 1] = 0; continue }   // quita el clic inicial
            val v = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xff)).toShort().toInt()
            val g = (v * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (g and 0xff).toByte(); out[i + 1] = ((g shr 8) and 0xff).toByte()
        }
        return out
    }

    fun cancel() { stop() }

    companion object {
        const val RATE = 16_000
        /** Mínimo absoluto de energía (ya sin zumbido) para contar como voz. */
        const val VOICE_RMS = 60.0
        const val SILENCE_MS = 900L
        const val SKIP_MS = 250L
        const val CALIBRATE_MS = 300L
        const val MIN_VOICE_MS = 150L
    }
}

/** Filtro paso alto de 4 polos (dos biquads Butterworth) para quitar el zumbido grave. */
private class HighPass(fc: Double, rate: Int = VoiceRecorder.RATE) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private val st = DoubleArray(8)

    init {
        val w0 = 2 * Math.PI * fc / rate
        val al = Math.sin(w0) / (2 * 0.7071)
        val c = Math.cos(w0)
        val a0 = 1 + al
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0
    }

    fun next(x: Double): Double {
        var v = x
        for (k in 0..1) {
            val o = k * 4
            val y = b0 * v + b1 * st[o] + b2 * st[o + 1] - a1 * st[o + 2] - a2 * st[o + 3]
            st[o + 1] = st[o]; st[o] = v; st[o + 3] = st[o + 2]; st[o + 2] = y
            v = y
        }
        return v
    }
}
