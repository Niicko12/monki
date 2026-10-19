package com.limber.asistente

import android.content.Context

/** Ajustes guardados de la app. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asistente", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** Claves de los respaldos gratis cuando Gemini se queda sin cuota (ver OpenAiCompat). */
    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    var nvidiaKey: String
        get() = sp.getString("nvidia_key", "") ?: ""
        set(v) = sp.edit().putString("nvidia_key", v.trim()).apply()

    var openRouterKey: String
        get() = sp.getString("openrouter_key", "") ?: ""
        set(v) = sp.edit().putString("openrouter_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var ttsModel: String
        get() = sp.getString("tts_model", DEFAULT_TTS_MODEL) ?: DEFAULT_TTS_MODEL
        set(v) = sp.edit().putString("tts_model", v.trim().ifEmpty { DEFAULT_TTS_MODEL }).apply()

    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    /** Servidores MCP externos, una línea por servidor: "alias|url|clave". */
    var mcpServers: String
        get() = sp.getString("mcp_servers", "") ?: ""
        set(v) = sp.edit().putString("mcp_servers", v).apply()

    /** Permitir que otros agentes de la red controlen la consola por MCP. */
    var mcpServerEnabled: Boolean
        get() = sp.getBoolean("mcp_server", true)
        set(v) = sp.edit().putBoolean("mcp_server", v).apply()

    /** Clave que deben enviar los agentes externos (se genera una sola vez). */
    val mcpToken: String
        get() = sp.getString("mcp_token", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(20)
            .also { sp.edit().putString("mcp_token", it).apply() }

    /** Personalidad del asistente (como el "personaje" de xiaozhi). */
    var persona: String
        get() = sp.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(v) = sp.edit().putString("persona", v.trim().ifEmpty { DEFAULT_PERSONA }).apply()

    /** Computadoras para prender con Wake-on-LAN, una línea: "nombre|MAC|IP opcional". */
    var wolDevices: String
        get() = sp.getString("wol", "") ?: ""
        set(v) = sp.edit().putString("wol", v).apply()

    /** Modo Alexa: siempre encendida, palabra de activación y conversación seguida. */
    var alexaMode: Boolean
        get() = sp.getBoolean("alexa", true)
        set(v) = sp.edit().putBoolean("alexa", v).apply()

    /** Nombre que se muestra y con el que se presenta (la palabra de activación puede escribirse distinto). */
    var assistantName: String
        get() = sp.getString("name", "Monki") ?: "Monki"
        set(v) = sp.edit().putString("name", v.trim().ifEmpty { "Monki" }).apply()

    /** Dónde vive el usuario: clima y respuestas locales (por IP puede salir otra ciudad). Vacío = ubicación por IP. */
    /** Cómo pronunciar palabras en voz alta, una por línea: "Oscar=Óscar" (el texto en pantalla no cambia). */
    var pronunciations: String
        get() = sp.getString("pronunciar", "") ?: ""
        set(v) = sp.edit().putString("pronunciar", v.trim()).apply()

    var location: String
        get() = sp.getString("location", "") ?: ""
        set(v) = sp.edit().putString("location", v.trim()).apply()

    /** Cómo se llama el usuario (vacío = solo "señor"). Se configura por adb: --es user_name "Nombre". */
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /** "Señor" o "Señor Nombre", para los avisos que dice solo (batería, recordatorios). */
    val honorific get() = if (userName.isBlank()) "Señor" else "Señor $userName"

    var wakeWord: String
        get() = sp.getString("wake_word", "monkey") ?: "monkey"
        set(v) = sp.edit().putString("wake_word", v.trim().lowercase().ifEmpty { "monkey" }).apply()

    /** Color de la cara: nombre (verde, azul, rosa…), #RRGGBB o "ánimo" (cambia con el estado de ánimo). */
    var faceColor: String
        get() = sp.getString("face_color", "verde") ?: "verde"
        set(v) = sp.edit().putString("face_color", v.trim().lowercase().ifEmpty { "verde" }).apply()

    /** Voz de Gemini (Charon: grave, de mayordomo IA). */
    var voiceName: String
        get() = sp.getString("voice_name", "Charon") ?: "Charon"
        set(v) = sp.edit().putString("voice_name", v.trim().ifEmpty { "Charon" }).apply()

    companion object {
        const val DEFAULT_PERSONA = "Eres Monki, el asistente personal del usuario: un mayordomo IA educado, sereno y eficiente, con humor seco y elegante (al estilo del asistente de las películas de superhéroes). Le dices \"señor\" (y su nombre, si lo sabes). Hablas español latino."
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_TTS_MODEL = "gemini-3.8-flash-lite-tts"
    }
}
