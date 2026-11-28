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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Una tarjeta de estudio: pregunta por delante, respuesta por detrás.
 * Solo existe si la persona tocó "Hacer tarjeta": nada se vuelve tarjeta solo.
 */
data class Card(
    val id: String,
    val bookId: String,
    val markId: String? = null, // el subrayado o la palabra de donde salió
    val kind: String = "hl",    // "hl" subrayado, "word" palabra, "cloze" completar
    val front: String,
    val back: String,
    val context: String = "",   // la frase del libro (para palabras)
    val box: Int = 0,           // caja de Leitner: 0 nueva … 5 madura
    val due: Long = 0,          // desde cuándo toca repasarla
    val created: Long = System.currentTimeMillis(),
    val reviews: Int = 0,
    val lapses: Int = 0,
    val lastMs: Long = 0,       // cuánto tardó en responder la última vez
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("book", bookId).put("mark", markId ?: JSONObject.NULL)
        .put("kind", kind).put("front", front).put("back", back).put("ctx", context).put("box", box).put("due", due)
        .put("created", created).put("reviews", reviews).put("lapses", lapses).put("ms", lastMs)

    companion object {
        fun fromJson(o: JSONObject) = Card(
            o.getString("id"), o.optString("book"), o.optString("mark").takeIf { it.isNotBlank() && it != "null" },
            o.optString("kind", "hl"), o.optString("front"), o.optString("back"), o.optString("ctx"), o.optInt("box"),
            o.optLong("due"), o.optLong("created"), o.optInt("reviews"), o.optInt("lapses"), o.optLong("ms"),
        )
    }
}

/**
 * Tarjetas con el sistema de cajas de Leitner: si te acordaste, la tarjeta vuelve cada vez más lejos
 * (1, 3, 7, 16, 35 y 90 días); si se te olvidó, vuelve mañana. Así se repasa justo antes de olvidar.
 */
object Cards {
    /** Días hasta el próximo repaso según la caja. */
    val DAYS = listOf(1, 3, 7, 16, 35, 90)
    const val MATURE = 4 // desde esta caja la tarjeta está "madura" (ya se recuerda sin esfuerzo)

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    private val _days = MutableStateFlow<Set<String>>(emptySet()) // días con repaso (aaaa-mm-dd)
    val cards: StateFlow<List<Card>> = _cards.asStateFlow()
    val days: StateFlow<Set<String>> = _days.asStateFlow()

