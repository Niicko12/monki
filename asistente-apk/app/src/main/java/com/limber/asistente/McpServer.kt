package com.limber.asistente

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Servidor MCP de la consola (JSON-RPC 2.0 por HTTP, POST /mcp), como el lado dispositivo de xiaozhi.
 * Otros agentes en la red pueden listar y llamar las herramientas self.* con la clave de acceso.
 */
class McpServer(private val tools: () -> List<Tool>, private val token: () -> String, val port: Int = 8765) {

    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val s = runCatching { ServerSocket(port) }.getOrElse { Log.w(TAG, "No pude abrir el puerto $port", it); return }
        socket = s
        thread(name = "mcp-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }.onFailure { Log.w(TAG, "Error", it) }; client.close() }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = client.getOutputStream()
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (method == "GET" && path == "/") return respond(out, 200, "text/plain", "Asistente MCP: POST /mcp")
        if (method != "POST" || !path.startsWith("/mcp")) return respond(out, 404, "text/plain", "No encontrado")
        if (headers["authorization"] != "Bearer ${token()}") return respond(out, 401, "application/json", """{"error":"clave incorrecta"}""")

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val buf = CharArray(len)
        var read = 0
        while (read < len) { val n = reader.read(buf, read, len - read); if (n < 0) break; read += n }
        val req = JSONObject(String(buf, 0, read))
        if (!req.has("id")) return respond(out, 202, "application/json", "")
        respond(out, 200, "application/json", dispatch(req).toString())
    }

    private fun dispatch(req: JSONObject): JSONObject {
        val res = JSONObject().put("jsonrpc", "2.0").put("id", req.get("id"))
        val params = req.optJSONObject("params") ?: JSONObject()
        return when (req.optString("method")) {
            "initialize" -> res.put("result", JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", "2024-11-05"))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "anbernic-rg353v").put("version", BuildConfigInfo.VERSION)))
            "ping" -> res.put("result", JSONObject())
            "tools/list" -> res.put("result", JSONObject().put("tools", JSONArray().apply {
                tools().forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema)) }
            }))
            "tools/call" -> {
                val tool = tools().firstOrNull { it.name == params.optString("name") }
                if (tool == null) res.put("error", JSONObject().put("code", -32602).put("message", "Herramienta desconocida"))
                else {
                    val r = runCatching { runBlocking { tool.handler(params.optJSONObject("arguments") ?: JSONObject()) } }
                        .getOrElse { ToolResult("Error: ${it.message}") }
                    val content = JSONArray().put(JSONObject().put("type", "text").put("text", r.text))
                    r.jpeg?.let { content.put(JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(it, Base64.NO_WRAP))) }
                    res.put("result", JSONObject().put("content", content).put("isError", r.text.startsWith("Error")))
                }
            }
            else -> res.put("error", JSONObject().put("code", -32601).put("message", "Método no soportado"))
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(200 to "OK", 202 to "Accepted", 401 to "Unauthorized", 404 to "Not Found")[code] ?: "OK"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object { private const val TAG = "McpServer" }
}

object BuildConfigInfo { const val VERSION = "2.0" }
