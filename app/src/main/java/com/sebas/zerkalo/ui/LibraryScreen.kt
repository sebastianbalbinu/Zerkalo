package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.sebas.zerkalo.data.Prefs
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Scanner
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(haze: HazeState, onReady: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val sorted = remember(books) { books.sortedByDescending { maxOf(it.lastOpened, it.addedAt) } }
    var tagSel by rememberSaveable { mutableStateOf<String?>(null) }
    val allTags = remember(books) { books.flatMap { it.tags }.distinct().sortedBy { it.lowercase() } }
    if (tagSel != null && tagSel !in allTags) tagSel = null
    val list = remember(sorted, query, tagSel) {
        val q = query.trim().lowercase()
        sorted.filter { (q.isEmpty() || (it.title + " " + it.author + " " + it.tags.joinToString(" ")).lowercase().contains(q)) && (tagSel == null || tagSel in it.tags) }
    }
    var mode by rememberSaveable { mutableStateOf(Prefs.libraryMode) }
    var quick by remember { mutableStateOf<Book?>(null) }
    val favorites = list.filter { it.favorite }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.sebas.zerkalo.data.Genres.backfill((ctx.applicationContext as com.sebas.zerkalo.ZerkaloApp).readium) }
    val reading = list.filter { it.reading }
    val unread = list.filter { !it.reading && !it.finished }
    val done = list.filter { it.finished }
    val current = if (query.isBlank() && tagSel == null) reading.firstOrNull() else null

    LaunchedEffect(Unit) { onReady() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze), state = listState,
            contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Caption(SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(Date()))
                        Text("Biblioteca", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    }
                    CircleButton("plus", a.importEpub, desc = "Traer libros")
                    Spacer(Modifier.width(10.dp))
                    CircleButton("search", { searching = !searching; if (!searching) query = "" })
                    Spacer(Modifier.width(10.dp))
                    CircleButton("gear", { a.open(Screen.Settings) })
                }
            }
            if (allTags.isNotEmpty()) item(key = "tags") {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    item { SmallPill("Todos", tagSel == null) { tagSel = null } }
                    items(allTags.size) { i -> val t = allTags[i]; SmallPill("#$t", tagSel == t) { tagSel = if (tagSel == t) null else t } }
                }
            }
            if (books.isNotEmpty()) item(key = "modes") {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    items(LIB_MODES.size) { i -> SmallPill(LIB_MODES[i], mode == i) { mode = i; Prefs.libraryMode = i } }
                }
            }
            item(key = "inbox") { LibraryInbox() }
            if (searching) item(key = "search") { SearchField(query, { query = it }, "Título o autor", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }

            if (books.isEmpty()) {
                item(key = "empty") { EmptyLibrary() }
            } else {
                val onLong: (Book) -> Unit = { quick = it }
                current?.let { b -> item(key = "cont") { ContinueCard(b) } }
                if (favorites.isNotEmpty()) item(key = "s-fav") { ShelfRow("Favoritos", count(favorites.size), favorites, onLong = onLong) }
                when (mode) {
                    1, 2, 3 -> {
                        val shelves = shelvesFor(mode, list)
                        shelves.forEach { (title, group) ->
                            item(key = "g-$mode-$title") {
                                ShelfRow(title, count(group.size), group, addSlot = query.isBlank() && mode == 1, onAdd = { a.goTab(1) }, onLong = onLong)
                            }
                        }
                    }
                    else -> {
                        val rest = if (current != null) reading.drop(1) else reading
                        if (rest.isNotEmpty()) item(key = "s-reading") { ShelfRow("Leyendo", count(reading.size), rest, onLong = onLong) }
                        item(key = "s-unread") { ShelfRow("Por leer", if (unread.isEmpty()) "" else count(unread.size), unread, addSlot = query.isBlank(), onLong = onLong) }
                        if (done.isNotEmpty()) item(key = "s-done") { ShelfRow("Terminados", count(done.size), done, onLong = onLong) }
                    }
                }
                if (query.isNotBlank() && list.isEmpty()) item(key = "none") {
                    Text("Nada con «$query» en tu estante.", style = Type.ui, color = p.text2, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center)
                }
            }
        }
        CollapsingTop("Biblioteca", listState, Modifier.align(Alignment.TopCenter))
        ProgressiveEdge(haze, top = false, height = 150.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }
    quick?.let { BookQuickSheet(it) { quick = null } }
}

