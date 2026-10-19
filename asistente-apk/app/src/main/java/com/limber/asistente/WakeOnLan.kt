package com.limber.asistente

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** Wake-on-LAN: prende computadoras de la casa mandando el "paquete mágico" por la red. */
object WakeOnLan {

    class Device(val name: String, val mac: String, val ip: String?)

    /** Formato en ajustes: una línea por equipo, "nombre|MAC|IP opcional". */
    fun parse(config: String): List<Device> = config.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { l -> l.split("|").map { it.trim() }.let { p -> if (p.size >= 2) Device(p[0], p[1], p.getOrNull(2)?.ifBlank { null }) else null } }

    private fun macBytes(mac: String): ByteArray {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        require(hex.length == 12) { "MAC inválida: $mac" }
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun broadcasts(ip: String?): Set<String> {
        val out = mutableSetOf("255.255.255.255")
        NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
            nif.interfaceAddresses.forEach { a -> a.broadcast?.hostAddress?.let { out.add(it) } }
        }
        ip?.let { out.add(it); out.add(it.substringBeforeLast('.') + ".255") }
        return out
    }

    fun send(d: Device): String {
        val mac = macBytes(d.mac)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
        val sent = mutableListOf<String>()
        DatagramSocket().use { s ->
            s.broadcast = true
            for (addr in broadcasts(d.ip)) for (port in listOf(9, 7)) runCatching {
                s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(addr), port)); sent.add(addr)
            }
        }
        return "Mandé la señal de encendido a ${d.name} (${sent.distinct().size} rutas). Tarda un minuto en prender; " +
            "si no prende, revisa que tenga Wake-on-LAN activo y esté en la misma red que la consola."
    }

    fun tools(prefs: Prefs): List<Tool> = listOf(
        Tool("casa.encender_computadora", "Prende una computadora de la casa con Wake-on-LAN. Equipos guardados: " +
            parse(prefs.wolDevices).joinToString { it.name }.ifEmpty { "ninguno todavía" },
            schema("nombre" to prop("string", "nombre del equipo"), required = listOf("nombre"))) { a ->
            val list = parse(prefs.wolDevices)
            val want = a.optString("nombre").lowercase()
            val d = list.firstOrNull { it.name.lowercase() == want } ?: list.firstOrNull { want.contains(it.name.lowercase()) || it.name.lowercase().contains(want) }
                ?: list.singleOrNull()
            if (d == null) ToolResult("No tengo computadoras guardadas para prender. Agrégalas en la app (nombre|MAC).")
            else withContext(Dispatchers.IO) { ToolResult(runCatching { send(d) }.getOrElse { "No pude mandar la señal: ${it.message}" }) }
        },
    )
}
