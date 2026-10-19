package com.limber.asistente

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Temporizadores, alarmas y recordatorios (como Alexa). Se guardan y sobreviven a reinicios del servicio. */
class Alarms(context: Context, private val scope: CoroutineScope, private val onFire: (String) -> Unit) {

    private val sp = context.getSharedPreferences("alarmas", Context.MODE_PRIVATE)
    private val jobs = mutableMapOf<Int, Job>()
    private val fmt = SimpleDateFormat("HH:mm", Locale("es"))

    private class Item(val id: Int, val at: Long, val text: String)

    private fun load(): MutableList<Item> {
        val arr = JSONArray(sp.getString("lista", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Item(it.getInt("id"), it.getLong("at"), it.getString("text")) }.toMutableList()
    }

    private fun save(list: List<Item>) = sp.edit().putString("lista",
        JSONArray(list.map { JSONObject().put("id", it.id).put("at", it.at).put("text", it.text) }).toString()).apply()

    /** Reprograma lo guardado (al arrancar el servicio). Lo que ya pasó se avisa de inmediato. */
    fun restore() = load().forEach { schedule(it) }

    private fun schedule(item: Item) {
        jobs[item.id]?.cancel()
        jobs[item.id] = scope.launch {
            delay((item.at - System.currentTimeMillis()).coerceAtLeast(0))
            save(load().filter { it.id != item.id })
            jobs.remove(item.id)
            onFire(item.text)
        }
    }

    fun add(at: Long, text: String): String {
        val list = load()
        val item = Item((list.maxOfOrNull { it.id } ?: 0) + 1, at, text)
        save(list + item)
        schedule(item)
        val mins = ((at - System.currentTimeMillis()) / 60_000.0)
        val cuando = if (mins < 60) "en ${"%.0f".format(mins.coerceAtLeast(0.0))} min" else "a las ${fmt.format(Date(at))}"
        return "Listo: \"$text\" $cuando (número ${item.id})."
    }

    fun list(): String = load().sortedBy { it.at }.joinToString("\n") { "${it.id}. ${it.text} — ${fmt.format(Date(it.at))}" }
        .ifEmpty { "No hay alarmas ni recordatorios." }

    fun cancel(id: Int?): String {
        val list = load()
        val remove = if (id == null) list else list.filter { it.id == id }
        if (remove.isEmpty()) return "No encontré esa alarma."
        remove.forEach { jobs.remove(it.id)?.cancel() }
        save(list - remove.toSet())
        return "Cancelé ${remove.size}."
    }

    fun tools(): List<Tool> = listOf(
        Tool("alarma.temporizador", "Pone un temporizador que suena dentro de cierto tiempo.",
            schema("minutos" to prop("number", "minutos"), "segundos" to prop("integer", "segundos extra"),
                "etiqueta" to prop("string", "para qué es, ej. la pizza"))) {
            val ms = (it.optDouble("minutos", 0.0) * 60_000 + it.optInt("segundos", 0) * 1000).toLong()
            if (ms <= 0) ToolResult("Dime cuánto tiempo.")
            else ToolResult(add(System.currentTimeMillis() + ms, "Temporizador: " + it.optString("etiqueta").ifBlank { "se acabó el tiempo" }))
        },
        Tool("alarma.recordatorio", "Recordatorio o alarma a una hora (HH:MM, 24 h). Si la hora ya pasó hoy, es para mañana.",
            schema("hora" to prop("string", "HH:MM en 24 horas"), "texto" to prop("string", "qué recordar"), required = listOf("hora", "texto"))) {
            val (hh, mm) = it.optString("hora").split(":").map { p -> p.trim().toIntOrNull() ?: 0 }.let { p -> p[0] to p.getOrElse(1) { 0 } }
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm); set(Calendar.SECOND, 0) }
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
            ToolResult(add(cal.timeInMillis, it.optString("texto")))
        },
        Tool("alarma.listar", "Lista los temporizadores, alarmas y recordatorios pendientes.") { ToolResult(list()) },
        Tool("alarma.cancelar", "Cancela una alarma por número, o todas si no se da número.",
            schema("numero" to prop("integer", "número de la alarma; vacío = todas"))) {
            ToolResult(cancel(if (it.has("numero")) it.optInt("numero") else null))
        },
    )
}

/** Memoria a largo plazo: datos del usuario que Monki recuerda entre conversaciones. */
class Memory(context: Context) {
    private val sp = context.getSharedPreferences("memoria", Context.MODE_PRIVATE)

    fun all(): List<String> = JSONArray(sp.getString("datos", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }

    private fun save(list: List<String>) = sp.edit().putString("datos", JSONArray(list.takeLast(60)).toString()).apply()

    fun tools(): List<Tool> = listOf(
        Tool("memoria.guardar", "Guarda un dato importante del usuario para recordarlo siempre (gustos, nombres, cumpleaños, preferencias).",
            schema("dato" to prop("string", "el dato, en una frase"), required = listOf("dato"))) {
            val d = it.optString("dato").trim()
            if (d.isEmpty()) ToolResult("No había nada que guardar.") else { save(all().filterNot { x -> x.equals(d, true) } + d); ToolResult("Lo recordaré.") }
        },
        Tool("memoria.olvidar", "Olvida un dato guardado (que contenga ese texto).",
            schema("texto" to prop("string", ""), required = listOf("texto"))) {
            val t = it.optString("texto").lowercase()
            val before = all(); val after = before.filterNot { x -> x.lowercase().contains(t) }
            save(after); ToolResult(if (after.size < before.size) "Olvidado." else "No tenía ese dato.")
        },
    )
}

/** Rutinas: una frase ("buenas noches") que hace varias cosas. Los pasos van en texto y Gemini los ejecuta con sus herramientas. */
class Routines(context: Context) {
    private val sp = context.getSharedPreferences("rutinas", Context.MODE_PRIVATE)

    fun all(): Map<String, String> = JSONObject(sp.getString("lista", null) ?: DEFAULTS.toString()).let { o ->
        o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun save(map: Map<String, String>) = sp.edit().putString("lista", JSONObject(map).toString()).apply()

    fun tools(): List<Tool> = listOf(
        Tool("rutina.guardar", "Crea o cambia una rutina: un nombre (la frase que la activa) y sus pasos en orden.",
            schema("nombre" to prop("string", "frase que la activa, por ejemplo: buenas noches"),
                "pasos" to prop("string", "qué hacer, en orden y separado por punto y coma"), required = listOf("nombre", "pasos"))) {
            val n = it.optString("nombre").trim().lowercase(); val p = it.optString("pasos").trim()
            if (n.isEmpty() || p.isEmpty()) ToolResult("Falta el nombre o los pasos.") else { save(all() + (n to p)); ToolResult("Rutina \"$n\" guardada.") }
        },
        Tool("rutina.borrar", "Borra una rutina por su nombre.",
            schema("nombre" to prop("string", ""), required = listOf("nombre"))) {
            val n = it.optString("nombre").trim().lowercase()
            val before = all(); save(before - n); ToolResult(if (n in before) "Rutina borrada." else "No tengo esa rutina.")
        },
    )

    companion object {
        private val DEFAULTS = JSONObject()
            .put("buenas noches", "pídele a Alexa que apague las luces; baja el brillo de la consola a 10; baja el volumen de la consola a 20; despídete deseando buenas noches")
            .put("buenos días", "di la hora; di el clima de hoy; pídele a Alexa que encienda las luces")
    }
}