private val LIB_MODES = listOf("Estado", "Géneros", "Autores", "Idiomas")

/**
 * Estantes de la biblioteca según el modo: por género, por autor o por idioma.
 * Los autores con un solo libro van juntos en "Más autores", para que cada estante se vea lleno.
 */
private fun shelvesFor(mode: Int, books: List<Book>): List<Pair<String, List<Book>>> = when (mode) {
    1 -> books.groupBy { b -> b.genre.takeIf { g -> com.sebas.zerkalo.data.Catalogs.TOPICS.any { it.id == g } } ?: "-" }
        .entries.sortedWith(compareBy<Map.Entry<String, List<Book>>> { it.key == "-" }.thenByDescending { it.value.size })
        .map { com.sebas.zerkalo.data.Genres.name(it.key) to it.value }
    2 -> {
        fun who(b: Book) = b.author.split(',', ';', '&').firstOrNull()?.trim().orEmpty().ifBlank { "Autor desconocido" }
        val byAuthor = books.groupBy { who(it) }
        val big = byAuthor.filter { it.value.size >= 2 }.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
        val rest = byAuthor.filter { it.value.size < 2 }.values.flatten().sortedBy { who(it) }
        big + if (rest.isNotEmpty()) listOf("Más autores" to rest) else emptyList()
    }
    else -> books.groupBy { it.lang.take(2) }.entries.sortedByDescending { it.value.size }.map { (l, g) ->
        (com.sebas.zerkalo.data.Catalogs.LANGS.firstOrNull { it.id == l }?.name ?: if (l.isBlank()) "Sin idioma" else l.uppercase()) to g
    }
}

private fun count(n: Int) = if (n == 1) "1 libro" else "$n libros"

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(p.surface.copy(alpha = 0.92f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZIcon("search", p.text2, 19.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.ui.copy(fontSize = 16.sp), color = p.text2.copy(alpha = 0.8f))
            BasicTextField(
                value, onChange, singleLine = true, textStyle = Type.ui.copy(fontSize = 16.sp, color = p.text),
                cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
            )
        }
        if (value.isNotEmpty()) ZIcon("close", p.text2, 18.dp, modifier = Modifier.clickable { onChange("") })
    }
}

