package com.limber.asistente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Servicio de accesibilidad: ve la pantalla, la controla y muestra la burbuja y el panel del asistente.
 * Se abre con la burbuja o con Select + R1.
 */
class AgentService : AccessibilityService(), Controls {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var agent: Agent
    private lateinit var wm: WindowManager
    private val recorder: VoiceRecorder = VoiceRecorder { scope.launch { if (recorder.isRecording) toggleMic() } }
    private var player: MediaPlayer? = null
    private var job: Job? = null

    private var bubble: EyesView? = null
    private var panel: LinearLayout? = null
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micButton: Button
    private lateinit var faceView: EyesView
    private lateinit var stateView: TextView
    private lateinit var caption: TextView
    private var selectHeld = false

    /** Estados como en xiaozhi: esperando → escuchando → pensando → hablando. */
    private enum class State(val label: String, val face: String) {
        IDLE("Esperando", "🙂"), LISTENING("Escuchando…", "👂"), THINKING("Pensando…", "🤔"), SPEAKING("Hablando…", "🗣")
    }
    private var state = State.IDLE
    private var emotion = Emotion.NEUTRAL
    private var emotionAt = 0L   // la emoción de la última respuesta se ve un rato; luego la cara vuelve al ánimo
    private val mood by lazy { Mood(this) }

    private val localTools by lazy { deviceTools(this) }
    private var remoteClients: List<McpClient> = emptyList()
    private lateinit var mcpServer: McpServer
    private val wake by lazy { WakeWord(this) { scope.launch { onWake() } } }
    private val memory by lazy { Memory(this) }
    private val routines by lazy { Routines(this) }
    private val alarms by lazy { Alarms(this, scope) { text -> ring(text) } }
    private lateinit var clockView: TextView
    private lateinit var faceRow: LinearLayout
    private lateinit var musicBar: LinearLayout
    private lateinit var musicArt: android.widget.ImageView
    private lateinit var musicText: TextView
    private lateinit var musicPlay: Button
    private var musicAt = 0L
    /** Ánimo de la canción que suena (lo decide un modelo por título y artista); null si no hay música. */
    private var musicMood: Animo? = null
    private var musicKey = ""
    private var musicArtUrl = ""
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private var weatherText = "…"
    private var weatherAt = 0L
    private val pcText = mutableMapOf<String, String>()
    private var pcAt = 0L
    private lateinit var inputRow: LinearLayout
    private var typing = false
    private var lastActivity = System.currentTimeMillis()
    private var asleep = false
    private var ringing: Job? = null
    private var followUp = false

    /** Herramientas que ve Gemini: las de la consola más las de servidores MCP conectados. */
    fun allTools(): List<Tool> = localTools + Web.tools(prefs) + WakeOnLan.tools(prefs) + alarms.tools() + memory.tools() + routines.tools() + faceTools() + remoteClients.flatMap { it.asTools() }

