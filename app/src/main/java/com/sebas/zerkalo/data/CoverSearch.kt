package com.sebas.zerkalo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Una portada posible para un libro: de qué edición es y de dónde viene. */
data class CoverOption(val big: String, val thumb: String, val source: String, val label: String, val lang: String)

/**
 * Portadas de otras ediciones del mismo libro: Open Library (portadas de muchas ediciones, libres de usar)
 * y Google Books (la API oficial de Google para libros). También guarda una imagen elegida por la persona.
 */
object CoverSearch {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val OL_LANG = mapOf(
        "spa" to "es", "eng" to "en", "fre" to "fr", "por" to "pt", "ger" to "de", "ita" to "it", "rus" to "ru",
        "jpn" to "ja", "lat" to "la", "dut" to "nl", "pol" to "pl", "chi" to "zh", "gre" to "el",
    )

    /** Título sin subtítulos ni paréntesis, que es como mejor encuentran las búsquedas. */
    private fun shortTitle(t: String) = t.substringBefore(':').substringBefore('(').substringBefore(" / ").trim().ifBlank { t }

    /** El apellido (la palabra más larga del autor), suficiente para no confundir obras con el mismo título. */
    private fun surname(a: String) = a.split(',', ';').firstOrNull().orEmpty().split(' ').filter { it.length > 2 }.maxByOrNull { it.length }.orEmpty()

    suspend fun find(book: Book): List<CoverOption> = coroutineScope {
        val ol = async { runCatching { openLibrary(book) }.getOrDefault(emptyList()) }
        val gb = async { runCatching { googleBooks(book) }.getOrDefault(emptyList()) }
        (ol.await() + gb.await()).distinctBy { it.thumb }
    }

    private suspend fun openLibrary(book: Book): List<CoverOption> {
        val q = "https://openlibrary.org/search.json?title=" + enc(shortTitle(book.title)) +
            surname(book.author).takeIf { it.isNotBlank() }?.let { "&author=" + enc(it) }.orEmpty() +
            "&fields=key,title,cover_i&limit=3"
        val docs = JSONObject(Net.text(q, timeout = 20_000)).optJSONArray("docs") ?: JSONArray()
        val out = mutableListOf<CoverOption>()
        for (i in 0 until docs.length().coerceAtMost(2)) {
            val d = docs.getJSONObject(i)
            val key = d.optString("key").takeIf { it.startsWith("/works/") } ?: continue
            val eds = runCatching { JSONObject(Net.text("https://openlibrary.org$key/editions.json?limit=80", timeout = 20_000)).optJSONArray("entries") }
                .getOrNull() ?: JSONArray()
            for (j in 0 until eds.length()) {
                val e = eds.getJSONObject(j)
                val ids = e.optJSONArray("covers") ?: continue
                val id = (0 until ids.length()).map { ids.optLong(it) }.firstOrNull { it > 0 } ?: continue
                val lang = e.optJSONArray("languages")?.optJSONObject(0)?.optString("key")?.substringAfterLast('/')?.let { OL_LANG[it] ?: it }.orEmpty()
                val label = listOf(e.optJSONArray("publishers")?.optString(0).orEmpty(), e.optString("publish_date"))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                out += CoverOption(
                    big = "https://covers.openlibrary.org/b/id/$id-L.jpg", thumb = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                    source = "Open Library", label = label.ifBlank { e.optString("title") }, lang = lang,
                )
            }
            d.optLong("cover_i").takeIf { it > 0 }?.let { id ->
                out += CoverOption("https://covers.openlibrary.org/b/id/$id-L.jpg", "https://covers.openlibrary.org/b/id/$id-M.jpg", "Open Library", d.optString("title"), "")
            }
        }
        return out.distinctBy { it.thumb }.take(40)
    }

    private suspend fun googleBooks(book: Book): List<CoverOption> {
        val q = "intitle:" + shortTitle(book.title) + surname(book.author).takeIf { it.isNotBlank() }?.let { " inauthor:$it" }.orEmpty()
        val items = JSONObject(Net.text("https://www.googleapis.com/books/v1/volumes?maxResults=30&printType=books&q=" + enc(q), timeout = 20_000))
            .optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val v = items.getJSONObject(i).optJSONObject("volumeInfo") ?: return@mapNotNull null
            val img = v.optJSONObject("imageLinks")?.let { it.optString("thumbnail").ifBlank { it.optString("smallThumbnail") } }
                ?.takeIf { it.isNotBlank() }?.replace("http://", "https://")?.replace("&edge=curl", "") ?: return@mapNotNull null
            CoverOption(
                big = "$img&fife=w800-h1200", thumb = img, source = "Google Books",
                label = listOf(v.optString("publisher"), v.optString("publishedDate").take(4)).filter { it.isNotBlank() }.joinToString(" · "),
                lang = v.optString("language"),
            )
        }
    }

    /** Baja la portada elegida (en grande; si no se puede, la pequeña) y la pone al libro. */
    suspend fun apply(book: Book, option: CoverOption): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(Library.coversDir, "tmp-${book.id}.img")
        try {
            Net.download(listOf(option.big, option.thumb), tmp, zip = false) { }
            save(book, BitmapFactory.decodeFile(tmp.path))
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** Una imagen elegida por la persona: de la galería o compartida desde el navegador. */
    suspend fun applyUri(ctx: Context, book: Book, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            save(book, bmp)
        }.getOrDefault(false)
    }

    private fun save(book: Book, bmp: Bitmap?): Boolean {
        if (bmp == null || bmp.width < 40 || bmp.height < 40) return false
        val scaled = if (bmp.width <= 600) bmp else Bitmap.createScaledBitmap(bmp, 600, (bmp.height * 600f / bmp.width).toInt(), true)
        val name = "${book.id}-c${System.currentTimeMillis()}.jpg"
        File(Library.coversDir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val old = Library.book(book.id)?.cover
        Library.update(book.id) { it.copy(cover = name, useBinding = false) }
        if (old != null && old != original(book) && old != name) File(Library.coversDir, old).delete()
        return true
    }

    /** La portada que traía el EPUB (se guarda al importar con el nombre del libro). */
    private fun original(book: Book) = "${book.id}.jpg"

    fun hasCustom(book: Book) = book.cover != null && book.cover != original(book)

    /** Vuelve a la portada del EPUB (o a la encuadernación si no traía). */
    fun restore(book: Book) {
        val cur = Library.book(book.id)?.cover
        val orig = original(book).takeIf { File(Library.coversDir, it).exists() }
        Library.update(book.id) { it.copy(cover = orig) }
        if (cur != null && cur != orig) File(Library.coversDir, cur).delete()
    }

    /** Búsqueda de Google Imágenes con el título y el autor, para abrir en el navegador. */
    fun googleImagesUrl(book: Book): String =
        "https://www.google.com/search?tbm=isch&q=" + enc("${book.title} ${book.author} portada libro".trim())
}
