package com.sebas.zerkalo.data

import android.content.Context
import com.sebas.zerkalo.data.Catalogs.IndexBook
import com.sebas.zerkalo.data.Catalogs.toItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.exp
import kotlin.random.Random

/**
 * Arma el Tansu para explorar sin buscar: estantes que cambian cada día, sin repetir libros entre
 * estantes y sin mostrar los que ya tienes. Todo sale del catálogo propio de Gutenberg (en el teléfono).
 */
object Explore {
    /** Qué libros entran en un estante: un género, un momento, una época, un autor o "los más leídos". */
    data class Query(val kind: String, val value: String, val title: String)

    data class Shelf(val key: String, val title: String, val kicker: String, val items: List<CatalogItem>, val more: Query?)

    data class Tile(val query: Query, val count: Int, val subtitle: String = "")

    data class Plan(
        val total: Int,
        val forYou: List<Shelf>,
        val top: Shelf?,
        val moods: List<Tile>,
        val genreShelves: List<Shelf>,
        val genres: List<Tile>,
        val eras: List<Tile>,
    )

    /** Según el momento: cada uno junta géneros (y, a veces, el largo del libro). */
    val MOODS = listOf(
        Query("mood", "tarde", "Para una tarde"), Query("mood", "reir", "Para reír"), Query("mood", "pensar", "Para pensar"),
        Query("mood", "temblar", "Para no dormir"), Query("mood", "viajar", "Para viajar sin moverte"),
        Query("mood", "amar", "Para enamorarse"), Query("mood", "sonar", "Para soñar"), Query("mood", "saber", "Para aprender algo"),
    )

    private val MOOD_NOTE = mapOf(
        "tarde" to "Libros cortos", "reir" to "Humor y sátira", "pensar" to "Filosofía y ensayo", "temblar" to "Terror y misterio",
        "viajar" to "Viajes y aventura", "amar" to "Romance y poesía", "sonar" to "Mitos y fantasía", "saber" to "Ciencia e historia",
    )

    /** Por época (según el año en que nació el autor). */
    val ERAS = listOf(
        Triple("antigua", "Antigüedad", Int.MIN_VALUE..500), Triple("media", "Edad Media", 501..1450),
        Triple("oro", "Siglos XVI y XVII", 1451..1660), Triple("ilustracion", "Siglo XVIII", 1661..1760),
        Triple("romantico", "Romanticismo", 1761..1820), Triple("xix", "Realismo, siglo XIX", 1821..1870),
        Triple("moderno", "Siglo XX", 1871..2100),
    )

    private fun bit(id: String) = Catalogs.TOPICS.indexOfFirst { it.id == id }.let { if (it < 0) 0 else 1 shl it }
    private fun has(b: IndexBook, vararg ids: String) = ids.any { (b.mask and bit(it)) != 0 }

    fun matcher(q: Query): (IndexBook) -> Boolean = when (q.kind) {
        "topic" -> { b -> (b.mask and bit(q.value)) != 0 }
        "era" -> { b -> val r = ERAS.firstOrNull { it.first == q.value }?.third; r != null && b.birth != 0 && b.birth in r }
        "author" -> Catalogs.norm(q.value).let { n -> { b: IndexBook -> Catalogs.norm(b.author).contains(n) } }
        "mood" -> when (q.value) {
            // Cortos: menos de ~180 KB de EPUB (una o dos horas), o cuentos/poesía si no se sabe el tamaño
            "tarde" -> { b -> if (b.kb > 0) b.kb in 8..180 && has(b, "fiction", "short stories", "poetry", "drama", "humor", "myths") else has(b, "short stories") }
            "reir" -> { b -> has(b, "humor") }
            "pensar" -> { b -> has(b, "philosophy", "essays", "psychology") }
            "temblar" -> { b -> has(b, "horror", "mystery") }
            "viajar" -> { b -> has(b, "travel", "adventure") }
            "amar" -> { b -> has(b, "romance") }
            "sonar" -> { b -> has(b, "myths", "science fiction") }
            "saber" -> { b -> has(b, "science", "history", "nature") }
            else -> { _ -> false }
        }
        else -> { _ -> true }
    }

    // ---------------- lo que ya se mostró (para rotar) ----------------
    private lateinit var shownFile: File
    private val shown = HashMap<Int, Int>()
    private var shownDay = ""

    fun init(ctx: Context) {
        shownFile = File(ctx.filesDir, "tansu_visto.json")
        runCatching {
            val o = JSONObject(shownFile.readText())
            shownDay = o.optString("day")
            o.optJSONObject("n")?.let { n -> n.keys().forEach { k -> shown[k.toInt()] = n.optInt(k) } }
        }
    }