    override fun onServiceConnected() {
        GestureService.setEnabled(this, false)   // por si quedó prendido (crash o reinicio)
        LocalLlm.setup(this)
        scope.launch { offlineVoice.warmUp() }
        instance = this
        prefs = Prefs(this)
        agent = Agent(prefs, this, { allTools() }, { memory.all() }, { routines.all() }, { currentMood() })
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mcpServer = McpServer({ localTools }, { prefs.mcpToken })
        if (prefs.mcpServerEnabled) mcpServer.start()
        reloadRemoteServers()
        if (prefs.bubbleEnabled) showBubble()
        // Reconectar a las computadoras cada 2 minutos (por si se prendieron después)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(120_000)
                val configured = McpClient.parse(prefs.mcpServers).size
                if (remoteClients.size < configured) reloadRemoteServers()
            }
        }
        alarms.restore()
        // Barra de estado y reposo (como xiaozhi): reloj, batería, Wi-Fi; dormir tras 5 min sin uso
        scope.launch {
            var warnedBattery = false
            while (true) {
                updateStatusBar()
                updateWidgets()
                val battery = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).isCharging
                if (battery in 1..20 && !charging && !warnedBattery) { warnedBattery = true; say("${prefs.honorific}, me queda $battery por ciento de batería. ¿Me conecta al cargador?") }
                if (charging || battery > 25) warnedBattery = false
                if (!asleep && state == State.IDLE && job?.isActive != true) refreshFace()
                if (job?.isActive != true) withContext(Dispatchers.IO) { LocalLlm.releaseIfIdle() }
                if (!asleep && panel != null && state == State.IDLE && job?.isActive != true &&
                    System.currentTimeMillis() - lastActivity > SLEEP_AFTER_MS) sleep()
                kotlinx.coroutines.delay(15_000)
            }
        }
        if (prefs.alexaMode) {
            showPanel()
            wake.word = prefs.wakeWord
            wake.load { ok -> if (ok) startWake() else say("No pude cargar la palabra de activación.", speak = false) }
        }
    }

    /** Vuelve a escuchar la palabra de activación (modo Alexa). */
    private fun startWake() {
        if (!prefs.alexaMode || recorder.isRecording) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        wake.word = prefs.wakeWord
        wake.start()
        if (::stateView.isInitialized && state == State.IDLE) stateView.text = "Di \"Hey ${prefs.assistantName}\" o presiona X"
    }

    private fun onWake() {
        wakeUp()
        if (panel == null) showPanel()
        // Como xiaozhi: "Hey Monki" mientras habla o piensa lo interrumpe y vuelve a escuchar
        ringing?.cancel(); ringing = null
        if (job?.isActive == true) job?.cancel()
        speakJob?.cancel(); stopVoice()
        setOverlaysVisible(true)
        chime()
        followUp = false
        toggleMic()
    }

    private fun chime() = runCatching {
        android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
    }

    /** Conecta los servidores MCP externos de los ajustes. Devuelve un resumen. */
    fun reloadRemoteServers(onDone: ((String) -> Unit)? = null) = scope.launch {
        val clients = McpClient.parse(prefs.mcpServers)
        val summary = withContext(Dispatchers.IO) {
            clients.map { c -> runCatching { c.connect(); "${c.alias}: ${c.tools.size} herramientas" }.getOrElse { "${c.alias}: ${it.message}" } }
        }
        remoteClients = clients.filter { it.tools.isNotEmpty() }
        pcAt = 0L
        android.util.Log.i("Asistente", "Servidores MCP: " + summary.joinToString(" | "))
        onDone?.invoke(summary.joinToString("\n").ifEmpty { "Sin servidores externos." })
    }

    fun setMcpServerEnabled(on: Boolean) = if (on) mcpServer.start() else mcpServer.stop()

    override fun onDestroy() {
        instance = null
        if (::mcpServer.isInitialized) mcpServer.stop()
        wake.stop()
        scope.cancel()
        recorder.cancel()
        player?.release()
        removeViews()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- atajo Select + R1 y control con botones dentro del panel ----------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld = down
            KeyEvent.KEYCODE_BUTTON_R1 -> if (selectHeld) {
                if (down && event.repeatCount == 0) togglePanel()
                return true
            }
        }
        val p = panel ?: return false
        if (!p.isShown || !p.hasWindowFocus()) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) p.findFocus()?.let { if (it is EditText) showKeyboardFor(it) else it.performClick() }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> { if (down) { if (job?.isActive == true || state == State.SPEAKING) interrupt() else hidePanel() }; true }
            KeyEvent.KEYCODE_BUTTON_X -> { if (down) toggleMic(); true }
            else -> false
        }
    }

    // ---------- interfaz flotante ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (focusable && prefs.alexaMode) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0) or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return
        val size = dp(46)
        val b = EyesView(this).apply {
            setBackgroundResource(R.drawable.bubble)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            expression = emotion.toExpression()
            eyeColor = faceColor()
        }
        val lp = overlayParams(size, size, false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenSize.first - size - dp(4); y = screenSize.second / 3
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy; wm.updateViewLayout(b, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun buildPanel(): LinearLayout {
        val pad = dp(10)
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0)
            isFocusable = true; setOnClickListener { onClick() }
        }
        val bg = Color.parseColor("#0E1311")
        faceView = EyesView(this).apply { setBackgroundColor(bg); isFocusable = false; eyeColor = faceColor() }
        stateView = TextView(this).apply { setTextColor(Color.parseColor("#9AA6A1")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        caption = TextView(this).apply {
            setTextColor(Color.parseColor("#E4E9E6")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER; maxLines = 3; setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        log = TextView(this).apply {
            setTextColor(Color.parseColor("#E4E9E6")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
        }
        logScroll = ScrollView(this).apply { addView(log); isFocusable = false; visibility = View.GONE }
        // Reproductor: lo que suena en Alexa (Spotify) con sus botones; solo aparece si hay música
        musicArt = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        musicText = TextView(this).apply { maxLines = 2; setPadding(dp(10), 0, dp(6), 0); setTextColor(Color.WHITE) }
        musicPlay = btn("⏯") { musicControl(if (musicPlay.tag == "sonando") "pausa" else "play") }
        musicBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#18201D")) }
            addView(musicArt, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(musicText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("⏮") { musicControl("anterior") })
            addView(musicPlay)
            addView(btn("⏭") { musicControl("siguiente") })
        }
        // Widgets en columnas a los lados de la cara: grandes y sin taparla
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        faceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leftCol, LinearLayout.LayoutParams(dp(WIDGET_W), -1))
            addView(faceView, LinearLayout.LayoutParams(0, -1, 1f))
            addView(rightCol, LinearLayout.LayoutParams(dp(WIDGET_W), -1))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(stateView, LinearLayout.LayoutParams(0, -2, 1f))
            clockView = TextView(this@AgentService).apply {
                setTextColor(Color.parseColor("#9AA6A1")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, 0, dp(10), 0)
            }
            addView(clockView)
            addView(btn("Historial") {
                val show = logScroll.visibility != View.VISIBLE
                logScroll.visibility = if (show) View.VISIBLE else View.GONE
                faceView.visibility = if (show) View.GONE else View.VISIBLE
                caption.visibility = faceView.visibility
                faceRow.visibility = faceView.visibility
                if (show) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            })
            addView(btn("Nuevo") { agent.reset(); log.text = ""; say("Conversación nueva. ¿En qué te ayudo?", speak = false) })
            addView(btn("Cerrar") { hidePanel() })
        }
        input = EditText(this).apply {
            hint = "Escribe tu pedido…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            setOnFocusChangeListener { _, has -> setTypingMode(has) }
            setOnClickListener { setTypingMode(true) }
        }
        micButton = btn("🎙 Hablar (X)") { toggleMic() }
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(micButton)
            addView(btn("Enviar") { send() })
        }
        val row = inputRow
        // Pantalla completa como xiaozhi: ojos grandes al centro, la respuesta abajo.
        // El panel lee los botones directamente (sin filtrar teclas, para no afectar a los juegos).
        return object : LinearLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN && asleep) { wakeUp(); return true }
                if (e.action == KeyEvent.ACTION_DOWN && ringing != null) { ringing?.cancel(); ringing = null; startWake(); return true }
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) when (e.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_X -> { toggleMic(); return true }
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                        if (job?.isActive == true || state == State.SPEAKING || recorder.isRecording) interrupt() else hidePanel()
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        findFocus()?.let { if (it is EditText) showKeyboardFor(it) else it.performClick() }
                        return true
                    }
                }
                if (e.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK)) return true
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
            addView(header)
            addView(faceRow, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(musicBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
            addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(caption, LinearLayout.LayoutParams(-1, -2))
            addView(row)
        }
    }

    /** Al escribir, el panel se encoge a la mitad de arriba (el teclado usa la de abajo) y muestra la conversación. */
    private fun setTypingMode(on: Boolean) {
        val root = panel ?: return
        if (on == typing) return
        typing = on
        wakeUp()
        val (_, h) = screenSize
        val lp = root.layoutParams as WindowManager.LayoutParams
        lp.height = if (on) (h * 0.5).toInt() else h
        lp.gravity = Gravity.TOP or Gravity.START
        runCatching { wm.updateViewLayout(root, lp) }
        faceView.visibility = if (on) View.GONE else View.VISIBLE
        caption.visibility = faceView.visibility
        faceRow.visibility = faceView.visibility
        logScroll.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            input.post { showKeyboardFor(input) }
        }
    }

    fun togglePanel() = if (panel?.isShown == true) hidePanel() else showPanel()

    fun showPanel() {
        if (panel == null) {
            val (w, h) = screenSize
            val lp = overlayParams(w, h, true).apply {
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            panel = buildPanel().also { wm.addView(it, lp) }
            setState(state)
            pcAt = 0L; updateWidgets()
            if (log.text.isEmpty()) say("Hola, ¿qué hacemos? Escribe, o presiona X para hablar. B interrumpe.", speak = false)
        }
        setOverlaysVisible(true)
        micButton.requestFocus()
    }

    fun hidePanel() {
        typing = false
        if (job?.isActive != true) startWake()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        if (recorder.isRecording) recorder.cancel()
    }

    private fun removeViews() { hidePanel(); hideBubble() }

    /** Oculta la interfaz mientras el agente mira y toca la pantalla, para no taparla. */
    private fun setOverlaysVisible(visible: Boolean) {
        listOfNotNull(panel, bubble).forEach { v ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            v.alpha = if (visible) 1f else 0f
            lp.flags = if (visible) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (visible && v === panel) lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    private fun showKeyboardFor(e: EditText) {
        e.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(e, 0)
    }

    /** Dice un texto en voz alta (para probar la voz desde adb). */
    fun announce(text: String) = say(text)

    /** Hace un pedido escrito (para probar desde adb). */
    fun askText(text: String) { if (panel == null) showPanel(); turnStart = System.currentTimeMillis(); run(text, null, "Tú: $text") }

    private fun say(text: String, speak: Boolean = true, who: String = "Asistente") {
        if (panel == null && speak) showPanel()
        if (::log.isInitialized) {
            log.append((if (log.text.isEmpty()) "" else "\n\n") + "$who: $text")
            caption.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (speak && prefs.voiceEnabled) speakAloud(text)
    }

    private var speakJob: Job? = null

    /** Aplica las pronunciaciones de los ajustes (nombres que la voz lee mal, por ejemplo agudos por terminar en r). */
    private fun speakable(text: String): String = prefs.pronunciations.lines().fold(
        // Si Monki dice "Alexa" se activa el Echo de la casa
        text.replace(Regex("\\bAlexa\\b", RegexOption.IGNORE_CASE), "la bocina")) { t, line ->
        val (from, to) = line.split("=", limit = 2).map { it.trim() }.let { if (it.size == 2) it[0] to it[1] else return@fold t }
        if (from.isEmpty()) t else t.replace(Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE), to)
    }

    private fun speakAloud(shown: String) {
        val text = speakable(shown)
        speakJob?.cancel()
        speakJob = scope.launch {
            setState(State.SPEAKING)
            // 1) Voz de Edge desde la computadora ("casa"): natural y rápida (~1 s)
            val edge = withContext(Dispatchers.IO) {
                remoteClients.firstNotNullOfOrNull { c -> runCatching { c.voice(text) }.getOrNull() }
            }
            if (edge != null) {
                lap("voz de Edge")
                playVoice(File(cacheDir, "voz.mp3").apply { writeBytes(edge) }, offline = false)
                stopVoice(); setState(State.IDLE); afterTurn()
                return@launch
            }
            // 2) Gemini. Tras un fallo (cuota agotada) no lo reintenta por un rato: va directo a la voz sin internet
            val wav = if (System.currentTimeMillis() - geminiVoiceFailedAt < GEMINI_VOICE_RETRY_MS) null
                else withContext(Dispatchers.IO) {
                    runCatching { Gemini(prefs.apiKey).speech(prefs.ttsModel, text, prefs.voiceName) }.getOrNull()
                }.also { if (it == null) geminiVoiceFailedAt = System.currentTimeMillis() }
            lap(if (wav != null) "voz de Gemini" else "intento de voz de Gemini")
            if (wav != null) {
                val f = File(cacheDir, "voz.wav").apply { writeBytes(wav) }
                playVoice(f, offline = false)
            } else {
                // Voz sin internet por oraciones: suena la primera mientras se genera la siguiente
                val parts = sentences(text)
                val ready = kotlinx.coroutines.channels.Channel<File?>(capacity = 1)
                val producer = launch {
                    parts.forEachIndexed { i, part ->
                        val f = File(cacheDir, "voz_$i.wav")
                        ready.send(if (offlineVoice.synthesize(part, f)) f else null)
                    }
                    ready.close()
                }
                var played = 0
                for (f in ready) {
                    if (f == null) continue
                    playVoice(f, offline = true)
                    played++
                }
                producer.join()
                if (played == 0) android.util.Log.w("Asistente", "Sin voz: Gemini falló y la voz sin internet también")
                else android.util.Log.i("Asistente", "Voz sin internet en $played partes (Gemini sin cuota de voz)")
            }
            stopVoice(); setState(State.IDLE); afterTurn()
        }
    }

    /** Separa en oraciones, juntando las muy cortas para que no suene entrecortado. */
    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        for (p in text.split(Regex("(?<=[.!?…;:])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.isNotEmpty() && out.last().length < 25) out[out.size - 1] = out.last() + " " + p else out += p
        }
        // La primera parte corta (hasta la primera coma) para que la voz empiece a sonar antes
        if (out.isNotEmpty() && out[0].length > 45) {
            val cut = out[0].indexOf(", ").takeIf { it in 12..60 }
            if (cut != null) { val first = out[0]; out[0] = first.substring(0, cut + 1); out.add(1, first.substring(cut + 2)) }
        }
        return out.ifEmpty { listOf(text) }
    }

    /** Reproduce un .wav por la bocina y espera a que termine (cancelable: lo corta "Hey Monki" o un botón). */
    private suspend fun playVoice(f: File, offline: Boolean) = suspendCancellableCoroutine<Unit> { cont ->
        stopVoice()
        player = MediaPlayer().apply {
            setDataSource(f.path)
            // Siempre por la bocina de la consola, aunque haya audífonos conectados
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { setPreferredDevice(it) }
            setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
            prepare()
            // Toque de "IA": reverberación, como la de un mayordomo IA
            runCatching {
                reverb?.release()
                reverb = android.media.audiofx.PresetReverb(1, audioSessionId).apply {
                    preset = if (offline) android.media.audiofx.PresetReverb.PRESET_MEDIUMROOM
                        else android.media.audiofx.PresetReverb.PRESET_SMALLROOM
                    enabled = true
                }
            }
            start()
            wake.strict = true
            startMouth(audioSessionId)
        }
        lap(if (offline) "voz sin internet y empieza a sonar" else "empieza a sonar")
        startWake()   // escuchar "Hey Monki" mientras habla, para poder interrumpirlo
        cont.invokeOnCancellation { scope.launch { stopVoice() } }
    }

    private var reverb: android.media.audiofx.PresetReverb? = null
    private var visualizer: android.media.audiofx.Visualizer? = null

    /** La boca se mueve con el volumen real de la voz; si el Visualizer falla, EyesView la mueve sola. */
    private fun startMouth(session: Int) {
        setTalking(true)
        runCatching {
            visualizer?.release()
            visualizer = android.media.audiofx.Visualizer(session).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                        wave ?: return
                        // Forma de onda en 8 bits sin signo (128 = silencio)
                        var peak = 0
                        for (b in wave) peak = maxOf(peak, kotlin.math.abs((b.toInt() and 0xFF) - 128))
                        val level = ((peak - 6) / 70f).coerceIn(0f, 1f)
                        if (::faceView.isInitialized) faceView.setMouthLevel(level)
                        bubble?.setMouthLevel(level)
                    }
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
        }.onFailure { android.util.Log.w("Asistente", "Boca sin Visualizer: ${it.message}") }
    }

    private fun setTalking(on: Boolean) {
        if (::faceView.isInitialized) faceView.post { faceView.talking = on }
        bubble?.let { b -> b.post { b.talking = on } }
    }

    // Tiempos de cada turno por voz, en el log ("adb logcat -s Tiempos")
    private var turnStart = 0L
    private var lapAt = 0L
    private fun lap(what: String) {
        val now = System.currentTimeMillis()
        if (turnStart == 0L) return
        android.util.Log.i("Tiempos", "$what: ${now - maxOf(lapAt, turnStart)} ms (total ${now - turnStart} ms)")
        lapAt = now
        if (what.contains("sonar")) { turnStart = 0L; lapAt = 0L }
    }
    private val offlineVoice by lazy { OfflineVoice(this) }
    private var geminiVoiceFailedAt = 0L

    /** Después de cada respuesta: en modo Alexa sigue escuchando unos segundos, luego vuelve a la palabra de activación. */
    private fun afterTurn() {
        if (!prefs.alexaMode) return
        if (lastWasVoice) { followUp = true; toggleMic() } else startWake()
    }
    private var lastWasVoice = false

    private fun stopVoice() {
        visualizer?.run { runCatching { enabled = false }; release() }
        visualizer = null
        setTalking(false)
        wake.strict = false
        player?.run { runCatching { stop() }; release() }
        player = null
    }

    /** Corta lo que esté haciendo: la voz y el pedido en curso. */
    private fun interrupt() {
        if (recorder.isRecording) { recorder.cancel(); micButton.text = "🎙 Hablar (X)" }
        speakJob?.cancel(); stopVoice()
        if (job?.isActive == true) { LocalLlm.stop(); job?.cancel(); say("Ok, me detengo.", speak = false) }
        setOverlaysVisible(true)
        setState(State.IDLE)
        followUp = false
        startWake()
    }

    private fun setState(s: State, detail: String? = null) {
        state = s
        lastActivity = System.currentTimeMillis()
        if (asleep && s != State.IDLE) wakeUp()
        applyFace(detail)
    }

    private fun applyFace(detail: String? = null) {
        val animo = musicMood ?: currentMood()
        val recent = emotion != Emotion.NEUTRAL && System.currentTimeMillis() - emotionAt < EMOTION_SHOWN_MS
        val expr = when (state) {
            State.IDLE -> if (recent) emotion.toExpression() else animo.expression
            State.LISTENING -> Expression.LISTENING
            State.THINKING -> Expression.THINKING
            State.SPEAKING -> if (emotion == Emotion.NEUTRAL) Expression.SPEAKING else emotion.toExpression()
        }
        // Con música, la cara toma el color y el ánimo de la canción, y se mece si es alegre
        val color = musicMood?.let { Color.parseColor(it.color) } ?: faceColor(animo)
        val dance = musicMood in listOf(Animo.FELIZ, Animo.EMOCIONADO) && state == State.IDLE
        if (::faceView.isInitialized) {
            faceView.expression = expr
            faceView.eyeColor = color
            faceView.dancing = dance
            stateView.text = detail ?: if (state == State.IDLE) "${state.label} · ánimo: ${animo.tag}${if (musicMood != null) " 🎵" else ""}" else state.label
        }
        bubble?.let { it.expression = expr; it.eyeColor = color; it.dancing = dance }
    }

    /** Fija el ánimo a mano (por adb: --es animo feliz). */
    fun setMood(tag: String) {
        Animo.entries.firstOrNull { it.tag.equals(tag.trim(), true) }?.let { mood.set(it); emotion = Emotion.NEUTRAL; refreshFace() }
    }

    /** Vuelve a pintar la cara (color y ánimo) sin contar como actividad. */
    fun refreshFace() {
        drawWidgets()
        if (asleep) { faceColor().let { c -> if (::faceView.isInitialized) faceView.eyeColor = c; bubble?.eyeColor = c }; return }
        applyFace()
    }

    private fun currentMood(): Animo {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return mood.current(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging)
    }

    private fun faceColor(animo: Animo = currentMood()) =
        FaceColors.parse(prefs.faceColor) ?: Color.parseColor(animo.color)

    /** Herramientas para que Monki cambie su cara por voz. */
    private fun faceTools() = listOf(
        Tool("cara.color", "Cambia el color de tu cara en la pantalla. Colores: ${FaceColors.names.keys.joinToString()}, " +
            "un #RRGGBB, o \"ánimo\" para que cambie con tu estado de ánimo.",
            schema("color" to prop("string", "nombre, #RRGGBB o ánimo"), required = listOf("color"))) {
            val c = it.optString("color")
            if (!FaceColors.isValid(c)) ToolResult("Color no válido.")
            else { prefs.faceColor = c; withContext(Dispatchers.Main) { refreshFace() }; ToolResult("Listo, cara en $c.") }
        },
        Tool("cara.animo", "Cambia tu estado de ánimo: ${Animo.entries.joinToString { it.tag }}.",
            schema("animo" to prop("string", Animo.entries.joinToString(" | ") { it.tag }), required = listOf("animo"))) {
            val a = Animo.entries.firstOrNull { a -> a.tag.equals(it.optString("animo").trim(), true) }
            if (a == null) ToolResult("Ánimo no válido.")
            else { mood.set(a); withContext(Dispatchers.Main) { refreshFace() }; ToolResult("Ahora estás ${currentMood().tag}.") }
        },
    )

    // ---------- pedidos ----------

    private fun toggleMic() {
        if (recorder.isRecording) {
            micButton.text = "🎙 Hablar (X)"
            val heard = recorder.heardVoice
            val wav = recorder.stop()
            wav?.let { runCatching { File(cacheDir, "ultima_grabacion.wav").writeBytes(it) } }
            setState(State.IDLE)
            // Aunque el detector no esté seguro, si hay más de 1 s de audio que lo decida la transcripción
            if (wav == null || (!heard && (followUp || wav.size < RATE_BYTES_1S))) {
                if (!followUp) say("No te escuché. Habla más cerca del micrófono e intenta de nuevo.", speak = false)
                followUp = false
                startWake()
                return
            }
            val wasFollowUp = followUp
            followUp = false
            lastWasVoice = true
            transcribeAndRun(wav, wasFollowUp)
        } else {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                say("Falta el permiso de micrófono. Ábrelo desde la app Asistente.", speak = false); return
            }
            // Como en xiaozhi: hablar mientras el asistente habla lo interrumpe
            speakJob?.cancel(); stopVoice()
            wake.stop()
            if (recorder.start()) {
                micButton.text = "⏹ Listo (X)"; setState(State.LISTENING)
                scope.launch {
                    while (recorder.isRecording) {
                        val bars = (recorder.level / 400).toInt().coerceIn(0, 10)
                        stateView.text = "Escuchando  " + "▮".repeat(bars) + "▯".repeat(10 - bars)
                        kotlinx.coroutines.delay(150)
                    }
                }
            }
            else say("No encontré un micrófono. Conecta audífonos Bluetooth o uno USB.", speak = false)
        }
    }

    /** Paso 1: voz a texto. Paso 2: el texto al asistente. */
    private fun transcribeAndRun(wav: ByteArray, wasFollowUp: Boolean) {
        turnStart = System.currentTimeMillis()
        scope.launch {
            setState(State.THINKING, "Entendiendo…")
            val text = withContext(Dispatchers.IO) {
                // Whisper de Groq primero (gratis y no gasta la cuota de Gemini); Gemini de respaldo
                OpenAiCompat.groq(prefs)?.let { g -> runCatching { g.transcribe(wav) }.onFailure { android.util.Log.w("Asistente", "Whisper: ${it.message}") }.getOrNull() }
                    ?: runCatching { Gemini(prefs.apiKey).transcribe(listOf(prefs.model) + Agent.FALLBACK_MODELS, wav) }
                        .getOrElse { withContext(Dispatchers.Main) { say("Tuve un problema: ${it.message}", speak = false) }; null }
            }
            lap("transcripción (${wav.size / 32_000} s de audio)")
            setState(State.IDLE)
            when {
                text == null -> startWake()
                text.isBlank() -> { if (!wasFollowUp) say("No te entendí. ¿Me lo repites?", speak = false); startWake() }
                else -> run(text, null, "Tú: $text")
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        lastWasVoice = false
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        micButton.requestFocus()
        setTypingMode(false)
        run(text, null, "Tú: $text")
    }

    private fun run(text: String?, wav: ByteArray?, echo: String) {
        if (prefs.apiKey.isBlank() && prefs.groqKey.isBlank()) { say("Falta la API Key de Gemini. Ponla en la app Asistente.", speak = false); return }
        if (job?.isActive == true) return
        lastActivity = System.currentTimeMillis()
        log.append("\n\n$echo")
        job = scope.launch {
            setState(State.THINKING)
            startWake()   // permite interrumpir con "Hey Monki" mientras piensa
            val reply = try {
                agent.ask(text, wav) { status ->
                    if (status == "pensando") setState(State.THINKING)
                    else {
                        if (status.contains("alexa_")) scope.launch { kotlinx.coroutines.delay(4000); refreshMusic(); kotlinx.coroutines.delay(5000); refreshMusic() }
                        setState(State.THINKING, "Usando $status")
                        if (status.startsWith("self.screen") || status.startsWith("self.system") || status.startsWith("self.app"))
                            setOverlaysVisible(false)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply("Tuve un problema: ${e.message}", Emotion.TRISTE)
            }
            lap("respuesta (${reply.text.length} letras)")
            emotion = reply.emotion
            emotionAt = System.currentTimeMillis()
            mood.onReply(reply.emotion)
            if (panel == null) showPanel() else setOverlaysVisible(true)
            setState(State.IDLE)
            say(reply.text)
            if (!prefs.voiceEnabled) afterTurn()
        }
    }

    // ---------- Controls: lo que Gemini puede hacer ----------

    override val screenSize: Pair<Int, Int>
        get() = wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }

    override suspend fun screenshotJpeg(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                val out = ByteArrayOutputStream()
                bmp?.compress(Bitmap.CompressFormat.JPEG, 60, out)
                cont.resume(if (bmp != null) out.toByteArray() else null)
            }
            override fun onFailure(errorCode: Int) = cont.resume(null)
        })
    }

    private var gestureOff: Job? = null

    /** Toca con GestureService y lo apaga a los pocos segundos, para que los juegos vuelvan a recibir la cruceta. */
    private suspend fun gesture(path: Path, ms: Long): Boolean {
        gestureOff?.cancel()
        val ok = GestureService.gesture(this, path, ms)
        gestureOff = scope.launch { kotlinx.coroutines.delay(GESTURE_OFF_MS); GestureService.setEnabled(this@AgentService, false) }
        return ok
    }

    override suspend fun tap(x: Int, y: Int) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, ms.coerceIn(50, 3000))

    override fun globalAction(name: String): Boolean {
        val action = when (name) {
            "ATRAS" -> GLOBAL_ACTION_BACK
            "INICIO" -> GLOBAL_ACTION_HOME
            "RECIENTES" -> GLOBAL_ACTION_RECENTS
            "NOTIFICACIONES" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        windows.filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .forEach { walk(it.root) }
        if (out.isEmpty()) walk(rootInActiveWindow)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    override fun clickText(text: String): String {
        val want = text.trim().lowercase()
        val nodes = allNodes().filter { label(it).isNotEmpty() && it.isVisibleToUser }
        val match = nodes.firstOrNull { label(it).lowercase() == want } ?: nodes.firstOrNull { label(it).lowercase().contains(want) }
            ?: return "No encontré \"$text\" en pantalla."
        var n: AccessibilityNodeInfo? = match
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Hice clic en \"${label(match)}\"."
        val r = Rect().also { match.getBoundsInScreen(it) }
        scope.launch { tap(r.centerX(), r.centerY()) }
        return "Toqué \"${label(match)}\" en (${r.centerX()}, ${r.centerY()})."
    }

    override fun typeText(text: String): String {
        val target = allNodes().firstOrNull { it.isFocused && it.isEditable } ?: allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            ?: return "No hay un campo de texto en pantalla."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Escribí el texto." else "No pude escribir."
    }

    override fun readScreen(): String {
        val r = Rect()
        val lines = allNodes().filter { it.isVisibleToUser && (label(it).isNotEmpty() || it.isEditable) }.take(150).map {
            it.getBoundsInScreen(r)
            val tags = buildList { if (it.isClickable) add("clic"); if (it.isEditable) add("texto"); if (it.isFocused) add("foco") }
            "\"${label(it).take(60)}\" (${r.centerX()},${r.centerY()})" + if (tags.isEmpty()) "" else " [${tags.joinToString()}]"
        }
        return if (lines.isEmpty()) "No veo elementos con texto (puede ser un juego)." else lines.joinToString("\n")
    }

    override fun listApps(): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { "${it.loadLabel(pm)} = ${it.activityInfo.packageName}" }
            .distinct().sorted().joinToString("\n")
    }

    override fun openApp(pkg: String): String {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return "No existe la app $pkg."
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Abrí $pkg."
    }

    override fun volume(action: String, level: Int?): String {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        when (action.lowercase()) {
            "subir" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "bajar" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "silenciar" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "nivel" -> am.setStreamVolume(s, ((level ?: 50).coerceIn(0, 100) * am.getStreamMaxVolume(s)) / 100, AudioManager.FLAG_SHOW_UI)
            else -> return "Acción de volumen desconocida."
        }
        return "Volumen: ${am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s)}%."
    }

    override fun setBrightness(level: Int): String {
        if (!android.provider.Settings.System.canWrite(this)) return "No tengo permiso para cambiar el brillo."
        val v = (level.coerceIn(0, 100) * 255) / 100
        android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, v.coerceAtLeast(5))
        return "Brillo al ${level.coerceIn(0, 100)}%."
    }

    override fun deviceStatus(): String {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val bright = runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        }.getOrDefault(-1)
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val app = rootInActiveWindow?.packageName ?: "?"
        val time = java.text.SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", java.util.Locale("es")).format(java.util.Date())
        return "Batería $battery%${if (charging) " (cargando)" else ""}. Wi-Fi ${if (wifi) "conectado" else "sin conexión"}. " +
            "Volumen $vol%. Brillo ${if (bright >= 0) "$bright%" else "?"}. App en pantalla: $app. Hora: $time. " +
            "Servidor MCP: ${if (prefs.mcpServerEnabled) "${localIp()}:${mcpServer.port}" else "apagado"}."
    }

    /** Barra de estado: hora, Wi-Fi y batería. */
    private fun updateStatusBar() {
        if (!::clockView.isInitialized) return
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val bat = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es")).format(java.util.Date())
        val pcs = if (remoteClients.isNotEmpty()) "  ·  🖥 ${remoteClients.size}" else ""
        clockView.text = "$hora  ·  ${if (online) "Wi-Fi" else "sin internet"}$pcs  ·  ${if (bm.isCharging) "⚡" else ""}$bat%"
    }

    /**
     * Widgets bajo la cara: hora y fecha, clima de la casa, próxima alarma y cada computadora conectada
     * (con pc_info del hub "casa"). El clima se pide cada 20 min y las computadoras cada minuto.
     */
    private fun updateWidgets() {
        if (!::faceRow.isInitialized || panel == null) return
        val now = System.currentTimeMillis()
        if (now - weatherAt > 20 * 60_000 && prefs.location.isNotBlank()) {
            weatherAt = now
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { Web.weatherShort(prefs.location) }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && it.length < 30 && !it.contains("<") }?.let { weatherText = it; drawWidgets() }
            }
        }
        if (now - musicAt > 10_000) { musicAt = now; refreshMusic() }
        if (now - pcAt > 60_000) {
            pcAt = now
            scope.launch {
                val clients = remoteClients.filter { c -> c.tools.any { it.optString("name") == "pc_info" } }
                pcText.keys.retainAll(clients.map { it.alias }.toSet())
                for (c in clients) {
                    pcText[c.alias] = withContext(Dispatchers.IO) {
                        runCatching { c.call("pc_info", org.json.JSONObject().put("corto", true)).text }.getOrDefault("sin conexión")
                    }
                }
                drawWidgets()
            }
        }
        drawWidgets()
    }

    /** El hub que tiene Alexa (alexa_reproduciendo), si hay. */
    private fun musicClient() = remoteClients.firstOrNull { c -> c.tools.any { it.optString("name") == "alexa_reproduciendo" } }

    private fun refreshMusic() {
        val c = musicClient() ?: return
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { org.json.JSONObject(c.call("alexa_reproduciendo", org.json.JSONObject()).text) }.getOrNull()
            }
            if (!::musicBar.isInitialized) return@launch
            val estado = info?.optString("estado").orEmpty()
            if (info == null || estado == "nada" || info.optString("titulo").isBlank()) {
                musicBar.visibility = View.GONE
                if (musicMood != null) { musicMood = null; musicKey = ""; refreshFace() }
                return@launch
            }
            updateMusicMood(info)
            musicBar.visibility = View.VISIBLE
            musicPlay.tag = estado
            musicPlay.text = if (estado == "sonando") "⏸" else "▶"
            val title = info.optString("titulo"); val sub = listOf(info.optString("artista"), info.optString("dispositivo")).filter { it.isNotBlank() }.joinToString(" · ")
            val t = android.text.SpannableString("$title\n$sub")
            t.setSpan(android.text.style.ForegroundColorSpan(faceColor()), 0, title.length, 0)
            t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
            t.setSpan(android.text.style.AbsoluteSizeSpan(15, true), 0, title.length, 0)
            t.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#B5C0BB")), title.length + 1, t.length, 0)
            t.setSpan(android.text.style.AbsoluteSizeSpan(12, true), title.length + 1, t.length, 0)
            musicText.text = t
            val url = info.optString("portada")
            if (url.isNotBlank() && url != musicArtUrl) {
                musicArtUrl = url
                withContext(Dispatchers.IO) {
                    runCatching { java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                }?.let { musicArt.setImageBitmap(it) }
            }
        }
    }

    /** Al cambiar de canción, pregunta (a Groq, rápido y gratis) qué ánimo tiene; en pausa la cara vuelve a lo suyo. */
    private fun updateMusicMood(info: org.json.JSONObject) {
        if (info.optString("estado") != "sonando") {
            if (musicMood != null) { musicMood = null; refreshFace() }
            musicKey = ""
            return
        }
        val key = info.optString("titulo") + "|" + info.optString("artista")
        if (key == musicKey) return
        musicKey = key
        val g = OpenAiCompat.groq(prefs) ?: return
        scope.launch {
            val opciones = "emocionado, feliz, tranquilo, triste, molesto"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    g.quick("Canción: \"${info.optString("titulo")}\" de ${info.optString("artista")} (álbum o lista: ${info.optString("album")}). " +
                        "¿Qué ánimo transmite? Responde solo una palabra de: $opciones. " +
                        "(fiesta o bailable = emocionado; alegre = feliz; relajante, lofi o instrumental suave = tranquilo; " +
                        "balada triste o desamor = triste; rock pesado, metal o enojo = molesto)")
                }.getOrNull()
            }
            if (musicKey != key) return@launch
            musicMood = Animo.entries.firstOrNull { r?.lowercase()?.contains(it.tag) == true } ?: Animo.TRANQUILO
            android.util.Log.i("Asistente", "Ánimo de la canción \"${info.optString("titulo")}\": ${musicMood?.tag} ($r)")
            refreshFace()
        }
    }

    private fun musicControl(accion: String) {
        val c = musicClient() ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { c.call("alexa_control", org.json.JSONObject().put("accion", accion)) } }
            kotlinx.coroutines.delay(1500); refreshMusic()
        }
    }

    private fun drawWidgets() {
        if (!::faceRow.isInitialized) return
        val loc = java.util.Locale("es")
        val next = alarms.next()
        val left = listOf(
            java.text.SimpleDateFormat("HH:mm", loc).format(java.util.Date()) to java.text.SimpleDateFormat("EEE d MMM", loc).format(java.util.Date()),
            (if (prefs.location.isBlank()) "☁ —" else weatherText) to prefs.location.ifBlank { "sin ubicación" }.substringBefore(","),
        )
        val right = mutableListOf((next?.let { "⏰ ${it.first}" } ?: "⏰ —") to (next?.second?.take(16) ?: "sin alarmas"))
        pcText.forEach { (alias, t) -> right += "💻 $alias" to t.replace(" · ", "\n") }
        val accent = faceColor()
        fillColumn(leftCol, left, accent)
        fillColumn(rightCol, right, accent)
    }

    /** Una tarjeta por dato, repartidas a lo alto de la columna. */
    private fun fillColumn(col: LinearLayout, cards: List<Pair<String, String>>, accent: Int) {
        if (col.childCount != cards.size) {
            col.removeAllViews()
            repeat(cards.size) {
                col.addView(TextView(this).apply {
                    gravity = Gravity.CENTER; setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#18201D")) }
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
        }
        cards.forEachIndexed { i, (big, small) ->
            val text = android.text.SpannableString("$big\n$small")
            text.setSpan(android.text.style.ForegroundColorSpan(accent), 0, big.length, 0)
            text.setSpan(android.text.style.AbsoluteSizeSpan(if (cards.size > 2) 18 else 24, true), 0, big.length, 0)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, big.length, 0)
            text.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#B5C0BB")), big.length + 1, text.length, 0)
            text.setSpan(android.text.style.AbsoluteSizeSpan(13, true), big.length + 1, text.length, 0)
            (col.getChildAt(i) as TextView).text = text
        }
    }

    /** Reposo como xiaozhi: ojos dormidos y pantalla tenue; sigue escuchando "Hey Monki". */
    private fun sleep() {
        asleep = true
        if (::faceView.isInitialized) faceView.expression = Expression.SLEEPY
        bubble?.expression = Expression.SLEEPY
        if (::stateView.isInitialized) stateView.text = "Durmiendo… di \"Hey ${prefs.assistantName}\""
        setPanelBrightness(0.03f)
    }

    private fun wakeUp() {
        lastActivity = System.currentTimeMillis()
        if (!asleep) return
        asleep = false
        setPanelBrightness(-1f)
        setState(state)
    }

    private fun setPanelBrightness(b: Float) {
        val p = panel ?: return
        val lp = p.layoutParams as WindowManager.LayoutParams
        lp.screenBrightness = b
        runCatching { wm.updateViewLayout(p, lp) }
    }

    /** Suena una alarma o recordatorio: muestra, suena y lo dice. Se calla con cualquier botón o "Hey Monki". */
    private fun ring(text: String) {
        wakeUp()
        if (panel == null) showPanel()
        emotion = Emotion.SORPRESA
        emotionAt = System.currentTimeMillis()
        setState(State.IDLE, "⏰ $text")
        ringing?.cancel()
        ringing = scope.launch {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 90)
            repeat(8) { tone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900); kotlinx.coroutines.delay(1500) }
            tone.release()
            ringing = null
            say("${prefs.honorific}, le recuerdo: ${text.removePrefix("Temporizador: ")}")
        }
    }

    override fun foregroundApp(): String = rootInActiveWindow?.packageName?.toString() ?: "?"

    fun localIp(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull() ?: "?"

    companion object {
        private const val RATE_BYTES_1S = 16_000 * 2 + 44
        private const val SLEEP_AFTER_MS = 5 * 60_000L
        private const val EMOTION_SHOWN_MS = 30_000L
        private const val WIDGET_W = 140
        /** Cuánto sigue prendido el servicio de toques tras el último toque (varios toques seguidos no lo reinician). */
        private const val GESTURE_OFF_MS = 8_000L
        private const val GEMINI_VOICE_RETRY_MS = 30 * 60_000L

        @Volatile var instance: AgentService? = null
    }
}
