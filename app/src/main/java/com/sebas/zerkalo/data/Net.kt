package com.sebas.zerkalo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Error de red explicado en palabras sencillas. */
class NetError(message: String, val code: Int = 0) : IOException(message)

object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android) Zerkalo/2.0 (lector EPUB gratuito; +https://github.com/sebastianbalbinu/Zerkalo)"

    /** Abre una conexión siguiendo redirecciones (también entre http/https y entre dominios). */
    private fun open(urlStr: String, timeout: Int): HttpURLConnection {
        var url = URL(urlStr)
        var hops = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = minOf(timeout, 12_000)
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "es,en;q=0.8")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 8) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                url = URL(url, loc)
                continue
            }
            return c
        }
    }

    private fun explain(e: Exception): NetError = when (e) {
        is NetError -> e
        is UnknownHostException -> NetError("Sin internet o la biblioteca no responde")
        is SocketTimeoutException -> NetError("La biblioteca tardó demasiado en responder")
        else -> NetError(e.message ?: "Error de conexión")
    }

    private fun httpError(code: Int) = NetError(
        when (code) {
            404 -> "La biblioteca ya no tiene este libro (404)"
            429 -> "La biblioteca pidió esperar un momento (429)"
            in 500..599 -> "La biblioteca está ocupada o caída ($code). Intenta en un rato"
            else -> "La biblioteca respondió con un error ($code)"
        }, code,
    )

    /** GET de texto con un reintento si el servidor está ocupado. */
    suspend fun text(url: String, timeout: Int = 15_000): String = withContext(Dispatchers.IO) {
        var last: NetError? = null
        repeat(2) { attempt ->
            try {
                val c = open(url, timeout)
                val code = c.responseCode
                if (code !in 200..299) {
                    c.disconnect()
                    throw httpError(code)
                }
                return@withContext c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
            } catch (e: Exception) {
                last = explain(e)
                val retry = (last?.code ?: 0) >= 500 || e is SocketTimeoutException
                if (!retry || attempt == 1) throw last!!
                delay(1200)
            }
        }
        throw last ?: NetError("Error de conexión")
    }

    /**
     * Descarga un archivo a [dest]. Prueba las direcciones en orden hasta que una funcione.
     * Revisa que lo descargado sea de verdad un EPUB (un ZIP), y no una página de error.
     */
    suspend fun download(urls: List<String>, dest: File, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var lastError: NetError = NetError("No hay enlace de descarga")
        for (url in urls) {
            for (attempt in 0..1) {
                try {
                    val c = open(url, 60_000)
                    val code = c.responseCode
                    if (code !in 200..299) { c.disconnect(); throw httpError(code) }
                    val total = c.contentLengthLong
                    var loaded = 0L
                    var tick = 0L
                    c.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                loaded += n
                                val now = System.currentTimeMillis()
                                if (now - tick > 120) {
                                    tick = now
                                    onProgress(if (total > 0) loaded.toFloat() / total else null)
                                }
                            }
                        }
                    }
                    c.disconnect()
                    if (!isZip(tmp)) throw NetError("La biblioteca mandó una página en vez del libro")
                    dest.delete()
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
                    onProgress(1f)
                    return@withContext dest
                } catch (e: Exception) {
                    tmp.delete()
                    lastError = explain(e)
                    android.util.Log.w("Zerkalo", "descarga falló $url: ${lastError.message}")
                    if (lastError.code in 500..599 && attempt == 0) { delay(1500); continue }
                    break
                }
            }
        }
        throw lastError
    }

    fun isZip(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
    } catch (e: Exception) { false }
}
