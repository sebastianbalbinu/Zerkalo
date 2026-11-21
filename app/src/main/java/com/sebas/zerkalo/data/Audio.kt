package com.sebas.zerkalo.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Un capítulo de un audiolibro. */
data class AudioTrack(val title: String, val url: String, val seconds: Int) {
    fun toJson(): JSONObject = JSONObject().put("t", title).put("u", url).put("s", seconds)

    companion object {
        fun fromJson(o: JSONObject) = AudioTrack(o.optString("t"), o.optString("u"), o.optInt("s"))
    }
}

/**
 * Un audiolibro de LibriVox: grabado por voluntarios con su propia voz (nunca voces sintéticas)
 * y de dominio público. Se escucha desde el Internet Archive, donde LibriVox guarda sus grabaciones.
 */
data class AudioBook(
    val id: String,
    val title: String,
    val author: String,
    val lang: String = "",
    val about: String = "",
    val tracks: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val updatedAt: Long = 0,
    val linkedBook: String? = null, // el EPUB de la biblioteca con el que se lee a la vez
    val spots: Map<Int, Long> = emptyMap(), // por dónde ibas en cada capítulo empezado
) {
    val cover get() = "https://archive.org/services/img/$id"
    val totalSeconds get() = tracks.sumOf { it.seconds }

    /** Dónde retomar un capítulo: el último punto escuchado en él, o el inicio. */
    fun spot(i: Int): Long = if (i == index && position > 0) position else spots[i] ?: 0L

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author).put("lang", lang)
        .put("about", about).put("tracks", JSONArray(tracks.map { it.toJson() })).put("index", index).put("pos", position)
        .put("at", updatedAt).put("book", linkedBook ?: JSONObject.NULL)
        .put("spots", JSONObject().apply { spots.forEach { (k, v) -> put(k.toString(), v) } })

    companion object {
        fun fromJson(o: JSONObject) = AudioBook(
            o.getString("id"), o.optString("title"), o.optString("author"), o.optString("lang"), o.optString("about"),
            o.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { AudioTrack.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optInt("index"), o.optLong("pos"), o.optLong("at"), o.optString("book").takeIf { it.isNotBlank() && it != "null" },
            o.optJSONObject("spots")?.let { j -> j.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to j.optLong(k) } }.toMap() } ?: emptyMap(),
        )
    }
}

/** Un idioma de LibriVox: código de la app, nombre y cómo lo escribe el Internet Archive. */
data class AudioLang(val id: String, val name: String, val archive: List<String>)

/** Orden de los resultados del buscador de audiolibros. */
enum class AudioSort(val label: String, val archive: String) {
    POPULAR("Más escuchados", "downloads desc"),
    RECENT("Recientes", "addeddate desc"),
    TITLE("A–Z", "titleSorter asc"),
}

object LibriVox {
    /** "all" = todos los idiomas. */
    val LANGS = listOf(
        AudioLang("all", "Todos", emptyList()),
        AudioLang("es", "Español", listOf("spa", "Spanish")), AudioLang("en", "English", listOf("eng", "English")),
        AudioLang("fr", "Français", listOf("fre", "French")), AudioLang("pt", "Português", listOf("por", "Portuguese")),
        AudioLang("de", "Deutsch", listOf("ger", "German")), AudioLang("it", "Italiano", listOf("ita", "Italian")),
        AudioLang("ru", "Русский", listOf("rus", "Russian")), AudioLang("ja", "日本語", listOf("jpn", "Japanese")),
        AudioLang("la", "Latina", listOf("lat", "Latin")), AudioLang("nl", "Nederlands", listOf("dut", "Dutch")),
        AudioLang("pl", "Polski", listOf("pol", "Polish")), AudioLang("el", "Ελληνικά", listOf("gre", "Greek")),
        AudioLang("zh", "中文", listOf("chi", "Chinese")), AudioLang("sv", "Svenska", listOf("swe", "Swedish")),
        AudioLang("fi", "Suomi", listOf("fin", "Finnish")), AudioLang("da", "Dansk", listOf("dan", "Danish")),
    )

