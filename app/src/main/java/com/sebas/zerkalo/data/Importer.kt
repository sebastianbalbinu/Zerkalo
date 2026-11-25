package com.sebas.zerkalo.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.zip.ZipFile

/** Los objetos de Readium que abren los EPUB. Se crean una sola vez. */
class ReadiumCore(context: Context) {
    val httpClient = DefaultHttpClient()
    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    /** Abre un EPUB guardado. Si falla, el mensaje dice por qué en palabras sencillas. */
    suspend fun open(file: File): Result<Publication> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Result.failure(ImportError("El archivo del libro ya no está en el teléfono"))
        drmProblem(file)?.let { return@withContext Result.failure(ImportError(it)) }
        val asset = assetRetriever.retrieve(file).getOrElse {
            return@withContext Result.failure(ImportError("Este archivo no parece un EPUB (${it.message})"))
        }
        val pub = opener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            return@withContext Result.failure(ImportError("No se pudo leer el EPUB: ${it.message}"))
        }
        if (pub.isRestricted) {
            pub.close()
            return@withContext Result.failure(ImportError("Este libro tiene protección DRM y no se puede abrir fuera de la tienda donde se compró"))
        }
        Result.success(pub)
    }

    /** Detecta libros con DRM de Adobe, Apple o Kobo, para explicar por qué no abren. */
    private fun drmProblem(file: File): String? = try {
        ZipFile(file).use { zip ->
            val enc = zip.getEntry("META-INF/encryption.xml")?.let { e -> zip.getInputStream(e).bufferedReader().use { it.readText() } } ?: ""
            when {
                zip.getEntry("META-INF/rights.xml") != null || enc.contains("adobe.com/adept") ->
                    "Este libro tiene DRM de Adobe (protección de copia). Zerkalo solo abre EPUB libres de DRM"
                zip.getEntry("META-INF/sinf.xml") != null ->
                    "Este libro tiene DRM de Apple Books y no se puede abrir fuera de esa app"
                enc.contains("EncryptedData") && !enc.contains("http://www.idpf.org/2008/embedding") && !enc.contains("http://ns.adobe.com/pdf/enc#RC") ->
                    "Este libro está cifrado (DRM) y no se puede abrir"
                else -> null
            }
        }
    } catch (e: java.util.zip.ZipException) {
        "El archivo está dañado o no es un EPUB (no se pudo abrir como ZIP)"
    } catch (e: Exception) {
        null
    }
}

class ImportError(message: String) : Exception(message)

object Importer {
    private fun newId() = "b" + System.currentTimeMillis().toString(36) + (100..999).random()

