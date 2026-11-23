package com.sebas.zerkalo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Encuentra el libro en texto de un audiolibro de LibriVox.
 * LibriVox suele anotar qué edición leyó (casi siempre de Project Gutenberg o Wikisource):
 * esa va primero, porque el texto coincide palabra por palabra con lo que se oye.
 */
object AudioText {
    data class Option(val item: CatalogItem, val exact: Boolean)

    suspend fun find(b: AudioBook): List<Option> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Option>()
        val lang = b.lang.ifBlank { "es" }
        // 1) La edición que usó LibriVox, si la anota en su ficha
        runCatching {
            val meta = Net.text("https://archive.org/metadata/${b.id}/metadata", timeout = 15_000)
            Regex("gutenberg\\.org/(?:ebooks|etext|files|cache/epub)/(\\d+)").findAll(meta).map { it.groupValues[1] }.distinct().take(2).forEach { id ->
                val key = "pg:$id"
                out[key] = Option(
                    CatalogItem(key, "Project Gutenberg", b.title, b.author, lang, "https://gutenberg.pglaf.org/cache/epub/$id/pg$id.cover.medium.jpg", Catalogs.gutenbergUrls(id, null)),
                    exact = true,
                )
            }
            Regex("([a-z]{2,3})\\.wikisource\\.org/wiki/([^\"\\s<>\\\\]+)").findAll(meta).take(2).forEach { m ->
                val wl = m.groupValues[1]
                val title = runCatching { URLDecoder.decode(m.groupValues[2], "UTF-8") }.getOrDefault(m.groupValues[2]).replace('_', ' ').substringBefore('/').substringBefore('#')
                val key = "ws:$wl:$title"
                if (title.isNotBlank()) out[key] = Option(CatalogItem(key, "Wikisource", title, b.author, wl, "", listOf("wsb:$wl:$title")), exact = true)
            }
        }
        // 2) Por título en Project Gutenberg (con el autor, si se puede)
        val words = Catalogs.norm(b.title).replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.length > 2 }.take(3)
        val surname = Catalogs.norm(b.author).split(Regex("[\\s,.]+")).filter { it.length > 2 }.maxByOrNull { it.length }.orEmpty()
        if (words.isNotEmpty()) runCatching {
            val found = Catalogs.gutenberg(lang, search = words.joinToString(" ")).items
            val withAuthor = found.filter { surname.isBlank() || Catalogs.norm(it.author).contains(surname) }
            (withAuthor.ifEmpty { found }).take(3).forEach { if (it.key !in out) out[it.key] = Option(it, false) }
        }
        // 3) Wikisource en el idioma del audio
        if (words.isNotEmpty()) runCatching {
            Catalogs.wikisource(b.title, lang).items.take(2).forEach { if (it.key !in out) out[it.key] = Option(it.copy(author = b.author), false) }
        }
        out.values.toList()
    }
}
