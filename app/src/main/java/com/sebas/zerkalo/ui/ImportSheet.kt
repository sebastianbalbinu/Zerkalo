package com.sebas.zerkalo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.ZerkaloApp
import com.sebas.zerkalo.data.Scanner
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pedir acceso a los archivos: en Android 11+ es un ajuste aparte ("Acceso a todos los archivos"). */
@Composable
fun rememberAccessRequest(onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) onGranted() }
    return {
        if (Build.VERSION.SDK_INT >= 30) {
            AppEvents.toast("Activa «Permitir acceso a todos los archivos» y vuelve")
            runCatching { ctx.startActivity(Scanner.accessIntent(ctx)!!) }
                .recoverCatching { Scanner.fallbackAccessIntent()?.let { ctx.startActivity(it) } }
        } else perm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** Traer libros: buscar todos los EPUB del teléfono o elegir varios a mano. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(onPickFiles: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val core = (ctx.applicationContext as ZerkaloApp).readium
    var access by remember { mutableStateOf(Scanner.hasAccess(ctx)) }
    val found by Scanner.found.collectAsState()
    val scanning by Scanner.scanning.collectAsState()
    val job by Scanner.job.collectAsState()
    var selected by remember { mutableStateOf<Set<String>?>(null) } // null = todos
    val ask = rememberAccessRequest { access = true; Scanner.scan(ctx) }

    // Al volver del ajuste de Android, se nota solo que ya hay permiso
    LaunchedEffect(access) {
        while (!access) { delay(800); if (Scanner.hasAccess(ctx)) { access = true } }
        if (found == null) Scanner.scan(ctx)
    }
    val list = found.orEmpty()
    val sel = selected ?: list.map { it.path }.toSet()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text("Traer libros", style = Type.title, color = p.text)
            Gap(4)
            Text("Zerkalo copia tus EPUB a su biblioteca. Los originales no se tocan.", style = Type.uiSmall, color = p.text2)

            if (job.running) {
                Gap(18)
                ImportProgress(job)
            }

            Gap(20)
            Caption("En tu teléfono", accent = true)
            Gap(8)
            when {
                !access -> {
                    Text(
                        "Zerkalo puede buscar todos los EPUB que tengas (Descargas, WhatsApp, Telegram, Documentos…) para que no tengas que encontrarlos uno por uno.",
                        style = Type.ui.copy(fontSize = 14.sp), color = p.text,
                    )
                    Gap(12)
                    PillButton("Buscar mis EPUB", ask, Modifier.fillMaxWidth(), icon = "search")
                }
                scanning || found == null -> Text("Buscando EPUB en tu teléfono…", style = Type.ui, color = p.text2, modifier = Modifier.padding(vertical = 14.dp))
                list.isEmpty() -> {
                    Text("No encontré EPUB nuevos. Los que ya trajiste no aparecen aquí.", style = Type.ui.copy(fontSize = 14.sp), color = p.text2)
                    Gap(10)
                    PillButton("Buscar otra vez", { Scanner.scan(ctx) }, Modifier.fillMaxWidth(), ghost = true, icon = "search")
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${list.size} EPUB encontrados", style = Type.ui, color = p.text, modifier = Modifier.weight(1f))
                        Text(
                            if (sel.size == list.size) "Quitar todos" else "Marcar todos", style = Type.uiSmall, color = p.accent,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { selected = if (sel.size == list.size) emptySet() else list.map { it.path }.toSet() }.padding(6.dp),
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 340.dp).padding(top = 6.dp)) {
                        items(list, key = { it.path }) { f ->
                            val on = f.path in sel
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { selected = if (on) sel - f.path else sel + f.path }.padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) p.accent else p.line),
                                    contentAlignment = Alignment.Center,
                                ) { if (on) ZIcon("check", p.bg, 15.dp) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name.removeSuffix(".epub"), style = Type.ui.copy(fontSize = 14.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(folderOf(f.path) + " · " + sizeOf(f.size) + " · " + dateOf(f.modified), style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Gap(12)
                    val chosen = list.filter { it.path in sel }
                    PillButton(
                        if (chosen.size == 1) "Traer 1 libro" else "Traer ${chosen.size} libros",
                        { Scanner.importFiles(core, chosen); selected = null }, Modifier.fillMaxWidth(),
                        enabled = chosen.isNotEmpty() && !job.running, icon = "import",
                    )
                    if (chosen.isNotEmpty() && chosen.size < list.size) {
                        Gap(6)
                        Text(
                            "Ocultar los que no marqué", style = Type.uiSmall, color = p.text2,
                            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp))
                                .clickable { Scanner.dismiss(list.filter { it.path !in sel }); selected = null }.padding(6.dp),
                        )
                    }
                }
            }

            Gap(24)
            Caption("O a mano", accent = true)
            Gap(8)
            PillButton("Elegir archivos (puedes marcar varios)", { onPickFiles() }, Modifier.fillMaxWidth(), ghost = true, icon = "folder")
            Gap(10)
            Text(
                "Desde WhatsApp, Drive o Archivos también puedes tocar un EPUB y abrirlo con Zerkalo.",
                style = Type.uiSmall, color = p.text2,
            )
        }
    }
}

@Composable
fun ImportProgress(job: Scanner.Job) {
    val p = LocalPalette.current
    Column {
        Text(
            "Importando ${job.done + 1} de ${job.total}" + if (job.current.isNotBlank()) " · ${job.current}" else "",
            style = Type.uiSmall, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Gap(6)
        ProgressLine(if (job.total > 0) job.done.toFloat() / job.total else 0f, Modifier.fillMaxWidth())
    }
}

private fun folderOf(path: String): String {
    val parts = path.split('/').dropLast(1)
    val i = parts.indexOfFirst { it == "0" || it == "emulated" }
    val rel = parts.drop(if (i >= 0) i + 2 else parts.size - 1).filter { it.isNotBlank() && it != "Android" && it != "media" }
    return when {
        rel.any { it.contains("WhatsApp", true) } -> "WhatsApp"
        rel.any { it.contains("Telegram", true) } -> "Telegram"
        rel.isEmpty() -> "Almacenamiento"
        else -> rel.takeLast(2).joinToString(" / ")
    }
}

private fun sizeOf(b: Long) = if (b > 1_048_576) String.format(Locale.forLanguageTag("es"), "%.1f MB", b / 1_048_576.0) else "${b / 1024} KB"
private fun dateOf(t: Long) = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es")).format(Date(t))
