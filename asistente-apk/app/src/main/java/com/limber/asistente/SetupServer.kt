package com.limber.asistente

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Configurar Monki desde el celular: un servidor web pequeño en la red local que muestra un formulario para pegar las
 * claves (escribirlas con el teclado de la consola es tedioso). Solo corre mientras la pantalla de configuración está
 * abierta, y cada petición debe traer la clave aleatoria [token], que va dentro del código QR.
 */
class SetupServer(private val prefs: Prefs, private val onSaved: () -> Unit) {
    val token: String = SecureRandom().let { r -> (1..12).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val s = runCatching { ServerSocket(PORT) }.getOrElse { Log.w("Asistente", "Setup: no pude abrir el puerto: ${it.message}"); return }
        server = s
        Thread {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(client) }; runCatching { client.close() } }.start()
            }
        }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = c.getInputStream().buffered()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return; head.append(b.toChar()); if (head.length > 16_000) return }
        val lines = head.lines()
        val (method, target) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
        val body = if (length in 1..64_000) String(ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }) else ""
        val query = parse(target.substringAfter("?", ""))
        val form = parse(body)
        val ok = (query["t"] ?: form["t"]) == token
        val html = when {
            !ok -> page("Código vencido", "<p>Vuelve a escanear el código QR que muestra Monki en la consola.</p>")
            method == "POST" -> { save(form); onSaved(); page("¡Listo!", "<p>Monki ya tiene tus datos. Puedes cerrar esta página.</p>") }
            else -> page("Configurar Monki", form())
        }
        val bytes = html.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 ${if (ok) 200 else 403} OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun parse(s: String) = s.split("&").filter { it.contains("=") }.associate {
        URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
    }

    /** Solo cambia lo que se escribió: un campo vacío deja el valor que ya había. */
    private fun save(f: Map<String, String>) {
        fun v(k: String) = f[k]?.trim()?.takeIf { it.isNotEmpty() }
        v("groq")?.let { prefs.groqKey = it }
        v("nvidia")?.let { prefs.nvidiaKey = it }
        v("openrouter")?.let { prefs.openRouterKey = it }
        v("gemini")?.let { prefs.apiKey = it }
        v("nombre")?.let { prefs.userName = it }
        v("ciudad")?.let { prefs.location = it }
        v("spotify")?.let { prefs.spotifyClientId = it }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun form(): String {
        fun has(v: String) = if (v.isNotBlank()) " <span class=ok>✓ ya guardada</span>" else ""
        fun key(name: String, label: String, current: String, url: String) =
            "<label>$label${has(current)} <a href=\"$url\" target=_blank>Obtener</a></label><input name=$name type=password autocomplete=off placeholder=\"Pega aquí\">"
        return """
            <form method=post>
            <input type=hidden name=t value="$token">
            <h2>1 · Inteligencia (gratis)</h2>
            <p class=hint>Con una basta; con varias, Monki casi nunca se queda sin respuesta. Deja vacío lo que no cambies.</p>
            ${key("groq", "Clave de Groq (recomendada)", prefs.groqKey, "https://console.groq.com/keys")}
            ${key("nvidia", "Clave de NVIDIA", prefs.nvidiaKey, "https://build.nvidia.com")}
            ${key("openrouter", "Clave de OpenRouter", prefs.openRouterKey, "https://openrouter.ai/keys")}
            ${key("gemini", "Clave de Gemini (para que vea la pantalla)", prefs.apiKey, "https://aistudio.google.com/apikey")}
            <h2>2 · Sobre ti</h2>
            <label>Tu nombre</label><input name=nombre value="${esc(prefs.userName)}">
            <label>Tu ciudad</label><input name=ciudad value="${esc(prefs.location)}" placeholder="Ciudad, estado, país">
            <h2>Spotify (opcional)</h2>
            <p class=hint>Crea una app en <a href="https://developer.spotify.com/dashboard" target=_blank>Spotify for Developers</a> con la Redirect URI <code>http://127.0.0.1:8899/callback</code>. Después toca <b>Conectar</b> en la consola.</p>
            <label>Client ID</label><input name=spotify value="${esc(prefs.spotifyClientId)}">
            <button>Guardar en Monki</button>
            </form>
        """.trimIndent()
    }

    private fun page(title: String, content: String) = """
        <!doctype html><html lang=es><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
        <title>$title</title><style>
        body{margin:0;background:#141917;color:#E4E9E6;font:16px system-ui,sans-serif}
        main{max-width:520px;margin:0 auto;padding:20px 16px 40px}
        h1{color:#6CC79C;margin:8px 0 4px}h2{color:#6CC79C;font-size:17px;margin:24px 0 4px}
        label{display:block;margin:14px 0 6px;font-size:14px}label a{float:right;color:#6CC79C}
        input{box-sizing:border-box;width:100%;padding:12px;border-radius:10px;border:1px solid #3A4741;background:#1B2320;color:#fff;font-size:16px}
        button{margin-top:24px;width:100%;padding:14px;border:0;border-radius:24px;background:#6CC79C;color:#0F1412;font-size:17px;font-weight:600}
        .hint{color:#9AA6A1;font-size:13px}.ok{color:#6CC79C;font-size:12px}a{color:#6CC79C}code{font-size:12px}
        </style></head><body><main><h1>✦ $title</h1>$content</main></body></html>
    """.trimIndent()

    companion object { const val PORT = 8790 }
}
