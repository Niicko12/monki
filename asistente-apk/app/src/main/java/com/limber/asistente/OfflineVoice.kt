package com.limber.asistente

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Voz de respaldo sin internet (Piper es_MX vía el motor sherpa-onnx), para cuando la voz de Gemini
 * se queda sin cuota (429). Genera un .wav para reproducirlo por la misma ruta que la voz de Gemini.
 */
class OfflineVoice(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech? {
        val r = ready ?: CompletableDeferred<Boolean>().also { d ->
            ready = d
            tts = TextToSpeech(context, { status ->
                // El idioma se fija una sola vez: cada cambio hace que el motor recargue el modelo (~4 s)
                if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "MX")
                d.complete(status == TextToSpeech.SUCCESS)
            }, ENGINE)
        }
        val ok = withTimeoutOrNull(60_000) { r.await() } ?: false   // la primera carga del modelo tarda ~20 s
        if (!ok) { shutdown(); return null }
        return tts
    }

    /** Carga el motor de antemano, para no esperar la primera vez que haga falta. */
    suspend fun warmUp() { engine() }

    /** Escribe el texto como .wav en [out]; false si no hay motor o falló. */
    suspend fun synthesize(text: String, out: File): Boolean {
        val t = engine() ?: return false
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.complete(false) }
            override fun onError(id: String?, code: Int) { done.complete(false) }
        })
        if (t.synthesizeToFile(text, Bundle(), out, "monki") != TextToSpeech.SUCCESS) return false
        val ok = withTimeoutOrNull(60_000) { done.await() } ?: false
        if (!ok) Log.w("Asistente", "Voz sin internet: no pude generar el audio")
        if (ok) deepen(out)
        return ok && out.length() > 44
    }

    /**
     * Voz al estilo Jarvis: más grave y pausada, bajando la frecuencia de muestreo del .wav (como una cinta más lenta).
     * No se usa setPlaybackParams porque el reproductor de Rockchip se traba con velocidad distinta de 1.
     */
    private fun deepen(f: File) = runCatching {
        java.io.RandomAccessFile(f, "rw").use { w ->
            val h = ByteArray(36); w.readFully(h)
            val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return@use
            val rate = bb.getInt(24); val blockAlign = bb.getShort(32).toInt()
            val newRate = (rate * DEEPEN).toInt()
            w.seek(24); w.write(le(newRate)); w.write(le(newRate * blockAlign))
        }
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun shutdown() {
        tts?.shutdown(); tts = null; ready = null
    }

    companion object {
        const val ENGINE = "com.k2fsa.sherpa.onnx.tts.engine"
        /** 0.9 = 10 % más grave y más lenta. */
        private const val DEEPEN = 0.9
    }
}
