package com.sebas.zerkalo.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de la biblioteca en un .zip, para guardarla o mandarla entera.
 * - Completa: libros, documentos, portadas, avance, subrayados, notas adhesivas, trazos de pluma y apuntes.
 * - Solo los libros: los libros y sus portadas, sin notas ni avance (para regalar la biblioteca). Sin documentos.
 * Al recibir una copia, sus libros se suman a los tuyos (los que ya tienes no se repiten).
 */
object Backup {
    private const val META = "zerkalo-copia.json"
    private val EXTRA = listOf("apuntes", "tinta", "pdfmarcas")

    suspend fun export(ctx: Context, full: Boolean): File = withContext(Dispatchers.IO) {
        val books = Library.books.value.filter { full || !it.doc }
        val ids = books.map { it.id }.toSet()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val out = File(Export.outDir(ctx), "Biblioteca Zerkalo $day" + (if (full) "" else " (solo libros)") + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.setLevel(Deflater.BEST_SPEED) // los EPUB y PDF ya vienen comprimidos: lo rápido basta
            val meta = JSONObject().put("zerkalo", 1).put("full", full).put("at", System.currentTimeMillis())
                .put("books", JSONArray(books.map { b ->
                    (if (full) b else b.copy(progress = 0.0, locator = null, lastOpened = 0, finishedAt = 0, favorite = false, pair = null)).toJson()
                }))
            if (full) {
                meta.put("marks", JSONArray(Library.marks.value.filter { it.bookId in ids }.map { it.toJson() }))
                meta.put("sessions", JSONArray(Library.sessions.value.filter { it.bookId in ids }.map { it.toJson() }))
            }
            z.putNextEntry(ZipEntry(META)); z.write(meta.toString().toByteArray()); z.closeEntry()
            for (b in books) {
                put(z, "libros/${b.file}", File(Library.booksDir, b.file))
                b.cover?.let { put(z, "portadas/$it", File(Library.coversDir, it)) }
            }
            if (full) for (d in EXTRA) {
                val root = File(ctx.filesDir, d)
                root.walkTopDown().filter { it.isFile && ids.any { id -> it.name.startsWith(id) || it.parentFile?.name == id } }.forEach { f ->
                    put(z, "$d/" + f.relativeTo(root).path, f)
                }
            }
        }
        out
    }

    private fun put(z: ZipOutputStream, name: String, f: File) {
        if (!f.isFile) return
        z.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(z) }
        z.closeEntry()
    }

    /** Suma a la biblioteca los libros (y, si es copia completa, las notas) de un .zip de Zerkalo. Devuelve cuántos libros llegaron. */
    suspend fun restore(ctx: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "copia-entrante").apply { deleteRecursively(); mkdirs() }
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("No se pudo leer el archivo"))
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    // Nada fuera de la carpeta temporal (nombres raros como "../")
                    val dest = File(tmp, e.name).canonicalFile
                    if (!dest.path.startsWith(tmp.canonicalPath + File.separator) || e.isDirectory) continue
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zin.copyTo(it) }
                }
            }
            val metaFile = File(tmp, META)
            if (!metaFile.exists()) return@withContext Result.failure(Exception("Ese .zip no es una copia de Zerkalo"))
            val meta = JSONObject(metaFile.readText())
            val added = HashSet<String>()
            val arr = meta.optJSONArray("books") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val b = runCatching { Book.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
                if (Library.book(b.id) != null || Library.twinOf(b.title, b.author, b.id) != null) continue
                val src = File(tmp, "libros/${b.file}")
                val dst = File(Library.booksDir, b.file)
                if (!src.isFile || dst.exists()) continue
                if (!src.renameTo(dst)) src.copyTo(dst)
                b.cover?.let { c -> File(tmp, "portadas/$c").takeIf { it.isFile && !File(Library.coversDir, c).exists() }?.let { it.copyTo(File(Library.coversDir, c)) } }
                Library.upsert(b)
                added += b.id
            }
            meta.optJSONArray("marks")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = runCatching { Mark.fromJson(a.getJSONObject(i)) }.getOrNull() ?: continue
                    if (m.bookId in added) Library.putMark(m)
                }
            }
            meta.optJSONArray("sessions")?.let { a ->
                Library.importSessions((0 until a.length()).mapNotNull { runCatching { Session.fromJson(a.getJSONObject(it)) }.getOrNull() }.filter { it.bookId in added })
            }
            for (d in EXTRA) {
                val root = File(tmp, d)
                if (!root.isDirectory) continue
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(root).path
                    if (added.none { rel.startsWith(it) }) return@forEach
                    val target = File(File(ctx.filesDir, d), rel)
                    if (!target.exists()) { target.parentFile?.mkdirs(); f.copyTo(target) }
                }
            }
            Result.success(added.size)
        } catch (e: Exception) {
            Result.failure(Exception("No se pudo leer la copia: ${e.message}"))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
