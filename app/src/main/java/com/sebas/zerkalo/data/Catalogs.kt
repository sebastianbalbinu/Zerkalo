package com.sebas.zerkalo.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URI
import java.net.URLEncoder

data class Lang(val id: String, val name: String)
data class Topic(val id: String, val name: String)
data class FreeLibrary(val name: String, val what: String, val donate: String)
data class Page(val items: List<CatalogItem>, val next: String? = null, val title: String = "")

/**
 * Catálogos legales y gratuitos. Zerkalo no guarda libros: los trae directo de cada biblioteca.
 * Cada respuesta se guarda en caché para mostrarla al instante la próxima vez.
 */
object Catalogs {
    val LANGS = listOf(
        Lang("es", "Español"), Lang("en", "English"), Lang("fr", "Français"), Lang("pt", "Português"),
        Lang("de", "Deutsch"), Lang("it", "Italiano"), Lang("ru", "Русский"), Lang("ja", "日本語"), Lang("la", "Latina"),
    )
    val TOPICS = listOf(
        Topic("fiction", "Novela"), Topic("poetry", "Poesía"), Topic("short stories", "Cuentos"),
        Topic("drama", "Teatro"), Topic("philosophy", "Filosofía"), Topic("history", "Historia"),
        Topic("adventure", "Aventura"), Topic("science fiction", "Ciencia ficción"), Topic("children", "Infantil"),
    )
    val LIBRARIES = listOf(
        FreeLibrary("Project Gutenberg", "Más de 75.000 libros de dominio público", "https://www.gutenberg.org/donate/"),
        FreeLibrary("Wikisource", "Textos libres en decenas de idiomas, hechos por voluntarios", "https://donate.wikimedia.org"),
        FreeLibrary("OAPEN", "Libros académicos de acceso abierto", "https://www.oapen.org"),
        FreeLibrary("Standard Ebooks", "Clásicos en inglés con ediciones cuidadas", "https://standardebooks.org/donate"),
    )

    private lateinit var cacheDir: File
    fun init(ctx: Context) { cacheDir = File(ctx.cacheDir, "catalogos").apply { mkdirs() } }

    // ---------------- caché ----------------
    private fun cacheFile(key: String) = File(cacheDir, key.hashCode().toUInt().toString(16) + ".json")

