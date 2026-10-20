package com.limber.asistente

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Internet gratis, sin gastar cuota de Gemini: buscar (DuckDuckGo), leer páginas y clima (wttr.in). */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private fun get(url: String, max: Int = 400_000, ua: String = UA): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua); setRequestProperty("Accept-Language", "es-MX,es;q=0.9")
        }
        return conn.inputStream.bufferedReader().use { r ->
            val buf = CharArray(max); var n = 0
            while (n < max) { val k = r.read(buf, n, max - n); if (k < 0) break; n += k }
            String(buf, 0, n)
        }.also { conn.disconnect() }
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    fun search(query: String): String {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=mx-es")
        val re = Regex("class=\"result__a\" href=\"([^\"]+)\">(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val items = re.findAll(html).take(6).map { m ->
            var url = m.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            "- ${clean(m.groupValues[2])}: ${clean(m.groupValues[3])} ($url)"
        }.toList()
        return if (items.isEmpty()) "No encontré resultados." else items.joinToString("\n")
    }

    fun read(url: String): String {
        val html = get(if (url.startsWith("http")) url else "https://$url")
        val body = html.replace(Regex("(?is)<(script|style|noscript|svg|header|footer|nav)[^>]*>.*?</\\1>"), " ")
        return clean(body).take(5000).ifEmpty { "La página no tiene texto legible." }
    }

    fun weather(place: String, home: String): String {
        val q = URLEncoder.encode(place.ifBlank { home }, "UTF-8").replace("+", "%20")
        val fmt = URLEncoder.encode("%l: %C, %t (sensación %f), humedad %h, viento %w, lluvia %p", "UTF-8").replace("+", "%20")
        // Con user agent de navegador wttr.in devuelve una página HTML; con el de curl, texto
        val now = get("https://wttr.in/$q?format=$fmt&lang=es", ua = "curl/8.0").trim()
        val days = runCatching { get("https://wttr.in/$q?format=%25C+%25t&lang=es&2", ua = "curl/8.0").trim() }.getOrDefault("")
        return "Ahora: $now" + if (days.isNotBlank() && days.length < 300) "\nPronóstico: $days" else ""
    }

    /** Busca en YouTube: lista de (id del video, título), en el orden de la página de resultados. */
    fun youtubeSearch(query: String): List<Pair<String, String>> {
        // Con user agent de celular YouTube manda la versión móvil con los datos escapados; la de escritorio es legible
        val html = get("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&hl=es", max = 2_000_000,
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
        val re = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(html).take(8).map { it.groupValues[1] to it.groupValues[2].replace("\\u0026", "&").replace("\\\"", "\"") }.toList()
    }

    /** Clima corto para el widget, por ejemplo "⛅ +23°C". */
    fun weatherShort(home: String): String {
        val q = URLEncoder.encode(home, "UTF-8").replace("+", "%20")
        // Con user agent de navegador wttr.in devuelve HTML; con el de curl, texto
        return get("https://wttr.in/$q?format=%25c%25t&lang=es", ua = "curl/8.0").trim().replace(Regex("\\s+"), " ")
    }

    fun tools(prefs: Prefs): List<Tool> = listOf(
        Tool("internet.buscar", "Busca en internet (noticias, resultados deportivos, precios, datos actuales, lo que sea). Devuelve títulos, resúmenes y enlaces.",
            schema("consulta" to prop("string", "qué buscar"), required = listOf("consulta"))) {
            withContext(Dispatchers.IO) { ToolResult(runCatching { search(it.optString("consulta")) }.getOrElse { e -> "No pude buscar: ${e.message}" }) }
        },
        Tool("internet.leer", "Lee el texto de una página web (por ejemplo un enlace de internet.buscar) para dar detalles.",
            schema("url" to prop("string", "enlace"), required = listOf("url"))) {
            withContext(Dispatchers.IO) { ToolResult(runCatching { read(it.optString("url")) }.getOrElse { e -> "No pude abrir la página: ${e.message}" }) }
        },
        Tool("internet.clima", "Clima actual y pronóstico de una ciudad. Si no te dicen ciudad, deja el lugar vacío y usa la casa del usuario.",
            schema("lugar" to prop("string", "ciudad, puede ir vacío"))) {
            withContext(Dispatchers.IO) { ToolResult(runCatching { weather(it.optString("lugar"), prefs.location) }.getOrElse { e -> "No pude ver el clima: ${e.message}" }) }
        },
    )
}
