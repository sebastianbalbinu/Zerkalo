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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
        val base = sorted.filter { tagSel == null || tagSel in it.tags }
        if (q.isEmpty()) base else {
            // Primero lo que contiene lo escrito tal cual; después lo parecido (con margen de error)
            val exact = base.filter { (it.title + " " + it.author + " " + it.tags.joinToString(" ")).lowercase().contains(q) }
            val want = com.sebas.zerkalo.data.Fuzzy.tokens(q)
            val near = base.filter { it !in exact }
                .map { it to com.sebas.zerkalo.data.Fuzzy.score(want, com.sebas.zerkalo.data.Fuzzy.tokens(it.title + " " + it.author + " " + it.tags.joinToString(" "))) }
                .filter { it.second >= 0.66 }.sortedByDescending { it.second }.map { it.first }
            exact + near
        }
    }
    var mode by rememberSaveable { mutableStateOf(Prefs.libraryMode) }
    var estado by rememberSaveable { mutableStateOf(0) }   // 0 todos, 1 leyendo, 2 por leer, 3 leídos, 4 favoritos
    var panel by rememberSaveable { mutableStateOf(false) } // ordenar por género, autor, idioma o etiqueta
    var quick by remember { mutableStateOf<Book?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.sebas.zerkalo.data.Genres.backfill((ctx.applicationContext as com.sebas.zerkalo.ZerkaloApp).readium) }
    val reading = list.filter { it.reading }
    val unread = list.filter { !it.reading && !it.finished }
    val done = list.filter { it.finished }
    val favorites = list.filter { it.favorite }
    if (estado == 4 && favorites.isEmpty()) estado = 0
    val current = if (query.isBlank() && tagSel == null) reading.firstOrNull() else null
    val shown = when (estado) { 1 -> reading; 2 -> unread; 3 -> done; 4 -> favorites; else -> list }
    val onLong: (Book) -> Unit = { quick = it }
    // Tres libros por estante, del mismo ancho, usando toda la pantalla
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val coverW = ((screenW - 40.dp - 28.dp) / 3).coerceIn(84.dp, 150.dp)

    LaunchedEffect(Unit) { onReady() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze), state = listState,
            contentPadding = PaddingValues(bottom = 150.dp),
        ) {
            item(key = "head") {
                Box {
                    current?.let { HeroGlow(it, Modifier.matchParentSize()) }
                    Column(Modifier.padding(top = top + 18.dp)) {
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
                        current?.let { HeroBook(it) }
                    }
                }
            }
            if (searching) item(key = "search") { SearchField(query, { query = it }, "Título o autor", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
            if (books.isNotEmpty()) item(key = "filters") {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.lazy.LazyRow(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 20.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { SmallPill("Todos ${list.size}", estado == 0) { estado = 0 } }
                        if (reading.isNotEmpty()) item { SmallPill("Leyendo ${reading.size}", estado == 1) { estado = if (estado == 1) 0 else 1 } }
                        if (unread.isNotEmpty()) item { SmallPill("Por leer ${unread.size}", estado == 2) { estado = if (estado == 2) 0 else 2 } }
                        if (done.isNotEmpty()) item { SmallPill("Leídos ${done.size}", estado == 3) { estado = if (estado == 3) 0 else 3 } }
                        if (favorites.isNotEmpty()) item { SmallPill("Favoritos ${favorites.size}", estado == 4) { estado = if (estado == 4) 0 else 4 } }
                    }
                    CircleButton("sort", { panel = !panel }, size = 38.dp, desc = "Ordenar")
                    Spacer(Modifier.width(20.dp))
                }
            }
            if (panel && books.isNotEmpty()) item(key = "panel") {
                Column(Modifier.padding(top = 12.dp)) {
                    Caption("Agrupar por", modifier = Modifier.padding(horizontal = 20.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { items(LIB_MODES.size) { i -> SmallPill(LIB_MODES[i], mode == i) { mode = i; Prefs.libraryMode = i } } }
                    if (allTags.isNotEmpty()) {
                        Caption("Etiquetas", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            item { SmallPill("Todas", tagSel == null) { tagSel = null } }
                            items(allTags.size) { i -> val t = allTags[i]; SmallPill("#$t", tagSel == t) { tagSel = if (tagSel == t) null else t } }
                        }
                    }
                }
            }
            item(key = "inbox") { LibraryInbox() }

            if (books.isEmpty()) {
                item(key = "empty") { EmptyLibrary() }
            } else {
                // Las secciones: por estado (si se ven todos) o por género, autor o idioma
                val sections: List<Pair<String, List<Book>>> = when {
                    mode in 1..3 -> shelvesFor(mode, shown)
                    estado == 0 -> listOf(
                        "Leyendo" to (if (current != null) reading.drop(1) else reading),
                        "Por leer" to unread,
                        "Leídos" to done,
                    ).filter { it.second.isNotEmpty() }
                    else -> listOf("" to shown)
                }
                sections.forEach { (title, group) ->
                    if (title.isNotEmpty()) item(key = "t-$mode-$estado-$title") {
                        SectionTitle(title, count(group.size), modifier = Modifier.padding(top = 28.dp))
                    }
                    val rows = group.chunked(3)
                    rows.forEachIndexed { r, row ->
                        item(key = "r-$mode-$estado-$title-$r", contentType = "estante") {
                            ShelfGrid(row, coverW, onLong)
                        }
                    }
                }
                if (query.isBlank() && tagSel == null && estado in listOf(0, 2)) item(key = "add") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp).clip(RoundedCornerShape(16.dp))
                            .background(p.surface.copy(alpha = 0.7f)).clickable { a.goTab(1) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZIcon("plus", p.text2, 20.dp)
                        Text("Buscar más libros en el Tansu", style = Type.ui, color = p.text2, modifier = Modifier.padding(start = 12.dp))
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

/** El color de la portada del libro actual, difuminado detrás del encabezado. */
@Composable
private fun HeroGlow(b: Book, modifier: Modifier) {
    val p = LocalPalette.current
    val file = remember(b.cover) { b.cover?.let { java.io.File(Library.coversDir, it) }?.takeIf { it.exists() } }
    Box(modifier) {
        if (file != null && !b.useBinding) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(file).size(120).build(),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(if (p.isDark) 0.55f else 0.4f),
            )
        } else {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(p.accent.copy(alpha = 0.28f), Color.Transparent))))
        }
        // Se funde con el fondo hacia abajo, sin borde
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.35f to Color.Transparent, 1f to p.bg)))
    }
}