    private val file get() = File(dir, "tarjetas.json")

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.filesDir
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val a = o.optJSONArray("cards") ?: JSONArray()
                _cards.value = (0 until a.length()).mapNotNull { runCatching { Card.fromJson(a.getJSONObject(it)) }.getOrNull() }
                val d = o.optJSONArray("days") ?: JSONArray()
                _days.value = (0 until d.length()).map { d.optString(it) }.toSet()
            }
        }.onFailure { android.util.Log.e("Zerkalo", "No se pudieron leer las tarjetas", it) }
    }

    /** Suma las tarjetas de una copia (las que ya tienes no se repiten). */
    fun merge(f: File) {
        runCatching {
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("cards") ?: JSONArray()
            val have = _cards.value.map { it.id }.toHashSet()
            val more = (0 until a.length()).mapNotNull { runCatching { Card.fromJson(a.getJSONObject(it)) }.getOrNull() }.filter { it.id !in have }
            val d = o.optJSONArray("days") ?: JSONArray()
            _cards.value = _cards.value + more
            _days.value = _days.value + (0 until d.length()).map { d.optString(it) }
            save()
        }
    }

    private fun save() {
        if (!::dir.isInitialized) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val o = JSONObject().put("version", 1)
                .put("cards", JSONArray(_cards.value.map { it.toJson() }))
                .put("days", JSONArray(_days.value.sorted().takeLast(800)))
            val tmp = File(dir, "tarjetas.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    fun dayKey(t: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }.timeInMillis

    /** Las que tocan hoy (o se atrasaron). */
    fun dueToday(list: List<Card> = _cards.value): List<Card> {
        val end = startOfDay(1)
        return list.filter { it.due < end }
    }

    fun add(card: Card) {
        _cards.value = _cards.value.filterNot { it.id == card.id } + card.copy(due = if (card.due == 0L) System.currentTimeMillis() else card.due)
        save()
    }

    fun remove(id: String) {
        _cards.value = _cards.value.filterNot { it.id == id }
        save()
    }

    /** Ya hay una tarjeta hecha de este subrayado o palabra. */
    fun hasFor(markId: String) = _cards.value.any { it.markId == markId }

    /** Cuántos días faltan si se acuerda (para el botón "vuelve en N días"). */
    fun nextDays(c: Card, remembered: Boolean) = if (remembered) DAYS[(c.box + 1).coerceAtMost(DAYS.lastIndex)] else 1

    /** Respuesta en el repaso. En práctica libre no se cambian las fechas. Devuelve la tarjeta nueva. */
    fun review(id: String, remembered: Boolean, ms: Long, practice: Boolean = false): Card? {
        val c = _cards.value.firstOrNull { it.id == id } ?: return null
        val n = if (practice) c.copy(lastMs = ms) else {
            val box = if (remembered) (c.box + 1).coerceAtMost(DAYS.lastIndex) else 0
            c.copy(
                box = box, due = startOfDay(DAYS[box].takeIf { remembered } ?: 1), reviews = c.reviews + 1,
                lapses = c.lapses + if (remembered) 0 else 1, lastMs = ms,
            )
        }
        _cards.value = _cards.value.map { if (it.id == id) n else it }
        _days.value = _days.value + dayKey()
        save()
        return n
    }

    private fun keyAgo(i: Int) = dayKey(System.currentTimeMillis() - i * 86_400_000L)

    /**
     * Racha de días con repaso, y los días de descanso usados en ella. Un día suelto sin repasar no
     * la rompe si en la semana anterior no hubo otro descanso; dos días seguidos sin repasar, sí.
     */
    private fun walk(days: Set<String>): Pair<Int, Set<String>> {
        var i = if (dayKey() in days) 0 else 1 // si hoy aún no repasaste, la racha sigue viva desde ayer
        var n = 0
        var lastRest = -100
        val rests = HashSet<String>()
        while (i < 800) {
            val k = keyAgo(i)
            if (k in days) { n++; i++; continue }
            if (n > 0 && i - lastRest > 7 && keyAgo(i + 1) in days) { lastRest = i; rests += k; i++; continue }
            break
        }
        return n to rests
    }

    fun streak(days: Set<String> = _days.value): Int = walk(days).first

    fun restDays(days: Set<String> = _days.value): Set<String> = walk(days).second
}

/**
 * Preguntas sugeridas para una tarjeta, sin inteligencia artificial: reglas simples sobre la frase.
 * "La fotosíntesis es…" → "¿Qué es la fotosíntesis?"; "Teorema del valor medio…" → "¿Qué dice el teorema…?";
 * y siempre "completar el espacio" (se oculta la palabra más importante) y "¿cómo sigue esta frase?".
 */
object Suggest {
    data class Option(val label: String, val front: String, val back: String, val kind: String = "hl")

    private val STOP = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "y", "o", "u", "que", "en", "por", "para", "con",
        "sin", "se", "su", "sus", "es", "son", "fue", "era", "como", "más", "pero", "porque", "cuando", "donde", "este", "esta",
        "esto", "ese", "esa", "eso", "lo", "le", "les", "ya", "muy", "también", "entre", "sobre", "hasta", "desde", "the", "and",
        "of", "to", "in", "is", "are", "was", "that", "this", "with", "for", "it", "as", "on", "be", "by",
    )

    fun options(text: String): List<Option> {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length < 3) return emptyList()
        val out = mutableListOf<Option>()
        // Definición: "X es / son / fue / se llama / consiste en …"
        Regex("^(.{2,70}?)\\s+(es|son|fue|era|eran|significa|consiste en|se define como|se llama|se denomina)\\s+(.{3,})$", RegexOption.IGNORE_CASE)
            .find(t)?.let { m ->
                val subj = m.groupValues[1].trim().trimEnd(',', ':')
                val verb = m.groupValues[2].lowercase()
                val q = when (verb) {
                    "son", "eran" -> "¿Qué son $subj?"
                    "fue", "era" -> "¿Qué fue $subj?"
                    "significa" -> "¿Qué significa $subj?"
                    "consiste en" -> "¿En qué consiste $subj?"
                    else -> "¿Qué es $subj?"
                }
                out += Option("Definición", q.replaceFirstChar { it.uppercase() }, t)
            }
        // Teoremas, leyes y principios
        Regex("\\b(teorema|ley|principio|lema|regla|axioma|postulado|propiedad|efecto|paradoja)\\s+(de la|del|de los|de las|de)?\\s*([^.,;:]{2,50})", RegexOption.IGNORE_CASE)
            .find(t)?.let { m ->
                val name = (m.groupValues[1] + " " + m.groupValues[2] + " " + m.groupValues[3]).replace(Regex("\\s+"), " ").trim()
                out += Option("Enunciado", "¿Qué dice ${articleFor(m.groupValues[1])} ${name.lowercase()}?", t)
            }
        // Completar el espacio: la palabra más importante (número, nombre propio o la más larga)
        cloze(t)?.let { (q, word) -> out += Option("Completar", q, word, "cloze") }
        // ¿Cómo sigue?
        val words = t.split(' ')
        if (words.size >= 8) out += Option("¿Cómo sigue?", "¿Cómo sigue? «" + words.take(words.size / 3).joinToString(" ") + "…»", t)
        else out += Option("¿Qué recuerdas?", "¿Qué recuerdas de esto? (${words.take(3).joinToString(" ")}…)", t)
        return out.distinctBy { it.front }.take(4)
    }

    private fun articleFor(w: String) = when (w.lowercase()) { "ley", "regla", "propiedad", "paradoja" -> "la"; else -> "el" }

    private fun cloze(t: String): Pair<String, String>? {
        val tokens = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*").findAll(t).toList()
        if (tokens.size < 4) return null
        val scored = tokens.mapIndexed { i, m ->
            val w = m.value
            var s = w.length.toDouble()
            if (w.lowercase() in STOP) s -= 100
            if (w.any { it.isDigit() }) s += 8
            if (i > 0 && w.first().isUpperCase()) s += 6
            m to s
        }
        val best = scored.maxByOrNull { it.second }?.takeIf { it.second > 3 }?.first ?: return null
        val q = t.substring(0, best.range.first) + "_____" + t.substring(best.range.last + 1)
        return q to best.value
    }
}
