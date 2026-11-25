package com.sebas.zerkalo.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * El apunte de cada libro: un texto simple que se va escribiendo mientras se lee.
 * Las frases que llegan desde el libro empiezan con "› " y terminan con la página o el capítulo.
 * Vive en files/apuntes/<id del libro>.txt y se guarda solo, un momento después de dejar de escribir.
 */
object Apuntes {
    const val QUOTE = "› "
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = HashMap<String, MutableStateFlow<String>>()
    private val saves = HashMap<String, Job>()

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "apuntes").apply { mkdirs() }
    }

    private fun file(bookId: String) = File(dir, "$bookId.txt")

    /** El texto del apunte de un libro (se lee del disco la primera vez). */
    fun flow(bookId: String): MutableStateFlow<String> = synchronized(flows) {
        flows.getOrPut(bookId) {
            MutableStateFlow(runCatching { file(bookId).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
        }
    }

    fun set(bookId: String, text: String) {
        flow(bookId).value = text
        saves[bookId]?.cancel()
        saves[bookId] = scope.launch {
            delay(500)
            val f = file(bookId)
            if (text.isBlank()) f.delete() else {
                val tmp = File(dir, "$bookId.tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }
        }
    }

    /** Pega una frase del libro al final del apunte, con dónde estaba, y deja una línea para escribir debajo. */
    fun addQuote(bookId: String, quote: String, where: String) {
        val q = quote.replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return
        val now = flow(bookId).value.trimEnd()
        val line = QUOTE + q + if (where.isNotBlank()) " ($where)" else ""
        set(bookId, (if (now.isEmpty()) "" else "$now\n\n") + line + "\n")
    }

    /** Cuántas frases tiene el apunte (para el numerito de la bolita). */
    fun quotes(text: String): Int = text.lines().count { it.startsWith(QUOTE) }

    fun remove(bookId: String) {
        flows.remove(bookId)
        runCatching { file(bookId).delete() }
    }
}