    private fun today(): String = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }

    /** Una vez por día se anota qué libros se mostraron; los que salen mucho sin abrirse bajan de puesto. */
    private fun remember(ids: Collection<Int>) {
        if (shownDay == today()) return
        shownDay = today()
        ids.forEach { shown[it] = (shown[it] ?: 0) + 1 }
        if (shown.size > 6000) shown.entries.sortedBy { it.value }.take(shown.size - 6000).forEach { shown.remove(it.key) }
        runCatching {
            val n = JSONObject(); shown.forEach { (k, v) -> n.put(k.toString(), v) }
            shownFile.writeText(JSONObject().put("day", shownDay).put("n", n).toString())
        }
    }

    // ---------------- armado del Tansu ----------------
    private val plans = HashMap<String, Plan>()

    private fun owned(): Pair<Set<Int>, Set<String>> {
        val books = Library.books.value
        val ids = books.mapNotNull { it.key.removePrefix("pg:").toIntOrNull()?.takeIf { _ -> it.key.startsWith("pg:") } }.toSet()
        val titles = books.map { Catalogs.norm(it.title).take(40) }.toSet()
        return ids to titles
    }

    /** El Tansu del día para un idioma. Se recalcula si cambian tus libros. */
    suspend fun plan(lang: String): Plan? = withContext(Dispatchers.Default) {
        val idx = Catalogs.gutenbergIndex(lang) ?: return@withContext null
        val (ownIds, ownTitles) = owned()
        val key = "$lang|${today()}|${ownIds.hashCode()}|${ownTitles.size}"
        plans[key]?.let { return@withContext it }

        val free = idx.filter { it.id !in ownIds && Catalogs.norm(it.title).take(40) !in ownTitles }
        val rank = HashMap<Int, Int>().also { m -> free.forEachIndexed { i, b -> m[b.id] = i } }
        val rnd = Random((today() + lang).hashCode())
        val noise = HashMap<Int, Double>()
        fun score(b: IndexBook): Double {
            // Más leídos primero, con un poco de azar que cambia cada día y castigo a los ya vistos
            val n = noise.getOrPut(b.id) { exp(rnd.nextDouble() * 1.8) }
            return ((rank[b.id] ?: 0) + 8) * n * (1 + 0.6 * (shown[b.id] ?: 0))
        }
        val used = HashSet<Int>()
        fun pick(f: (IndexBook) -> Boolean, n: Int = 16, pool: Int = 350): List<IndexBook> {
            val out = free.asSequence().filter { it.id !in used && f(it) }.take(pool).sortedBy { score(it) }.take(n).toList()
            used += out.map { it.id }
            return out
        }
        fun shelf(k: String, title: String, kicker: String, books: List<IndexBook>, more: Query?) =
            Shelf(k, title, kicker, books.map { it.toItem(lang) }, more)

        // Para ti: según los libros de tu biblioteca
        val forYou = mutableListOf<Shelf>()
        val mine = Library.books.value.sortedByDescending { it.lastOpened }
        val mineIdx = mine.mapNotNull { b -> b.key.removePrefix("pg:").toIntOrNull()?.let { id -> idx.firstOrNull { it.id == id } } }
        mineIdx.firstOrNull { it.mask != 0 }?.let { seed ->
            val title = mine.firstOrNull { it.key == "pg:${seed.id}" }?.title ?: seed.title
            val bits = seed.mask
            val similar = free.asSequence().filter { it.id !in used && it.id != seed.id && (it.mask and bits) != 0 }.take(600)
                .sortedByDescending { Integer.bitCount(it.mask and bits) * 1000 - (rank[it.id] ?: 0) / 3 }.take(16).toList()
            used += similar.map { it.id }
            if (similar.size >= 4) forYou += shelf("fy-sim", "Porque leíste «${title.take(40)}»", "Para ti", similar, null)
        }
        // Autores que lees: "Más de …"
        mine.map { it.author }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(6).forEach { author ->
                if (forYou.size >= 3) return@forEach
                val surname = author.split(' ', ',').filter { it.length > 2 }.lastOrNull() ?: return@forEach
                val q = Query("author", surname, author)
                val books = pick(matcher(q), 14, pool = 60)
                if (books.size >= 2) forYou += shelf("fy-a-$surname", "Más de $author", "Para ti", books, q)
            }

        val top = pick({ true }, 18, pool = 400).takeIf { it.isNotEmpty() }?.let { shelf("top", "Los más leídos", "Project Gutenberg", it, Query("top", "", "Los más leídos")) }

        // Géneros: cuántos libros tiene cada uno en este idioma
        val genres = Catalogs.TOPICS.map { t -> val q = Query("topic", t.id, t.name); Tile(q, free.count(matcher(q))) }
            .filter { it.count >= 6 }.sortedByDescending { it.count }
        val moods = MOODS.map { q -> Tile(q, free.count(matcher(q)), MOOD_NOTE[q.value] ?: "") }.filter { it.count >= 5 }
        val eras = ERAS.map { (id, name, _) -> val q = Query("era", id, name); Tile(q, free.count(matcher(q))) }.filter { it.count >= 5 }

        // Cada día, seis géneros distintos como estantes (rotan entre todos los que tienen libros)
        val rotated = genres.shuffled(Random(today().hashCode())).take(6)
        val genreShelves = rotated.mapNotNull { g ->
            pick(matcher(g.query), 16).takeIf { it.size >= 4 }?.let { shelf("g-${g.query.value}", g.query.title, "", it, g.query) }
        }
        remember(used)
        Plan(idx.size, forYou, top, moods, genreShelves, genres, eras).also { plans.clear(); plans[key] = it }
    }

    /** Todos los libros de un estante (para "Ver todo"), sin los que ya tienes. */
    suspend fun all(lang: String, q: Query): List<CatalogItem> = withContext(Dispatchers.Default) {
        val idx = Catalogs.gutenbergIndex(lang) ?: return@withContext emptyList()
        val (ownIds, _) = owned()
        val f = matcher(q)
        idx.filter { it.id !in ownIds && f(it) }.map { it.toItem(lang) }
    }

    /** Un libro al azar entre los buenos (los 1.500 más leídos que no tienes). */
    suspend fun surprise(lang: String, avoid: Set<String> = emptySet()): CatalogItem? = withContext(Dispatchers.Default) {
        val idx = Catalogs.gutenbergIndex(lang) ?: return@withContext null
        val (ownIds, _) = owned()
        idx.asSequence().filter { it.id !in ownIds && "pg:${it.id}" !in avoid && it.summary.isNotBlank() }.take(1500).toList()
            .ifEmpty { idx.filter { it.id !in ownIds }.take(1500) }
            .randomOrNull()?.toItem(lang)
    }
}