/** El libro que se está leyendo, en grande: portada, avance y el botón para seguir. */
@Composable
private fun HeroBook(b: Book) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val pct = (b.progress * 100).toInt()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        BookCover(b, 132.dp, 198.dp, Modifier.clickable { a.open(Screen.BookDetail(b.id)) }, ribbon = true, elevation = 14.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Caption("Sigues en", accent = true)
            Text(b.title, style = Type.title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(b.author, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            ProgressLine(b.progress.toFloat(), Modifier.fillMaxWidth().padding(top = 14.dp))
            Text("$pct %", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            Gap(12)
            PillButton("Seguir leyendo", { a.read(b) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Un estante de madera con hasta tres libros, todos a la vista. */
@Composable
private fun ShelfGrid(row: List<Book>, w: androidx.compose.ui.unit.Dp, onLong: (Book) -> Unit) {
    val a = LocalActions.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Shelf(Modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            row.forEach { b ->
                Box {
                    BookCover(
                        b, w, w * 1.5f,
                        Modifier.pointerInput(b.id) {
                            detectTapGestures(
                                onTap = { a.open(Screen.BookDetail(b.id)) },
                                onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLong(b) },
                            )
                        },
                        ribbon = b.reading,
                    )
                    // Cuánto va, como una rayita bermellón sobre la portada
                    if (b.reading) Box(
                        Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 7.dp).fillMaxWidth()
                            .height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66000000)),
                    ) { Box(Modifier.fillMaxWidth(b.progress.toFloat().coerceIn(0.02f, 1f)).height(3.dp).background(LocalPalette.current.accent)) }
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
            // EPUB nuevos en el teléfono: una sola línea discreta, para que mande la biblioteca
            access && news.isNotEmpty() -> Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZIcon("import", p.accent, 17.dp)
                Text(
                    if (news.size == 1) "«${news[0].name.removeSuffix(".epub")}» está en tu teléfono" else "${news.size} EPUB nuevos en tu teléfono",
                    style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    if (news.size == 1) "Traer" else "Traer todos", style = Type.uiSmall, color = p.text,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { Scanner.importFiles(core, news) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    "Elegir", style = Type.uiSmall, color = p.text2,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = a.importEpub).padding(horizontal = 6.dp, vertical = 6.dp),
                )
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
