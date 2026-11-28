package com.sebas.zerkalo.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Si la app se cierra por un error, queda anotado en un archivo pequeño (solo en tu teléfono).
 * Desde Ajustes se puede mandar, para saber qué arreglar.
 */
object Crashes {
    private lateinit var file: File

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "fallos.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
                val head = "— " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) +
                    " · Zerkalo $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · hilo ${t.name}\n"
                val old = if (file.exists()) file.readText().takeLast(40_000) else ""
                file.writeText(old + head + sw.toString().take(12_000) + "\n")
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun has(): Boolean = ::file.isInitialized && file.isFile && file.length() > 0
    fun count(): Int = if (has()) file.readText().lines().count { it.startsWith("— ") } else 0
    fun log(): File = file
    fun clear() { if (::file.isInitialized) file.delete() }
}
