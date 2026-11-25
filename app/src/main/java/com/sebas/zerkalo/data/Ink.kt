package com.sebas.zerkalo.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Un trazo de la pluma. Los puntos van de 0 a 1 dentro de la página (x por el ancho, y por el alto),
 * así el trazo queda en su sitio aunque se haga zoom o se mueva la página. El grosor también es
 * una fracción del ancho de la página.
 */
class Stroke(val color: Int, val width: Float, val marker: Boolean, val pts: FloatArray)

/**
 * Los trazos de cada libro, guardados en files/tinta/<id del libro>.json.
 * Se agrupan por página: "p12" en los PDF; en los EPUB, la pantalla exacta (capítulo, punto y tamaño de letra).
 * El archivo del libro nunca se toca.
 */
object InkStore {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = HashMap<String, Job>()

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "tinta").apply { mkdirs() }
    }

    private fun file(bookId: String) = File(dir, "$bookId.json")

    fun load(bookId: String): HashMap<String, MutableList<Stroke>> {
        val out = HashMap<String, MutableList<Stroke>>()
        if (!::dir.isInitialized) return out
        runCatching {
            val o = JSONObject(file(bookId).readText())
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    val p = s.getJSONArray("p")
                    Stroke(s.getInt("c"), s.getDouble("w").toFloat(), s.optBoolean("m"), FloatArray(p.length()) { p.getDouble(it).toFloat() })
                }.toMutableList()
            }
        }
        return out
    }

    /** Guarda un momento después del último trazo (agrupa los trazos seguidos). */
    fun save(bookId: String, all: Map<String, List<Stroke>>) {
        if (!::dir.isInitialized) return
        val o = JSONObject()
        for ((k, list) in all) {
            if (list.isEmpty()) continue
            val a = JSONArray()
            list.forEach { s ->
                val p = JSONArray()
                s.pts.forEach { p.put(Math.round(it * 10000) / 10000.0) }
                a.put(JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("m", s.marker).put("p", p))
            }
            o.put(k, a)
        }
        val text = o.toString()
        saves[bookId]?.cancel()
        saves[bookId] = scope.launch {
            delay(600)
            val f = file(bookId)
            if (o.length() == 0) f.delete() else {
                val tmp = File(dir, "$bookId.tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }
        }
    }

    fun remove(bookId: String) { runCatching { file(bookId).delete() } }
}