    fun langName(id: String) = LANGS.firstOrNull { it.id == id && id != "all" }?.name.orEmpty()

    /** El idioma de un resultado, a partir de lo que dice el Internet Archive ("Spanish", "spa"…). */
    private fun langOf(raw: String): String {
        val r = raw.lowercase()
        return LANGS.firstOrNull { l -> l.archive.any { it.lowercase() == r } }?.id.orEmpty()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Audiolibros de LibriVox en un idioma ("all" = todos); con [query], los que coinciden con el título o el autor.
     * [page] empieza en 1 (para "Cargar más").
     */
    suspend fun search(
        lang: String, query: String? = null, rows: Int = 40, raw: Boolean = false,
        sort: AudioSort = AudioSort.POPULAR, page: Int = 1,
    ): List<AudioBook> {
        val q = buildString {
            append("collection:librivoxaudio AND mediatype:audio")
            LANGS.firstOrNull { it.id == lang }?.archive?.takeIf { it.isNotEmpty() }?.let { append(" AND language:(").append(it.joinToString(" OR ")).append(")") }
            if (!query.isNullOrBlank()) append(" AND (").append(if (raw) query else query.replace(Regex("[():\\\"]"), " ")).append(")")
        }
        val u = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=description&fl[]=language&sort[]=" + enc(sort.archive) +
            "&rows=$rows&page=$page&output=json"
        val docs = JSONObject(Net.text(u, timeout = 20_000)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        fun str(o: JSONObject, k: String): String = when (val v = o.opt(k)) { is JSONArray -> v.optString(0); null -> ""; else -> v.toString() }
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            AudioBook(
                id = d.optString("identifier"), title = cleanTitle(str(d, "title")), author = Catalogs.niceName(str(d, "creator")),
                lang = if (lang == "all") langOf(str(d, "language")) else lang, about = Importer.cleanHtml(str(d, "description")).take(700),
            )
        }
    }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\(?[Vv]ersion \\d+\\)?\\s*$"), "").trim()

    /** Los capítulos (MP3 de 64 kbps) en orden. */
    suspend fun tracks(id: String): List<AudioTrack> {
        val o = JSONObject(Net.text("https://archive.org/metadata/$id", timeout = 20_000))
        val files = o.optJSONArray("files") ?: JSONArray()
        val mp3 = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("format") == "64Kbps MP3" || it.optString("name").endsWith("_64kb.mp3") }
            .sortedWith(compareBy({ it.optString("track").substringBefore('/').toIntOrNull() ?: 9999 }, { it.optString("name") }))
        return mp3.map { f ->
            val name = f.optString("name")
            AudioTrack(
                title = f.optString("title").ifBlank { name.substringBeforeLast('.') },
                url = "https://archive.org/download/$id/" + enc(name).replace("+", "%20"),
                seconds = parseLength(f.optString("length")),
            )
        }
    }

    private fun parseLength(s: String): Int = when {
        s.contains(':') -> s.split(':').fold(0) { acc, p -> acc * 60 + (p.toDoubleOrNull()?.toInt() ?: 0) }
        else -> s.toDoubleOrNull()?.toInt() ?: 0
    }
}

