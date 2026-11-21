package com.sebas.zerkalo.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Catalogs
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Mark
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun BookScreen(id: String) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val sessions by Library.sessions.collectAsState()
    val book = books.firstOrNull { it.id == id }
    if (book == null) { androidx.compose.runtime.LaunchedEffect(Unit) { a.back() }; return }
    val days = remember(sessions, id) { Library.bookDays(id) }
    val haze = rememberHazeState()
    var menu by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val washi = Taller.WASHI.getOrElse(book.binding.washi) { Taller.WASHI[0] }.color

    Box(Modifier.fillMaxSize()) {
        // Luz de color detrás de la portada
        Box(Modifier.fillMaxWidth().height(top + 360.dp).background(Brush.verticalGradient(listOf(washi.copy(alpha = 0.35f), p.card.copy(alpha = 0.25f), Color.Transparent))))
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), contentPadding = PaddingValues(top = top + 64.dp, bottom = 140.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BookCover(book, 160.dp, 234.dp, ribbon = book.reading, elevation = 18.dp)
                    Gap(24)
                    Text(book.title, style = Type.title.copy(fontSize = 26.sp, lineHeight = 32.sp), color = p.text, textAlign = TextAlign.Center)
                    Gap(6)
                    Text(book.author.ifBlank { "Autor desconocido" }, style = Type.ui, color = p.text2, textAlign = TextAlign.Center)
                    Gap(14)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Catalogs.LANGS.firstOrNull { it.id == book.lang }?.let { Chip(it.name) }
                        if (book.source.isNotBlank()) Chip(book.source)
                        if (book.progress > 0) Chip("${(book.progress * 100).toInt()} %")
                    }
                    Gap(12)
                    TagEditor(book)
                }
            }
            if (book.finished) item {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Seal("読了", 58.dp, -4f, fontSize = 15f)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Leído", style = Type.bookTitle, color = p.text)
                            Text(SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es")).format(book.finishedAt), style = Type.uiSmall, color = p.text2)
                        }
                    }
                }
            }
            item {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Sellos de lectura", style = Type.bookTitle, color = p.text, modifier = Modifier.weight(1f))
                            Text(if (days.isEmpty()) "aún sin sellos" else if (days.size == 1) "1 día" else "${days.size} días", style = Type.uiSmall, color = p.text2)
                        }
                        Gap(14)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(days.takeLast(30)) { (date, min) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Seal("$min′", 46.dp, listOf(-6f, 4f, -2f, 5f, -4f, 2f)[date.hashCode().mod(6)], fontSize = 13f)
                                    Text(shortDate(date), style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                            if (days.none { it.first == Library.today() }) item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Seal("", 46.dp, filled = false)
                                    Text("hoy", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (book.summary.isNotBlank()) item {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    Column {
                        Text("Sobre el libro", style = Type.bookTitle, color = p.text)
                        Gap(8)
                        Text(book.summary.take(1400), style = Type.body, color = p.text.copy(alpha = 0.88f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp)) {
            CircleButton("back", a.back, glass = haze)
            Spacer(Modifier.weight(1f))
            CircleButton("more", { menu = true }, glass = haze)
        }
        ProgressiveEdge(haze, top = false, height = 140.dp, modifier = Modifier.align(Alignment.BottomCenter))
        Row(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                when { book.finished -> "Leer de nuevo"; book.progress > 0 -> "Seguir leyendo"; else -> "Empezar a leer" },
                { a.read(book) }, Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            CircleButton("share", { share = true }, size = 52.dp, glass = haze)
        }
    }
    var cover by remember { mutableStateOf(false) }
    var shelf by remember { mutableStateOf(false) }
    if (menu) BookMenu(book, onCover = { menu = false; cover = true }, onShelf = { menu = false; shelf = true }) { menu = false }
    if (cover) CoverSheet(book) { cover = false }
    if (shelf) BookQuickSheet(book, startMode = "genre") { shelf = false }
    if (share) ShareSheet(book) { share = false }
}

private fun shortDate(d: String): String = try {
    SimpleDateFormat("d MMM", Locale("es")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!)
} catch (e: Exception) { d }

@Composable
private fun BookMenu(book: Book, onCover: () -> Unit, onShelf: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            if (!confirm) {
                Text(book.title, style = Type.title.copy(fontSize = 20.sp), color = p.text)
                Gap(8)
                ListRow(if (book.favorite) "Quitar de favoritos" else "Agregar a favoritos", onClick = {
                    Library.update(book.id) { it.copy(favorite = !it.favorite) }; onClose()
                })
                ListRow("Cambiar portada", "Otras ediciones, Google o tu galería", onClick = onCover)
                ListRow("Estante: " + com.sebas.zerkalo.data.Genres.name(book.genre), "Cambiar el género del libro", onClick = onShelf)
                ListRow(if (book.useBinding) "Mostrar la portada original" else "Mostrar mi encuadernación", onClick = {
                    Library.update(book.id) { it.copy(useBinding = !it.useBinding) }; onClose()
                })
                ListRow("Diseñar su encuadernación", "Washi, patrón e hilo para su portada", onClick = { onClose(); a.open(Screen.Taller(book.id)) })
                ListRow(if (book.finished) "Marcar como no terminado" else "Marcar como terminado", onClick = {
                    Library.update(book.id) { if (it.finished) it.copy(finishedAt = 0) else it.copy(finishedAt = System.currentTimeMillis(), progress = 1.0) }
                    onClose()
                })
                ListRow("Exportar subrayados", "Con la cita lista para tus trabajos", onClick = {
                    val hls = Library.marks.value.filter { it.bookId == book.id && it.type == "hl" }
                    if (hls.isEmpty()) a.toast("Este libro aún no tiene subrayados")
                    else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText(book, hls)), "Compartir"))
                    onClose()
                })
                ListRow("Quitar de mi biblioteca", "Se borran el archivo, sus sellos y sus notas", onClick = { confirm = true })
            } else {
                Text("¿Quitar «${book.title}»?", style = Type.title.copy(fontSize = 20.sp), color = p.text)
                Gap(8)
                Text("Se borra del teléfono junto con sus sellos y notas. Esto no se puede deshacer.", style = Type.ui, color = p.text2)
                Gap(18)
                Row {
                    PillButton("Cancelar", { confirm = false }, Modifier.weight(1f), ghost = true)
                    Spacer(Modifier.width(10.dp))
                    PillButton("Quitar", { Library.remove(book); onClose(); a.back(); a.toast("Libro quitado") }, Modifier.weight(1f), accent = true)
                }
            }
        }
    }
}

