package com.sebas.zerkalo.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sebas.zerkalo.Reminder
import com.sebas.zerkalo.Updates
import com.sebas.zerkalo.data.Catalogs
import com.sebas.zerkalo.data.PaperPref
import com.sebas.zerkalo.data.Prefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val p = LocalPalette.current
    val a = LocalActions.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val paper by Prefs.paper.collectAsState()
    val status by Updates.status.collectAsState()
    val available by Updates.available.collectAsState()
    val upProgress by Updates.progress.collectAsState()
    val upReady by Updates.ready.collectAsState()
    val upFailed by Updates.failed.collectAsState()
    var remOn by remember { mutableStateOf(Prefs.reminderOn) }
    var remTime by remember { mutableStateOf(Prefs.reminderHour to Prefs.reminderMinute) }
    var auto by remember { mutableStateOf(Updates.autoEnabled(ctx)) }
    var catalogs by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    // Copia de la biblioteca en .zip
    var working by remember { mutableStateOf(false) }
    fun backup(full: Boolean) {
        if (working) return
        working = true
        a.toast(if (full) "Preparando la copia completa…" else "Preparando tus libros…")
        scope.launch {
            runCatching { com.sebas.zerkalo.data.Backup.export(ctx, full) }
                .onSuccess { com.sebas.zerkalo.data.Export.share(ctx, it, "application/zip") }
                .onFailure { a.toast("No se pudo hacer la copia: ${it.message}") }
            working = false
        }
    }
    // Guardar la copia directo en Drive (o en cualquier carpeta) con el selector del sistema
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !working) {
            working = true
            a.toast("Preparando la copia completa…")
            scope.launch {
                runCatching {
                    val f = com.sebas.zerkalo.data.Backup.export(ctx, true)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("no se pudo escribir")
                        f.delete()
                    }
                }.onSuccess { a.toast("Copia guardada") }.onFailure { a.toast("No se pudo guardar la copia: ${it.message}") }
                working = false
            }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            a.toast("Leyendo la copia…")
            scope.launch {
                com.sebas.zerkalo.data.Backup.restore(ctx, uri)
                    .onSuccess { n -> a.toast((if (n == 0) "Ya tenías todos esos libros" else if (n == 1) "Llegó 1 libro a tu biblioteca" else "Llegaron $n libros a tu biblioteca") + " · tus ajustes se ven completos al volver a abrir la app") }
                    .onFailure { a.toast(it.message ?: "No se pudo leer la copia") }
            }
        }
    }

    fun setReminder(on: Boolean, h: Int, m: Int) {
        if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminder.save(ctx, on, h, m); Reminder.schedule(ctx)
        remOn = on; remTime = h to m
        if (on) a.toast("Te avisaré a las %02d:%02d".format(h, m))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 10.dp, bottom = 60.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                CircleButton("back", a.back)
                Text("Ajustes", style = Type.display, color = p.text, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                Column {
                    Text("Papel", style = Type.bookTitle, color = p.text)
                    Text("Nogal es madera de nogal; Sumi es negro puro para pantallas OLED, con letra ámbar para leer a oscuras.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                    Gap(12)
                    Segmented(listOf("Sistema", "Crema", "Nogal", "Sumi"), paper.ordinal, { Prefs.setPaper(PaperPref.entries[it]) })
                }
            }
        }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    ListRow("Hora de lectura", "Un aviso amable cada día, nada más") { ZSwitch(remOn) { setReminder(it, remTime.first, remTime.second) } }
                    if (remOn) ListRow("A las", onClick = {
                        TimePickerDialog(ctx, { _, h, m -> setReminder(true, h, m) }, remTime.first, remTime.second, true).show()
                    }) { Text("%02d:%02d".format(remTime.first, remTime.second), style = Type.ui, color = p.text) }
                }
            }
        }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    UpdateRow(version, status, available, upProgress, upReady != null, upFailed)
                    ListRow("Actualizar sola", "Descarga la versión nueva y la instala cuando no estás leyendo") {
                        ZSwitch(auto) { auto = it; Updates.setAuto(ctx, it) }
                    }
                }
            }
        }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    ListRow("Traer mis EPUB", "Busca en tu teléfono o elige varios a la vez", onClick = a.importEpub) { ZIcon("chevron", p.text2, 16.dp) }
                    ListRow("Catálogos OPDS", "Agrega otras bibliotecas libres", onClick = { catalogs = true }) { ZIcon("chevron", p.text2, 16.dp) }
                }
            }
        }
        item { SectionTitle("Copia de la biblioteca", modifier = Modifier.padding(top = 28.dp)) }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    ListRow("Guardar copia en Drive o en una carpeta", "Todo: libros, avance, subrayados, notas, trazos, apuntes, tarjetas, sellos, perfiles y ajustes", onClick = {
                        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        savePicker.launch("Copia Zerkalo $day.zip")
                    }) {
                        ZIcon("share", p.text2, 18.dp)
                    }
                    ListRow("Enviar copia completa", "La misma copia, para mandarla por WhatsApp, correo o a otro teléfono", onClick = { backup(true) }) {
                        ZIcon("share", p.text2, 18.dp)
                    }
                    ListRow("Compartir solo los libros", "Sin tus notas ni tu avance, y sin documentos: para regalar tu biblioteca", onClick = { backup(false) }) {
                        ZIcon("share", p.text2, 18.dp)
                    }
                    ListRow("Recibir una copia", "Abre un .zip de Zerkalo y suma sus libros a los tuyos", onClick = {
                        restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }) { ZIcon("chevron", p.text2, 16.dp) }
                }
            }
        }
        // Si la app se cerró por un error, se puede mandar el informe
        item {
            var fails by remember { mutableStateOf(com.sebas.zerkalo.data.Crashes.count()) }
            if (fails > 0) Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    ListRow("Informe de cierres inesperados", if (fails == 1) "La app se cerró 1 vez. Mándalo para poder arreglarlo" else "La app se cerró $fails veces. Mándalo para poder arreglarlo", onClick = {
                        val f = java.io.File(com.sebas.zerkalo.data.Export.outDir(ctx), "Zerkalo fallos.txt")
                        runCatching { com.sebas.zerkalo.data.Crashes.file().copyTo(f, overwrite = true); com.sebas.zerkalo.data.Export.share(ctx, f, "text/plain") }
                    }) { ZIcon("share", p.text2, 18.dp) }
                    ListRow("Borrar el informe", "", onClick = { com.sebas.zerkalo.data.Crashes.clear(); fails = 0 }) { ZIcon("chevron", p.text2, 16.dp) }
                }
            }
        }
        item { SectionTitle("Las bibliotecas", modifier = Modifier.padding(top = 28.dp)) }
        item {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Column {
                    Catalogs.LIBRARIES.forEach { l ->
                        ListRow(l.name, l.what) {
                            PillButton("Apoyar", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.donate))) } }, ghost = true)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo(p.isDark, 180.dp)
                Text(
                    "Gratis, sin anuncios y sin servidores propios.\nTus libros, sellos y notas viven en tu teléfono.",
                    style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (catalogs) CatalogsSheet { catalogs = false }
}

