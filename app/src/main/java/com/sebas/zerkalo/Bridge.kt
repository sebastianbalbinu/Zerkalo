package com.sebas.zerkalo

import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Puente entre la interfaz (JavaScript) y Android.
 * Todas las llamadas largas responden luego con window.__native.resolve(id, ok, texto).
 */
class Bridge(private val act: MainActivity) {
    private val pool = Executors.newFixedThreadPool(4)
    private val ua = "Zerkalo/1.0 (Android; lector EPUB gratuito; https://github.com/sebastianbalbinu/Zerkalo)"

    fun resolve(cb: String, ok: Boolean, payload: String) {
        act.js("window.__native && window.__native.resolve(${JSONObject.quote(cb)}, $ok, ${JSONObject.quote(payload)})")
    }

    private fun event(name: String, data: JSONObject) {
        act.js("window.__native && window.__native.event(${JSONObject.quote(name)}, ${data})")
    }

    /** Abre una conexión siguiendo redirecciones, también entre http y https o entre dominios. */
    private fun open(urlStr: String, timeout: Int): HttpURLConnection {
        var url = URL(urlStr)
        var hops = 0
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept-Language", "es,en;q=0.8")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 8) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                url = URL(url, loc)
                continue
            }
            return c
        }
    }

    /** GET de texto (JSON, XML de catálogos). Responde {status, body}. */
    @JavascriptInterface
    fun httpGet(url: String, cb: String) {
        pool.execute {
            try {
                val c = open(url, 30000)
                val status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                resolve(cb, status in 200..299, JSONObject().put("status", status).put("body", body).toString())
            } catch (e: Exception) {
                resolve(cb, false, JSONObject().put("status", 0).put("body", e.message ?: "sin conexión").toString())
            }
        }
    }

    /** Descarga un archivo a la biblioteca interna (/books/nombre). Avisa el progreso. */
    @JavascriptInterface
    fun download(url: String, name: String, cb: String) {
        pool.execute {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(act.booksDir, safe)
            val tmp = File(act.booksDir, "$safe.part")
            try {
                val c = open(url, 180000)
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var loaded = 0L
                var lastTick = 0L
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            loaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200) {
                                lastTick = now
                                event("progress", JSONObject().put("cb", cb).put("loaded", loaded).put("total", total))
                            }
                        }
                    }
                }
                c.disconnect()
                if (loaded < 200) throw IllegalStateException("archivo vacío")
                dest.delete()
                tmp.renameTo(dest)
                resolve(cb, true, JSONObject().put("file", safe).put("bytes", loaded).toString())
            } catch (e: Exception) {
                tmp.delete()
                resolve(cb, false, e.message ?: "error")
            }
        }
    }

    @JavascriptInterface
    fun deleteFile(name: String) {
        try { File(act.booksDir, File(name).name).delete() } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun pickEpub(cb: String) = act.pickEpub(cb)

    @JavascriptInterface
    fun share(text: String) = act.runOnUiThread {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try { act.startActivity(Intent.createChooser(i, "Compartir")) } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun openUrl(url: String) = act.runOnUiThread {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
    }

    /** Íconos claros u oscuros en las barras del sistema según el tema de la app. */
    @JavascriptInterface
    fun setDark(dark: Boolean) = act.runOnUiThread {
        val c = androidx.core.view.WindowCompat.getInsetsController(act.window, act.web)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
        act.web.setBackgroundColor(android.graphics.Color.parseColor(if (dark) "#2E231D" else "#F7F2E8"))
    }

    /** Mantener la pantalla encendida mientras se lee. */
    @JavascriptInterface
    fun keepAwake(on: Boolean) = act.runOnUiThread {
        if (on) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @JavascriptInterface
    fun systemDark(): Boolean = act.isNight()

    /** La interfaz terminó de dibujarse. */
    @JavascriptInterface
    fun ready() = act.onWebReady()

    /** Errores de la interfaz, para mostrarlos si algo no abre. */
    @JavascriptInterface
    fun log(msg: String) = act.addError(msg)

    @JavascriptInterface
    fun step(msg: String) = act.trace("· $msg")

    @JavascriptInterface
    fun appInfo(): String = JSONObject()
        .put("versionCode", Updates.currentCode(act))
        .put("versionName", try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (e: Exception) { "" })
        .put("android", Build.VERSION.SDK_INT)
        .toString()

    // ---------- Actualizaciones ----------
    @JavascriptInterface
    fun updateState(): String = JSONObject()
        .put("status", Updates.status.value)
        .put("available", Updates.available.value?.version ?: "")
        .put("ready", Updates.ready.value != null)
        .put("auto", Updates.autoEnabled(act))
        .put("canInstall", Updates.canInstall(act))
        .toString()

    @JavascriptInterface
    fun checkUpdate() { act.lifecycleScope.launch { Updates.check(act, force = true) } }

    @JavascriptInterface
    fun installUpdate() {
        act.lifecycleScope.launch {
            if (!Updates.canInstall(act)) { Updates.openInstallPermission(act); return@launch }
            Updates.available.value?.let { if (Updates.ready.value == null) Updates.download(act, it) }
            Updates.install(act)
        }
    }

    @JavascriptInterface
    fun setAutoUpdate(on: Boolean) = Updates.setAuto(act, on)

    // ---------- Recordatorio de lectura ----------
    @JavascriptInterface
    fun setReminder(on: Boolean, hour: Int, minute: Int) = act.runOnUiThread {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(act, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        Reminder.save(act, on, hour, minute)
        Reminder.schedule(act)
    }

    @JavascriptInterface
    fun setReminderBook(title: String) = Reminder.setBook(act, title)
}