/** Etiquetas propias del libro: se agregan escribiendo y se quitan tocándolas. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(book: Book) {
    val p = LocalPalette.current
    val all by Library.books.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val suggestions = remember(all, book.tags) { all.flatMap { it.tags }.distinct().filter { it !in book.tags }.take(8) }
    fun add(t: String) {
        val tag = t.trim().take(28)
        if (tag.isNotEmpty()) Library.update(book.id) { it.copy(tags = (it.tags + tag).distinct()) }
        text = ""; adding = false
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        book.tags.forEach { t ->
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(p.text.copy(alpha = 0.9f)).clickable {
                    Library.update(book.id) { it.copy(tags = it.tags - t) }
                }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#$t", style = Type.uiSmall, color = p.bg)
                Text("  ×", style = Type.uiSmall, color = p.bg.copy(alpha = 0.7f))
            }
        }
        if (!adding) Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(p.surface).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZIcon("tag", p.text2, 14.dp)
            Text(if (book.tags.isEmpty()) "  Agregar etiqueta" else "  Otra", style = Type.uiSmall, color = p.text2)
        }
    }
    if (adding) {
        Gap(10)
        SearchField(text, { text = it }, "Nueva etiqueta (por ejemplo: Rusos)", Modifier.fillMaxWidth(), onSubmit = { add(text) })
        if (suggestions.isNotEmpty()) {
            Gap(8)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { t -> SmallPill("#$t", false) { add(t) } }
            }
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Agregar", { add(text) }, enabled = text.isNotBlank())
            PillButton("Cancelar", { adding = false; text = "" }, ghost = true)
        }
    }
}

/** Enlace público del libro en su biblioteca de origen (si se puede armar). */
fun sourceLink(book: Book): String? {
    val k = book.key
    return when {
        k.startsWith("pg:") -> "https://www.gutenberg.org/ebooks/" + k.removePrefix("pg:")
        k.startsWith("ia:") -> "https://archive.org/details/" + k.removePrefix("ia:")
        k.startsWith("ws:") -> k.removePrefix("ws:").split(":", limit = 2).let { "https://${it[0]}.wikisource.org/wiki/" + java.net.URLEncoder.encode(it.getOrElse(1) { "" }.replace(' ', '_'), "UTF-8").replace("+", "%20").replace("%2F", "/") }
        k.startsWith("oapen:") -> "https://library.oapen.org/handle/" + k.removePrefix("oapen:")
        else -> null
    }
}

/** Compartir con amigos: el archivo EPUB (WhatsApp, Quick Share, correo…) o solo el enlace. */
@Composable
private fun ShareSheet(book: Book, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val a = LocalActions.current
    val link = remember(book.key) { sourceLink(book) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text("Compartir", style = Type.title.copy(fontSize = 21.sp), color = p.text)
            Text(book.title, style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 4.dp))
            Gap(12)
            ListRow("Enviar el libro", "El archivo EPUB: tu amigo lo abre en Zerkalo o en cualquier lector", onClick = {
                runCatching {
                    val dir = java.io.File(ctx.cacheDir, "compartir").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val name = (book.title.take(60) + if (book.author.isNotBlank()) " - " + book.author.take(40) else "")
                        .replace(Regex("[\\/:*?\"<>|]"), " ").trim() + ".epub"
                    val out = java.io.File(dir, name)
                    java.io.File(Library.booksDir, book.file).copyTo(out, overwrite = true)
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", out)
                    val send = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TEXT, "Te comparto «${book.title}» 📖").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, "Enviar libro"))
                }.onFailure { a.toast("No se pudo preparar el archivo") }
                onClose()
            })
            if (link != null) ListRow("Enviar el enlace", "Pesa nada: lo bajan de ${book.source}", onClick = {
                val t = "«${book.title}»" + (if (book.author.isNotBlank()) " de ${book.author}" else "") + "\n$link"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Enviar enlace"))
                onClose()
            })
        }
    }
}

fun exportText(book: Book, hls: List<Mark>): String {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val head = "${book.author.ifBlank { "Autor desconocido" }}. ${book.title}. ${book.source} (consultado en $year)."
    return "${book.title}\n${book.author}\n\n" + hls.sortedBy { it.progress }.joinToString("\n\n") { m ->
        "«${m.text.trim()}»" + (if (m.chapter.isNotBlank()) "\n(${m.chapter})" else "") + (if (m.note.isNotBlank()) "\nNota: ${m.note}" else "")
    } + "\n\nCita: $head\n\nSubrayados exportados desde Zerkalo"
}
