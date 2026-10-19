package com.limber.asistente

import org.json.JSONArray
import org.json.JSONObject

/** Una herramienta con el formato de MCP: nombre, descripción, inputSchema (JSON Schema) y su ejecución. */
class Tool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject = schema(),
    val handler: suspend (JSONObject) -> ToolResult,
)

/** Resultado de una herramienta: texto y, opcionalmente, una imagen JPEG. */
class ToolResult(val text: String, val jpeg: ByteArray? = null)

fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))

fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

/** Herramientas propias de la consola, con nombres al estilo de xiaozhi (self.*). */
fun deviceTools(c: Controls): List<Tool> = listOf(
    Tool("self.get_device_status", "Estado de la consola: batería, carga, Wi-Fi, volumen, brillo, app en pantalla y hora.") {
        ToolResult(c.deviceStatus())
    },
    Tool("self.screen.capture", "Mira la pantalla actual (te llega como imagen). Gasta cuota: úsala solo cuando la necesites.") {
        val img = c.screenshotJpeg()
        ToolResult(if (img != null) "Captura de ${c.screenSize.first}x${c.screenSize.second}." else "No pude capturar.", img)
    },
    Tool("self.screen.read", "Textos y botones visibles con sus coordenadas.") { ToolResult(c.readScreen()) },
    Tool("self.screen.tap", "Toca la pantalla en (x, y), en píxeles de la captura.",
        schema("x" to prop("integer", "x"), "y" to prop("integer", "y"), required = listOf("x", "y"))) {
        c.tap(it.optInt("x"), it.optInt("y")); ToolResult("Toqué (${it.optInt("x")}, ${it.optInt("y")}).")
    },
    Tool("self.screen.tap_text", "Hace clic en el elemento visible que contenga ese texto. Más preciso que tocar por coordenadas.",
        schema("text" to prop("string", "texto visible"), required = listOf("text"))) {
        ToolResult(c.clickText(it.optString("text")))
    },
    Tool("self.screen.swipe", "Desliza de (x1,y1) a (x2,y2). Sirve para hacer scroll.",
        schema("x1" to prop("integer", ""), "y1" to prop("integer", ""), "x2" to prop("integer", ""), "y2" to prop("integer", ""),
            "duration_ms" to prop("integer", "por defecto 300"), required = listOf("x1", "y1", "x2", "y2"))) {
        c.swipe(it.optInt("x1"), it.optInt("y1"), it.optInt("x2"), it.optInt("y2"), it.optLong("duration_ms", 300))
        ToolResult("Deslicé.")
    },
    Tool("self.screen.set_brightness", "Cambia el brillo de la pantalla (0 a 100).",
        schema("level" to prop("integer", "0 a 100"), required = listOf("level"))) {
        ToolResult(c.setBrightness(it.optInt("level")))
    },
    Tool("self.system.button", "Presiona un botón del sistema: BACK, HOME, RECENTS o NOTIFICATIONS.",
        schema("name" to prop("string", "BACK | HOME | RECENTS | NOTIFICATIONS"), required = listOf("name"))) {
        ToolResult(if (c.globalAction(it.optString("name").uppercase())) "Listo." else "Botón no válido.")
    },
    Tool("self.system.type_text", "Escribe texto en el campo de texto con foco.",
        schema("text" to prop("string", ""), required = listOf("text"))) { ToolResult(c.typeText(it.optString("text"))) },
    Tool("self.app.list", "Apps instaladas (nombre = paquete).") { ToolResult(c.listApps()) },
    Tool("self.app.open", "Abre una app por su paquete (usa self.app.list para encontrarlo).",
        schema("package" to prop("string", ""), required = listOf("package"))) { ToolResult(c.openApp(it.optString("package"))) },
    Tool("self.audio_speaker.set_volume", "Cambia el volumen multimedia (0 a 100).",
        schema("volume" to prop("integer", "0 a 100"), required = listOf("volume"))) {
        ToolResult(c.volume("nivel", it.optInt("volume")))
    },
    Tool("self.wait", "Espera unos segundos a que cargue la pantalla (máximo 10).",
        schema("seconds" to prop("number", ""), required = listOf("seconds"))) {
        kotlinx.coroutines.delay((it.optDouble("seconds", 1.0).coerceIn(0.0, 10.0) * 1000).toLong()); ToolResult("Listo.")
    },
)

/** Convierte un JSON Schema de MCP al formato de parámetros de Gemini. */
fun toGeminiSchema(s: JSONObject): JSONObject {
    val out = JSONObject()
    s.optString("type").takeIf { it.isNotEmpty() }?.let { out.put("type", it.uppercase()) }
    s.optString("description").takeIf { it.isNotEmpty() }?.let { out.put("description", it) }
    s.optJSONArray("enum")?.let { out.put("enum", it) }
    s.optJSONObject("properties")?.let { props ->
        val p = JSONObject()
        props.keys().forEach { k -> p.put(k, toGeminiSchema(props.getJSONObject(k))) }
        out.put("properties", p)
    }
    s.optJSONArray("required")?.let { if (it.length() > 0) out.put("required", it) }
    s.optJSONObject("items")?.let { out.put("items", toGeminiSchema(it)) }
    return out
}
