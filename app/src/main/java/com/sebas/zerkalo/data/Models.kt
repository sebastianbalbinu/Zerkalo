package com.sebas.zerkalo.data

import org.json.JSONArray
import org.json.JSONObject

/** Encuadernación japonesa: índices en las listas de Washi, Patrón, Hilo y Etiqueta. */
data class Binding(val washi: Int, val pattern: Int, val thread: Int, val label: Int) {
    fun toJson(): JSONObject = JSONObject().put("w", washi).put("p", pattern).put("t", thread).put("l", label)

    companion object {
        fun fromJson(o: JSONObject?): Binding? = o?.let {
            Binding(it.optInt("w"), it.optInt("p"), it.optInt("t"), it.optInt("l"))
        }

        /** Una encuadernación estable para cada libro, sacada de su identificador. */
        fun forSeed(seed: String): Binding {
            var h = 2166136261L.toInt()
            for (c in seed) { h = h xor c.code; h *= 16777619 }
            val u = h.toLong() and 0xffffffffL
            return Binding((u % 7).toInt(), ((u ushr 3) % 3).toInt(), ((u ushr 5) % 4).toInt(), 0)
        }
    }
}

data class Book(
    val id: String,
    val key: String,
    val title: String,
    val author: String,
    val lang: String = "",
    val source: String = "",
    val summary: String = "",
    val file: String,
    val cover: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpened: Long = 0,
    val progress: Double = 0.0,
    val locator: String? = null,
    val finishedAt: Long = 0,
    val binding: Binding = Binding.forSeed(id),
    val useBinding: Boolean = false,
    val pair: String? = null,   // la misma obra en otro idioma (para leer lado a lado)
    val tags: List<String> = emptyList(), // etiquetas propias
    val favorite: Boolean = false,
    val genre: String = "",     // id de Catalogs.TOPICS; "" = sin revisar todavía, "-" = no se supo
) {
    val reading get() = progress > 0.0 && finishedAt == 0L
    val finished get() = finishedAt > 0L
    /** Los PDF se abren con su propio lector (zoom, búsqueda y apunte), no con el de EPUB. */
    val isPdf get() = file.endsWith(".pdf", ignoreCase = true)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("key", key).put("title", title).put("author", author).put("lang", lang)
        .put("source", source).put("summary", summary).put("file", file).put("cover", cover ?: JSONObject.NULL)
        .put("addedAt", addedAt).put("lastOpened", lastOpened).put("progress", progress)
        .put("locator", locator ?: JSONObject.NULL).put("finishedAt", finishedAt)
        .put("binding", binding.toJson()).put("useBinding", useBinding).put("pair", pair ?: JSONObject.NULL)
        .put("tags", JSONArray(tags)).put("fav", favorite).put("genre", genre)

    companion object {
        fun fromJson(o: JSONObject): Book {
            val id = o.getString("id")
            return Book(
                id = id, key = o.optString("key", id), title = o.optString("title"), author = o.optString("author"),
                lang = o.optString("lang"), source = o.optString("source"), summary = o.optString("summary"),
                file = o.getString("file"), cover = o.optStringOrNull("cover"), addedAt = o.optLong("addedAt"),
                lastOpened = o.optLong("lastOpened"), progress = o.optDouble("progress", 0.0),
                locator = o.optStringOrNull("locator"), finishedAt = o.optLong("finishedAt"),
                binding = Binding.fromJson(o.optJSONObject("binding")) ?: Binding.forSeed(id),
                useBinding = o.optBoolean("useBinding"), pair = o.optStringOrNull("pair"),
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                favorite = o.optBoolean("fav"), genre = o.optString("genre"),
            )
        }
    }
}

/** Subrayado ("hl"), shiori ("shiori") o palabra guardada ("word": text = palabra, note = traducción, context = la frase). */
data class Mark(
    val id: String,
    val bookId: String,
    val type: String,
    val locator: String,
    val text: String = "",
    val note: String = "",
    val color: Int = 0,
    val chapter: String = "",
    val progress: Double = 0.0,
    val at: Long = System.currentTimeMillis(),
    val context: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("bookId", bookId).put("type", type)
        .put("locator", locator).put("text", text).put("note", note).put("color", color)
        .put("chapter", chapter).put("progress", progress).put("at", at).put("context", context)

    companion object {
        fun fromJson(o: JSONObject) = Mark(
            o.getString("id"), o.getString("bookId"), o.optString("type"), o.optString("locator"),
            o.optString("text"), o.optString("note"), o.optInt("color"), o.optString("chapter"),
            o.optDouble("progress", 0.0), o.optLong("at"), o.optString("context"),
        )
    }
}

/** Minutos de lectura de un libro en un día. */
data class Session(val bookId: String, val date: String, val ms: Long, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("bookId", bookId).put("date", date).put("ms", ms).put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = Session(o.optString("bookId"), o.optString("date"), o.optLong("ms"), o.optLong("at"))
    }
}

/** Un libro de un catálogo en línea (todavía no descargado). */
data class CatalogItem(
    val key: String,
    val source: String,
    val title: String,
    val author: String,
    val lang: String = "",
    val cover: String = "",
    val epubUrls: List<String>,
    val summary: String = "",
    val navUrl: String? = null,
) {
    val isNav get() = navUrl != null

    fun toJson(): JSONObject = JSONObject().put("key", key).put("source", source).put("title", title)
        .put("author", author).put("lang", lang).put("cover", cover).put("summary", summary)
        .put("epub", JSONArray(epubUrls)).put("nav", navUrl ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): CatalogItem {
            val arr = o.optJSONArray("epub") ?: JSONArray()
            return CatalogItem(
                o.optString("key"), o.optString("source"), o.optString("title"), o.optString("author"),
                o.optString("lang"), o.optString("cover"), (0 until arr.length()).map { arr.getString(it) },
                o.optString("summary"), o.optStringOrNull("nav"),
            )
        }
    }
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotEmpty() }
