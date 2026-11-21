package com.sebas.zerkalo.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Busca los EPUB que ya están en el teléfono (Descargas, WhatsApp, Telegram, Documentos…) y los trae
 * de a muchos. También guarda la cola de importación, para mostrar "Importando 3 de 12".
 */
object Scanner {
    data class Found(val path: String, val name: String, val size: Long, val modified: Long) {
        val sig get() = "$name|$size"
    }

    /** Progreso de una importación de varios libros. */
    data class Job(val done: Int = 0, val total: Int = 0, val current: String = "", val failed: Int = 0, val running: Boolean = false)

    val found = MutableStateFlow<List<Found>?>(null)  // null = todavía no se buscó
    val scanning = MutableStateFlow(false)
    val job = MutableStateFlow(Job())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun prefs() = app.getSharedPreferences("zerkalo", Context.MODE_PRIVATE)

    /** Los que ya se trajeron (o se descartaron) para no volver a ofrecerlos. */
    fun known(): Set<String> = prefs().getStringSet("scanKnown", emptySet()) ?: emptySet()
    private fun remember(sigs: Collection<String>) {
        prefs().edit().putStringSet("scanKnown", HashSet(known() + sigs)).apply()
    }
    fun dismiss(list: List<Found>) { remember(list.map { it.sig }); found.value = found.value?.filterNot { f -> list.any { it.sig == f.sig } } }

    /** Zerkalo puede mirar los archivos del teléfono. */
    fun hasAccess(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+: abre el ajuste "Acceso a todos los archivos" de Zerkalo. */
    fun accessIntent(ctx: Context): Intent? = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
    } else null

    fun fallbackAccessIntent(): Intent? = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) else null

    /** Busca en todo el teléfono. Rápido: primero el índice de Android, después recorre carpetas. */
    fun scan(ctx: Context) {
        if (scanning.value || !hasAccess(ctx)) return
        scanning.value = true
        scope.launch {
            val t0 = System.currentTimeMillis()
            val out = LinkedHashMap<String, Found>()
            fun add(f: File) {
                if (!f.isFile || f.length() < 1024) return
                val path = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                if (path.contains("/Android/data/") || path.contains("/Android/obb/")) return
                out.getOrPut(path) { Found(path, f.name, f.length(), f.lastModified()) }
            }
            // 1) Índice de archivos de Android
            runCatching {
                val uri = MediaStore.Files.getContentUri("external")
                @Suppress("DEPRECATION") val data = MediaStore.Files.FileColumns.DATA
                ctx.contentResolver.query(
                    uri, arrayOf(data), "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%.epub"), null,
                )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { add(File(it)) } }
            }.onFailure { Log.w("Zerkalo", "ZK_SCAN índice: ${it.message}") }
            // 2) Recorrido de carpetas (encuentra lo que el índice no ve, p. ej. carpetas de WhatsApp o Telegram)
            runCatching {
                val root = Environment.getExternalStorageDirectory()
                val stack = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
                var visited = 0
                while (stack.isNotEmpty() && visited < 40_000) {
                    val (dir, depth) = stack.removeLast()
                    val kids = dir.listFiles() ?: continue
                    for (k in kids) {
                        visited++
                        if (k.isDirectory) {
                            val n = k.name
                            if (depth < 8 && !(n.startsWith(".") && n != ".Statuses") && !(depth == 0 && n == "Android")) stack.add(k to depth + 1)
                            else if (depth == 0 && n == "Android") {
                                // WhatsApp moderno guarda sus documentos dentro de Android/media
                                File(k, "media").takeIf { it.isDirectory }?.let { stack.add(it to depth + 1) }
                            }
                        } else if (k.name.endsWith(".epub", ignoreCase = true)) add(k)
                    }
                }
            }.onFailure { Log.w("Zerkalo", "ZK_SCAN carpetas: ${it.message}") }
            val known = known()
            val have = Library.books.value.map { Importer.cleanName(it.title) }.toSet()
            val list = out.values.filter { it.sig !in known && Importer.cleanName(it.name.removeSuffix(".epub")) !in have }
                .distinctBy { it.sig }.sortedByDescending { it.modified }
            found.value = list
            scanning.value = false
            Log.i("Zerkalo", "ZK_SCAN ${out.size} epub, ${list.size} nuevos en ${System.currentTimeMillis() - t0} ms")
        }
    }

    /** Importa varios archivos del teléfono, uno tras otro, mostrando el avance. */
    fun importFiles(core: ReadiumCore, list: List<Found>) {
        if (list.isEmpty() || job.value.running) return
        found.value = found.value?.filterNot { f -> list.any { it.sig == f.sig } }
        runJob(list.size, list.map { it.name }) { i -> Importer.fromPath(core, File(list[i].path)).also { remember(listOf(list[i].sig)) } }
    }

    /** Importa varios archivos elegidos con el selector de Android. */
    fun importUris(ctx: Context, core: ReadiumCore, uris: List<Uri>) {
        if (uris.isEmpty() || job.value.running) return
        runJob(uris.size, uris.map { "" }) { i -> Importer.fromUri(ctx, core, uris[i]) }
    }

    private fun runJob(total: Int, names: List<String>, one: suspend (Int) -> Result<Book>) {
        job.value = Job(0, total, names.firstOrNull().orEmpty(), 0, true)
        scope.launch {
            var failed = 0
            var last: Book? = null
            for (i in 0 until total) {
                job.value = job.value.copy(done = i, current = names[i].removeSuffix(".epub"))
                one(i).onSuccess { last = it }.onFailure { failed++; Log.w("Zerkalo", "ZK_IMPORT ${names[i]}: ${it.message}") }
            }
            job.value = Job(total, total, "", failed, false)
            val ok = total - failed
            withContext(Dispatchers.Main) {
                com.sebas.zerkalo.ui.AppEvents.toast(
                    when {
                        total == 1 && last != null -> "«${last!!.title}» llegó a tu biblioteca"
                        failed == 0 -> "$ok libros llegaron a tu biblioteca"
                        ok == 0 -> "No se pudo importar ${if (total == 1) "el libro" else "ninguno"}"
                        else -> "$ok libros importados · $failed no se pudieron abrir"
                    },
                )
            }
        }
    }
}
