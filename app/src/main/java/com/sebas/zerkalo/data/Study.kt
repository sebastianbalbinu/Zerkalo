package com.sebas.zerkalo.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resumen de estudio de un libro: subrayados y notas por capítulo, vocabulario y marcadores. */
object Study {
    data class Chapter(val title: String, val marks: List<Mark>)

    data class Summary(
        val book: Book,
        val chapters: List<Chapter>,
        val words: List<Mark>,
        val shioris: List<Mark>,
        val minutes: Int,
    ) {
        val highlights get() = chapters.sumOf { it.marks.size }
        val notes get() = chapters.sumOf { c -> c.marks.count { it.note.isNotBlank() } }
        val isEmpty get() = highlights == 0 && words.isEmpty()
    }

    private fun argbOf(c: Int) = if (c in 0..15) InkColor.DEFAULTS.getOrElse(c) { InkColor.DEFAULTS[0] }.argb else c
    private fun hex(c: Int) = String.format("#%06X", argbOf(c) and 0xFFFFFF)
    private fun inkName(c: Int) = Prefs.inks.value.firstOrNull { it.argb == argbOf(c) }?.name.orEmpty()

    fun of(book: Book, all: List<Mark> = Library.marks.value): Summary {
        val mine = all.filter { it.bookId == book.id }
        val hls = mine.filter { it.type == "hl" }.sortedBy { it.progress }
        val chapters = hls.groupBy { it.chapter.ifBlank { "Sin capítulo" } }.map { (t, xs) -> Chapter(t, xs) }
            .sortedBy { c -> c.marks.minOf { it.progress } }
        val minutes = (Library.sessions.value.filter { it.bookId == book.id }.sumOf { it.ms } / 60_000).toInt()
        return Summary(
            book, chapters,
            mine.filter { it.type == "word" }.distinctBy { it.text.lowercase() }.sortedBy { it.text.lowercase() },
            mine.filter { it.type == "shiori" }.sortedBy { it.progress },
            minutes,
        )
    }

    private fun today() = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es")).format(Date())