/** Fila de actualización: dice siempre qué está pasando (buscando, % descargado, lista, se cortó). */
@Composable
fun UpdateRow(version: String, status: String, available: com.sebas.zerkalo.UpdateInfo?, progress: Float?, ready: Boolean, failed: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column {
        ListRow("Zerkalo $version", status.ifBlank { "Se actualiza sola desde GitHub" }) {
            val label = when {
                progress != null -> "${(progress * 100).toInt()} %"
                ready -> "Instalar"
                failed -> "Reintentar"
                available != null -> "Descargar"
                else -> "Buscar"
            }
            PillButton(label, { if (progress == null) scope.launch { updateAction(ctx) } }, ghost = available == null && !failed, enabled = progress == null)
        }
        if (progress != null) ProgressLine(progress, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    }
}

fun updateLabel(progress: Float?, ready: Boolean, failed: Boolean, available: Boolean) = when {
    progress != null -> "${(progress * 100).toInt()} %"
    ready -> "Instalar"
    failed -> "Reintentar"
    available -> "Descargar"
    else -> "Buscar"
}

/** Lo que hace el botón de actualizar según el momento: buscar, descargar o instalar. */
suspend fun updateAction(ctx: android.content.Context) {
    val info = Updates.available.value
    if (info == null) { Updates.check(ctx, force = true); return }
    if (Updates.ready.value == null) Updates.download(ctx, info)
    if (Updates.ready.value != null) {
        if (!Updates.canInstall(ctx)) {
            Updates.openInstallPermission(ctx)
            AppEvents.toast("Permite a Zerkalo instalar apps y vuelve a tocar Instalar")
            return
        }
        Updates.install(ctx)
    }
}
