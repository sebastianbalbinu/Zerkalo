package com.sebas.zerkalo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val code: Long, val url: String, val notes: String)

/**
 * Actualizaciones: revisa GitHub, descarga la APK nueva en segundo plano y la instala sola.
 *
 * - La primera vez Android pide confirmar (y permitir "instalar apps" para Zerkalo).
 * - Desde Android 12, una vez que Zerkalo se instaló a sí misma, las siguientes versiones se instalan
 *   sin preguntar: se hace cuando la app queda en segundo plano y no está sonando música.
 */
object Updates {
    private const val API = "https://api.github.com/repos/sebastianbalbinu/Zerkalo/releases/latest"
    const val PAGE = "https://github.com/sebastianbalbinu/Zerkalo/releases/latest"

    val available = MutableStateFlow<UpdateInfo?>(null)
    val status = MutableStateFlow("")
    val dismissed = MutableStateFlow(false)
    /** APK nueva ya descargada y lista para instalar. */
    val ready = MutableStateFlow<File?>(null)
    /** La app está a la vista (entre onStart y onStop). */
    val foreground = MutableStateFlow(false)
    val installing = MutableStateFlow(false)

    fun currentCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zerkalo", Context.MODE_PRIVATE)

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("autoUpdate", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoUpdate", on).apply()

    /** Zerkalo tiene permiso para instalar apps. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Puede actualizarse sin preguntar: Android 12+, permiso dado y la versión actual la instaló Zerkalo misma. */
    fun silentPossible(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canInstall(ctx)) return false
        return try {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName
        } catch (e: Exception) {
            false
        }
    }

    /** Abre el ajuste de Android para que Zerkalo pueda instalar sus propias actualizaciones. */
    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    private fun apkFile(ctx: Context) = File(File(ctx.filesDir, "update").apply { mkdirs() }, "Zerkalo.apk")

    /** Recupera una descarga anterior, o la borra si ya está instalada. */
    private fun restoreReady(ctx: Context) {
        val f = apkFile(ctx)
        val code = prefs(ctx).getLong("readyCode", 0L)
        if (f.exists() && code > currentCode(ctx)) ready.value = f
        else {
            f.delete()
            ready.value = null
        }
    }

    suspend fun check(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        restoreReady(ctx)
        val prefs = prefs(ctx)
        val last = prefs.getLong("updateCheck", 0L)
        // Al abrir la app se revisa si pasó más de media hora; el trabajo de fondo revisa cada pocas horas.
        if (!force && System.currentTimeMillis() - last < 30 * 60_000L) return@withContext
        status.value = "Buscando…"
        if (force) dismissed.value = false
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            prefs.edit().putLong("updateCheck", System.currentTimeMillis()).apply()
            val o = JSONObject(text)
            val tag = o.optString("tag_name").removePrefix("v")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: 0L
            val assets = o.optJSONArray("assets")
            var url = "https://github.com/sebastianbalbinu/Zerkalo/releases/latest/download/Zerkalo.apk"
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url", url)
                }
            }
            if (code > currentCode(ctx)) {
                val info = UpdateInfo(tag, code, url, o.optString("body"))
                available.value = info
                status.value = String.format("Hay una versión nueva: %s", tag)
                if (autoEnabled(ctx)) download(ctx, info)
            } else {
                available.value = null
                status.value = "Tienes la última versión"
            }
        } catch (e: Exception) {
            status.value = "No se pudo revisar (¿sin internet?)"
        }
    }

    /** Descarga la APK en segundo plano (si no estaba ya). */
    suspend fun download(ctx: Context, info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val f = apkFile(ctx)
        if (ready.value != null && prefs(ctx).getLong("readyCode", 0L) == info.code) return@withContext ready.value
        status.value = String.format("Descargando la versión %s…", info.version)
        val tmp = File(f.parentFile, "Zerkalo.part")
        try {
            var url = URL(info.url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                val codeHttp = conn.responseCode
                if (codeHttp in 300..399 && hops++ < 5) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    continue
                }
                break
            }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (tmp.length() < 1_000_000) { tmp.delete(); throw IllegalStateException("APK incompleta") }
            f.delete()
            tmp.renameTo(f)
            prefs(ctx).edit().putLong("readyCode", info.code).apply()
            ready.value = f
            status.value = String.format("Versión %s lista · se instala sola", info.version)
            f
        } catch (e: Exception) {
            tmp.delete()
            status.value = "No se pudo descargar la actualización"
            null
        }
    }

    /** Está sonando música en el teléfono (de Zerkalo o de otra app): entonces no se interrumpe nada. */
    fun musicActive(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
    } catch (e: Exception) {
        false
    }

    /** Programa la revisión en segundo plano: cada 4 horas, con internet, aunque la app esté cerrada. */
    fun schedule(ctx: Context) {
        val req = androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(4, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
            )
            .setInitialDelay(20, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        androidx.work.WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("zerkalo-updates", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Aviso cuando la versión nueva está lista pero Android pide confirmar (la primera vez). */
    fun notifyReady(ctx: Context, version: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("updates", "Actualizaciones", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = androidx.core.app.NotificationCompat.Builder(ctx, "updates")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(String.format("Zerkalo %s está lista", version))
                .setContentText("Toca para actualizar")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(4242, n)
        } catch (e: Exception) {
        }
    }

    /** Instala la APK descargada. Si Android necesita confirmación, muestra su ventana. */
    suspend fun install(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val f = ready.value ?: return@withContext false
        if (!canInstall(ctx) || installing.value) return@withContext false
        installing.value = true
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("Zerkalo.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
            status.value = "Instalando la actualización…"
            true
        } catch (e: Exception) {
            installing.value = false
            status.value = "No se pudo instalar la actualización"
            false
        }
    }

    internal fun onInstallResult(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Primera vez: Android pide confirmar. Solo se muestra si la app está a la vista.
                installing.value = false
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null && foreground.value) {
                    try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> installing.value = false
            else -> {
                installing.value = false
                status.value = "No se pudo instalar la actualización"
            }
        }
    }
}

/** Recibe el resultado del instalador de Android. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.onInstallResult(context, intent)
}

/**
 * Revisión de fondo: busca la versión nueva, la descarga y, si se puede sin preguntar y no suena música,
 * la instala. Si Android aún pide confirmar, deja un aviso para tocar.
 */
class UpdateWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Updates.autoEnabled(ctx)) return Result.success()
                Updates.check(ctx, force = true)
        val info = Updates.available.value ?: return Result.success()
        Updates.ready.value ?: Updates.download(ctx, info) ?: return Result.retry()
        when {
            Updates.silentPossible(ctx) && !Updates.foreground.value -> Updates.install(ctx)
            !Updates.silentPossible(ctx) && !Updates.foreground.value -> Updates.notifyReady(ctx, info.version)
        }
        return Result.success()
    }
}
