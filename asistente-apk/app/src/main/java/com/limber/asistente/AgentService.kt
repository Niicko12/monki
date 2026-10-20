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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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
    private lateinit var backButton: Button   // solo al escribir: cierra el teclado y vuelve a la cara
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
    private lateinit var musicPlay: TextView
    private lateinit var musicProgress: android.widget.SeekBar
    private var musicSeeking = false     // mientras lo arrastras, el avance automático no mueve la barra
    private var musicSeekJob: Job? = null
    private lateinit var musicTime: TextView
    // Lo último que se supo de la canción: el avance se calcula cada segundo sin preguntar a Amazon
    private var musicBitmap: android.graphics.Bitmap? = null
    private var musicTint = Color.parseColor("#18201D")
    private var musicPos = 0L
    private var musicLen = 0L
    private var musicPosAt = 0L
    private var musicPlaying = false
    private var musicTicker: Job? = null
    private var musicPausedSince = 0L
    private var musicSource = ""         // "spotify" si el estado viene de la API de Spotify; si no, del puente de Alexa
    private var musicDeviceType = ""     // tipo de equipo donde suena (Smartphone = la consola)   // con Spotify en pausa más de 1 min el reproductor se oculta
    private lateinit var audioButton: Button
    @Volatile private var audioSwitching = false
    private var musicAt = 0L
    /** Ánimo de la canción que suena (lo decide un modelo por título y artista); null si no hay música. */
    private var musicMood: Animo? = null
    /** Ánimo del video de YouTube abierto (manda sobre el de la música mientras el video está en pantalla). */
    private var videoMood: Animo? = null
    private var musicKey = ""
    private var musicArtUrl = ""
    private lateinit var leftCol: LinearLayout
    private lateinit var detailView: TextView     // detalle de un widget tocado, en el lugar de la cara
    private lateinit var queueScroll: ScrollView  // lista de canciones de lo que suena, también en el lugar de la cara
    private lateinit var queueList: LinearLayout
    private var musicList = ""                    // "spotify:playlist:…" de la canción actual
    private var youtubeView: android.webkit.WebView? = null   // reproductor de YouTube en miniatura, en el lugar de la cara
    private lateinit var youtubeBox: android.widget.FrameLayout
    private var musicTrack = ""
    private var detailJob: Job? = null
    private lateinit var rightCol: LinearLayout
    private var weatherText = "…"
    private var weatherAt = 0L
    /** Tarjeta de cada computadora conectada: título (modelo) y detalle (batería, disco, CPU, RAM). */
    private val pcCards = mutableMapOf<String, Pair<String, String>>()
    /** Alarmas del Echo (JSON de alexa_alarmas), para el widget. */
    private var echoAlarms = org.json.JSONArray()
    private var pcAt = 0L
    private lateinit var inputRow: LinearLayout
    private var typing = false
    private var lastActivity = System.currentTimeMillis()
    private var asleep = false
    private var ringing: Job? = null
    private var followUp = false

    /** Herramientas que ve Gemini: las de la consola más las de servidores MCP conectados. */
    fun allTools(): List<Tool> = localTools + Web.tools(prefs) + WakeOnLan.tools(prefs) + alarms.tools() + memory.tools() + routines.tools() + faceTools() +
        (if (alexa.configured) alexa.tools() else emptyList()) +
        // Con el puente de Alexa en la consola, las mismas herramientas de casa sobran (y confundirían al modelo)
        remoteClients.flatMap { it.asTools() }.filterNot { alexa.configured && Regex("""\.(alexa_[a-z_]+|spotify_lista)$""").containsMatchIn(it.name) }

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
                if (remoteClients.size < configured) {
                    reloadRemoteServers().join()
                    // Si sigue sin conectar, quizá el router le cambió la IP a la computadora: se busca en la red
                    if (remoteClients.size < configured && findMovedServers()) reloadRemoteServers()
                }
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
        if (::stateView.isInitialized && state == State.IDLE) stateView.text = "Di \"Hey ${prefs.assistantName}\""
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

    /**
     * Busca en la red local los servidores de los ajustes que dejaron de responder (el router cambió la IP de la
     * computadora): prueba el mismo puerto en las 254 direcciones de la red y, si un equipo responde como casa con la
     * misma clave, actualiza la dirección guardada. Devuelve true si cambió algo.
     */
    private suspend fun findMovedServers(): Boolean = withContext(Dispatchers.IO) {
        val myIp = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: return@withContext false
        val prefix = myIp.substringBeforeLast(".")
        var changed = false
        val lines = prefs.mcpServers.lines().map { line ->
            val p = line.split("|").map { it.trim() }
            if (p.size < 3 || remoteClients.any { it.alias == McpClient.safeName(p[0]) }) return@map line
            val url = java.net.URL(p[1])
            val port = if (url.port > 0) url.port else 80
            val sem = kotlinx.coroutines.sync.Semaphore(32)
            val found = kotlinx.coroutines.coroutineScope {
                (1..254).map { n ->
                    async {
                        sem.withPermit {
                            val host = "$prefix.$n"
                            runCatching {
                                val c = (java.net.URL("http://$host:$port/voz").openConnection() as java.net.HttpURLConnection).apply {
                                    requestMethod = "POST"; connectTimeout = 400; readTimeout = 1500; doOutput = true
                                    setRequestProperty("Authorization", "Bearer ${p[2]}")
                                }
                                c.outputStream.use { it.write("{}".toByteArray()) }
                                // casa responde 400 ("sin texto") con la clave correcta; 401 sería otra clave
                                val ok = c.responseCode == 400
                                c.disconnect()
                                if (ok) host else null
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull().firstOrNull()
            }
            if (found == null || found == url.host) line
            else { changed = true; android.util.Log.i("Asistente", "${p[0]} se movió a $found"); "${p[0]}|${p[1].replace(url.host, found)}|${p[2]}" }
        }
        if (changed) prefs.mcpServers = lines.joinToString("\n")
        changed
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

    /** Oculta la barra de estado de Android mientras el panel está abierto (vuelve deslizando desde arriba). */
    private fun hideSystemBars() {
        panel?.post {
            panel?.windowInsetsController?.apply {
                hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Pone al Echo en modo de emparejamiento, lo busca por Bluetooth (nombre con "Echo") y lo vincula.
     * Así no hay que pasar por la pantalla de ajustes, que queda debajo del panel de Monki.
     */
    @SuppressLint("MissingPermission")
    private suspend fun pairWithEcho(): String {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter ?: return "Esta consola no tiene Bluetooth."
        bt.bondedDevices.firstOrNull { it.name?.contains("Echo", true) == true }?.let { return "Ya está emparejada con ${it.name}." }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Falta el permiso de ubicación (Android lo pide para buscar equipos Bluetooth)."
        echoCall("alexa_comando", org.json.JSONObject().put("texto", "empareja bluetooth"))
        kotlinx.coroutines.delay(4000)
        val found = kotlinx.coroutines.CompletableDeferred<android.bluetooth.BluetoothDevice?>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, i: android.content.Intent) {
                val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.name?.contains("Echo", true) == true && !found.isCompleted) found.complete(d)
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND))
        try {
            bt.startDiscovery()
            val echo = kotlinx.coroutines.withTimeoutOrNull(25_000) { found.await() } ?: return "No encontré el Echo. Dile \"empareja Bluetooth\" y vuelve a intentar."
            bt.cancelDiscovery()
            // Android pregunta "¿Vincular?": se oculta el panel para que el aviso quede arriba y Monki lo acepta solo
            withContext(Dispatchers.Main) { setOverlaysVisible(false) }
            echo.createBond()
            repeat(20) {
                kotlinx.coroutines.delay(1000)
                if (echo.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    withContext(Dispatchers.Main) { setOverlaysVisible(true) }
                    return "Listo, emparejada con ${echo.name}."
                }
                withContext(Dispatchers.Main) {
                    for (b in listOf("Vincular", "Sincronizar", "Emparejar", "Pair")) {
                        if (!clickText(b).startsWith("No")) break
                    }
                }
            }
            withContext(Dispatchers.Main) { setOverlaysVisible(true) }
            return "Intenté emparejar con ${echo.name}, pero no terminó. Revisa si la consola muestra una pregunta."
        } finally {
            runCatching { unregisterReceiver(receiver) }; bt.cancelDiscovery()
        }
    }

    /**
     * La consola abre la conexión de audio (A2DP) con el Echo emparejado, como un teléfono. Usa la función interna
     * BluetoothA2dp.connect: en Android 11 está oculta a las apps, así que antes se habilita el acceso a funciones
     * internas (VMRuntime.setHiddenApiExemptions por doble reflexión, que funciona hasta Android 11).
     * (El menú de Bluetooth de Android se queda en negro en esta consola: no sirve para tocar el Echo.)
     */
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private suspend fun connectFromSettings(bt: android.bluetooth.BluetoothAdapter): Boolean {
        val echo = bt.bondedDevices.firstOrNull { it.name?.contains("Echo", true) == true } ?: return false
        runCatching {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getMethod.invoke(vmRuntime, "getRuntime", null) as java.lang.reflect.Method
            val setExemptions = getMethod.invoke(vmRuntime, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        }.onFailure { android.util.Log.w("Asistente", "Funciones internas: ${it.javaClass.simpleName}: ${it.message}") }
        val proxy = kotlinx.coroutines.CompletableDeferred<android.bluetooth.BluetoothA2dp?>()
        bt.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, profile: android.bluetooth.BluetoothProfile) { proxy.complete(profile as android.bluetooth.BluetoothA2dp) }
            override fun onServiceDisconnected(p: Int) { if (!proxy.isCompleted) proxy.complete(null) }
        }, android.bluetooth.BluetoothProfile.A2DP)
        val a2dp = kotlinx.coroutines.withTimeoutOrNull(3000) { proxy.await() } ?: return false
        try {
            val ok = a2dp.javaClass.getMethod("connect", android.bluetooth.BluetoothDevice::class.java).invoke(a2dp, echo) as? Boolean
            android.util.Log.i("Asistente", "A2DP connect desde la consola: $ok")
            if (ok != true) return false
            for (i in 0 until 20) { kotlinx.coroutines.delay(500); if (audioOnSpeaker()) return true }
            return audioOnSpeaker()
        } catch (e: Exception) {
            android.util.Log.w("Asistente", "A2DP connect: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
            return false
        } finally {
            bt.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp)
        }
    }

    /** Acepta la pregunta "¿Vincular?" de Android: oculta el panel para que quede visible y toca el botón. */
    private suspend fun acceptPairingDialog() {
        withContext(Dispatchers.Main) { setOverlaysVisible(false) }
        for (i in 0 until 8) {
            kotlinx.coroutines.delay(500)
            val done = withContext(Dispatchers.Main) {
                listOf("Pair", "Vincular", "Sincronizar", "Emparejar").any { !clickText(it).startsWith("No") }
            }
            if (done) break
        }
        kotlinx.coroutines.delay(800)
        withContext(Dispatchers.Main) { setOverlaysVisible(true) }
    }

    /** ¿El audio de la consola sale ahora por Bluetooth (el Echo)? */
    private fun audioOnSpeaker(): Boolean = (getSystemService(AUDIO_SERVICE) as AudioManager)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    /**
     * Por dónde sale el sonido de la consola: se le pide al Echo que se conecte o desconecte nombrando a la consola
     * ("conéctate a rk3566" / "desconéctate de rk3566"); a secas ("conecta bluetooth") a veces no obedece.
     * Si el Echo no se desconecta, se apaga el Bluetooth (en esta consola eso borra el emparejamiento; al volver a la
     * bocina se empareja solo otra vez).
     */
    @SuppressLint("MissingPermission")
    private suspend fun setAudioOutput(toSpeaker: Boolean): String {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            ?: return "Esta consola no tiene Bluetooth."
        // Consola con Spotify sonando en otro equipo (el Echo): la música se pasa a Spotify de la consola (Spotify Connect).
        // Se pregunta en vivo a Spotify, porque con el panel cerrado Monki no sigue la música.
        if (!toSpeaker && spotify.configured && spotifyPlayingElsewhere()) {
            if (audioOnSpeaker()) setAudioOutputBluetoothOnly(false, bt)
            return musicToConsole()
        }
        // ¿Sonaba un video? Al cambiar de salida Android puede pausarlo: se reanuda al terminar
        val videoWasPlaying = youtubeState() == 1
        android.util.Log.i("Asistente", "Video antes del cambio: ${youtubeState()}")
        // ¿Sonaba Spotify antes del cambio? Al soltar el Bluetooth Android lo pausa (como al quitar unos audífonos)
        val wasPlaying = spotify.configured && withContext(Dispatchers.IO) {
            runCatching { spotify.nowPlaying()?.optString("estado") == "sonando" }.getOrDefault(false)
        }
        val r = setAudioOutputBluetoothOnly(toSpeaker, bt)
        if (!toSpeaker && spotify.configured) {
            for (i in 0 until 6) {
                kotlinx.coroutines.delay(1000)
                // A veces Spotify brinca a sonar en el Echo mismo: se regresa a la consola
                if (spotifyPlayingElsewhere()) return musicToConsole()
            }
            if (wasPlaying) withContext(Dispatchers.IO) {
                runCatching { if (spotify.nowPlaying()?.optString("estado") != "sonando") spotify.play() }
            }
        }
        if (videoWasPlaying) {
            kotlinx.coroutines.delay(800)
            val after = youtubeState()
            android.util.Log.i("Asistente", "Video después del cambio: $after")
            if (after != 1) youtubePlay()
        }
        return r
    }

    private val spotify by lazy { Spotify(prefs) }

    private fun isConsoleDevice(d: Spotify.Device) =
        d.type.equals("Smartphone", true) || d.name.contains(android.os.Build.MODEL, true) || d.name.contains("RG353", true)

    /** ¿Spotify está activo en otro equipo que no es la consola (p. ej. el Echo)? */
    private suspend fun spotifyPlayingElsewhere(): Boolean = withContext(Dispatchers.IO) {
        runCatching { spotify.devices().any { it.active && !isConsoleDevice(it) } }.getOrDefault(false)
    }

    /**
     * Pasa lo que suena en el Echo a Spotify de la consola, en la misma canción y segundo. Si la consola todavía no
     * aparece como dispositivo, abre Spotify en segundo plano para que se registre.
     */
    private suspend fun musicToConsole(): String = withContext(Dispatchers.IO) {
        fun consoleDevice() = spotify.devices().firstOrNull { isConsoleDevice(it) }
        runCatching {
            var dev = consoleDevice()
            if (dev == null) {
                withContext(Dispatchers.Main) {
                    packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                for (i in 0 until 10) { kotlinx.coroutines.delay(1000); dev = consoleDevice(); if (dev != null) break }
            }
            val target = dev ?: return@runCatching "No encontré Spotify de la consola. Ábrelo una vez y vuelve a intentar."
            youtubePause()
            spotify.transfer(target.id)
            withContext(Dispatchers.Main) { musicPausedSince = System.currentTimeMillis() - 120_000; refreshMusic(); updateAudioButton() }
            "Listo, la música sigue en la consola."
        }.getOrElse { "No pude pasar la música a la consola: ${it.message}" }
    }

    @SuppressLint("MissingPermission")
    private suspend fun setAudioOutputBluetoothOnly(toSpeaker: Boolean, bt: android.bluetooth.BluetoothAdapter): String {
        if (audioOnSpeaker() == toSpeaker) {
            withContext(Dispatchers.Main) { updateAudioButton() }
            return if (toSpeaker) "El sonido ya sale por la bocina." else "El sonido ya sale por la consola."
        }
        if (toSpeaker && !bt.isEnabled) {
            @Suppress("DEPRECATION") bt.enable()
            for (i in 0 until 20) { if (bt.isEnabled) break; kotlinx.coroutines.delay(500) }
            kotlinx.coroutines.delay(2000)
        }
        // Si se perdió el vínculo (pasa cuando el Echo pide re-emparejar y nadie acepta), se empareja otra vez solo
        if (toSpeaker && bt.bondedDevices.none { it.name?.contains("Echo", true) == true }) {
            withContext(Dispatchers.Main) { if (::caption.isInitialized) caption.text = "🔊 Emparejando con el Echo…" }
            val r = pairWithEcho()
            if (bt.bondedDevices.none { it.name?.contains("Echo", true) == true }) return r
        }
        // Al conectarse, el Echo a veces pide emparejar de nuevo: se acepta solo (si nadie acepta, Android borra el vínculo)
        val pairingReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, i: android.content.Intent) {
                scope.launch { acceptPairingDialog() }
            }
        }
        if (toSpeaker) registerReceiver(pairingReceiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST))
        try {
        // La consola se conecta al Echo: Monki abre los ajustes de Bluetooth y toca el Echo, como se haría a mano
        // (Android no deja que una app normal abra la conexión de audio por código).
        if (toSpeaker && connectFromSettings(bt)) {
            withContext(Dispatchers.Main) { updateAudioButton() }
            return "Listo, la consola se conectó al Echo: el sonido sale por la bocina."
        }
        if (!echoAvailable()) return "No hay conexión con el Echo."
        val name = bt.name
        val orders = if (toSpeaker) listOfNotNull(name?.let { "conéctate a $it" }, "conecta bluetooth")
            else listOfNotNull(name?.let { "desconéctate de $it" })
        for (order in orders) {
            echoCall("alexa_comando", org.json.JSONObject().put("texto", order))
            for (i in 0 until if (toSpeaker) 15 else 4) {
                kotlinx.coroutines.delay(1000)
                if (audioOnSpeaker() == toSpeaker) {
                    withContext(Dispatchers.Main) { updateAudioButton() }
                    return if (toSpeaker) "Listo, el sonido de la consola sale por la bocina." else "Listo, el sonido sale por la consola."
                }
            }
        }
        } finally {
            if (toSpeaker) runCatching { unregisterReceiver(pairingReceiver) }
        }
        // Consola: si el Echo no soltó la conexión, se apaga el Bluetooth (se pierde el emparejamiento; la bocina lo rehace sola)
        if (!toSpeaker && bt.isEnabled) {
            @Suppress("DEPRECATION") bt.disable()
            for (i in 0 until 10) { if (!audioOnSpeaker()) break; kotlinx.coroutines.delay(500) }
            withContext(Dispatchers.Main) { updateAudioButton() }
            if (!audioOnSpeaker()) return "Listo, el sonido sale por la consola."
        }
        withContext(Dispatchers.Main) { updateAudioButton() }
        return if (toSpeaker) "El Echo no se conectó; intenta de nuevo." else "No pude regresar el sonido a la consola."
    }

    /** Volumen: si suena Spotify en el Echo, el del Echo; si no, el de la consola (que va al Echo si está por Bluetooth). */
    private fun currentVolumeTarget() =
        if (musicPlaying && ::musicBar.isInitialized && musicBar.visibility == View.VISIBLE && !audioOnSpeaker() &&
            !musicDeviceType.equals("Smartphone", true)) "bocina" else "consola"

    private fun updateAudioButton() {
        // Spotify sonando en el Echo cuenta como "bocina": tocar el botón lo trae a la consola
        if (::audioButton.isInitialized) audioButton.text =
            if (audioOnSpeaker() || (musicPlaying && musicSource == "spotify" && !musicDeviceType.equals("Smartphone", true))) "🔊 Bocina" else "📺 Consola"
    }

    /** Muestra a dónde va el volumen y en qué nivel está. */
    private fun showVolume(level: Int? = null) {
        if (!::caption.isInitialized) return
        val pct = level ?: if (currentVolumeTarget() == "consola") consolePercent() else null
        val bar = pct?.let { p -> " " + "▮".repeat((p + 5) / 10) + "▯".repeat(10 - (p + 5) / 10) + " $p%" } ?: ""
        caption.text = (if (currentVolumeTarget() == "bocina") "🔊 Spotify en la bocina" else if (audioOnSpeaker()) "🔊 Bocina (Bluetooth)" else "📺 Consola") + bar
    }

    private fun consolePercent(): Int {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    /** Volumen de la bocina de la consola (videos, juegos, la voz de Monki). */
    private fun consoleVolume(up: Boolean) {
        (getSystemService(AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        showVolume()
    }

    /** Volumen del Echo que está sonando (de 5 en 5). */
    private fun echoVolume(cambio: Int) {
        if (musicSource == "spotify") {
            scope.launch {
                val level = withContext(Dispatchers.IO) {
                    runCatching {
                        val now = spotify.nowPlaying()?.optInt("volumen", -1) ?: -1
                        val v = ((if (now >= 0) now else 50) + cambio).coerceIn(0, 100)
                        spotify.volume(v); v
                    }.getOrNull()
                }
                if (level != null) showVolume(level)
            }
            return
        }
        if (!echoAvailable()) { if (::caption.isInitialized) caption.text = "🔊 Bocina: sin conexión con el Echo"; return }
        scope.launch {
            val r = echoCall("alexa_volumen", org.json.JSONObject().put("cambio", cambio))
            val level = r?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
            if (level != null) showVolume(level) else if (r != null && ::caption.isInitialized) caption.text = "🔊 Bocina: $r"
        }
    }

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
            eyeColor = moodColor()
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
        // Botones en forma de píldora: oscuros, o del color del ánimo si son la acción principal.
        // Enfocados con la cruceta se marcan con borde blanco.
        fun btn(label: String, main: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (main) Color.parseColor("#0E1311") else Color.parseColor("#E4E9E6"))
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            stateListAnimator = null
            background = buttonBackground(if (main) moodColor() else BTN_DARK)
            setPadding(dp(if (main) 17 else 13), dp(5), dp(if (main) 17 else 13), dp(5))   // después del fondo: el fondo reemplaza el padding
            isFocusable = true; setOnClickListener { onClick() }
        }
        val bg = Color.parseColor("#0E1311")
        faceView = EyesView(this).apply { setBackgroundColor(bg); isFocusable = false; eyeColor = moodColor() }
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
        // Reproductor: lo que suena en Alexa (Spotify): portada, canción, avance y botones; solo aparece si hay música
        musicArt = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#2A332F")) }
            clipToOutline = true
            musicBitmap?.let { setImageBitmap(it) }   // al volver a abrir el panel, la portada que ya teníamos
        }
        musicText = TextView(this).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.WHITE) }
        // Barra de la canción: se arrastra con el dedo, o con la cruceta (izquierda/derecha) estando enfocada
        musicProgress = android.widget.SeekBar(this).apply {
            max = 1000; keyProgressIncrement = 20; isFocusable = true
            setPadding(dp(8), 0, dp(8), 0)
            progressTintList = android.content.res.ColorStateList.valueOf(moodColor())
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A4440"))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || musicLen <= 0) return
                    musicSeeking = true
                    showMusicTime(p * musicLen / 1000)
                    // Con la cruceta no hay "soltar": salta cuando dejas de mover un momento
                    musicSeekJob?.cancel()
                    musicSeekJob = scope.launch { kotlinx.coroutines.delay(800); seekMusic(p * musicLen / 1000) }
                }
                override fun onStartTrackingTouch(b: android.widget.SeekBar) { musicSeeking = true; musicSeekJob?.cancel() }
                override fun onStopTrackingTouch(b: android.widget.SeekBar) {
                    musicSeekJob?.cancel(); seekMusic(b.progress * musicLen / 1000)
                }
            })
        }
        musicTime = TextView(this).apply { setTextColor(Color.parseColor("#B5C0BB")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) }
        fun roundBtn(label: String, main: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; setTextColor(if (main) Color.parseColor("#0E1311") else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (main) 18f else 15f)
            background = buttonBackground(if (main) moodColor() else BTN_DARK, oval = true, inset = 0)
            isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        }
        musicPlay = roundBtn("▶", main = true) { musicControl(if (musicPlaying) "pausa" else "play") }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0)
            addView(musicText)
            addView(musicProgress, LinearLayout.LayoutParams(-1, dp(18)).apply { setMargins(-dp(8), 0, -dp(8), 0) })
            addView(musicTime)
        }
        musicArt.setOnClickListener { showQueue() }
        info.setOnClickListener { showQueue() }
        musicBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(5), dp(4), dp(8), dp(4))
            background = musicBackground()
            addView(musicArt, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            addView(roundBtn("⏮") { musicControl("anterior") }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(musicPlay, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(6), 0, dp(6), 0) })
            addView(roundBtn("⏭") { musicControl("siguiente") }, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
        // Widgets en columnas a los lados de la cara: grandes y sin taparla
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        faceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leftCol, LinearLayout.LayoutParams(dp(WIDGET_W), -1))
            detailView = TextView(this@AgentService).apply {
                visibility = View.GONE; setTextColor(Color.parseColor("#E4E9E6")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { hideDetail() }
            }
            queueList = LinearLayout(this@AgentService).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
            queueScroll = ScrollView(this@AgentService).apply { visibility = View.GONE; addView(queueList) }
            youtubeBox = android.widget.FrameLayout(this@AgentService).apply { visibility = View.GONE }
            addView(android.widget.FrameLayout(this@AgentService).apply {
                addView(faceView, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(youtubeBox, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                addView(detailView, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                addView(queueScroll, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(rightCol, LinearLayout.LayoutParams(dp(WIDGET_W), -1))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(stateView, LinearLayout.LayoutParams(0, -2, 1f))
            clockView = TextView(this@AgentService).apply {
                setTextColor(Color.parseColor("#9AA6A1")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, 0, dp(10), 0)
            }
            addView(clockView)
            // Por dónde sale el sonido de la consola: tocarlo cambia entre su bocina y el Echo (por Bluetooth)
            audioButton = btn("📺 Consola") {
                if (audioSwitching) return@btn   // ya está cambiando: un segundo toque lo revertiría a medias
                audioSwitching = true
                audioButton.text = "⏳ Cambiando…"
                scope.launch {
                    // A la consola si el sonido ya va al Echo (Bluetooth) o si Spotify suena en otro equipo; si no, al Echo
                    val toSpeaker = !audioOnSpeaker() && !(spotify.configured && spotifyPlayingElsewhere())
                    audioButton.text = if (toSpeaker) "🔊 Conectando…" else "📺 Cambiando…"
                    val r = try { setAudioOutput(toSpeaker) } finally { audioSwitching = false }
                    updateAudioButton()
                    if (::caption.isInitialized) caption.text = r
                }
            }
            addView(audioButton)
            addView(btn("🕘") {
                val show = logScroll.visibility != View.VISIBLE
                logScroll.visibility = if (show) View.VISIBLE else View.GONE
                faceView.visibility = if (show) View.GONE else View.VISIBLE
                caption.visibility = faceView.visibility
                faceRow.visibility = faceView.visibility
                if (show) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            })
            // YouTube: abre el teclado con el pedido empezado, solo falta escribir qué video
            addView(btn("▶ YouTube") {
                input.setText("Pon en YouTube "); input.setSelection(input.text.length)
                setTypingMode(true)
            })
            addView(btn("✚") { agent.reset(); log.text = ""; say("Conversación nueva. ¿En qué te ayudo?", speak = false) })
            addView(btn("✕") { hidePanel() })
        }
        input = EditText(this).apply {
            hint = "Escribe tu pedido…"; setHintTextColor(Color.parseColor("#7D8A84")); setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#18201D")); setStroke(dp(1), Color.parseColor("#34403B"))
            }, dp(2))
            setPadding(dp(14), dp(5), dp(14), dp(5))
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            setOnFocusChangeListener { _, has -> setTypingMode(has) }
            setOnClickListener { setTypingMode(true) }
        }
        micButton = btn("🎙 Hablar (X)", main = true) { toggleMic() }
        backButton = btn("← Volver") { leaveTyping() }.apply { visibility = View.GONE }
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(micButton)
            addView(btn("➤") { send() })
        }
        val row = inputRow
        // Pantalla completa como xiaozhi: ojos grandes al centro, la respuesta abajo.
        // El panel lee los botones directamente (sin filtrar teclas, para no afectar a los juegos).
        return object : LinearLayout(this) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN && asleep) { wakeUp(); return true }
                // Con un detalle abierto, cualquier botón regresa a la cara (la lista de canciones sí se navega con la cruceta)
                if (e.action == KeyEvent.ACTION_DOWN && ::detailView.isInitialized && detailView.visibility == View.VISIBLE &&
                    e.keyCode != KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) { hideDetail(); return true }
                if (e.action == KeyEvent.ACTION_DOWN && ringing != null) { ringing?.cancel(); ringing = null; startWake(); return true }
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) when (e.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_X -> { toggleMic(); return true }
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                        if (::detailView.isInitialized && (detailView.visibility == View.VISIBLE || queueScroll.visibility == View.VISIBLE)) { hideDetail(); return true }
                        if (youtubeView != null) { closeYoutube(); return true }
                        if (job?.isActive == true || state == State.SPEAKING || recorder.isRecording) interrupt() else hidePanel()
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        val up = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        if (currentVolumeTarget() == "bocina") echoVolume(if (up) 5 else -5) else consoleVolume(up)
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        findFocus()?.let { if (it is EditText) showKeyboardFor(it) else it.performClick() }
                        return true
                    }
                }
                if (e.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK)) return true
                // El volumen lo maneja Monki (consola o bocina); manteniéndolo presionado sigue subiendo la consola
                if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && currentVolumeTarget() == "consola")
                        consoleVolume(e.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    return true
                }
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
    /** Sale del modo escribir: cierra el teclado y regresa a la cara. */
    private fun leaveTyping() {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        setTypingMode(false)
        micButton.requestFocus()
    }

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
        // Al escribir: botón para volver, y sin "Hablar" para dejar espacio al texto
        backButton.visibility = if (on) View.VISIBLE else View.GONE
        micButton.visibility = if (on) View.GONE else View.VISIBLE
        if (::musicBar.isInitialized && on) musicBar.visibility = View.GONE
        if (!on) refreshMusic()
        if (on) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            input.post { showKeyboardFor(input) }
        }
    }

    fun togglePanel() = if (panel?.isShown == true) hidePanel() else showPanel()

    fun showPanel() {
        if (panel == null) {
            val (w, h) = screenSize
            // Pantalla completa: la ventana ocupa también la franja de la barra de estado de Android (que se oculta)
            val lp = overlayParams(w, h, true).apply {
                fitInsetsTypes = 0
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN
                gravity = Gravity.TOP or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            panel = buildPanel().also { wm.addView(it, lp) }
            hideSystemBars()
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
    fun announce(text: String) { lastWasVoice = false; say(text) }

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
            // 1) Voz de Edge desde la computadora ("casa"): natural y rápida (~1 s).
            //    Con la Mac apagada no se vuelve a intentar en 2 min, para no esperar su tiempo de conexión en cada respuesta.
            val edge = if (System.currentTimeMillis() - edgeFailedAt < EDGE_RETRY_MS) null else withContext(Dispatchers.IO) {
                remoteClients.firstNotNullOfOrNull { c -> runCatching { c.voice(text) }.getOrNull() }
            }.also { if (it == null && remoteClients.isNotEmpty()) edgeFailedAt = System.currentTimeMillis() }
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
    private var edgeFailedAt = 0L

    /**
     * Después de cada respuesta: en modo Alexa sigue escuchando unos segundos, luego vuelve a la palabra de activación.
     * Solo si el pedido fue por voz, y nunca con música sonando: el micrófono la tomaba por voz, grababa 30 s,
     * la transcribía y "respondía", y eso volvía a encender la escucha.
     */
    private fun afterTurn() {
        if (!prefs.alexaMode) return
        val follow = lastWasVoice && !musicPlaying
        lastWasVoice = false
        if (follow) { followUp = true; toggleMic() } else startWake()
    }
    private var lastWasVoice = false

    private fun stopVoice() {
        visualizer?.run { runCatching { enabled = false }; release() }
        visualizer = null
        setTalking(false)
        wake.strict = youtubeView != null   // con un video abierto sigue estricto
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
        val animo = videoMood ?: musicMood ?: currentMood()
        val recent = emotion != Emotion.NEUTRAL && System.currentTimeMillis() - emotionAt < EMOTION_SHOWN_MS
        val expr = when (state) {
            State.IDLE -> if (recent) emotion.toExpression() else animo.expression
            State.LISTENING -> Expression.LISTENING
            State.THINKING -> Expression.THINKING
            State.SPEAKING -> if (emotion == Emotion.NEUTRAL) Expression.SPEAKING else emotion.toExpression()
        }
        // Con música, la cara toma el color y el ánimo de la canción, y se mece si es alegre
        val color = moodColor()   // el mismo color en la cara, los widgets y los botones
        val dance = musicMood in listOf(Animo.FELIZ, Animo.EMOCIONADO) && state == State.IDLE
        if (::faceView.isInitialized) {
            faceView.expression = expr
            faceView.eyeColor = color
            faceView.dancing = dance
            stateView.text = detail ?: if (state == State.IDLE) "${state.label} · ánimo: ${animo.tag}${if (musicMood != null) " 🎵" else ""}" else state.label
        }
        bubble?.let { it.expression = expr; it.eyeColor = color; it.dancing = dance }
    }

    /** Emparejar con el Echo o cambiar la salida de sonido (por adb: --es audio emparejar|bocina|consola). */
    fun audioCommand(cmd: String) {
        scope.launch {
            val r = when (cmd.trim().lowercase()) {
                "emparejar" -> pairWithEcho()
                "bocina" -> setAudioOutput(true)
                else -> setAudioOutput(false)
            }
            android.util.Log.i("Asistente", "Audio: $r")
            if (::caption.isInitialized) caption.text = r
        }
    }

    /** Fija el ánimo a mano (por adb: --es animo feliz). */
    fun setMood(tag: String) {
        Animo.entries.firstOrNull { it.tag.equals(tag.trim(), true) }?.let { mood.set(it); emotion = Emotion.NEUTRAL; refreshFace() }
    }

    /** Vuelve a pintar la cara (color y ánimo) sin contar como actividad. */
    fun refreshFace() {
        drawWidgets()
        tintMainButtons()
        updateAudioButton()   // por si el Echo se conectó o desconectó por Bluetooth
        if (asleep) { moodColor().let { c -> if (::faceView.isInitialized) faceView.eyeColor = c; bubble?.eyeColor = c }; return }
        applyFace()
    }

    private fun currentMood(): Animo {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return mood.current(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging)
    }

    private fun faceColor(animo: Animo = currentMood()) =
        FaceColors.parse(prefs.faceColor) ?: Color.parseColor(animo.color)

    /**
     * Video de YouTube en miniatura, en el lugar de la cara (reproductor oficial para insertar, en un WebView).
     * El origen falso da a YouTube un "referer": sin él, los videos insertados en apps fallan (error 153).
     */
    private fun showYoutube(id: String, title: String) {
        if (panel == null) showPanel()
        // Que no suenen Spotify y el video a la vez: pausa al instante con la tecla de pausa del sistema (la recibe la
        // app que esté sonando en la consola) y, por si acaso, también por la API de Spotify
        (getSystemService(AUDIO_SERVICE) as AudioManager).let { am ->
            if (am.isMusicActive) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            }
        }
        if (spotify.configured) scope.launch(Dispatchers.IO) {
            runCatching {
                val now = spotify.nowPlaying()
                // Esté donde esté (consola o Echo), para que el video se escuche solo
                if (now?.optString("estado") == "sonando") spotify.pause()
            }
        }
        hideDetail()
        closeYoutube()
        val accent = moodColor()
        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun error(code: String) { post { onYoutubeError(code.toIntOrNull() ?: -1) } }
            }, "Monki")
            isFocusable = false
            // enablejsapi: Monki puede saber si el video suena (window.ytState: 1 = reproduciendo) y reanudarlo
            val html = """<html><body style="margin:0;background:#000">
                <iframe id="yt" width="100%" height="100%" src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=https://monki.app"
                 frameborder="0" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
                 referrerpolicy="strict-origin-when-cross-origin"></iframe>
                <script>
                  window.ytState = -1;
                  window.ytInfo = {t: 0, d: 0, title: ''};
                  var yt = document.getElementById('yt');
                  function cmd(f, a) { yt.contentWindow.postMessage(JSON.stringify({event: 'command', func: f, args: a || ''}), '*'); }
                  yt.addEventListener('load', function () {
                    setTimeout(function () { yt.contentWindow.postMessage(JSON.stringify({event: 'listening', id: 1}), '*'); }, 500);
                  });
                  window.addEventListener('message', function (e) {
                    try {
                      var d = JSON.parse(e.data);
                      if (d.info && d.info.playerState !== undefined) window.ytState = d.info.playerState;
                      if (d.info && d.info.currentTime !== undefined) window.ytInfo.t = d.info.currentTime;
                      if (d.info && d.info.duration) window.ytInfo.d = d.info.duration;
                      if (d.info && d.info.videoData && d.info.videoData.title) window.ytInfo.title = d.info.videoData.title;
                      if (d.event === 'onError') Monki.error(String(d.info));   // 101/150 = su dueño no deja verlo en otras apps
                    } catch (x) {}
                  });
                </script></body></html>"""
            loadDataWithBaseURL("https://monki.app/", html, "text/html", "utf-8", null)
        }
        youtubeView = web
        youtubeBox.removeAllViews()
        youtubeBox.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.BLACK); setStroke(dp(1), accent) }
        youtubeBox.clipToOutline = true
        youtubeBox.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        youtubeBox.addView(TextView(this).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = buttonBackground(Color.parseColor("#AA000000"), oval = true, inset = 0)
            isFocusable = true; setOnClickListener { closeYoutube() }
        }, android.widget.FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        youtubeBox.visibility = View.VISIBLE
        faceView.visibility = View.INVISIBLE
        wake.strict = true   // el audio del video no debe despertar a Monki ("hey monki" sigue funcionando)
        if (::caption.isInitialized) caption.text = "▶ $title"
        youtubeId = id; youtubeTitle = title
        startYoutubeBar()
        // El ánimo del video tiñe la cara, los widgets y los botones mientras está abierto
        scope.launch {
            val animo = classifyMood("Video de YouTube: \"$title\"") ?: return@launch
            if (youtubeView == null) return@launch
            videoMood = animo
            android.util.Log.i("Asistente", "Ánimo del video \"$title\": ${animo.tag}")
            refreshFace()
        }
    }

    private var youtubeResults: List<Pair<String, String>> = emptyList()
    private var youtubeId = ""
    private var youtubeTitle = ""
    private var youtubeTicker: Job? = null
    /** Salida pedida al buscar el video (true bocina, false consola, null = la del botón de arriba). */
    private var youtubeTarget: Boolean? = null

    /** Resultados de YouTube con miniatura y título, en el lugar de la cara; tocar uno lo reproduce. */
    private fun showYoutubeResults(results: List<Pair<String, String>>) {
        if (panel == null) showPanel()
        hideDetail(); closeYoutube()
        youtubeResults = results
        val accent = moodColor()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        list.addView(TextView(this).apply {
            text = "▶ Elige un video"; setTextColor(accent); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(4), 0, 0, dp(4))
        })
        var first: View? = null
        for ((id, title) in results) {
            val thumb = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#2A332F")) }
                clipToOutline = true
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
                background = buttonBackground(Color.TRANSPARENT, inset = 1)
                isFocusable = true; isClickable = true
                setOnClickListener { playYoutubeOn(youtubeTarget ?: audioOnSpeaker(), id, title) }
                addView(thumb, LinearLayout.LayoutParams(dp(80), dp(45)))
                addView(TextView(this@AgentService).apply {
                    text = title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            list.addView(row)
            if (first == null) first = row
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { java.net.URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                }?.let { thumb.setImageBitmap(it) }
            }
        }
        youtubeBox.removeAllViews()
        youtubeBox.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(blend(accent, Color.parseColor("#18201D"), 0.88f)); setStroke(dp(1), accent)
        }
        youtubeBox.addView(ScrollView(this).apply { addView(list) }, android.widget.FrameLayout.LayoutParams(-1, -1))
        youtubeBox.visibility = View.VISIBLE
        faceView.visibility = View.INVISIBLE
        first?.requestFocus()
    }

    /** El video no se puede ver dentro de Monki (su dueño lo prohíbe): aviso y de vuelta a la lista. */
    private fun onYoutubeError(code: Int) {
        android.util.Log.w("Asistente", "YouTube error $code")
        val results = youtubeResults
        if (results.isEmpty()) { closeYoutube(); return }
        showYoutubeResults(results)
        if (::caption.isInitialized) caption.text = "Ese video no se puede ver dentro de Monki; elige otro."
    }

    /** Pone el audio donde se pidió (conectando o soltando el Echo si hace falta) y arranca el video. */
    private fun playYoutubeOn(speaker: Boolean, id: String, title: String) {
        scope.launch {
            if (audioOnSpeaker() != speaker) {
                if (::caption.isInitialized) caption.text = if (speaker) "🔊 Conectando la bocina…" else "📺 Regresando el sonido a la consola…"
                setAudioOutput(speaker)
            }
            showYoutube(id, title)
            if (::caption.isInitialized) caption.text = "▶ $title · ${if (audioOnSpeaker()) "🔊 en la bocina" else "📺 en la consola"}"
        }
    }

    /** Estado del video de YouTube: 1 reproduciendo, 2 pausa, -1 sin video o desconocido. */
    private suspend fun youtubeState(): Int {
        val web = youtubeView ?: return -1
        val r = kotlinx.coroutines.CompletableDeferred<Int>()
        withContext(Dispatchers.Main) { web.evaluateJavascript("window.ytState") { v -> r.complete(v?.trim('"')?.toIntOrNull() ?: -1) } }
        return kotlinx.coroutines.withTimeoutOrNull(1500) { r.await() } ?: -1
    }

    private suspend fun youtubePlay() {
        withContext(Dispatchers.Main) { youtubeView?.evaluateJavascript("cmd('playVideo')", null) }
    }

    /** Una sola cosa sonando a la vez: si Spotify va a sonar en la consola, el video se pausa. */
    private suspend fun youtubePause() {
        withContext(Dispatchers.Main) { youtubeView?.evaluateJavascript("cmd('pauseVideo')", null) }
    }

    /**
     * Con un video abierto, el reproductor de abajo controla el video: miniatura, título, avance, pausa, ±10 s y la
     * barra para adelantar o regresar. Lee el estado del reproductor de YouTube cada segundo.
     */
    private fun startYoutubeBar() {
        youtubeTicker?.cancel()
        musicArtUrl = ""
        youtubeTicker = scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { java.net.URL("https://i.ytimg.com/vi/$youtubeId/mqdefault.jpg").openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }?.let { bmp -> if (::musicArt.isInitialized) { musicArt.setImageBitmap(bmp); musicTint = averageColor(bmp); musicBar.background = musicBackground() } }
            while (youtubeView != null) {
                val web = youtubeView ?: break
                val raw = kotlinx.coroutines.CompletableDeferred<String?>()
                web.evaluateJavascript("JSON.stringify({s: window.ytState, t: window.ytInfo.t, d: window.ytInfo.d, title: window.ytInfo.title})") { raw.complete(it) }
                val j = kotlinx.coroutines.withTimeoutOrNull(1500) { raw.await() }
                    ?.let { runCatching { org.json.JSONObject(org.json.JSONTokener(it).nextValue() as String) }.getOrNull() }
                if (j != null && ::musicBar.isInitialized && !typing) {
                    musicSource = "youtube"
                    musicPlaying = j.optInt("s") == 1
                    musicPlay.text = if (musicPlaying) "⏸" else "▶"
                    musicLen = (j.optDouble("d", 0.0) * 1000).toLong()
                    if (!musicSeeking) { musicPos = (j.optDouble("t", 0.0) * 1000).toLong(); musicPosAt = System.currentTimeMillis() }
                    val title = j.optString("title").ifBlank { youtubeTitle }.let { if (it.length > 42) it.take(41).trimEnd() + "…" else it }
                    val t = android.text.SpannableString("$title\nYouTube")
                    t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
                    t.setSpan(android.text.style.AbsoluteSizeSpan(16, true), 0, title.length, 0)
                    t.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#C9D2CE")), title.length + 1, t.length, 0)
                    t.setSpan(android.text.style.AbsoluteSizeSpan(12, true), title.length + 1, t.length, 0)
                    musicText.text = t
                    musicBar.visibility = View.VISIBLE
                    drawMusicProgress()
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun closeYoutube() {
        youtubeTicker?.cancel(); youtubeTicker = null
        if (musicSource == "youtube") { musicSource = ""; musicArtUrl = ""; musicAt = 0L }
        youtubeView?.apply { loadUrl("about:blank"); destroy() }
        if (youtubeView != null) wake.strict = false
        youtubeView = null
        if (videoMood != null) { videoMood = null; refreshFace() }
        if (!::youtubeBox.isInitialized) return
        youtubeBox.removeAllViews()
        youtubeBox.visibility = View.GONE
        if (::faceRow.isInitialized && faceRow.visibility == View.VISIBLE) faceView.visibility = View.VISIBLE
    }

    /** Herramientas para que Monki cambie su cara por voz. */
    private fun faceTools() = listOf(
        Tool("audio.salida", "Elige por dónde suena el audio de la consola (videos de YouTube, juegos): \"consola\" (su bocina) o " +
            "\"bocina\" (el Echo por Bluetooth; debe estar emparejado una vez). La voz de Monki siempre sale por la consola.",
            schema("destino" to prop("string", "consola | bocina"), required = listOf("destino"))) {
            ToolResult(setAudioOutput(it.optString("destino").contains("bocina")))
        },
        Tool("audio.emparejar", "Empareja la consola con el Echo por Bluetooth (una sola vez) para poder usarlo como bocina.") {
            ToolResult(pairWithEcho())
        },
        Tool("youtube.reproducir", "Busca un video en YouTube y lo reproduce en miniatura en la pantalla de la consola (no en la tele). " +
            "\"donde\": \"consola\" o \"bocina\" solo si el usuario lo dijo; si no, vacío (suena donde esté elegido).",
            schema("consulta" to prop("string", "qué video buscar"), "donde" to prop("string", "consola | bocina | vacío"),
                required = listOf("consulta"))) {
            val found = withContext(Dispatchers.IO) { runCatching { Web.youtubeSearch(it.optString("consulta")) }.getOrDefault(emptyList()) }
            if (found.isEmpty()) return@Tool ToolResult("No encontré videos para eso.")
            val donde = it.optString("donde").lowercase()
            youtubeTarget = when { donde.contains("bocina") -> true; donde.contains("consola") -> false; else -> null }
            // Se muestran los resultados para que el usuario elija (tocar uno lo reproduce)
            withContext(Dispatchers.Main) { showYoutubeResults(found) }
            ToolResult("Listo: ${found.size} videos en pantalla; el usuario elige tocando uno. No hagas nada más (no abras apps ni toques " +
                "la pantalla); responde en una frase que elija su video.")
        },
        Tool("youtube.cerrar", "Cierra el video de YouTube de la pantalla.") {
            withContext(Dispatchers.Main) { closeYoutube() }; ToolResult("Video cerrado.")
        },
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
            // Seguimiento: corto (espera 4 s a que hables y graba máximo 10 s); pedido normal: hasta 30 s
            recorder.waitSeconds = if (followUp) 4 else 8
            recorder.maxSeconds = if (followUp) 10 else 30
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
                pcCards.keys.retainAll(clients.map { it.alias }.toSet())
                for (c in clients) {
                    val raw = withContext(Dispatchers.IO) {
                        runCatching { c.call("pc_info", org.json.JSONObject().put("formato", "json")).text }.getOrNull()
                    }
                    pcCards[c.alias] = pcCard(c.alias, raw)
                }
                if (echoAvailable()) echoCall("alexa_alarmas")?.let { r -> runCatching { org.json.JSONArray(r) }.getOrNull() }?.let { echoAlarms = it }
                drawWidgets()
            }
        }
        drawWidgets()
    }

    /** El hub que tiene Alexa (alexa_reproduciendo), si hay. Solo respaldo: el puente de Alexa vive en la consola. */
    private fun musicClient() = remoteClients.firstOrNull { c -> c.tools.any { it.optString("name") == "alexa_reproduciendo" } }

    private val alexa by lazy { AlexaClient(prefs) }

    /** ¿Hay forma de hablar con el Echo? (puente en la consola o, de respaldo, el de casa). */
    private fun echoAvailable() = alexa.configured || musicClient() != null

    /** Herramienta del Echo: el puente de la consola primero; si falla o no está, el de casa. null si nada respondió. */
    private suspend fun echoCall(tool: String, args: org.json.JSONObject = org.json.JSONObject()): String? = withContext(Dispatchers.IO) {
        (if (alexa.configured) runCatching { alexa.call(tool, args) }.onFailure { android.util.Log.w("Asistente", "Alexa local $tool: ${it.message}") }.getOrNull() else null)
            ?: musicClient()?.let { c -> runCatching { c.call(tool, args).text }.getOrNull() }
    }

    private fun refreshMusic() {
        val c = musicClient()
        if (youtubeView != null) return   // con un video abierto, el reproductor es del video
        if (!echoAvailable() && !spotify.configured) return
        scope.launch {
            // Spotify primero (ve la música en la consola y en el Echo); el puente de Alexa si Spotify no está o falla
            val info = withContext(Dispatchers.IO) {
                (if (spotify.configured) runCatching { spotify.nowPlaying() ?: org.json.JSONObject().put("estado", "nada") }.getOrNull() else null)
                    ?: echoCall("alexa_reproduciendo")?.let { r -> runCatching { org.json.JSONObject(r) }.getOrNull() }
            }
            musicSource = info?.optString("fuente").orEmpty()
            musicDeviceType = info?.optString("tipo_dispositivo").orEmpty()
            if (!::musicBar.isInitialized) return@launch
            val estado = info?.optString("estado").orEmpty()
            if (info == null || estado == "nada" || info.optString("titulo").isBlank()) {
                musicBar.visibility = View.GONE
                if (musicMood != null) { musicMood = null; musicKey = ""; refreshFace() }
                return@launch
            }
            updateMusicMood(info)
            // En pausa se queda 1 min (por si la reanudas) y luego se oculta
            if (estado == "sonando") musicPausedSince = 0L else if (musicPausedSince == 0L) musicPausedSince = System.currentTimeMillis()
            val stale = musicPausedSince != 0L && System.currentTimeMillis() - musicPausedSince > 60_000
            if (stale) {
                musicBar.visibility = View.GONE
                if (musicMood != null) { musicMood = null; musicKey = ""; refreshFace() }
                updateAudioButton()
                return@launch
            }
            if (!typing) musicBar.visibility = View.VISIBLE   // al escribir no tapa la conversación
            musicPlaying = estado == "sonando"
            updateAudioButton()
            musicPlay.text = if (musicPlaying) "⏸" else "▶"
            musicPos = info.optLong("progreso"); musicLen = info.optLong("duracion"); musicPosAt = System.currentTimeMillis()
            musicList = info.optString("lista"); musicTrack = info.optString("cancion")
            val title = info.optString("titulo"); val sub = listOf(info.optString("artista"), info.optString("dispositivo")).filter { it.isNotBlank() }.joinToString(" · ")
            val t = android.text.SpannableString("$title\n$sub")
            t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
            t.setSpan(android.text.style.AbsoluteSizeSpan(16, true), 0, title.length, 0)
            t.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#C9D2CE")), title.length + 1, t.length, 0)
            t.setSpan(android.text.style.AbsoluteSizeSpan(12, true), title.length + 1, t.length, 0)
            musicText.text = t
            drawMusicProgress()
            if (musicTicker?.isActive != true) musicTicker = scope.launch {
                while (::musicBar.isInitialized && musicBar.visibility == View.VISIBLE) { drawMusicProgress(); kotlinx.coroutines.delay(1000) }
            }
            // La portada se baja solo si cambió o si este panel aún no la tiene; si falla, se reintenta en la siguiente vuelta
            val url = info.optString("portada")
            if (url.isNotBlank() && (url != musicArtUrl || musicArt.drawable == null)) {
                withContext(Dispatchers.IO) {
                    // Primero por el hub (el router no resuelve el servidor de imágenes de Spotify); si no, directo
                    val bytes = c?.let { runCatching { it.albumArt(url) }.getOrNull() }
                        ?: runCatching { java.net.URL(url).openStream().use { it.readBytes() } }
                            .onFailure { android.util.Log.w("Asistente", "Portada: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
                    bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                }?.let { bmp ->
                    musicArtUrl = url; musicBitmap = bmp; musicTint = averageColor(bmp)
                    musicArt.setImageBitmap(bmp)
                    musicBar.background = musicBackground()
                }
            }
        }
    }

    /** Fondo de botón: píldora (u óvalo) del color dado; enfocada con la cruceta, borde blanco. */
    private fun buttonBackground(fill: Int, oval: Boolean = false, inset: Int = 2) = android.graphics.drawable.StateListDrawable().apply {
        fun shape(focused: Boolean) = android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
            if (oval) shape = android.graphics.drawable.GradientDrawable.OVAL else cornerRadius = dp(18).toFloat()
            setColor(if (focused && fill == BTN_DARK) Color.parseColor("#34403B") else fill)
            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.parseColor("#34403B"))
        }, dp(inset))
        addState(intArrayOf(android.R.attr.state_focused), shape(true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(true))
        addState(intArrayOf(), shape(false))
    }

    /** Los botones principales (Hablar, pausa) toman el color del ánimo. */
    private fun tintMainButtons() {
        val c = moodColor()
        if (::micButton.isInitialized) micButton.background = buttonBackground(c).also { micButton.setPadding(dp(17), dp(5), dp(17), dp(5)) }
        if (::musicPlay.isInitialized) musicPlay.background = buttonBackground(c, oval = true, inset = 0)
    }

    /** Fondo del reproductor: degradado del color de la portada (oscurecido) al color de las tarjetas. */
    private fun musicBackground() = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(blend(musicTint, Color.parseColor("#0E1311"), 0.55f), Color.parseColor("#18201D"))
    ).apply { cornerRadius = dp(14).toFloat() }

    private fun averageColor(bmp: android.graphics.Bitmap): Int {
        val s = android.graphics.Bitmap.createScaledBitmap(bmp, 8, 8, true)
        var r = 0; var g = 0; var b = 0
        for (x in 0 until 8) for (y in 0 until 8) { val p = s.getPixel(x, y); r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
        return Color.rgb(r / 64, g / 64, b / 64)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())

    /** Barra y tiempo de la canción, avanzando solos mientras suena. */
    private fun drawMusicProgress() {
        if (!::musicProgress.isInitialized || musicLen <= 0 || musicSeeking) return
        val pos = (musicPos + if (musicPlaying) System.currentTimeMillis() - musicPosAt else 0L).coerceAtMost(musicLen)
        musicProgress.progress = (pos * 1000 / musicLen).toInt()
        musicProgress.progressTintList = android.content.res.ColorStateList.valueOf(moodColor())
        showMusicTime(pos)
    }

    private fun showMusicTime(pos: Long) {
        fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
        musicTime.text = "${mmss(pos)} / ${mmss(musicLen)}"
    }

    /** Mueve la canción del Echo a esa posición (alexa_saltar del hub). */
    private fun seekMusic(posMs: Long) {
        musicPos = posMs; musicPosAt = System.currentTimeMillis()
        if (musicSource == "youtube" && youtubeView != null) {
            youtubeView?.evaluateJavascript("cmd('seekTo', [${posMs / 1000.0}, true])", null)
            scope.launch { kotlinx.coroutines.delay(1200); musicSeeking = false }
            return
        }
        if (!echoAvailable() && musicSource != "spotify") { musicSeeking = false; return }
        scope.launch {
            if (musicSource == "spotify") withContext(Dispatchers.IO) { runCatching { spotify.seek(posMs) } }
            else echoCall("alexa_saltar", org.json.JSONObject().put("segundo", posMs / 1000))
            musicSeeking = false
            kotlinx.coroutines.delay(1500); refreshMusic()
        }
    }

    /** Color de todo Monki: el fijo si lo pediste; si no, el del ánimo (el de la canción si suena música). */
    private fun moodColor() = FaceColors.parse(prefs.faceColor) ?: Color.parseColor((videoMood ?: musicMood ?: currentMood()).color)

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
        scope.launch {
            val animo = classifyMood("Canción: \"${info.optString("titulo")}\" de ${info.optString("artista")} (álbum o lista: ${info.optString("album")})")
            if (musicKey != key || animo == null) return@launch
            musicMood = animo
            android.util.Log.i("Asistente", "Ánimo de la canción \"${info.optString("titulo")}\": ${animo.tag}")
            refreshFace()
        }
    }

    /** Qué ánimo transmite una canción o un video, según su descripción (Groq, rápido y gratis). null si no se pudo. */
    private suspend fun classifyMood(description: String): Animo? {
        val g = OpenAiCompat.groq(prefs) ?: return null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                g.quick("$description. ¿Qué ánimo transmite? Responde solo una palabra de: emocionado, feliz, tranquilo, triste, molesto. " +
                    "(fiesta o bailable = emocionado; alegre o divertido = feliz; relajante, lofi o instrumental suave = tranquilo; " +
                    "balada triste, desamor o drama = triste; rock pesado, metal, enojo o terror = molesto)")
            }.getOrNull()
        } ?: return null
        return Animo.entries.firstOrNull { r.lowercase().contains(it.tag) } ?: Animo.TRANQUILO
    }

    private fun musicControl(accion: String) {
        if (musicSource == "youtube" && youtubeView != null) {
            val js = when (accion) {
                "play" -> "cmd('playVideo')"; "pausa" -> "cmd('pauseVideo')"
                "siguiente" -> "cmd('seekTo', [Math.min(window.ytInfo.d, window.ytInfo.t + 10), true])"
                else -> "cmd('seekTo', [Math.max(0, window.ytInfo.t - 10), true])"
            }
            youtubeView?.evaluateJavascript(js, null)
            return
        }
        scope.launch {
            if (accion == "play" && musicDeviceType.equals("Smartphone", true)) youtubePause()
            if (musicSource == "spotify") withContext(Dispatchers.IO) {
                runCatching { when (accion) { "play" -> spotify.play(); "pausa" -> spotify.pause(); "siguiente" -> spotify.next(); "anterior" -> spotify.previous() } }
            } else echoCall("alexa_control", org.json.JSONObject().put("accion", accion))
            kotlinx.coroutines.delay(1500); refreshMusic()
        }
    }

    /** Al tocar un widget: su detalle en el lugar de la cara (se cierra solo, tocándolo o con B). */
    private fun onWidgetTap(left: Boolean, index: Int) {
        val loc = java.util.Locale("es")
        scope.launch {
            when {
                left && index == 0 -> showDetail("🕐 " + java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", loc).format(java.util.Date()),
                    deviceStatus().replace(". ", ".\n"))
                left && index == 1 -> {
                    showDetail("☁ Clima", "Consultando…")
                    val w = withContext(Dispatchers.IO) { runCatching { Web.weather("", prefs.location) }.getOrElse { "No pude ver el clima: ${it.message}" } }
                    showDetail("☁ Clima en ${prefs.location.substringBefore(",").ifBlank { "tu zona" }}", w)
                }
                !left && index == 0 -> {
                    val echo = (0 until echoAlarms.length()).map { echoAlarms.getJSONObject(it) }.joinToString("\n") { a ->
                        "• ${fechaCorta(a.optString("fecha"))} ${a.optString("hora")} · ${a.optString("dispositivo")}" +
                            (a.optString("musica").takeIf { it.isNotBlank() }?.let { " · 🎵 $it" } ?: "")
                    }.ifEmpty { "Sin alarmas en el Echo." }
                    showDetail("⏰ Alarmas", "$echo\n\nEn la consola:\n${alarms.list()}")
                }
                pcCards.isEmpty() -> showDetail("🎮 Consola RG353V", consoleDetail())
                else -> {
                    val alias = pcCards.keys.toList().getOrNull(index - 1) ?: return@launch
                    val c = remoteClients.firstOrNull { it.alias == alias } ?: return@launch
                    showDetail(pcCards[alias]?.first ?: alias, "Consultando…")
                    val info = withContext(Dispatchers.IO) { runCatching { c.call("pc_info", org.json.JSONObject()).text }.getOrElse { "Sin conexión." } }
                    showDetail(pcCards[alias]?.first ?: alias, info.replace(", ", "\n"))
                }
            }
        }
    }

    /** "2026-10-20" → "mar 20 oct" (o "hoy" / "mañana"). */
    private fun fechaCorta(iso: String): String {
        val d = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso) }.getOrNull() ?: return iso
        val hoy = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val manana = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + 86_400_000))
        return when (iso) { hoy -> "hoy"; manana -> "mañana"; else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale("es")).format(d) }
    }

    private fun showDetail(title: String, body: String) {
        if (!::detailView.isInitialized) return
        val accent = moodColor()
        val hint = "Toca aquí o presiona B para volver"
        val t = android.text.SpannableString("$title\n\n$body\n\n$hint")
        t.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#7D8A84")), t.length - hint.length, t.length, 0)
        t.setSpan(android.text.style.AbsoluteSizeSpan(11, true), t.length - hint.length, t.length, 0)
        t.setSpan(android.text.style.ForegroundColorSpan(accent), 0, title.length, 0)
        t.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
        t.setSpan(android.text.style.AbsoluteSizeSpan(17, true), 0, title.length, 0)
        detailView.text = t
        detailView.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(blend(accent, Color.parseColor("#18201D"), 0.88f)); setStroke(dp(1), blend(accent, Color.parseColor("#18201D"), 0.5f))
        }
        detailView.visibility = View.VISIBLE
        queueScroll.visibility = View.GONE
        faceView.visibility = View.INVISIBLE
        restartDetailTimer(10_000)
    }

    /** Lista de canciones de lo que suena (spotify_lista del hub). Tocar una se la pide al Echo. */
    private fun showQueue() {
        if (musicList.isBlank()) { showDetail("🎵 Lista", "Esta música no viene de una lista de Spotify."); return }
        val accent = moodColor()
        queueList.removeAllViews()
        queueList.addView(TextView(this).apply { text = "🎵 Cargando lista…"; setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) })
        queueScroll.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(blend(accent, Color.parseColor("#18201D"), 0.88f)); setStroke(dp(1), blend(accent, Color.parseColor("#18201D"), 0.5f))
        }
        detailView.visibility = View.GONE
        queueScroll.visibility = View.VISIBLE
        faceView.visibility = View.INVISIBLE
        restartDetailTimer(30_000)
        scope.launch {
            val j = withContext(Dispatchers.IO) {
                runCatching { Spotify.playlist(musicList) }.getOrNull()
            }
            val songs = j?.optJSONArray("canciones")
            queueList.removeAllViews()
            queueList.addView(TextView(this@AgentService).apply {
                text = "🎵 " + (j?.optString("nombre")?.ifBlank { null } ?: "Lista"); setTextColor(accent)
                typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(dp(4), 0, 0, dp(6))
            })
            if (songs == null || songs.length() == 0) {
                queueList.addView(TextView(this@AgentService).apply { text = "No pude leer la lista."; setTextColor(Color.WHITE) }); return@launch
            }
            var current: View? = null
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val playing = s.optString("uri") == musicTrack
                val dur = s.optLong("duracion")
                val row = TextView(this@AgentService).apply {
                    val t = s.optString("titulo"); val a = s.optString("artista")
                    val line = android.text.SpannableString("${if (playing) "▶ " else "${i + 1}. "}$t\n$a · ${dur / 60_000}:${"%02d".format(dur / 1000 % 60)}")
                    line.setSpan(android.text.style.ForegroundColorSpan(if (playing) accent else Color.WHITE), 0, line.indexOf("\n"), 0)
                    line.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, line.indexOf("\n"), 0)
                    line.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#9AA6A1")), line.indexOf("\n") + 1, line.length, 0)
                    line.setSpan(android.text.style.AbsoluteSizeSpan(11, true), line.indexOf("\n") + 1, line.length, 0)
                    text = line; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(8), dp(5), dp(8), dp(5))
                    isFocusable = true; isClickable = true
                    background = buttonBackground(if (playing) blend(accent, Color.parseColor("#18201D"), 0.7f) else Color.TRANSPARENT, inset = 1)
                    setOnClickListener {
                        restartDetailTimer(30_000)
                        scope.launch {
                            // Con Spotify Connect se toca dentro de la misma lista; si no, se le pide al Echo
                            val ok = spotify.configured && withContext(Dispatchers.IO) {
                                runCatching { spotify.playInContext(musicList, s.optString("uri")) }.isSuccess
                            }
                            if (!ok) echoCall("alexa_comando", org.json.JSONObject().put("texto", "pon $t de $a en Spotify"))
                            caption.text = "🎵 $t"
                            kotlinx.coroutines.delay(4000); refreshMusic()
                        }
                    }
                }
                queueList.addView(row)
                if (playing) current = row
            }
            current?.let { v -> queueScroll.post { queueScroll.scrollTo(0, maxOf(0, v.top - dp(40))); v.requestFocus() } }
        }
    }

    private fun restartDetailTimer(ms: Long) {
        detailJob?.cancel()
        detailJob = scope.launch { kotlinx.coroutines.delay(ms); hideDetail() }
    }

    private fun hideDetail() {
        detailJob?.cancel()
        if (!::detailView.isInitialized) return
        queueScroll.visibility = View.GONE
        detailView.visibility = View.GONE
        if (faceRow.visibility == View.VISIBLE) faceView.visibility = View.VISIBLE
    }

    /** Del JSON de pc_info a la tarjeta: "💻 MacBook Air" y debajo batería, disco, CPU y RAM. */
    private fun pcCard(alias: String, raw: String?): Pair<String, String> {
        val j = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return "💻 $alias" to "sin conexión"
        val icon = if (j.optString("sistema") == "Windows") "🖥" else "💻"
        val bat = if (j.isNull("bateria")) "🔌" else "🔋${j.optInt("bateria")}%${if (j.optBoolean("cargando")) "⚡" else ""}"
        return "$icon ${j.optString("modelo", alias)}" to
            "$bat  💾${j.optInt("disco_libre_gb")} GB\nCPU ${j.optInt("cpu")}% · RAM ${j.optInt("ram")}%"
    }

    private fun drawWidgets() {
        if (!::faceRow.isInitialized) return
        val loc = java.util.Locale("es")
        val next = echoAlarms.optJSONObject(0)?.let { a ->
            a.optString("hora") to (a.optString("musica").takeIf { it.isNotBlank() }?.let { "🎵 $it" } ?: fechaCorta(a.optString("fecha")))
        } ?: alarms.next()
        val left = listOf(
            java.text.SimpleDateFormat("HH:mm", loc).format(java.util.Date()) to java.text.SimpleDateFormat("EEE d MMM", loc).format(java.util.Date()),
            (if (prefs.location.isBlank()) "☁ —" else weatherText) to prefs.location.ifBlank { "sin ubicación" }.substringBefore(","),
        )
        val right = mutableListOf((next?.let { "⏰ ${it.first}" } ?: "⏰ —") to (next?.second?.take(16) ?: "sin alarmas"))
        right += pcCards.values
        if (pcCards.isEmpty()) right += consoleCard()   // sin computadora conectada: la tarjeta es de la consola
        val accent = moodColor()
        fillColumn(leftCol, left, accent)
        fillColumn(rightCol, right, accent)
    }

    /** Tarjeta de la consola: modelo, batería, almacenamiento libre y RAM en uso. */
    private fun consoleCard(): Pair<String, String> {
        val bat = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val pct = bat?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val charging = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val free = android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes / 1_000_000_000
        val mem = android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        val ram = if (mem.totalMem > 0) 100 - mem.availMem * 100 / mem.totalMem else 0
        return "🎮 RG353V" to "🔋$pct%${if (charging) "⚡" else ""} · 💾 $free GB\nRAM $ram%"
    }

    /** Detalle de la consola al tocar su tarjeta: batería, almacenamiento, memoria, sonido, pantalla y sistema. */
    private fun consoleDetail(): String {
        val bat = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val pct = bat?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val charging = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        val fs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val mem = android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val bright = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 }.getOrDefault(0)
        val up = android.os.SystemClock.elapsedRealtime() / 60_000
        val gb = { b: Long -> "%.1f".format(b / 1e9) }
        return "🔋 Batería: $pct%${if (charging) " (cargando)" else ""} · $temp °C\n" +
            "💾 Almacenamiento: ${gb(fs.availableBytes)} GB libres de ${gb(fs.totalBytes)} GB\n" +
            "🧠 RAM: ${gb(mem.totalMem - mem.availMem)} de ${gb(mem.totalMem)} GB\n" +
            "🔊 Volumen: $vol% · ${if (audioOnSpeaker()) "en la bocina" else "en la consola"}\n" +
            "☀ Brillo: $bright%\n" +
            "🤖 Android ${android.os.Build.VERSION.RELEASE} · encendida ${up / 60} h ${up % 60} min"
    }

    /** Una tarjeta por dato, repartidas a lo alto de la columna. */
    private fun fillColumn(col: LinearLayout, cards: List<Pair<String, String>>, accent: Int) {
        if (col.childCount != cards.size) {
            col.removeAllViews()
            repeat(cards.size) {
                col.addView(TextView(this).apply {
                    gravity = Gravity.CENTER; setPadding(dp(6), dp(4), dp(6), dp(4))
                    isFocusable = true; isClickable = true
                    setOnClickListener { onWidgetTap(col === leftCol, col.indexOfChild(this)) }
                    setOnFocusChangeListener { _, _ -> drawWidgets() }
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
                }, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
        }
        // Tarjetas teñidas con el color del ánimo y con un borde de ese color
        for (i in 0 until col.childCount) (col.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)?.apply {
            val focused = col.getChildAt(i).isFocused
            setColor(blend(accent, Color.parseColor("#18201D"), 0.86f))
            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else blend(accent, Color.parseColor("#18201D"), 0.55f))
        }
        cards.forEachIndexed { i, (big, small) ->
            val text = android.text.SpannableString("$big\n$small")
            text.setSpan(android.text.style.ForegroundColorSpan(accent), 0, big.length, 0)
            // Títulos largos (el modelo de la computadora) más chicos para que no partan la tarjeta
            val bigSize = when { big.length > 10 -> 16; cards.size > 2 -> 18; else -> 24 }
            text.setSpan(android.text.style.AbsoluteSizeSpan(bigSize, true), 0, big.length, 0)
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
        private const val EDGE_RETRY_MS = 2 * 60_000L
        private val BTN_DARK = Color.parseColor("#232C28")
        /** Cuánto sigue prendido el servicio de toques tras el último toque (varios toques seguidos no lo reinician). */
        private const val GESTURE_OFF_MS = 8_000L
        private const val GEMINI_VOICE_RETRY_MS = 30 * 60_000L

        @Volatile var instance: AgentService? = null
    }
}
