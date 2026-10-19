package com.limber.asistente

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente MCP por HTTP (Streamable HTTP) para usar herramientas de otros servidores:
 * la Mac, la casa u otros dispositivos. Llamar fuera del hilo principal.
 */
class McpClient(val alias: String, private val url: String, private val token: String) {

    private val ids = AtomicInteger(1)
    private var sessionId: String? = null
    var tools: List<JSONObject> = emptyList()
        private set

    private fun post(body: JSONObject): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("$alias respondió $code")
        if (text.isBlank()) return null
        // Respuesta en SSE: tomar el último "data:" con JSON
        val json = if (text.trimStart().startsWith("{")) text
        else text.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull { it.startsWith("{") } ?: return null
        return JSONObject(json)
    }

    private fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val res = post(JSONObject().put("jsonrpc", "2.0").put("id", ids.getAndIncrement()).put("method", method).put("params", params))
            ?: throw RuntimeException("$alias no respondió")
        res.optJSONObject("error")?.let { throw RuntimeException(it.optString("message")) }
        return res.optJSONObject("result") ?: JSONObject()
    }

    fun connect() {
        request("initialize", JSONObject().put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "asistente-rg353v").put("version", "2.0")))
        runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")) }
        val list = request("tools/list").optJSONArray("tools") ?: JSONArray()
        tools = (0 until list.length()).map { list.getJSONObject(it) }
    }

    /** Llama una herramienta y junta su texto; si trae imagen, la devuelve también. */
    fun call(name: String, args: JSONObject): ToolResult {
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", args))
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        var jpeg: ByteArray? = null
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            when (c.optString("type")) {
                "text" -> text.append(c.optString("text")).append('\n')
                "image" -> if (c.optString("mimeType").contains("jpeg") || c.optString("mimeType").contains("png"))
                    jpeg = Base64.decode(c.optString("data"), Base64.DEFAULT)
            }
        }
        return ToolResult(text.toString().trim().ifEmpty { "Listo." }, jpeg)
    }

    /** Las herramientas remotas como [Tool], con prefijo del alias para no chocar con las locales. */
    fun asTools(): List<Tool> = tools.map { t ->
        val remote = t.getString("name")
        Tool(name = safeName("$alias.$remote"), description = "[$alias] " + t.optString("description"),
            inputSchema = t.optJSONObject("inputSchema") ?: schema()) { args ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { call(remote, args) }
        }
    }

    companion object {
        fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(64)

        /** Formato de la lista en ajustes: una línea por servidor, "alias|url|clave". */
        fun parse(config: String): List<McpClient> = config.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split("|").map { it.trim() }
                if (p.size < 2) null else McpClient(safeName(p[0]), p[1], p.getOrElse(2) { "" })
            }
    }
}
