package com.limber.asistente

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pantalla de configuración: API Key, modelo, permisos y prueba. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var mcpInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Configuración por adb: am start -n com.limber.asistente/.MainActivity --es key ... --es model ...
        intent.getStringExtra("key")?.let { prefs.apiKey = it }
        intent.getStringExtra("model")?.let { prefs.model = it }
        intent.getStringExtra("groq_key")?.let { prefs.groqKey = it }
        intent.getStringExtra("pronunciar")?.let { prefs.pronunciations = it.replace("\\n", "\n") }
        intent.getStringExtra("nvidia_key")?.let { prefs.nvidiaKey = it }
        intent.getStringExtra("openrouter_key")?.let { prefs.openRouterKey = it }
        intent.getStringExtra("tts_model")?.let { prefs.ttsModel = it }
        intent.getStringExtra("wake_word")?.let { prefs.wakeWord = it }
        intent.getStringExtra("persona")?.let { prefs.persona = it }
        intent.getStringExtra("location")?.let { prefs.location = it }
        intent.getStringExtra("user_name")?.let { prefs.userName = it }
        // Prueba de voz por adb: am start -n com.limber.asistente/.MainActivity --es decir "hola"
        intent.getStringExtra("decir")?.let { AgentService.instance?.announce(it) }
        // Prueba de los respaldos por adb: --ez sin_gemini true --es pedir "¿qué hora es?"
        if (intent.getBooleanExtra("sin_gemini", false)) Agent.skipGemini()
        // Prueba del modelo local por adb: --ez solo_local true (salta Gemini y los respaldos en la nube)
        if (intent.getBooleanExtra("solo_local", false)) { Agent.skipGemini(); Agent.onlyLocal = true }
        intent.getStringExtra("pedir")?.let { AgentService.instance?.askText(it) }
        intent.getStringExtra("animo")?.let { AgentService.instance?.setMood(it) }
        intent.getStringExtra("color")?.let { if (FaceColors.isValid(it)) { prefs.faceColor = it; AgentService.instance?.refreshFace() } }
        intent.getStringExtra("name")?.let { prefs.assistantName = it }
        intent.getStringExtra("wol")?.let { prefs.wolDevices = it }
        intent.getStringExtra("mcp_servers")?.let { prefs.mcpServers = it; AgentService.instance?.reloadRemoteServers() }
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun build(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#141917"))
        }
        fun text(t: String, size: Float = 14f, color: String = "#E4E9E6") = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(Color.parseColor(color))
            setPadding(0, dp(6), 0, dp(4))
        }
        fun field(value: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

        col.addView(text("✦ ${prefs.assistantName}", 22f, "#6CC79C"))
        col.addView(text("Tu asistente con Gemini dentro de la consola. Ve la pantalla, toca por ti y te responde en voz alta.", 13f, "#9AA6A1"))

        status = text("", 14f)
        col.addView(status)

        col.addView(text("API Key de Gemini", 13f, "#9AA6A1"))
        val key = field(prefs.apiKey, "Pega tu API Key", secret = true)
        col.addView(key)
        col.addView(text("Modelo", 13f, "#9AA6A1"))
        val model = field(prefs.model, Prefs.DEFAULT_MODEL)
        col.addView(model)
        col.addView(text("Personalidad", 13f, "#9AA6A1"))
        val persona = field(prefs.persona, Prefs.DEFAULT_PERSONA).apply { setSingleLine(false); maxLines = 4 }
        col.addView(persona)
        col.addView(text("Modo Alexa", 16f, "#6CC79C"))
        val alexa = CheckBox(this).apply { text = "Siempre encendida y escuchando la palabra de activación"; isChecked = prefs.alexaMode; setTextColor(Color.WHITE) }
        col.addView(alexa)
        col.addView(text("Palabra de activación", 13f, "#9AA6A1"))
        val wakeWord = field(prefs.wakeWord, "monkey")
        col.addView(wakeWord)
        col.addView(text("Voz (Charon, Orus, Iapetus, Algenib, Kore, Puck…)", 13f, "#9AA6A1"))
        val voiceName = field(prefs.voiceName, "Charon")
        col.addView(voiceName)
        col.addView(text("Color de la cara (${FaceColors.names.keys.joinToString()}, #RRGGBB o \"ánimo\")", 13f, "#9AA6A1"))
        val faceColor = field(prefs.faceColor, "verde")
        col.addView(faceColor)
        val voice = CheckBox(this).apply { text = "Responder en voz alta"; isChecked = prefs.voiceEnabled; setTextColor(Color.WHITE) }
        val bubble = CheckBox(this).apply { text = "Mostrar burbuja flotante"; isChecked = prefs.bubbleEnabled; setTextColor(Color.WHITE) }
        col.addView(voice); col.addView(bubble)

        col.addView(text("Control desde otros agentes (MCP)", 16f, "#6CC79C"))
        val mcpOn = CheckBox(this).apply { text = "Permitir que otros agentes de mi red controlen la consola"; isChecked = prefs.mcpServerEnabled; setTextColor(Color.WHITE) }
        col.addView(mcpOn)
        mcpInfo = text("", 13f, "#9AA6A1").apply { setTextIsSelectable(true) }
        col.addView(mcpInfo)
        col.addView(text("Servidores MCP externos (una línea: nombre|url|clave)", 13f, "#9AA6A1"))
        val servers = field(prefs.mcpServers, "mac|http://192.168.1.10:8800/mcp|clave").apply { setSingleLine(false); minLines = 2 }
        col.addView(servers)
        col.addView(text("Computadoras para prender (una línea: nombre|MAC|IP)", 13f, "#9AA6A1"))
        val wol = field(prefs.wolDevices, "pc|AA:BB:CC:DD:EE:FF|192.168.1.20").apply { setSingleLine(false); minLines = 2 }
        col.addView(wol)
        val serversStatus = text("", 13f, "#9AA6A1")
        col.addView(serversStatus)

        col.addView(button("Guardar") {
            prefs.apiKey = key.text.toString(); prefs.model = model.text.toString(); prefs.persona = persona.text.toString()
            prefs.voiceEnabled = voice.isChecked; prefs.bubbleEnabled = bubble.isChecked
            prefs.mcpServerEnabled = mcpOn.isChecked; prefs.mcpServers = servers.text.toString()
            prefs.wolDevices = wol.text.toString()
            prefs.alexaMode = alexa.isChecked; prefs.wakeWord = wakeWord.text.toString()
            prefs.voiceName = voiceName.text.toString()
            if (FaceColors.isValid(faceColor.text.toString())) prefs.faceColor = faceColor.text.toString()
            else faceColor.setText(prefs.faceColor)
            AgentService.instance?.let {
                if (bubble.isChecked) it.showBubble() else it.hideBubble()
                it.refreshFace()
                it.setMcpServerEnabled(mcpOn.isChecked)
                serversStatus.text = "Conectando…"
                it.reloadRemoteServers { s -> serversStatus.text = s }
            }
            refreshStatus()
        })
        col.addView(button("1. Activar el servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2. Permitir micrófono (opcional)") {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        })
        col.addView(button("Abrir el asistente") {
            AgentService.instance?.let { moveTaskToBack(true); it.showPanel() } ?: refreshStatus()
        })
        col.addView(text("Ábrelo con la burbuja flotante. Dentro del panel: A elige, X habla, B interrumpe o cierra.", 13f, "#9AA6A1"))
        return ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#141917")) }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val service = AgentService.instance != null
        val key = prefs.apiKey.isNotBlank()
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        status.text = listOf(
            (if (service) "✅" else "❌") + " Servicio de accesibilidad",
            (if (key) "✅" else "❌") + " API Key",
            (if (mic) "✅" else "➖") + " Micrófono",
        ).joinToString("\n")
        status.gravity = Gravity.START
        val ip = AgentService.instance?.localIp() ?: "?"
        mcpInfo.text = if (prefs.mcpServerEnabled) "Dirección: http://$ip:8765/mcp\nClave: ${prefs.mcpToken}" else "Apagado"
    }
}