@Composable
private fun EmptyLibrary() {
    val p = LocalPalette.current
    val a = LocalActions.current
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp), PaddingValues(28.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            EnsoImage(p.isDark, 84.dp)
            Gap(14)
            Text("Tu estante está esperando", style = Type.title, color = p.text, textAlign = TextAlign.Center)
            Gap(8)
            Text(
                "Trae tus propios EPUB o busca en el Tansu entre las bibliotecas libres del mundo.",
                style = Type.body, color = p.text2, textAlign = TextAlign.Center,
            )
            Gap(20)
            PillButton("Importar EPUB", a.importEpub, icon = "import", modifier = Modifier.fillMaxWidth())
            Gap(10)
            PillButton("Abrir el Tansu", { a.goTab(1) }, ghost = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ContinueCard(b: Book) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val pct = (b.progress * 100).toInt()
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), PaddingValues(18.dp)) {
        Column {
            Row {
                BookCover(b, 96.dp, 140.dp, Modifier.clickable { a.open(Screen.BookDetail(b.id)) }, ribbon = true, elevation = 10.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).height(140.dp)) {
                    Caption("Leyendo", accent = true)
                    Text(b.title, style = Type.title.copy(fontSize = 21.sp, lineHeight = 26.sp), color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(b.author, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(b.progress.toFloat(), Modifier.weight(1f))
                        Text("$pct %", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            Gap(16)
            PillButton("Seguir leyendo", { a.read(b) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ShelfRow(
    title: String, more: String, books: List<Book>, addSlot: Boolean = false,
    onAdd: (() -> Unit)? = null, onLong: ((Book) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.padding(top = 30.dp)) {
        SectionTitle(title, more)
        Gap(14)
        Shelf {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(books, key = { it.id }, contentType = { "libro" }) { b ->
                    Box {
                        BookCover(
                            b, 100.dp, 148.dp,
                            Modifier.pointerInput(b.id) {
                                detectTapGestures(
                                    onTap = { a.open(Screen.BookDetail(b.id)) },
                                    onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); (onLong ?: { x: Book -> a.open(Screen.BookDetail(x.id)) })(b) },
                                )
                            },
                            ribbon = b.reading,
                        )
                    }
                }
                if (addSlot) item(key = "add") {
                    Column(
                        Modifier.size(100.dp, 148.dp).clip(RoundedCornerShape(6.dp)).background(p.surface.copy(alpha = 0.7f)).clickable { (onAdd ?: a.importEpub)() },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        ZIcon("plus", p.text2, 24.dp)
                        Text(if (onAdd != null) "Buscar en\nel Tansu" else "Importar\nEPUB", style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Avisos arriba de la biblioteca: EPUB nuevos encontrados en el teléfono, importación en curso
 * y la actualización de Zerkalo (con su avance, para saber que está funcionando).
 */
@Composable
private fun LibraryInbox() {
    val p = LocalPalette.current
    val a = LocalActions.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val core = (ctx.applicationContext as com.sebas.zerkalo.ZerkaloApp).readium
    val found by Scanner.found.collectAsState()
    val job by Scanner.job.collectAsState()
    val upInfo by com.sebas.zerkalo.Updates.available.collectAsState()
    val upProgress by com.sebas.zerkalo.Updates.progress.collectAsState()
    val upReady by com.sebas.zerkalo.Updates.ready.collectAsState()
    val upFailed by com.sebas.zerkalo.Updates.failed.collectAsState()
    val upStatus by com.sebas.zerkalo.Updates.status.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("zerkalo", android.content.Context.MODE_PRIVATE) }
    var askOff by remember { mutableStateOf(prefs.getBoolean("scanAskOff", false)) }
    var access by remember { mutableStateOf(Scanner.hasAccess(ctx)) }
    val ask = rememberAccessRequest { access = true; Scanner.scan(ctx) }
    // Busca sola una vez por sesión (y al volver del permiso)
    LaunchedEffect(access) {
        if (!access && !askOff) while (!Scanner.hasAccess(ctx)) kotlinx.coroutines.delay(1000)
        access = Scanner.hasAccess(ctx)
        if (access && Scanner.found.value == null) Scanner.scan(ctx)
    }
    val news = found.orEmpty()
    Column {
        if (upInfo != null && (upProgress != null || upReady != null || upFailed)) {
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), PaddingValues(16.dp)) {
                Column {
                    Caption("Zerkalo ${upInfo?.version}", accent = true)
                    Text(upStatus, style = Type.ui.copy(fontSize = 14.sp), color = p.text, modifier = Modifier.padding(top = 4.dp))
                    upProgress?.let { ProgressLine(it, Modifier.fillMaxWidth().padding(top = 10.dp)) }
                    if (upProgress == null) {
                        Gap(12)
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        PillButton(updateLabel(null, upReady != null, upFailed, true), { scope.launch { updateAction(ctx) } }, Modifier.fillMaxWidth(), icon = "download")
                    }
                }
            }
        }
        when {
            job.running -> Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), PaddingValues(16.dp)) { ImportProgress(job) }
            access && news.isNotEmpty() -> Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), PaddingValues(16.dp)) {
                Column {
                    Caption("En tu teléfono", accent = true)
                    Text(
                        if (news.size == 1) "Encontré un EPUB: «${news[0].name.removeSuffix(".epub")}»" else "Encontré ${news.size} EPUB que aún no están aquí",
                        style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(if (news.size == 1) "Traerlo" else "Traer todos", { Scanner.importFiles(core, news) }, Modifier.weight(1f), icon = "import")
                        PillButton("Elegir", a.importEpub, Modifier.weight(1f), ghost = true)
                    }
                }
            }
            !access && !askOff -> Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), PaddingValues(16.dp)) {
                Column {
                    Caption("Tus EPUB", accent = true)
                    Text("¿Busco los EPUB que ya tienes en el teléfono? Así no tienes que encontrarlos uno por uno.", style = Type.ui.copy(fontSize = 14.sp), color = p.text, modifier = Modifier.padding(top = 4.dp))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Buscar", ask, Modifier.weight(1f), icon = "search")
                        PillButton("Ahora no", { askOff = true; prefs.edit().putBoolean("scanAskOff", true).apply() }, Modifier.weight(1f), ghost = true)
                    }
                }
            }
        }
    }
}