    private fun minutes(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

    /** Texto plano con formato Markdown: se lee bien en WhatsApp, correo, Notion u Obsidian. */
    fun markdown(s: Summary): String = buildString {
        appendLine("# ${s.book.title}")
        if (s.book.author.isNotBlank()) appendLine("*${s.book.author}*")
        appendLine()
        appendLine("Resumen de estudio · ${today()}")
        appendLine("${s.highlights} subrayados · ${s.notes} notas · ${s.words.size} palabras · ${(s.book.progress * 100).toInt()} % leído · ${minutes(s.minutes)} de lectura")
        s.chapters.forEach { c ->
            appendLine(); appendLine("## ${c.title}"); appendLine()
            c.marks.forEach { m ->
                inkName(m.color).takeIf { it.isNotBlank() }?.let { appendLine("[$it]") }
                appendLine("> ${m.text.trim().replace("\n", "\n> ")}")
                if (m.note.isNotBlank()) appendLine("\n**Nota:** ${m.note.trim()}")
                appendLine()
            }
        }
        if (s.words.isNotEmpty()) {
            appendLine(); appendLine("## Vocabulario"); appendLine()
            s.words.forEach { w ->
                appendLine("- **${w.text}** — ${w.note}" + if (w.context.isNotBlank()) "\n  _${w.context.take(220)}_" else "")
            }
        }
        if (s.shioris.isNotEmpty()) {
            appendLine(); appendLine("## Marcadores"); appendLine()
            s.shioris.forEach { appendLine("- ${it.chapter.ifBlank { "Página marcada" }} (${(it.progress * 100).toInt()} %)") }
        }
        appendLine(); append("— Hecho con Zerkalo")
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Página lista para imprimir o guardar como PDF. */
    fun html(s: Summary): String = buildString {
        append(
            """<!doctype html><html lang="es"><head><meta charset="utf-8"><style>
            @font-face { font-family: 'Literata'; src: url('fonts/literata_400_normal.ttf'); }
            @font-face { font-family: 'Literata'; font-style: italic; src: url('fonts/literata_400_italic.ttf'); }
            @font-face { font-family: 'Literata'; font-weight: bold; src: url('fonts/literata_500_normal.ttf'); }
            @page { margin: 18mm 16mm; }
            body { font-family: 'Literata', Georgia, serif; color: #2a221c; font-size: 11.5pt; line-height: 1.5; }
            h1 { font-size: 24pt; margin: 0 0 2pt; } .author { font-style: italic; color: #6b5d52; margin-bottom: 10pt; }
            .meta { font-family: sans-serif; font-size: 9pt; color: #6b5d52; border-bottom: 1px solid #d8cfc4; padding-bottom: 8pt; margin-bottom: 14pt; }
            h2 { font-size: 14pt; margin: 18pt 0 6pt; page-break-after: avoid; }
            blockquote { margin: 0 0 8pt; padding: 4pt 0 4pt 10pt; border-left: 3px solid #E2BE52; page-break-inside: avoid; }
            .note { font-family: sans-serif; font-size: 10pt; color: #4a3f37; margin: -2pt 0 10pt 13pt; }
            table { border-collapse: collapse; width: 100%; font-size: 10.5pt; }
            td { border-bottom: 1px solid #e6ded4; padding: 5pt 6pt; vertical-align: top; } td.w { font-weight: bold; width: 26%; }
            .ctx { font-style: italic; color: #6b5d52; font-size: 9.5pt; }
            .tag { display: block; font-family: sans-serif; font-size: 8.5pt; letter-spacing: 1px; text-transform: uppercase; margin-bottom: 2pt; }
            .foot { margin-top: 24pt; font-family: sans-serif; font-size: 8.5pt; color: #9a8d82; }
            </style></head><body>""",
        )
        append("<h1>${esc(s.book.title)}</h1>")
        if (s.book.author.isNotBlank()) append("<div class='author'>${esc(s.book.author)}</div>")
        append("<div class='meta'>Resumen de estudio · ${today()}<br>${s.highlights} subrayados · ${s.notes} notas · ${s.words.size} palabras · ${(s.book.progress * 100).toInt()} % leído · ${minutes(s.minutes)} de lectura</div>")
        s.chapters.forEach { c ->
            append("<h2>${esc(c.title)}</h2>")
            c.marks.forEach { m ->
                val tag = inkName(m.color).takeIf { it.isNotBlank() }?.let { "<span class='tag' style='color:${hex(m.color)}'>${esc(it)}</span>" }.orEmpty()
                append("<blockquote style='border-color:${hex(m.color)}'>$tag${esc(m.text.trim())}</blockquote>")
                if (m.note.isNotBlank()) append("<div class='note'>✎ ${esc(m.note.trim())}</div>")
            }
        }
        if (s.words.isNotEmpty()) {
            append("<h2>Vocabulario</h2><table>")
            s.words.forEach { w ->
                append("<tr><td class='w'>${esc(w.text)}</td><td>${esc(w.note)}" + (if (w.context.isNotBlank()) "<div class='ctx'>${esc(w.context.take(260))}</div>" else "") + "</td></tr>")
            }
            append("</table>")
        }
        if (s.shioris.isNotEmpty()) {
            append("<h2>Marcadores</h2><ul>")
            s.shioris.forEach { append("<li>${esc(it.chapter.ifBlank { "Página marcada" })} (${(it.progress * 100).toInt()} %)</li>") }
            append("</ul>")
        }
        append("<div class='foot'>Hecho con Zerkalo</div></body></html>")
    }

    fun share(ctx: Context, s: Summary) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Resumen de estudio: ${s.book.title}")
            .putExtra(Intent.EXTRA_TEXT, markdown(s))
        ctx.startActivity(Intent.createChooser(send, "Compartir resumen"))
    }

    private var printing: WebView? = null

    /** Abre el diálogo de impresión de Android, donde se elige "Guardar como PDF". */
    fun pdf(activity: Activity, s: Summary) {
        val web = WebView(activity)
        printing = web
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = "Zerkalo - " + s.book.title.take(60)
                pm.print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        web.loadDataWithBaseURL("file:///android_asset/", html(s), "text/html", "utf-8", null)
    }
}
