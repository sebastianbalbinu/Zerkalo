package com.sebas.zerkalo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Arma un EPUB a partir de una obra de Wikisource usando su API abierta de texto.
 * (El exportador oficial ahora pide demostrar que no eres un robot, así que una app no puede usarlo.)
 * Si la obra está dividida en capítulos (subpáginas), cada uno queda como un capítulo del libro.
 */
object WikisourceEpub {
    private const val MAX_CHAPTERS = 150

    private fun rest(lang: String, title: String) =
        "https://$lang.wikisource.org/api/rest_v1/page/html/" +
            URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")

    suspend fun build(lang: String, title: String, author: String, dest: File, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        onProgress(null)
        val main = Jsoup.parse(Net.text(rest(lang, title), timeout = 30_000))
        val prefix = title.replace(' ', '_') + "/"
        // Subpáginas enlazadas desde la portada de la obra (los capítulos), en su orden de aparición.
        val subs = main.select("a[href]").mapNotNull { a ->
            val h = runCatching { URLDecoder.decode(a.attr("href"), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val t = h.removePrefix("./").substringBefore('#').substringBefore('?')
            if (t.startsWith(prefix) && t.length > prefix.length) t.replace('_', ' ') else null
        }.distinct().take(MAX_CHAPTERS)

        val chapters = mutableListOf<Pair<String, String>>() // título, cuerpo XHTML
        if (subs.size >= 2) {
            // La portada también va, si tiene texto propio además del índice.
            clean(main).takeIf { it.text().length > 600 }?.let { chapters += title to xhtml(it) }
            subs.forEachIndexed { i, t ->
                onProgress(i / subs.size.toFloat() * 0.95f)
                val d = runCatching { Jsoup.parse(Net.text(rest(lang, t), timeout = 30_000)) }.getOrNull() ?: return@forEachIndexed
                val c = clean(d)
                if (c.text().isNotBlank()) chapters += t.substringAfterLast('/') to xhtml(c)
            }
        } else {
            chapters += title to xhtml(clean(main))
        }
        if (chapters.isEmpty() || chapters.sumOf { it.second.length } < 300) throw NetError("Esta obra de Wikisource no tiene texto para leer")
        write(dest, title, author, lang, chapters)
        onProgress(1f)
        dest
    }

    /** Quita encabezados de navegación, notas de edición, imágenes y todo lo que no es texto de la obra. */
    private fun clean(d: Document): Document {
        d.select(
            "script, style, link, meta, img, figure, audio, video, .mw-editsection, .noprint, .ws-noexport, .ws-header, " +
                "#headertemplate, .headertemplate, .cabecera, table.cabecera, .navbox, .metadata, .mw-references-wrap > .mw-references-columns > style",
        ).remove()
        d.select("a").forEach { it.unwrap() }
        d.select("[style]").removeAttr("style")
        d.select("[class]").removeAttr("class")
        d.select("[id]").removeAttr("id")
        return d
    }

    private fun xhtml(d: Document): String {
        d.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
        return d.body().html()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun write(dest: File, title: String, author: String, lang: String, chapters: List<Pair<String, String>>) {
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            // "mimetype" va primero y sin comprimir, como pide el estándar EPUB.
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put("OEBPS/style.css", "body{margin:0 4%;line-height:1.5} h1,h2,h3{text-align:center} p{text-indent:1.2em;margin:0} p+p{margin-top:0}")
            chapters.forEachIndexed { i, (t, body) ->
                put(
                    "OEBPS/c$i.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$lang" lang="$lang"><head><title>${esc(t)}</title><link rel="stylesheet" href="style.css" type="text/css"/></head><body><h2>${esc(t)}</h2>$body</body></html>""",
                )
            }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Índice</title></head><body><nav epub:type="toc"><ol>""" +
                    chapters.mapIndexed { i, (t, _) -> """<li><a href="c$i.xhtml">${esc(t)}</a></li>""" }.joinToString("") +
                    "</ol></nav></body></html>",
            )
            val id = "urn:uuid:" + UUID.nameUUIDFromBytes("$lang:$title".toByteArray())
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                    """<dc:identifier id="uid">$id</dc:identifier><dc:title>${esc(title)}</dc:title><dc:language>$lang</dc:language>""" +
                    (if (author.isNotBlank()) "<dc:creator>${esc(author)}</dc:creator>" else "") +
                    """<dc:source>https://$lang.wikisource.org</dc:source><meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest>""" +
                    """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/>""" +
                    chapters.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" } +
                    "</manifest><spine>" + chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" } + "</spine></package>",
            )
        }
    }
}
