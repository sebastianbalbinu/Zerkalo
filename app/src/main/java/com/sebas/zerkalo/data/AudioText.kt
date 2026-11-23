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

    private val metaCache = HashMap<String, String>()

    /** La ficha de LibriVox en el Internet Archive (se guarda mientras la app está abierta). */
    suspend fun metadata(id: String): String = metaCache[id] ?: withContext(Dispatchers.IO) {
        Net.text("https://archive.org/metadata/$id/metadata", timeout = 15_000).also { metaCache[id] = it }
    }

    /** Los números de Project Gutenberg que LibriVox anota como el texto que leyó. */
    suspend fun gutenbergIds(id: String): List<String> = runCatching {
        Regex("gutenberg\\.org/(?:ebooks|etext|files|cache/epub)/(\\d+)").findAll(metadata(id)).map { it.groupValues[1] }.distinct().toList()
    }.getOrDefault(emptyList())

    suspend fun find(b: AudioBook): List<Option> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Option>()
        val lang = b.lang.ifBlank { "es" }
        // 1) La edición que usó LibriVox, si la anota en su ficha
        runCatching {
            val meta = metadata(b.id)
            gutenbergIds(b.id).take(2).forEach { id ->
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
        val words = Fuzzy.tokens(b.title).filter { it.length > 2 && !it[0].isDigit() }.sortedByDescending { it.length }.take(3)
        val surname = Catalogs.norm(b.author).split(Regex("[\\s,.]+")).filter { it.length > 2 }.maxByOrNull { it.length }.orEmpty()
        if (words.isNotEmpty()) runCatching {
            val found = Catalogs.gutenberg(lang, search = words.joinToString(" ")).items
            val withAuthor = found.filter { Fuzzy.sameAuthor(b.author, it.author) && Fuzzy.titles(b.title, it.title) >= 0.5 }
                .sortedByDescending { Fuzzy.titles(b.title, it.title) }
            (withAuthor.ifEmpty { found }).take(3).forEach { if (it.key !in out) out[it.key] = Option(it, false) }
        }
        // 3) Wikisource en el idioma del audio
        if (words.isNotEmpty()) runCatching {
            Catalogs.wikisource(b.title, lang).items.take(2).forEach { if (it.key !in out) out[it.key] = Option(it.copy(author = b.author), false) }
        }
        out.values.toList()
    }
}
