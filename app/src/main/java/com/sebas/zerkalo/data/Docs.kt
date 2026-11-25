package com.sebas.zerkalo.data

import android.content.Context
import java.io.File

/**
 * Documentos: PDF abiertos desde otra app. Se leen con todas las herramientas, pero no llenan los estantes.
 * Los que no se abren en [DAYS] días se quitan solos; si tienen notas, trazos o apunte, antes se pregunta.
 */
object Docs {
    const val DAYS = 30

    /** ¿Tiene algo tuyo? (subrayados, notas adhesivas, trazos de pluma o apunte). */
    fun hasNotes(ctx: Context, b: Book): Boolean =
        Library.marks.value.any { it.bookId == b.id } ||
            Apuntes.flow(b.id).value.isNotBlank() ||
            File(File(ctx.filesDir, "tinta"), "${b.id}.json").exists() ||
            File(File(ctx.filesDir, "pdfmarcas"), "${b.id}.json").exists()

    /** Documentos que no se abren hace más de [DAYS] días. */
    fun stale(now: Long = System.currentTimeMillis()): List<Book> =
        Library.books.value.filter { it.doc && maxOf(it.lastOpened, it.addedAt) < now - DAYS * 86_400_000L }

    /** Lo pasa a los estantes: desde ahora es un libro. */
    fun toLibrary(b: Book) = Library.update(b.id) { it.copy(doc = false) }

    /** Lo deja como nuevo por otros [DAYS] días. */
    fun keep(b: Book) = Library.update(b.id) { it.copy(lastOpened = System.currentTimeMillis()) }

    /** Limpieza al abrir la app: quita en silencio los viejos sin notas; devuelve los que tienen notas (para preguntar). */
    fun cleanup(ctx: Context): List<Book> {
        val old = stale()
        old.filter { !hasNotes(ctx, it) }.forEach { Library.remove(it) }
        return old.filter { hasNotes(ctx, it) }
    }
}
