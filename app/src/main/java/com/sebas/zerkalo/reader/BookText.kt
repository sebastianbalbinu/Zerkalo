package com.sebas.zerkalo.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import java.text.Normalizer

/**
 * El texto de cada capítulo del libro, sin etiquetas: para la lupa (buscar palabras y frases)
 * y para armar un índice cuando el libro no trae uno.
 */
class BookText(private val pub: Publication) {
    private val cache = HashMap<Int, String>()

    data class Hit(val chapter: Int, val nth: Int, val progression: Double, val before: String, val match: String, val after: String)

    suspend fun text(i: Int): String = cache[i] ?: withContext(Dispatchers.IO) {
        val link = pub.readingOrder.getOrNull(i) ?: return@withContext ""
        val raw = runCatching { pub.get(link.url())?.read()?.getOrNull()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
        plain(raw).also { cache[i] = it }
    }

    /** El primer título del capítulo (h1–h3, o el <title> del archivo). */
    suspend fun heading(i: Int): String? = withContext(Dispatchers.IO) {
        val link = pub.readingOrder.getOrNull(i) ?: return@withContext null
        val raw = runCatching { pub.get(link.url())?.read()?.getOrNull()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
        val h = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)
        h?.let { plain(it).trim() }?.takeIf { it.length in 2..90 }
    }

    /** Busca [query] en todo el libro, sin importar tildes ni mayúsculas. */
    suspend fun search(query: String, limit: Int = 300): List<Hit> = withContext(Dispatchers.Default) {
        val q = fold(query.trim())
        if (q.length < 2) return@withContext emptyList()
        val out = ArrayList<Hit>()
        for (i in pub.readingOrder.indices) {
            val t = text(i)
            if (t.isEmpty()) continue
            val f = fold(t)
            var from = 0
            var n = 0
            while (out.size < limit) {
                val at = f.indexOf(q, from)
                if (at < 0) break
                val s = (at - 60).coerceAtLeast(0)
                val e = (at + q.length + 80).coerceAtMost(t.length)
                out += Hit(
                    i, n, at.toDouble() / t.length.coerceAtLeast(1),
                    (if (s > 0) "…" else "") + t.substring(s, at).trimStart(),
                    t.substring(at, at + q.length),
                    t.substring(at + q.length, e).trimEnd() + if (e < t.length) "…" else "",
                )
                n++
                from = at + 1
            }
            if (out.size >= limit) break
        }
        out
    }

    companion object {
        /** Cada letra sin tilde y en minúscula, conservando el largo (para que las posiciones coincidan con el texto). */
        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
                sb.append(n.firstOrNull() ?: c)
            }
            return sb.toString()
        }

        private val ENT = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#160;" to " ", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&laquo;" to "«", "&raquo;" to "»")

        fun plain(html: String): String {
            var t = html.replace(Regex("<(script|style|head)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            t = t.replace(Regex("<br\\s*/?>|</(p|div|h[1-6]|li|blockquote|tr)>", RegexOption.IGNORE_CASE), " ")
            t = t.replace(Regex("<[^>]+>"), "")
            ENT.forEach { (k, v) -> t = t.replace(k, v) }
            t = t.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
            t = t.replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
            return t.replace(Regex("\\s+"), " ").trim()
        }
    }
}
