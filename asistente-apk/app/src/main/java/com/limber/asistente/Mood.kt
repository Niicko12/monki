package com.limber.asistente

import android.content.Context
import android.graphics.Color
import java.util.Calendar

/** Estados de ánimo de Monki: cada uno tiene su cara en reposo y su color (si la cara va "según el ánimo"). */
enum class Animo(val tag: String, val expression: Expression, val color: String) {
    EMOCIONADO("emocionado", Expression.LAUGHING, "#FFB347"),
    FELIZ("feliz", Expression.HAPPY, "#8BE07A"),
    TRANQUILO("tranquilo", Expression.NEUTRAL, "#6CC79C"),
    ABURRIDO("aburrido", Expression.CONFUSED, "#8FA3B0"),
    CANSADO("cansado", Expression.RELAXED, "#9C8CD9"),
    TRISTE("triste", Expression.SAD, "#5E9BFF"),
    MOLESTO("molesto", Expression.ANGRY, "#FF6B5E"),
}

/** Colores de la cara que se pueden pedir por nombre (en ajustes, por adb o por voz). */
object FaceColors {
    const val SEGUN_ANIMO = "ánimo"
    val names = linkedMapOf(
        "verde" to "#6CC79C", "azul" to "#5E9BFF", "cian" to "#4FD8E8", "morado" to "#9C8CD9",
        "rosa" to "#F08AC0", "rojo" to "#FF6B5E", "naranja" to "#FFB347", "amarillo" to "#FFE066", "blanco" to "#E8EEF0",
    )

    /** Color fijo para [value] (nombre o #RRGGBB), o null si es "ánimo" o no se reconoce. */
    fun parse(value: String): Int? {
        val v = value.trim().lowercase()
        names[v]?.let { return Color.parseColor(it) }
        return runCatching { Color.parseColor(if (v.startsWith("#")) v else "#$v") }.getOrNull()
    }

    fun isValid(value: String) = value.trim().lowercase().let { it == SEGUN_ANIMO || it == "animo" || parse(it) != null }
}

/**
 * Estado de ánimo que dura entre conversaciones: sube con respuestas alegres y baja con tristes o con enojo,
 * se calma solo con el tiempo, y cambia por la hora (noche = cansado), la batería y el aburrimiento.
 */
class Mood(context: Context) {
    private val sp = context.getSharedPreferences("animo", Context.MODE_PRIVATE)

    /** -10 (muy triste) a 10 (muy contento). */
    private var happy: Float
        get() = sp.getFloat("happy", 2f)
        set(v) = sp.edit().putFloat("happy", v.coerceIn(-10f, 10f)).apply()

    /** 0 a 10: qué tan molesto está. */
    private var annoyed: Float
        get() = sp.getFloat("annoyed", 0f)
        set(v) = sp.edit().putFloat("annoyed", v.coerceIn(0f, 10f)).apply()

    private var updatedAt: Long
        get() = sp.getLong("at", System.currentTimeMillis())
        set(v) = sp.edit().putLong("at", v).apply()

    var lastInteraction: Long
        get() = sp.getLong("interaction", System.currentTimeMillis())
        set(v) = sp.edit().putLong("interaction", v).apply()

    /** Cada respuesta mueve el ánimo según la emoción con la que contestó. */
    fun onReply(e: Emotion) {
        decay()
        lastInteraction = System.currentTimeMillis()
        when (e) {
            Emotion.FELIZ, Emotion.RISA, Emotion.AMOR, Emotion.BESO, Emotion.DELICIOSO, Emotion.TONTO -> { happy += 2f; annoyed -= 1f }
            Emotion.COOL, Emotion.SEGURO, Emotion.GUIÑO, Emotion.RELAJADO -> happy += 1f
            Emotion.TRISTE, Emotion.LLORANDO -> happy -= 2.5f
            Emotion.ENOJO -> { annoyed += 3f; happy -= 1f }
            Emotion.APENADO, Emotion.CONFUNDIDO -> happy -= 0.5f
            else -> {}
        }
    }

    /** El ánimo vuelve poco a poco a "tranquilo": 1 punto cada 15 minutos. */
    private fun decay() {
        val now = System.currentTimeMillis()
        val steps = (now - updatedAt) / (15 * 60_000f)
        if (steps <= 0f) return
        happy = if (happy > 1f) maxOf(1f, happy - steps) else minOf(1f, happy + steps)
        annoyed -= steps * 1.5f
        updatedAt = now
    }

    fun current(battery: Int, charging: Boolean): Animo {
        decay()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val idleMin = (System.currentTimeMillis() - lastInteraction) / 60_000
        return when {
            annoyed >= 4f -> Animo.MOLESTO
            happy <= -4f -> Animo.TRISTE
            (battery in 1..15 && !charging) || hour >= 23 || hour < 6 -> Animo.CANSADO
            happy >= 7f -> Animo.EMOCIONADO
            happy >= 4f -> Animo.FELIZ
            idleMin >= 45 -> Animo.ABURRIDO
            else -> Animo.TRANQUILO
        }
    }

    /** Cambia el ánimo a mano (por voz: "anímate", "ponte triste"…). */
    fun set(a: Animo) {
        updatedAt = System.currentTimeMillis()
        lastInteraction = updatedAt
        when (a) {
            Animo.EMOCIONADO -> { happy = 8f; annoyed = 0f }
            Animo.FELIZ -> { happy = 5f; annoyed = 0f }
            Animo.TRANQUILO, Animo.ABURRIDO, Animo.CANSADO -> { happy = 1f; annoyed = 0f }
            Animo.TRISTE -> { happy = -5f; annoyed = 0f }
            Animo.MOLESTO -> annoyed = 5f
        }
    }
}
