package com.sebas.zerkalo.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La biblioteca vive en el teléfono: un archivo JSON pequeño con libros, marcas y sesiones.
 * Se carga una vez al abrir la app y se guarda en segundo plano, sin trabar la interfaz.
 */
object Library {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    private val _marks = MutableStateFlow<List<Mark>>(emptyList())
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()
    val marks: StateFlow<List<Mark>> = _marks.asStateFlow()
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    val booksDir: File get() = File(dir, "books").apply { mkdirs() }
    val coversDir: File get() = File(dir, "covers").apply { mkdirs() }

    private val file get() = File(dir, "biblioteca.json")

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.filesDir
        try {
            val f = file
            if (f.exists()) {
                val o = JSONObject(f.readText())
                _books.value = o.optJSONArray("books").objects().mapNotNull { runCatching { Book.fromJson(it) }.getOrNull() }
                _marks.value = o.optJSONArray("marks").objects().mapNotNull { runCatching { Mark.fromJson(it) }.getOrNull() }
                _sessions.value = o.optJSONArray("sessions").objects().mapNotNull { runCatching { Session.fromJson(it) }.getOrNull() }
            }
        } catch (e: Exception) {
            android.util.Log.e("Zerkalo", "No se pudo leer la biblioteca", e)
        }
    }

    /** Nombre para reconocer el mismo libro aunque el archivo se llame distinto: título + apellido del autor. */
    fun sameKey(title: String, author: String): String {
        val who = author.split(',', ';', '&').firstOrNull().orEmpty().split(' ').filter { it.length > 2 }.maxByOrNull { it.length }.orEmpty()
        return Importer.cleanName(title) + "|" + Importer.cleanName(who)
    }

    /** El libro que ya está en la biblioteca con el mismo título y autor (si lo hay). */
    fun twinOf(title: String, author: String, exceptId: String): Book? {
        val k = sameKey(title, author)
        if (k.startsWith("|")) return null
        return _books.value.firstOrNull { it.id != exceptId && sameKey(it.title, it.author) == k }
    }

    /**
     * Junta los libros repetidos: se queda el que tiene más subrayados, avance o lectura reciente,
     * y le pasa al que queda los subrayados, el tiempo de lectura, las etiquetas y el favorito del otro.
     * Devuelve cuántas copias quitó.
     */
    fun dedupe(): Int {
        val groups = _books.value.groupBy { sameKey(it.title, it.author) }.filter { (k, g) -> g.size > 1 && !k.startsWith("|") }
        if (groups.isEmpty()) return 0
        var removed = 0
        for ((_, g) in groups) {
            val marksOf = _marks.value.groupingBy { it.bookId }.eachCount()
            val keep = g.maxWith(compareBy<Book>({ marksOf[it.id] ?: 0 }, { it.progress }, { it.lastOpened }, { -it.addedAt }))
            val others = g.filter { it.id != keep.id }
            val ids = others.map { it.id }.toSet()
            _marks.update { l -> l.map { if (it.bookId in ids) it.copy(bookId = keep.id) else it } }
            _sessions.update { l -> l.map { if (it.bookId in ids) it.copy(bookId = keep.id) else it } }
            _books.update { l ->
                l.filterNot { it.id in ids }.map { b ->
                    when {
                        b.id == keep.id -> b.copy(
                            favorite = g.any { it.favorite }, tags = g.flatMap { it.tags }.distinct(),
                            pair = b.pair ?: others.firstNotNullOfOrNull { it.pair },
                            finishedAt = if (b.finishedAt > 0) b.finishedAt else others.maxOf { it.finishedAt },
                        )
                        b.pair in ids -> b.copy(pair = keep.id)
                        else -> b
                    }
                }
            }
            for (o in others) {
                File(booksDir, o.file).takeIf { o.file != keep.file }?.delete()
                o.cover?.takeIf { it != keep.cover }?.let { File(coversDir, it).delete() }
                removed++
            }
        }
        save()
        return removed
    }

    fun book(id: String?): Book? = _books.value.firstOrNull { it.id == id }
    fun byKey(key: String): Book? = _books.value.firstOrNull { it.key == key }

    fun upsert(book: Book) {
        _books.update { list -> if (list.any { it.id == book.id }) list.map { if (it.id == book.id) book else it } else list + book }
        save()
    }

    fun update(id: String, change: (Book) -> Book) {
        _books.update { list -> list.map { if (it.id == id) change(it) else it } }
        save()
    }

    fun remove(book: Book) {
        File(booksDir, book.file).delete()
        book.cover?.let { File(coversDir, it).delete() }
        // Portadas elegidas por la persona y la original del EPUB
        coversDir.listFiles()?.filter { it.name == "${book.id}.jpg" || it.name.startsWith("${book.id}-c") }?.forEach { it.delete() }
        _books.update { l -> l.filterNot { it.id == book.id } }
        _marks.update { l -> l.filterNot { it.bookId == book.id } }
        _sessions.update { l -> l.filterNot { it.bookId == book.id } }
        Apuntes.remove(book.id)
        save()
    }

    fun putMark(m: Mark) {
        _marks.update { l -> l.filterNot { it.id == m.id } + m }
        save()
    }

    fun removeMark(id: String) {
        _marks.update { l -> l.filterNot { it.id == id } }
        save()
    }

    fun addSession(bookId: String, ms: Long) {
        if (ms < 20_000) return
        _sessions.update { it + Session(bookId, today(), ms, System.currentTimeMillis()) }
        save()
    }

    fun minutesToday(): Int = (_sessions.value.filter { it.date == today() }.sumOf { it.ms } / 60_000).toInt()

    /** Minutos leídos por día de un libro, en orden. */
    fun bookDays(bookId: String): List<Pair<String, Int>> =
        _sessions.value.filter { it.bookId == bookId }.groupBy { it.date }
            .map { (d, xs) -> d to maxOf(1, (xs.sumOf { it.ms } / 60_000).toInt()) }.sortedBy { it.first }

    fun today(d: Date = Date()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

    /** Guarda medio segundo después del último cambio (agrupa los cambios seguidos). */
    private fun save() {
        if (!::dir.isInitialized) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val o = JSONObject()
                .put("version", 2)
                .put("books", JSONArray(_books.value.map { it.toJson() }))
                .put("marks", JSONArray(_marks.value.map { it.toJson() }))
                .put("sessions", JSONArray(_sessions.value.map { it.toJson() }))
            val tmp = File(dir, "biblioteca.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