/** Tus audiolibros: por dónde vas en cada uno y cuáles están descargados. */
object AudioStore {
    private lateinit var file: File
    lateinit var dir: File
        private set
    val books = MutableStateFlow<List<AudioBook>>(emptyList())
    /** Avance de descarga por audiolibro (0..1); 1 = completo. */
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "audiolibros.json")
        dir = File(ctx.filesDir, "audio").apply { mkdirs() }
        books.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { AudioBook.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        downloads.value = books.value.filter { isDownloaded(it) }.associate { it.id to 1f }
    }

    @Synchronized
    private fun save() = runCatching { file.writeText(JSONArray(books.value.map { it.toJson() }).toString()) }

    fun get(id: String) = books.value.firstOrNull { it.id == id }
    fun forBook(bookId: String) = books.value.firstOrNull { it.linkedBook == bookId }

    fun put(b: AudioBook) {
        books.value = listOf(b) + books.value.filter { it.id != b.id }
        save()
    }

    fun progress(id: String, index: Int, position: Long) {
        get(id) ?: return
        books.value = books.value.map {
            if (it.id == id) it.copy(index = index, position = position, spots = it.spots + (index to position), updatedAt = System.currentTimeMillis()) else it
        }
        save()
    }

    /** Un capítulo escuchado hasta el final: la próxima vez empieza desde el inicio. */
    fun finishTrack(id: String, i: Int) {
        get(id)?.takeIf { i in it.spots } ?: return
        books.value = books.value.map { if (it.id == id) it.copy(spots = it.spots - i) else it }
        save()
    }

    /** Une el audiolibro con un EPUB de la biblioteca, para leer mientras se escucha. */
    fun link(id: String, bookId: String) {
        books.value = books.value.map { if (it.id == id) it.copy(linkedBook = bookId) else it }
        save()
    }

    fun remove(id: String) {
        books.value = books.value.filter { it.id != id }
        File(dir, id).deleteRecursively()
        downloads.value = downloads.value - id
        save()
    }

    fun localFile(id: String, i: Int) = File(File(dir, id), "$i.mp3")
    fun isDownloaded(b: AudioBook) = b.tracks.isNotEmpty() && b.tracks.indices.all { localFile(b.id, it).length() > 0 }

    /** Baja todos los capítulos para escuchar sin internet. */
    fun download(b: AudioBook) {
        if (downloads.value[b.id]?.let { it < 1f } == true) return
        downloads.value = downloads.value + (b.id to 0f)
        scope.launch {
            runCatching {
                b.tracks.forEachIndexed { i, t ->
                    val f = localFile(b.id, i)
                    if (f.length() <= 0) { f.parentFile?.mkdirs(); Net.download(listOf(t.url), f, zip = false) { } }
                    downloads.value = downloads.value + (b.id to (i + 1) / b.tracks.size.toFloat())
                }
            }.onFailure { downloads.value = downloads.value - b.id }
        }
    }

    fun deleteDownload(id: String) {
        File(dir, id).deleteRecursively()
        downloads.value = downloads.value - id
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** El EPUB de tu biblioteca que es el mismo libro que este audiolibro (ya unido, o por título). */
    fun bookFor(b: AudioBook): Book? {
        b.linkedBook?.let { Library.book(it) }?.let { return it }
        val t = norm(b.title)
        if (t.length < 3) return null
        return Library.books.value.firstOrNull { val n = norm(it.title); n.length >= 3 && (n == t || n.startsWith(t) || t.startsWith(n)) }
    }

    /** Busca en LibriVox la versión en audio de un libro de tu biblioteca. */
    suspend fun findFor(book: Book): List<AudioBook> = withContext(Dispatchers.IO) {
        val words = book.title.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.length > 2 }.take(4)
        if (words.isEmpty()) return@withContext emptyList()
        val author = book.author.split(' ', ',').filter { it.length > 2 }.maxByOrNull { it.length }.orEmpty()
        val q = (listOf("title:(" + words.joinToString(" AND ") + ")") + if (author.isNotBlank()) listOf("creator:($author)") else emptyList()).joinToString(" AND ")
        val lang = book.lang.ifBlank { "es" }
        runCatching { LibriVox.search(lang, q, rows = 10, raw = true) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { LibriVox.search(lang, "title:(" + words.joinToString(" AND ") + ")", rows = 10, raw = true) }.getOrDefault(emptyList()) }
    }
}
