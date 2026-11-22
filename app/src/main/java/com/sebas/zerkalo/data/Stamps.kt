package com.sebas.zerkalo.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * El libro de sellos (goshuinchō): cada libro terminado deja su sello cuadrado con el kanji de su
 * género, en la tinta del rango de quien lee. A veces sale un sello de cera con los colores de la portada.
 * También hay sellos especiales por momentos importantes.
 */
object Stamps {
    /** Una tinta del camino, con colores tradicionales japoneses. */
    data class Ink(val id: String, val kanji: String, val name: String, val from: Int, val why: String, val light: Int, val dark: Int, val metal: Boolean = false)

    val INKS = listOf(
        Ink("shu", "朱", "Shu · bermellón", 1, "La tinta de los hanko y de los portales de los santuarios. Aquí empieza tu camino.", 0xFFD2452F.toInt(), 0xFFB0331F.toInt()),
        Ink("ai", "藍", "Ai · índigo", 100, "El azul de los teñidos japoneses, el «azul Japón». El color de quien es constante.", 0xFF2C4A7A.toInt(), 0xFF16294A.toInt()),
        Ink("sumi", "墨", "Sumi · tinta negra", 200, "La tinta de la caligrafía y de los maestros del pincel. Ya sabes leer de verdad.", 0xFF3A3835.toInt(), 0xFF121211.toInt()),
        Ink("murasaki", "紫", "Murasaki · púrpura", 500, "El color del rango más alto de la antigua corte, y el nombre de quien escribió La historia de Genji.", 0xFF7A4290.toInt(), 0xFF4A2259.toInt()),
        Ink("kin", "金", "Kin · oro", 1000, "El oro del kintsugi, que repara lo roto con belleza. Una vida de lectura.", 0xFFF3D77A.toInt(), 0xFF8A6512.toInt(), metal = true),
    )

    fun inkFor(n: Int): Ink = INKS.last { n >= it.from }

    /** Un kanji por género: la colección que se completa leyendo de todo. */
    val KANJI: List<Triple<String, String, String>> = listOf(
        Triple("fiction", "物", "Novela"), Triple("poetry", "詩", "Poesía"), Triple("short stories", "話", "Cuento"),
        Triple("drama", "劇", "Teatro"), Triple("essays", "論", "Ensayo"), Triple("philosophy", "哲", "Filosofía"),
        Triple("history", "史", "Historia"), Triple("biography", "伝", "Biografía"), Triple("travel", "旅", "Viajes"),
        Triple("adventure", "冒", "Aventura"), Triple("romance", "恋", "Romance"), Triple("mystery", "謎", "Misterio"),
        Triple("horror", "怪", "Terror"), Triple("science fiction", "星", "Ciencia ficción"), Triple("myths", "神", "Mitos"),
        Triple("children", "童", "Infantil"), Triple("humor", "笑", "Humor"), Triple("war", "戦", "Guerra"),
        Triple("nature", "森", "Naturaleza"), Triple("psychology", "心", "Psicología"), Triple("science", "理", "Ciencia"),
        Triple("classics", "古", "Clásicos"), Triple("religion", "祈", "Espiritualidad"), Triple("politics", "政", "Sociedad"),
        Triple("art", "芸", "Arte"), Triple("-", "書", "Otros"),
    )

    fun kanjiFor(genre: String): Triple<String, String, String> = KANJI.firstOrNull { it.first == genre } ?: KANJI.last()

    /** Sello de un libro terminado. [wax]: colores de su portada si salió sello de cera (raro). */
    data class BookStamp(val book: Book, val n: Int, val kanji: String, val genre: String, val ink: Ink, val wax: List<Int>?)

    /** Sello especial: [at] es cuándo se ganó (null si todavía no). Los [secret] no muestran su pista. */
    data class Special(val id: String, val kanji: String, val name: String, val hint: String, val secret: Boolean, val at: Long?)

