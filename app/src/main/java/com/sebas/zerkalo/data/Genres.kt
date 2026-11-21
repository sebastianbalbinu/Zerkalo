package com.sebas.zerkalo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Géneros para los estantes de la biblioteca. Se sacan de los temas que trae el propio EPUB
 * (dc:subject, en inglés o español) y, si no dice nada, de palabras del título ("Poemas", "Cuentos"…).
 * Usa los mismos géneros del Tansu (Catalogs.TOPICS).
 */
object Genres {
    // Primero los géneros más específicos; "fiction" (novela) va después de ellos y antes de los temas de no ficción.
    private val KEYS: List<Pair<String, List<String>>> = listOf(
        "poetry" to listOf("poetry", "poesia", "poems", "poemas", "verse", "versos", "rimas", "sonnets", "sonetos", "poesie", "poesia"),
        "short stories" to listOf("short stories", "cuentos", "relatos", "tales", "racconti", "contes", "nouvelles"),
        "drama" to listOf("drama", "teatro", "plays", "tragedy", "tragedies", "tragedia", "comedia", "comedies", "theatre", "theater"),
        "children" to listOf("juvenile", "children", "infantil", "juvenil", "fairy tales", "cuentos de hadas"),
        "science fiction" to listOf("science fiction", "ciencia ficcion", "fantasy", "fantasia", "fantastic fiction"),
        "mystery" to listOf("detective", "mystery", "misterio", "policial", "policiaca", "crime"),
        "horror" to listOf("horror", "terror", "gothic", "gotica", "gotico", "ghost stories", "vampires"),
        "adventure" to listOf("adventure", "aventura", "aventuras", "sea stories"),
        "romance" to listOf("love stories", "romance", "romantic fiction", "novela romantica"),
        "humor" to listOf("humor", "humour", "satire", "satira", "humorous"),
        "myths" to listOf("mythology", "mitologia", "legends", "leyendas", "fables", "fabulas", "folklore", "mitos"),
        "classics" to listOf("classical literature", "greek literature", "latin literature", "literatura griega", "literatura latina", "epic poetry"),
        "fiction" to listOf("fiction", "novela", "novel", "novels", "ficcion", "roman", "romanzo"),
        "travel" to listOf("travel", "viajes", "voyages", "description and travel"),
        "biography" to listOf("biography", "biografia", "autobiography", "autobiografia", "diaries", "diarios", "correspondence", "cartas", "memoirs", "memorias"),
        "essays" to listOf("essays", "ensayo", "ensayos"),
        "philosophy" to listOf("philosophy", "filosofia", "ethics", "etica"),
        "psychology" to listOf("psychology", "psicologia"),
        "religion" to listOf("religion", "bible", "biblia", "christian", "theology", "teologia", "spiritual", "espiritualidad"),
        "war" to listOf("war", "guerra", "military"),
        "history" to listOf("history", "historia"),
        "politics" to listOf("political", "politics", "politica", "sociology", "sociologia", "economics", "economia"),
        "science" to listOf("science", "ciencia", "mathematics", "matematicas", "physics", "fisica", "biology", "biologia", "astronomy"),
        "nature" to listOf("nature", "naturaleza", "animals", "animales", "botany"),
        "art" to listOf("art", "arte", "music", "musica", "painting", "pintura"),
    )

    private fun norm(s: String) = " " + java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    /** El género a partir de los temas del EPUB y, si no, del título. "" si no se sabe. */
    fun detect(subjects: List<String>, title: String = ""): String {
        val all = norm(subjects.joinToString(" | "))
        fun hit(text: String) = KEYS.firstOrNull { (_, words) -> words.any { text.contains(" $it ") } }?.first
        return (if (all.isNotBlank()) hit(all) else null) ?: hit(norm(title)).orEmpty()
    }

    /** Nombre en español para mostrar en el estante. */
    fun name(id: String): String = Catalogs.TOPICS.firstOrNull { it.id == id }?.name ?: "Otros"

    /** Revisa una vez los libros que ya estaban antes de esta versión (en segundo plano, sin apuro). */
    suspend fun backfill(core: ReadiumCore) = withContext(Dispatchers.IO) {
        delay(4_000)
        for (b in Library.books.value.filter { it.genre.isEmpty() }) {
            val g = runCatching {
                val pub = core.open(File(Library.booksDir, b.file)).getOrNull()
                try { pub?.let { detect(it.metadata.subjects.map { s -> s.name }, b.title) }.orEmpty() } finally { pub?.close() }
            }.getOrDefault("")
            Library.update(b.id) { it.copy(genre = g.ifBlank { "-" }) }
            delay(150)
        }
    }
}
