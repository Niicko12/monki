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

/** Pantalla de configuración: claves de IA, datos del usuario, permisos, modelo local, Spotify y opciones avanzadas. */
class MainActivity : Activity() {
    private companion object {
        const val LOCAL_MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf"
        const val SPOTIFY_REDIRECT = "http://127.0.0.1:8899/callback"
        const val SPOTIFY_SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"
    }

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
        intent.getStringExtra("spotify_client_id")?.let { prefs.spotifyClientId = it }
        intent.getStringExtra("alexa_cookie")?.let { prefs.alexaCookie = it }
        intent.getStringExtra("alexa_csrf")?.let { prefs.alexaCsrf = it }
        intent.getStringExtra("alexa_refresh")?.let { prefs.alexaRefresh = it }
        intent.getStringExtra("alexa_login")?.let { prefs.alexaLoginCookie = it }
        intent.getStringExtra("alexa_app")?.let { prefs.alexaAppName = it }
        intent.getStringExtra("alexa_principal")?.let { prefs.alexaPrincipal = it }
        intent.getStringExtra("spotify_refresh")?.let { prefs.spotifyRefresh = it }
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
        // Por adb: --es audio emparejar | bocina | consola
        intent.getStringExtra("audio")?.let { AgentService.instance?.audioCommand(it) }
        intent.getStringExtra("color")?.let { if (FaceColors.isValid(it)) { prefs.faceColor = it; AgentService.instance?.refreshFace() } }
        intent.getStringExtra("name")?.let { prefs.assistantName = it }
        intent.getStringExtra("wol")?.let { prefs.wolDevices = it }
        intent.getStringExtra("mcp_servers")?.let { prefs.mcpServers = it; AgentService.instance?.reloadRemoteServers() }
        // Órdenes de prueba por adb: no deja abierta esta pantalla (taparía lo que Monki tiene que ver o tocar)
        if (listOf("decir", "pedir", "audio", "animo", "color").any { intent.hasExtra(it) } && AgentService.instance != null) {
            finish(); return
        }
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun build(): ScrollView {
        val accent = Color.parseColor("#6CC79C")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        fun text(t: String, size: Float = 14f, color: String = "#E4E9E6") = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(4))
        }
        fun field(value: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(value); this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#6E7A75")); setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun pill(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(fill); setStroke(dp(1), stroke)
        }
        fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (primary) Color.parseColor("#0F1412") else Color.WHITE)
            background = if (primary) pill(accent, accent) else pill(Color.parseColor("#26302C"), Color.parseColor("#3A4741"))
            setPadding(dp(14), 0, dp(14), 0); minHeight = dp(40); minimumHeight = dp(40)
            setOnClickListener { onClick() }
        }
        fun card(title: String, subtitle: String? = null): LinearLayout {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#1B2320")); setStroke(dp(1), Color.parseColor("#2C3833"))
                }
            }
            c.addView(text(title, 16f, "#6CC79C").apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
            subtitle?.let { c.addView(text(it, 12f, "#9AA6A1")) }
            col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return c
        }
        fun row(vararg views: android.view.View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, v ->
                addView(v, if (v is EditText) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) })
            }
        }
        fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }

        col.addView(text("✦ ${prefs.assistantName}", 26f, "#6CC79C").apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        col.addView(text("Tu asistente de voz con cara. Configúralo en 3 pasos y di \"hey monki\".", 13f, "#9AA6A1"))
        status = text("", 14f)
        col.addView(status)

        // Paso 1: claves de IA
        val ia = card("1 · Inteligencia (gratis)", "Pega al menos una clave. Con varias, Monki casi nunca se queda sin respuesta.")
        val groq = field(prefs.groqKey, "Clave de Groq (recomendada)", secret = true)
        val nvidia = field(prefs.nvidiaKey, "Clave de NVIDIA", secret = true)
        val openrouter = field(prefs.openRouterKey, "Clave de OpenRouter", secret = true)
        val key = field(prefs.apiKey, "Clave de Gemini (para que vea la pantalla)", secret = true)
        ia.addView(row(groq, button("Obtener") { open("https://console.groq.com/keys") }))
        ia.addView(row(nvidia, button("Obtener") { open("https://build.nvidia.com") }))
        ia.addView(row(openrouter, button("Obtener") { open("https://openrouter.ai/keys") }))
        ia.addView(row(key, button("Obtener") { open("https://aistudio.google.com/apikey") }))

        // Paso 2: sobre ti
        val me = card("2 · Sobre ti", "Para saludarte por tu nombre y darte el clima de tu ciudad.")
        val userName = field(prefs.userName, "Tu nombre")
        val location = field(prefs.location, "Tu ciudad, estado, país")
        me.addView(userName); me.addView(location)

        // Paso 3: permisos
        val perms = card("3 · Permisos", "Monki necesita el servicio de accesibilidad para mostrarse y manejar la consola, y el micrófono para oírte.")
        perms.addView(row(
            button("Activar accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            button("Permitir micrófono") { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) },
        ))

        // Extras
        val local = card("Sin internet (opcional)", "Descarga un modelo pequeño (≈ 430 MB) para que Monki conteste aunque no haya internet o se acaben las cuotas.")
        val localStatus = text(if (localModel().exists()) "✅ Modelo instalado" else "No instalado", 13f)
        local.addView(row(localStatus, button(if (localModel().exists()) "Volver a descargar" else "Descargar") { downloadModel(localStatus) }))

        val spot = card("Spotify (opcional)", "Crea una app gratis en developer.spotify.com con la Redirect URI $SPOTIFY_REDIRECT y pega aquí su Client ID.")
        val spotifyId = field(prefs.spotifyClientId, "Client ID de Spotify")
        val spotifyStatus = text(if (prefs.spotifyRefresh.isNotBlank()) "✅ Conectado" else "Sin conectar", 13f)
        spot.addView(row(spotifyId, button("Conectar") {
            prefs.spotifyClientId = spotifyId.text.toString().trim()
            if (prefs.spotifyClientId.isBlank()) spotifyStatus.text = "Pega primero el Client ID" else connectSpotify(spotifyStatus)
        }))
        spot.addView(spotifyStatus)

        // Avanzado
        val adv = card("Avanzado")
        val advBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        adv.addView(button("Mostrar opciones avanzadas") {
            advBody.visibility = if (advBody.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
        })
        adv.addView(advBody)
        fun labeled(label: String, v: android.view.View) { advBody.addView(text(label, 12f, "#9AA6A1")); advBody.addView(v) }
        val model = field(prefs.model, Prefs.DEFAULT_MODEL)
        val persona = field(prefs.persona, Prefs.DEFAULT_PERSONA).apply { setSingleLine(false); maxLines = 4 }
        val wakeWord = field(prefs.wakeWord, "monkey")
        val voiceName = field(prefs.voiceName, "Charon")
        val faceColor = field(prefs.faceColor, "ánimo")
        labeled("Personalidad", persona)
        labeled("Color (${FaceColors.names.keys.joinToString()}, #RRGGBB o \"ánimo\")", faceColor)
        labeled("Palabra de activación", wakeWord)
        labeled("Voz de Gemini (Charon, Orus, Kore, Puck…)", voiceName)
        labeled("Modelo de Gemini", model)
        val alexa = CheckBox(this).apply { text = "Siempre escuchando la palabra de activación"; isChecked = prefs.alexaMode; setTextColor(Color.WHITE) }
        val voice = CheckBox(this).apply { text = "Responder en voz alta"; isChecked = prefs.voiceEnabled; setTextColor(Color.WHITE) }
        val bubble = CheckBox(this).apply { text = "Mostrar burbuja flotante"; isChecked = prefs.bubbleEnabled; setTextColor(Color.WHITE) }
        val mcpOn = CheckBox(this).apply { text = "Permitir que otros agentes de mi red controlen la consola (MCP)"; isChecked = prefs.mcpServerEnabled; setTextColor(Color.WHITE) }
        advBody.addView(alexa); advBody.addView(voice); advBody.addView(bubble); advBody.addView(mcpOn)
        mcpInfo = text("", 12f, "#9AA6A1").apply { setTextIsSelectable(true) }
        advBody.addView(mcpInfo)
        val servers = field(prefs.mcpServers, "nombre|http://IP:8800/mcp|clave").apply { setSingleLine(false); minLines = 2 }
        val wol = field(prefs.wolDevices, "pc|AA:BB:CC:DD:EE:FF|192.168.1.20").apply { setSingleLine(false); minLines = 2 }
        labeled("Servidores MCP (uno por línea: nombre|url|clave)", servers)
        labeled("Equipos para encender (uno por línea: nombre|MAC|IP)", wol)
        val serversStatus = text("", 12f, "#9AA6A1")
        advBody.addView(serversStatus)

        val saved = text("", 13f, "#9AA6A1")
        val actions = row(
            button("Guardar", primary = true) {
                prefs.groqKey = groq.text.toString().trim(); prefs.nvidiaKey = nvidia.text.toString().trim()
                prefs.openRouterKey = openrouter.text.toString().trim(); prefs.apiKey = key.text.toString().trim()
                prefs.userName = userName.text.toString(); prefs.location = location.text.toString()
                prefs.spotifyClientId = spotifyId.text.toString().trim()
                prefs.model = model.text.toString(); prefs.persona = persona.text.toString()
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
                    if (servers.text.isNotBlank()) { serversStatus.text = "Conectando…"; it.reloadRemoteServers { s -> serversStatus.text = s } }
                }
                saved.text = "✅ Guardado"
                refreshStatus()
            },
            button("Abrir Monki") {
                AgentService.instance?.let { moveTaskToBack(true); it.showPanel() } ?: refreshStatus()
            },
        )
        col.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        col.addView(saved)
        col.addView(text("Controles: \"hey monki\" o X habla · B interrumpe o cierra · cruceta y A eligen.", 12f, "#9AA6A1"))
        return ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#141917")) }
    }

    private fun localModel() = java.io.File(filesDir, "llm.gguf")

    /** Descarga el modelo local (Qwen2.5 0.5B) a files/llm.gguf, con avance en pantalla. */
    private fun downloadModel(status: TextView) {
        status.text = "Descargando… 0%"
        Thread {
            val part = java.io.File(filesDir, "llm.gguf.part")
            val msg = runCatching {
                val conn = java.net.URL(LOCAL_MODEL_URL).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                var done = 0L; var shown = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != shown) { shown = pct; runOnUiThread { status.text = if (pct >= 0) "Descargando… $pct%" else "Descargando… ${done / 1_000_000} MB" } }
                        }
                    }
                }
                if (part.length() < 10_000_000) throw RuntimeException("descarga incompleta")
                part.renameTo(localModel())
                "✅ Modelo instalado"
            }.getOrElse { part.delete(); "No se pudo descargar: ${it.message}" }
            runOnUiThread { status.text = msg }
        }.start()
    }

    /**
     * Conecta Spotify sin computadora: abre el inicio de sesión de Spotify dentro de la app (PKCE, sin client secret),
     * atrapa la redirección y guarda el permiso.
     */
    private fun connectSpotify(status: TextView) {
        val verifier = android.util.Base64.encodeToString(java.security.SecureRandom().generateSeed(64), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).take(96)
        val challenge = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val url = android.net.Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", prefs.spotifyClientId).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT).appendQueryParameter("scope", SPOTIFY_SCOPES)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge).build().toString()
        lateinit var dialog: android.app.Dialog
        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url
                    if (!u.toString().startsWith(SPOTIFY_REDIRECT)) return false
                    dialog.dismiss()
                    val code = u.getQueryParameter("code")
                    if (code == null) { status.text = "No se autorizó: ${u.getQueryParameter("error") ?: "cancelado"}"; return true }
                    status.text = "Conectando…"
                    Thread {
                        val msg = runCatching {
                            val body = "grant_type=authorization_code&code=" + java.net.URLEncoder.encode(code, "UTF-8") +
                                "&redirect_uri=" + java.net.URLEncoder.encode(SPOTIFY_REDIRECT, "UTF-8") +
                                "&client_id=" + java.net.URLEncoder.encode(prefs.spotifyClientId, "UTF-8") + "&code_verifier=" + verifier
                            val conn = java.net.URL("https://accounts.spotify.com/api/token").openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"; conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            conn.outputStream.use { it.write(body.toByteArray()) }
                            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                            prefs.spotifyRefresh = org.json.JSONObject(text).getString("refresh_token")
                            "✅ Conectado"
                        }.getOrElse { "No se pudo conectar: ${it.message}" }
                        runOnUiThread { status.text = msg }
                    }.start()
                    return true
                }
            }
            loadUrl(url)
        }
        dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { setContentView(web); show() }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val service = AgentService.instance != null
        val key = listOf(prefs.groqKey, prefs.nvidiaKey, prefs.openRouterKey, prefs.apiKey).any { it.isNotBlank() }
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        status.text = listOf(
            (if (service) "✅" else "❌") + " Servicio de accesibilidad",
            (if (key) "✅" else "❌") + " Clave de IA",
            (if (mic) "✅" else "❌") + " Micrófono",
        ).joinToString("   ")
        status.gravity = Gravity.START
        val ip = AgentService.instance?.localIp() ?: "?"
        mcpInfo.text = if (prefs.mcpServerEnabled) "Dirección: http://$ip:8765/mcp\nClave: ${prefs.mcpToken}" else "Apagado"
    }
}