    fun cached(key: String): Page? = try {
        val f = cacheFile(key)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("items")
            Page((0 until arr.length()).map { CatalogItem.fromJson(arr.getJSONObject(it)) }, o.optStringOrNull("next"), o.optString("title"))
        }
    } catch (e: Exception) { null }

    private fun store(key: String, p: Page) = runCatching {
        cacheFile(key).writeText(
            JSONObject().put("items", JSONArray(p.items.map { it.toJson() })).put("next", p.next ?: JSONObject.NULL)
                .put("title", p.title).toString(),
        )
    }

    /** Pide una página y la guarda en caché. */
    suspend fun load(key: String, fetch: suspend () -> Page): Page {
        val p = fetch()
        if (p.items.isNotEmpty()) store(key, p)
        return p
    }

    // ---------------- Project Gutenberg ----------------
    fun gutenbergUrls(id: String, fromFormats: String?): List<String> = listOfNotNull(
        // La carpeta "cache" es la que Gutenberg recomienda para descargas automáticas.
        "https://www.gutenberg.org/cache/epub/$id/pg$id-images-3.epub",
        "https://www.gutenberg.org/cache/epub/$id/pg$id-images.epub",
        "https://www.gutenberg.org/cache/epub/$id/pg$id.epub",
        fromFormats,
    ).distinct()

    private fun gutenbergCover(id: String) = "https://www.gutenberg.org/cache/epub/$id/pg$id.cover.medium.jpg"

    /**
     * Gutendex y el catálogo OPDS oficial de Gutenberg compiten: gana el primero que responda con libros.
     * Gutendex sale con ventaja (trae resúmenes); si tarda o se cae, el OPDS entra a los 3 segundos.
     */
    suspend fun gutenberg(lang: String, topic: String? = null, search: String? = null): Page = coroutineScope {
        val q = buildString {
            append("https://gutendex.com/books/?mime_type=application%2Fepub")
            if (lang.isNotEmpty()) append("&languages=").append(lang)
            topic?.let { append("&topic=").append(enc(it)) }
            search?.let { append("&search=").append(enc(it)) }
        }
        val terms = listOfNotNull(search, topic?.let { "s.$it" }, if (lang.isNotEmpty()) "l.$lang" else null).joinToString(" ")
        val opdsUrl = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&query=" + enc(terms)
        val results = Channel<Pair<String, Result<Page>>>(2)
        launch { results.send("gutendex" to runCatching { parseGutendex(Net.text(q, timeout = 20_000, tries = 1), lang) }) }
        launch { delay(3000); results.send("opds" to runCatching { opdsGutenberg(opdsUrl) }) }
        var error: Throwable? = null
        var empty: Page? = null
        repeat(2) {
            val (who, r) = results.receive()
            val page = r.getOrNull()
            if (page != null) {
                android.util.Log.i("Zerkalo", "ZK_FUENTE $who ok ${page.items.size} ($terms)")
                if (page.items.isNotEmpty()) { coroutineContext.cancelChildren(); return@coroutineScope page }
                empty = page
            } else {
                error = r.exceptionOrNull()
                android.util.Log.w("Zerkalo", "ZK_FUENTE $who falló: ${error?.message} ($terms)")
            }
        }
        empty ?: throw (error ?: NetError("Sin resultados"))
    }

    private fun parseGutendex(json: String, lang: String): Page {
        val o = JSONObject(json)
        val arr = o.optJSONArray("results") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val b = arr.getJSONObject(i)
            val id = b.optInt("id").toString()
            val f = b.optJSONObject("formats") ?: JSONObject()
            val epub = f.keys().asSequence().firstOrNull { it.startsWith("application/epub") }?.let { f.optString(it) }
            val a = b.optJSONArray("authors")?.optJSONObject(0)
            val langs = b.optJSONArray("languages")
            CatalogItem(
                key = "pg:$id", source = "Project Gutenberg",
                title = b.optString("title").replace(Regex("\\s*[\\r\\n]+\\s*"), " — "),
                author = niceName(a?.optString("name") ?: ""),
                lang = langs?.optString(0) ?: lang,
                cover = f.optString("image/jpeg").ifEmpty { gutenbergCover(id) },
                epubUrls = gutenbergUrls(id, epub),
                summary = b.optJSONArray("summaries")?.optString(0) ?: "",
            )
        }
        return Page(items, o.optStringOrNull("next"))
    }

    private suspend fun opdsGutenberg(url: String): Page {
        val p = opds(url, "Project Gutenberg")
        return p.copy(items = p.items.filter { !it.isNav }.map { it ->
            val id = Regex("ebooks/(\\d+)").find(it.key + " " + it.epubUrls.joinToString(" "))?.groupValues?.get(1)
            if (id == null) it else it.copy(key = "pg:$id", epubUrls = gutenbergUrls(id, it.epubUrls.firstOrNull()), cover = it.cover.ifEmpty { gutenbergCover(id) })
        })
    }

    // ---------------- Wikisource (EPUB que arma WS Export) ----------------
    suspend fun wikisource(q: String, lang: String): Page {
        val wl = if (LANGS.any { it.id == lang }) lang else "es"
        val u = "https://$wl.wikisource.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=30&format=json&srsearch=" + enc(q)
        val o = JSONObject(Net.text(u))
        val arr = o.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        val seen = HashSet<String>()
        val items = mutableListOf<CatalogItem>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val title = r.optString("title").substringBefore('/')
            if (!seen.add(title)) continue
            items += CatalogItem(
                key = "ws:$wl:$title", source = "Wikisource", title = title, author = "", lang = wl,
                epubUrls = listOf("https://ws-export.wmcloud.org/?format=epub&lang=$wl&page=" + enc(title)),
                summary = Importer.cleanHtml(r.optString("snippet")),
            )
            if (items.size >= 15) break
        }
        return Page(items)
    }

    // ---------------- OAPEN (libros académicos de acceso abierto) ----------------
    suspend fun oapen(q: String): Page {
        val u = "https://library.oapen.org/rest/search?query=" + enc(q) + "&expand=metadata,bitstreams&limit=40"
        val arr = JSONArray(Net.text(u, timeout = 20_000))
        val items = mutableListOf<CatalogItem>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val md = it.optJSONArray("metadata") ?: JSONArray()
            fun get(k: String) = (0 until md.length()).map { j -> md.getJSONObject(j) }.filter { m -> m.optString("key") == k }.map { m -> m.optString("value") }
            val bs = it.optJSONArray("bitstreams") ?: JSONArray()
            val bl = (0 until bs.length()).map { j -> bs.getJSONObject(j) }
            val ep = bl.firstOrNull { b -> b.optString("mimeType").contains("epub") || b.optString("name").endsWith(".epub", true) } ?: continue
            val th = bl.firstOrNull { b -> b.optString("bundleName") == "THUMBNAIL" }
            fun abs(l: String?) = if (l.isNullOrEmpty()) "" else if (l.startsWith("http")) l else "https://library.oapen.org" + (if (l.startsWith("/")) "" else "/") + l
            items += CatalogItem(
                key = "oapen:" + it.optString("handle", it.optString("uuid")), source = "OAPEN",
                title = get("dc.title").firstOrNull() ?: it.optString("name", "Sin título"),
                author = get("dc.contributor.author").map(::niceName).take(2).joinToString(", ")
                    .ifEmpty { get("dc.contributor.editor").map(::niceName).firstOrNull() ?: "" },
                lang = (get("dc.language").firstOrNull() ?: "").take(2).lowercase(),
                cover = abs(th?.optString("retrieveLink")),
                epubUrls = listOf(abs(ep.optString("retrieveLink"))),
                summary = (get("dc.description.abstract").firstOrNull() ?: "").take(900),
            )
            if (items.size >= 15) break
        }
        return Page(items)
    }

    // ---------------- OPDS (Standard Ebooks y los catálogos que agregue la persona) ----------------
    suspend fun standardEbooks(): Page = opds("https://standardebooks.org/feeds/atom/new-releases", "Standard Ebooks")

    suspend fun opds(url: String, sourceName: String = ""): Page {
        val xml = Net.text(url, timeout = 20_000)
        return withContext(Dispatchers.Default) { parseOpds(xml, url, sourceName) }
    }

    private fun parseOpds(xml: String, base: String, sourceName: String): Page {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val items = mutableListOf<CatalogItem>()
        var feedTitle = ""
        var next: String? = null
        var inEntry = false
        var inAuthor = false
        var title = ""; var id = ""; var summary = ""; var lang = ""
        val authors = mutableListOf<String>()
        val links = mutableListOf<Triple<String, String, String>>() // rel, type, href
        var text = StringBuilder()
        fun abs(h: String) = try { URI(base).resolve(h.trim()).toString() } catch (e: Exception) { h }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "entry" -> { inEntry = true; title = ""; id = ""; summary = ""; lang = ""; authors.clear(); links.clear() }
                        "author" -> inAuthor = true
                        "link" -> {
                            val rel = p.getAttributeValue(null, "rel") ?: ""
                            val type = p.getAttributeValue(null, "type") ?: ""
                            val href = p.getAttributeValue(null, "href") ?: ""
                            if (inEntry) links += Triple(rel, type, abs(href))
                            else if (rel == "next") next = abs(href)
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name) {
                        "title" -> if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t
                        "id" -> if (inEntry) id = t
                        "name" -> if (inAuthor) authors += t
                        "author" -> inAuthor = false
                        "summary", "content" -> if (inEntry && summary.isEmpty()) summary = Importer.cleanHtml(t).take(900)
                        "dc:language", "language" -> if (inEntry) lang = t.take(2)
                        "entry" -> {
                            inEntry = false
                            val epub = links.firstOrNull { (rel, type, _) -> type.contains("epub") && (rel.contains("acquisition") || rel == "enclosure" || rel.isEmpty()) }
                            val img = links.firstOrNull { (rel, _, _) -> rel.contains("opds-spec.org/image") && !rel.contains("thumbnail") }
                                ?: links.firstOrNull { (rel, type, _) -> rel.contains("image") || type.startsWith("image/") }
                            if (epub != null) {
                                // Si hay varias versiones EPUB, la más nueva (epub3) va primero.
                                val all = links.filter { (rel, type, _) -> type.contains("epub") && (rel.contains("acquisition") || rel.isEmpty()) }
                                    .sortedByDescending { it.third.contains("epub3") || it.third.contains("images") }.map { it.third }
                                items += CatalogItem(
                                    key = "opds:" + id.ifEmpty { epub.third }, source = sourceName.ifEmpty { feedTitle.ifEmpty { "Catálogo OPDS" } },
                                    title = title, author = authors.joinToString(", "), lang = lang,
                                    cover = img?.third ?: "", epubUrls = all.ifEmpty { listOf(epub.third) }, summary = summary,
                                )
                            } else {
                                val nav = links.firstOrNull { (_, type, _) -> type.contains("opds-catalog") || type.contains("atom+xml") }
                                if (nav != null) items += CatalogItem("nav:" + nav.third, "", title, "", epubUrls = emptyList(), summary = summary, navUrl = nav.third)
                            }
                        }
                    }
                }
            }
        }
        return Page(items, next, feedTitle)
    }

    fun niceName(n: String): String {
        if (n.isBlank()) return ""
        val m = Regex("^([^,]+),\\s*(.+)$").find(n)
        val s = if (m != null) "${m.groupValues[2]} ${m.groupValues[1]}" else n
        return s.replace(Regex("\\s*\\(.*?\\)\\s*"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
