package com.sebas.zerkalo.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Exportar copias editadas: el libro original en Zerkalo nunca cambia.
 * - PDF con mis notas: trazos de la pluma, subrayados y notas adhesivas dentro del PDF (el texto sigue seleccionable).
 * - EPUB con mis notas: subrayados marcados en el texto y notas adhesivas como notas al pie, en su sitio.
 * - Páginas rayadas: las pantallas de un EPUB donde se usó la pluma, juntas en un PDF.
 */
object Export {
    fun outDir(ctx: Context) = File(ctx.cacheDir, "compartir").apply { mkdirs() }

    private fun safe(s: String) = s.take(70).replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Libro" }

    /** Abre el menú de Android para mandar o guardar el archivo. */
    fun share(ctx: Context, file: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- PDF con mis notas ----------

    /**
     * [strokes]: trazos por página ("p3"), con puntos de 0 a 1. [highlights]: subrayados como (página, izq, arriba, der, abajo)
     * en puntos del PDF contados desde arriba. [stickies]: notas adhesivas con locator "pdf:página:x:y".
     */
    suspend fun pdfWithNotes(
        ctx: Context, book: Book, strokes: Map<String, List<Stroke>>, highlights: List<FloatArray>, stickies: List<Mark>,
    ): File = withContext(Dispatchers.IO) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        val out = File(outDir(ctx), safe(book.title) + " (con notas).pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(File(Library.booksDir, book.file)).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val list = strokes["p$i"].orEmpty()
                val hls = highlights.filter { it[0].toInt() == i }
                val notes = stickies.filter { it.locator.split(":").getOrNull(1)?.toIntOrNull() == i }
                if (list.isEmpty() && hls.isEmpty() && notes.isEmpty()) continue
                val page = doc.getPage(i)
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val x0 = box.lowerLeftX
                val top = box.upperRightY
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                    doc, page, com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true, true,
                ).use { cs ->
                    if (hls.isNotEmpty()) {
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.38f })
                        cs.setNonStrokingColor(233 / 255f, 196 / 255f, 106 / 255f)
                        for (r in hls) cs.addRect(x0 + r[1], top - r[4], r[3] - r[1], r[4] - r[2])
                        cs.fill()
                        cs.restoreGraphicsState()
                    }
                    for (s in list) {
                        if (s.pts.size < 4) continue
                        cs.saveGraphicsState()
                        if (s.marker) cs.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.42f })
                        val c = s.color
                        cs.setStrokingColor(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
                        cs.setLineWidth((s.width * w).coerceAtLeast(0.5f))
                        cs.setLineCapStyle(if (s.marker) 2 else 1)
                        cs.setLineJoinStyle(1)
                        cs.moveTo(x0 + s.pts[0] * w, top - s.pts[1] * h)
                        var k = 2
                        while (k + 1 < s.pts.size) { cs.lineTo(x0 + s.pts[k] * w, top - s.pts[k + 1] * h); k += 2 }
                        cs.stroke()
                        cs.restoreGraphicsState()
                    }
                }
                // Notas adhesivas como notas de PDF de verdad (se abren en cualquier lector)
                for (m in notes) {
                    val p = m.locator.split(":")
                    val nx = p.getOrNull(2)?.toFloatOrNull() ?: continue
                    val ny = p.getOrNull(3)?.toFloatOrNull() ?: continue
                    val ann = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
                    ann.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(x0 + nx * w - 10f, top - ny * h - 10f, 20f, 20f)
                    ann.contents = m.note
                    ann.titlePopup = "Zerkalo"
                    ann.color = com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(
                        floatArrayOf(0.94f, 0.85f, 0.58f), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE,
                    )
                    page.annotations.add(ann)
                }
            }
            doc.save(out)
        }
        out
    }

    // ---------- EPUB con mis notas ----------

    suspend fun epubWithNotes(ctx: Context, book: Book, marks: List<Mark>): File = withContext(Dispatchers.IO) {
        val out = File(outDir(ctx), safe(book.title) + " (con notas).epub")
        val items = marks.filter { it.bookId == book.id && (it.type == "hl" || it.type == "sticky") }.mapNotNull { m ->
            val o = runCatching { JSONObject(m.locator) }.getOrNull() ?: return@mapNotNull null
            Triple(m, o.optString("href"), o.optJSONObject("text"))
        }
        ZipFile(File(Library.booksDir, book.file)).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                // "mimetype" va primero y sin comprimir, como pide el formato EPUB
                zip.getEntry("mimetype")?.let { e ->
                    val b = zip.getInputStream(e).readBytes()
                    z.putNextEntry(ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().apply { update(b) }.value
                    })
                    z.write(b); z.closeEntry()
                }
                for (e in zip.entries()) {
                    if (e.isDirectory || e.name == "mimetype") continue
                    val bytes = zip.getInputStream(e).readBytes()
                    val mine = items.filter { sameFile(it.second, e.name) }
                    val html = e.name.endsWith(".xhtml", true) || e.name.endsWith(".html", true) || e.name.endsWith(".htm", true)
                    val data = if (mine.isNotEmpty() && html) runCatching { annotate(String(bytes, Charsets.UTF_8), mine).toByteArray(Charsets.UTF_8) }.getOrDefault(bytes) else bytes
                    z.putNextEntry(ZipEntry(e.name)); z.write(data); z.closeEntry()
                }
            }
        }
        out
    }

    private fun sameFile(href: String, entry: String): Boolean {
        val h = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }.getOrDefault(href).removePrefix("/")
        if (h.isBlank()) return false
        return entry == h || entry.endsWith("/$h") || h.endsWith("/$entry")
    }

    /** Marca los subrayados y agrega las notas al pie dentro de un capítulo (XHTML). */
    private fun annotate(html: String, items: List<Triple<Mark, String, JSONObject?>>): String {
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml).charset("UTF-8")
        val body = doc.selectFirst("body") ?: return html
        doc.selectFirst("head")?.appendElement("style")?.attr("type", "text/css")?.appendText(
            ".zk-mark{background-color:rgba(233,196,106,.45)}" +
                ".zk-ref{color:#C4432A;text-decoration:none;font-size:.8em;vertical-align:super}" +
                ".zk-note{font-size:.9em;border-left:3px solid #E9C46A;padding-left:.6em;margin:.8em 0}",
        )
        doc.selectFirst("html")?.let { if (!it.hasAttr("xmlns:epub")) it.attr("xmlns:epub", "http://www.idpf.org/2007/ops") }
        // De atrás para adelante: así marcar uno no corre la posición de los otros
        for ((m, _, t) in items.sortedByDescending { it.first.progress }) {
            val hl = t?.optString("highlight").orEmpty().ifBlank { m.text }
            if (hl.isBlank()) continue
            val (s, e) = find(body, hl, t?.optString("before").orEmpty()) ?: continue
            val last = wrap(body, s, e, if (m.type == "sticky") "zk-mark zk-sticky" else "zk-mark") ?: continue
            if (m.note.isNotBlank()) {
                val id = "zkn-${m.id}"
                last.after("<a epub:type=\"noteref\" class=\"zk-ref\" href=\"#$id\">✎</a>")
                body.appendElement("aside").attr("epub:type", "footnote").attr("id", id).addClass("zk-note").appendElement("p").text(m.note)
            }
        }
        return doc.outerHtml()
    }

    private fun textNodes(body: Element): List<TextNode> {
        val out = ArrayList<TextNode>()
        body.traverse { node, _ -> if (node is TextNode && node.parent()?.nodeName() != "script" && node.parent()?.nodeName() != "style") out.add(node) }
        return out
    }

    /** Busca la frase en el texto del capítulo (sin importar saltos de línea ni espacios dobles). Devuelve inicio y fin. */
    private fun find(body: Element, hl: String, before: String): Pair<Int, Int>? {
        val full = StringBuilder().apply { textNodes(body).forEach { append(it.wholeText) } }.toString()
        val norm = StringBuilder()
        val map = ArrayList<Int>()
        var space = false
        full.forEachIndexed { i, c ->
            if (c.isWhitespace()) { if (!space) { norm.append(' '); map.add(i) }; space = true } else { norm.append(c); map.add(i); space = false }
        }
        fun n(s: String) = s.replace(Regex("\\s+"), " ")
        val h = n(hl).trim()
        if (h.isEmpty()) return null
        val b = n(before).takeLast(40)
        var at = if (b.isNotBlank()) norm.indexOf(b + h).let { if (it >= 0) it + b.length else -1 } else -1
        if (at < 0) at = norm.indexOf(h)
        if (at < 0) return null
        val end = at + h.length - 1
        if (end >= map.size) return null
        return map[at] to map[end] + 1
    }

    /** Envuelve el texto entre [s] y [e] en <mark>; devuelve la última marca (para poner la nota al pie después). */
    private fun wrap(body: Element, s: Int, e: Int, cls: String): Element? {
        var off = 0
        var last: Element? = null
        for (node in textNodes(body)) {
            val len = node.wholeText.length
            val a = maxOf(s, off) - off
            val z = minOf(e, off + len) - off
            off += len
            if (z <= a || a >= len || z <= 0) continue
            var piece = node
            if (a > 0) piece = piece.splitText(a)
            if (z - a < piece.wholeText.length) piece.splitText(z - a)
            if (piece.wholeText.isBlank()) continue
            piece.wrap("<mark class=\"$cls\"></mark>")
            last = piece.parent() as? Element ?: last
        }
        return last
    }

    // ---------- Páginas rayadas de un EPUB ----------

    /** Carpeta con la foto de cada pantalla rayada con la pluma. */
    fun inkShotsDir(ctx: Context, bookId: String) = File(File(ctx.filesDir, "tinta"), bookId).apply { mkdirs() }

    fun inkPagesPdf(ctx: Context, book: Book): File? {
        val shots = inkShotsDir(ctx, book.id).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        if (shots.isEmpty()) return null
        val out = File(outDir(ctx), safe(book.title) + " (páginas rayadas).pdf")
        val pdf = android.graphics.pdf.PdfDocument()
        shots.forEachIndexed { i, f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create())
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdf.finishPage(page)
            bmp.recycle()
        }
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return out
    }
}