    private lateinit var dir: File
    private val waxMap = HashMap<String, List<Int>>()
    private val earned = HashMap<String, Long>()
    private var since = 0L
    private val _version = MutableStateFlow(0)
    /** Cambia cada vez que se gana un sello o se decide un sello de cera (para refrescar la pantalla). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val file get() = File(dir, "sellos.json")

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.filesDir
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.optJSONObject("wax")?.let { w -> w.keys().forEach { k -> val a = w.optJSONArray(k) ?: JSONArray(); waxMap[k] = (0 until a.length()).map { a.optInt(it) } } }
                o.optJSONObject("earned")?.let { e -> e.keys().forEach { k -> earned[k] = e.optLong(k) } }
                since = o.optLong("since")
            }
        }
        if (since == 0L) {
            since = Library.books.value.minOfOrNull { it.addedAt }?.takeIf { it > 0 } ?: System.currentTimeMillis()
            save()
        }
    }

    private fun save() {
        if (!::dir.isInitialized) return
        val o = JSONObject().put("since", since)
            .put("wax", JSONObject().apply { waxMap.forEach { (k, v) -> put(k, JSONArray(v)) } })
            .put("earned", JSONObject().apply { earned.forEach { (k, v) -> put(k, v) } })
        runCatching { File(dir, "sellos.json.tmp").apply { writeText(o.toString()) }.renameTo(file) }
    }

    fun since(): Long = since

    /** Libros terminados, en el orden en que se terminaron, con su sello. */
    fun bookStamps(books: List<Book>): List<BookStamp> =
        books.filter { it.finished && it.finishedAt > 0 }.sortedBy { it.finishedAt }.mapIndexed { i, b ->
            val (_, k, name) = kanjiFor(b.genre)
            BookStamp(b, i + 1, k, name, inkFor(i + 1), waxMap[b.id]?.takeIf { it.size >= 2 })
        }

    /** ¿Sale sello de cera? Más o menos uno de cada diez libros, siempre el mismo para el mismo libro. */
    private fun isRare(id: String) = ((id.hashCode().toLong() and 0x7fffffff) * 2654435761L % 1000) < 100

    /** Decide (una sola vez) el sello de cera de los libros terminados, con los colores de su portada. */
    suspend fun settle(books: List<Book>) = withContext(Dispatchers.IO) {
        var changed = false
        for (b in books.filter { it.finished && it.finishedAt > 0 && it.id !in waxMap }) {
            waxMap[b.id] = if (isRare(b.id)) b.cover?.let { palette(File(Library.coversDir, it)) }.orEmpty() else emptyList()
            changed = true
        }
        if (changed) { save(); _version.value++ }
    }

    /** Los colores principales de una portada (hasta 5, distintos entre sí). */
    fun palette(f: File): List<Int> {
        if (!f.exists()) return emptyList()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var s = 1
        while (opts.outWidth / (s * 2) >= 48 && opts.outHeight / (s * 2) >= 48) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return emptyList()
        val counts = HashMap<Int, IntArray>() // cubeta → [cuántos, r, g, b]
        for (y in 0 until bmp.height step 1) for (x in 0 until bmp.width step 1) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            val a = counts.getOrPut(key) { IntArray(4) }
            a[0]++; a[1] += r; a[2] += g; a[3] += b
        }
        val total = bmp.width * bmp.height
        val picked = mutableListOf<Int>()
        for (a in counts.values.sortedByDescending { it[0] }) {
            if (a[0] < total * 0.02) break
            val r = a[1] / a[0]; val g = a[2] / a[0]; val b = a[3] / a[0]
            val far = picked.all { p ->
                val dr = ((p shr 16) and 255) - r; val dg = ((p shr 8) and 255) - g; val db = (p and 255) - b
                dr * dr + dg * dg + db * db > 55 * 55
            }
            if (far) picked += (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            if (picked.size == 5) break
        }
        return picked
    }

