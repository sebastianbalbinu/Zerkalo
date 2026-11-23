package com.sebas.zerkalo.data

import java.text.Normalizer

/**
 * Comparar títulos y buscar "con margen de error", como cualquier buscador:
 * sin tildes ni mayúsculas, números escritos igual en cifras o en letras ("veinte mil" = "20000" = "20.000"),
 * romanos (IV = 4), sin artículos, y tolerando letras de más o de menos.
 */
object Fuzzy {
    private val STOP = setOf(
        "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del", "al", "y", "e", "o", "en", "por", "con",
        "the", "a", "an", "of", "and", "in", "on", "to",
        "le", "les", "des", "du", "d", "l", "et", "au", "aux",
        "il", "gli", "della", "di", "da", "os", "as", "do", "da", "dos", "das", "der", "die", "das", "und", "von",
    )

    private val NUM = mapOf(
        // español
        "cero" to 0, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18,
        "diecinueve" to 19, "veinte" to 20, "veintiuno" to 21, "veintiun" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25,
        "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50,
        "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90, "cien" to 100, "ciento" to 100, "doscientos" to 200, "doscientas" to 200,
        "trescientos" to 300, "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600, "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900,
        "mil" to 1000,
        // inglés
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "hundred" to 100, "thousand" to 1000,
        // francés y portugués (lo más común en títulos)
        "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "vingt" to 20, "trente" to 30,
        "quarante" to 40, "cent" to 100, "mille" to 1000, "quatro" to 4, "vinte" to 20, "trinta" to 30, "cem" to 100,
    )
    private val ROMAN = Regex("^(?=[ivxlc]{2,}$)c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")

    private fun roman(s: String): Int {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (k in s.indices) {
            val a = v[s[k]] ?: return -1
            val b = if (k + 1 < s.length) v[s[k + 1]] ?: 0 else 0
            total += if (a < b) -a else a
        }
        return total
    }

    /** Palabras limpias de un texto, con los números ya unificados. */
    fun tokens(s: String): List<String> {
        val base = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("(?<=\\d)[.,'’ ](?=\\d{3}\\b)"), "") // 20.000 / 20,000 / 20 000 → 20000
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (base.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var total = -1
        var cur = 0
        fun flush() { if (total >= 0) { out += (total + cur).toString(); total = -1; cur = 0 } }
        val words = base.split(' ')
        for ((k, w) in words.withIndex()) {
            val n = NUM[w]
            when {
                n != null -> {
                    if (total < 0) { total = 0; cur = 0 }
                    when {
                        n == 1000 -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                        n == 100 && cur in 1..9 -> cur *= 100
                        else -> cur += n
                    }
                }
                // "treinta y cinco": la "y" entre dos números es parte del número
                total >= 0 && (w == "y" || w == "and" || w == "e" || w == "et") && words.getOrNull(k + 1)?.let { it in NUM } == true -> {}
                else -> {
                    flush()
                    out += if (w.length >= 2 && ROMAN.matches(w)) roman(w).toString() else w
                }
            }
        }
        flush()
        return out.filter { it !in STOP }
    }

    private fun lev(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var best = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (cur[j] < best) best = cur[j]
            }
            if (best > max) return max + 1
            prev = cur
        }
        return prev[b.length]
    }

    /** ¿Son "la misma palabra" con margen de error? Números: solo si son iguales. */
    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a[0].isDigit() || b[0].isDigit()) return false
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        val m = minOf(a.length, b.length)
        val max = when { m >= 7 -> 2; m >= 4 -> 1; else -> 0 }
        return max > 0 && lev(a, b, max) <= max
    }

    /** Qué parte de lo buscado (0..1) aparece en el texto, palabra por palabra con margen de error. */
    fun score(query: List<String>, text: List<String>): Double {
        if (query.isEmpty()) return 1.0
        if (text.isEmpty()) return 0.0
        val hits = query.count { q -> text.any { same(q, it) } }
        return hits.toDouble() / query.size
    }

    fun score(query: String, text: String) = score(tokens(query), tokens(text))

    /** Parecido entre dos títulos (0..1): cuenta en las dos direcciones, para que no gane un título cortito. */
    fun titles(a: String, b: String): Double {
        val ta = tokens(a.substringBefore(':').substringBefore(" o ").substringBefore(" or "))
        val tb = tokens(b.substringBefore(':').substringBefore(" o ").substringBefore(" or "))
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return minOf(score(ta, tb), score(tb, ta))
    }

    /** El apellido del autor (la palabra más larga), sin tildes. */
    fun surname(author: String): String = tokens(author).filter { !it[0].isDigit() }.maxByOrNull { it.length }.orEmpty()

    fun sameAuthor(a: String, b: String): Boolean {
        val s = surname(a)
        if (s.isEmpty() || b.isBlank()) return true // sin autor no se puede descartar
        return tokens(b).any { same(s, it) }
    }

    /** ¿El libro y el audiolibro son la misma obra? */
    fun sameWork(titleA: String, authorA: String, titleB: String, authorB: String): Boolean {
        val t = titles(titleA, titleB)
        return (t >= 0.75 && sameAuthor(authorA, authorB)) || t >= 0.95
    }
}