    /** Trae un EPUB desde otra app (Archivos, WhatsApp, Drive…). */
    /** [asDoc]: los PDF llegan como documento (no van a los estantes). Se usa al abrir un archivo desde otra app. */
    suspend fun fromUri(ctx: Context, core: ReadiumCore, uri: Uri, asDoc: Boolean = false): Result<Book> = withContext(Dispatchers.IO) {
        try {
            var display = "libro.epub"
            runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) display = c.getString(i) ?: display
                }
            }
            val id = newId()
            val type = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
            val tmp = File(Library.booksDir, "$id.tmp")
            val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(ImportError("No se pudo leer el archivo"))
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            // ¿PDF? Por el nombre, por el tipo o por cómo empieza el archivo ("%PDF")
            val pdf = display.endsWith(".pdf", true) || type == "application/pdf" || looksPdf(tmp)
            val dest = File(Library.booksDir, if (pdf) "$id.pdf" else "$id.epub")
            tmp.renameTo(dest)
            if (pdf) fromPdf(dest, id, display.substringBeforeLast('.'), doc = asDoc)
            else fromFile(core, dest, id, key = "local:$id", source = "Importado", fallbackTitle = display.removeSuffix(".epub"))
        } catch (e: SecurityException) {
            Result.failure(ImportError("La otra app no dio permiso para leer el archivo"))
        } catch (e: Exception) {
            Result.failure(ImportError("No se pudo copiar el archivo: ${e.message}"))
        }
    }

    /** Copia un EPUB que está en el teléfono (lo encontró el buscador) a la biblioteca. */
    suspend fun fromPath(core: ReadiumCore, src: File): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val id = newId()
            val dest = File(Library.booksDir, "$id.epub")
            src.inputStream().use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            fromFile(core, dest, id, key = "local:$id", source = "Importado", fallbackTitle = src.name.removeSuffix(".epub"))
        } catch (e: Exception) {
            Result.failure(ImportError("No se pudo copiar ${src.name}: ${e.message}"))
        }
    }

    private fun looksPdf(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(5); i.read(b) == 5 && String(b, Charsets.US_ASCII) == "%PDF-" }
    }.getOrDefault(false)

    /** Título legible a partir del nombre del archivo: sin guiones bajos ni puntos raros. */
    private fun titleFromName(n: String) = n.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Documento PDF" }

    /**
     * Registra un PDF: la portada es la primera página. Android trae su propio lector de PDF
     * (PdfRenderer), así que no hace falta nada más para dibujarla.
     */
    suspend fun fromPdf(file: File, id: String, name: String, doc: Boolean = false): Result<Book> = withContext(Dispatchers.IO) {
        try {
            var pages = 0
            var cover: String? = null
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r ->
                    pages = r.pageCount
                    if (pages > 0) r.openPage(0).use { pg ->
                        val w = 480
                        val h = (pg.height * w.toFloat() / pg.width.coerceAtLeast(1)).toInt().coerceIn(200, 1200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(Library.coversDir, "$id.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                        cover = "$id.jpg"
                    }
                }
            }
            if (pages == 0) { file.delete(); return@withContext Result.failure(ImportError("El PDF está vacío")) }
            val book = Book(
                id = id, key = "local:$id", title = titleFromName(name), author = "", source = "PDF",
                file = file.name, cover = cover, genre = "-", doc = doc,
            )
            Library.twinOf(book.title, book.author, book.id)?.takeIf { it.isPdf }?.let { twin ->
                file.delete(); cover?.let { File(Library.coversDir, it).delete() }
                return@withContext Result.success(twin)
            }
            Library.upsert(book)
            Result.success(book)
        } catch (e: SecurityException) {
            file.delete()
            Result.failure(ImportError("Este PDF tiene contraseña y no se puede abrir"))
        } catch (e: Exception) {
            file.delete()
            Result.failure(ImportError("No se pudo abrir el PDF: ${e.message}"))
        }
    }

    /** Nombre simplificado para reconocer un libro que ya está en la biblioteca. */
    fun cleanName(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "")

    /** Registra un EPUB que ya está en la carpeta de libros. */
    suspend fun fromFile(
        core: ReadiumCore, file: File, id: String, key: String, source: String,
        fallbackTitle: String = "", item: CatalogItem? = null,
    ): Result<Book> = withContext(Dispatchers.IO) {
        val pub = core.open(file).getOrElse {
            file.delete()
            return@withContext Result.failure(it)
        }
        try {
            val md = pub.metadata
            var cover: String? = null
            runCatching { pub.cover() }.getOrNull()?.let { bmp ->
                val name = "$id.jpg"
                val scaled = scale(bmp, 480)
                File(Library.coversDir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                cover = name
            }
            val book = Book(
                id = id, key = key,
                title = item?.title?.takeIf { it.isNotBlank() } ?: md.title?.takeIf { it.isNotBlank() } ?: fallbackTitle.ifBlank { "Sin título" },
                author = item?.author?.takeIf { it.isNotBlank() } ?: md.authors.joinToString(", ") { it.name },
                lang = (item?.lang?.takeIf { it.isNotBlank() } ?: md.languages.firstOrNull() ?: "").take(2),
                source = source,
                summary = item?.summary?.takeIf { it.isNotBlank() } ?: cleanHtml(md.description ?: ""),
                file = file.name, cover = cover,
                genre = Genres.detect(md.subjects.map { it.name }, item?.title ?: md.title.orEmpty()).ifBlank { "-" },
            )
            // Si ya está (mismo título y autor, aunque el archivo se llame distinto), no se repite.
            Library.twinOf(book.title, book.author, book.id)?.let { twin ->
                if (twin.file != file.name) file.delete()
                cover?.let { File(Library.coversDir, it).delete() }
                return@withContext Result.success(twin)
            }
            Library.upsert(book)
            Result.success(book)
        } finally {
            pub.close()
        }
    }

    private fun scale(b: Bitmap, maxW: Int): Bitmap =
        if (b.width <= maxW) b else Bitmap.createScaledBitmap(b, maxW, (b.height * maxW.toFloat() / b.width).toInt(), true)

    fun cleanHtml(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

    /** Descarga un libro de un catálogo y lo deja en la biblioteca. */
    suspend fun fromCatalog(core: ReadiumCore, item: CatalogItem, onProgress: (Float?) -> Unit): Result<Book> {
        Library.byKey(item.key)?.let { return Result.success(it) }
        val id = newId()
        val dest = File(Library.booksDir, "$id.epub")
        return try {
            val ws = item.epubUrls.firstOrNull { it.startsWith("wsb:") }
            if (ws != null) {
                // Wikisource: Zerkalo arma el EPUB con el texto de la obra.
                val (lang, title) = ws.removePrefix("wsb:").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                WikisourceEpub.build(lang, title, item.author, dest) { p -> onProgress(p?.let { it * 0.92f }) }
            } else {
                // Internet Archive: primero se busca cuál es su archivo EPUB libre.
                val urls = item.epubUrls.flatMap { u -> if (u.startsWith("ia:")) Catalogs.resolveIa(u.removePrefix("ia:")) else listOf(u) }
                if (urls.isEmpty()) throw NetError("Este libro del Internet Archive no tiene un EPUB libre")
                Net.download(urls, dest) { p -> onProgress(p?.let { it * 0.92f }) }
            }
            val r = fromFile(core, dest, id, item.key, item.source, item = item)
            onProgress(1f)
            r
        } catch (e: Exception) {
            dest.delete()
            Result.failure(ImportError(e.message ?: "No se pudo descargar"))
        }
    }
}