    private fun dayKey(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** La racha más larga de días seguidos leyendo. */
    private fun longestRun(sessions: List<Session>): Int {
        val days = sessions.map { dayKey(it.at) }.toSortedSet().map { k ->
            Calendar.getInstance().apply { val p = k.split('-'); set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 12, 0, 0) }.timeInMillis / 86_400_000L
        }
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (d in days) { run = if (d == prev + 1) run + 1 else 1; best = maxOf(best, run); prev = d }
        return best
    }

    /**
     * Sellos especiales. Calcula cuáles se ganaron y anota la fecha la primera vez.
     * Devuelve también los recién ganados (para avisar con un golpecito).
     */
    fun specials(books: List<Book>, sessions: List<Session>, cards: List<Card>): Pair<List<Special>, List<Special>> {
        val done = books.filter { it.finished && it.finishedAt > 0 }.sortedBy { it.finishedAt }
        val myLang = Locale.getDefault().language.take(2)
        val firstSession = sessions.groupBy { it.bookId }.mapValues { (_, xs) -> xs.minOf { it.at } }
        val run = longestRun(sessions)
        val mature = cards.count { it.box >= Cards.MATURE }
        fun cond(id: String): Long? = when (id) {
            "bienvenida" -> since
            "primero" -> done.firstOrNull()?.finishedAt
            "idioma" -> done.firstOrNull { it.lang.isNotBlank() && it.lang.take(2) != myLang }?.finishedAt
            "siete" -> if (run >= 7) System.currentTimeMillis() else null
            "mes" -> if (run >= 30) System.currentTimeMillis() else null
            "diez" -> done.getOrNull(9)?.finishedAt
            "cincuenta" -> done.getOrNull(49)?.finishedAt
            "cien" -> if (mature >= 100) System.currentTimeMillis() else null
            "dos" -> done.firstOrNull { it.pair != null }?.finishedAt
            "noche" -> sessions.firstOrNull { val h = Calendar.getInstance().apply { timeInMillis = it.at }.get(Calendar.HOUR_OF_DAY); h in 1..4 }?.at
            "dia" -> done.firstOrNull { b -> firstSession[b.id]?.let { b.finishedAt - it in 1..86_400_000L } == true }?.finishedAt
            "generos" -> if (done.map { it.genre }.filter { g -> KANJI.any { it.first == g && g != "-" } }.distinct().size >= 5) System.currentTimeMillis() else null
            else -> null
        }
        val defs = listOf(
            Triple("bienvenida", "鏡", "Bienvenida") to ("Por ser parte de Zerkalo" to false),
            Triple("primero", "初", "Primer libro") to ("Termina tu primer libro" to false),
            Triple("idioma", "語", "Otro idioma") to ("Termina un libro en otro idioma" to false),
            Triple("siete", "七", "Siete días") to ("Lee 7 días seguidos" to false),
            Triple("mes", "月", "Un mes") to ("Lee 30 días seguidos" to false),
            Triple("diez", "十", "Diez libros") to ("Termina 10 libros" to false),
            Triple("cincuenta", "五", "Cincuenta libros") to ("Termina 50 libros" to false),
            Triple("cien", "百", "Cien tarjetas") to ("Ten 100 tarjetas maduras" to false),
            Triple("dos", "二", "Dos idiomas") to ("Termina un libro leído junto a su edición en otro idioma" to false),
            Triple("noche", "夜", "Madrugada") to ("Leíste entre la 1 y las 4 de la mañana" to true),
            Triple("dia", "速", "En un día") to ("Terminaste un libro el mismo día que lo empezaste" to true),
            Triple("generos", "彩", "Cinco géneros") to ("Terminaste libros de 5 géneros distintos" to true),
        )
        val fresh = mutableListOf<Special>()
        val list = defs.map { (t, h) ->
            val (id, k, name) = t
            val known = earned[id]
            val at = known ?: cond(id)?.also { earned[id] = it }
            val s = Special(id, k, name, h.first, h.second, at)
            if (known == null && at != null && id != "bienvenida") fresh += s
            s
        }
        if (fresh.isNotEmpty() || "bienvenida" !in earned) { earned.putIfAbsent("bienvenida", since); save(); _version.value++ }
        return list to fresh
    }
}
