package com.sebas.zerkalo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.ZerkaloApp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Importer
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Prefs
import com.sebas.zerkalo.data.Scanner
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sebas.zerkalo.reader.ReaderActivity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    data class BookDetail(val id: String) : Screen()
    data class Taller(val id: String) : Screen()
    data object Settings : Screen()
    data class Catalog(val url: String, val name: String) : Screen()
    data class Shelf(val lang: String, val kind: String, val value: String, val title: String) : Screen()
    /** Repaso de tarjetas: [practice] = práctica libre (no cambia fechas); [introOnly] = solo la explicación. */
    data class Review(val practice: Boolean = false, val introOnly: Boolean = false) : Screen()
    data object CardList : Screen()
    /** Documentos: PDF abiertos desde otra app, aparte de los libros. */
    data object Documents : Screen()
}

/** Acciones que cualquier pantalla puede pedir. */
class AppActions(
    val open: (Screen) -> Unit,
    val back: () -> Unit,
    val read: (Book) -> Unit,
    val importEpub: () -> Unit,
    val toast: (String) -> Unit,
    val goTab: (Int) -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> { error("sin acciones") }

object AppEvents {
    /** EPUB que llegó desde otra app y falta importar. */
    val incoming = MutableStateFlow<Uri?>(null)
    /** Imagen compartida desde otra app para usarla como portada. */
    val coverIncoming = MutableStateFlow<Uri?>(null)
    val toast = MutableStateFlow<Pair<String, Long>?>(null)
    /** Libro que hay que abrir en el lector apenas exista (lo usa la prueba automática). */
    val openBook = MutableStateFlow<String?>(null)
    /** Solo para la prueba automática: abrir el libro en modo continuo. */
    var testContinuous = false
    /** Pestaña que hay que mostrar (por ejemplo, al tocar la notificación del audiolibro). */
    val tab = MutableStateFlow<Int?>(null)
    fun toast(msg: String) { toast.value = msg to System.currentTimeMillis() }
}

fun openReader(ctx: Context, book: Book) {
    if (book.isPdf) { ctx.startActivity(Intent(ctx, com.sebas.zerkalo.reader.PdfReaderActivity::class.java).putExtra("id", book.id)); return }
    ctx.startActivity(Intent(ctx, ReaderActivity::class.java).putExtra("id", book.id).putExtra("continuo", AppEvents.testContinuous))
}

private val TABS = listOf("home" to "Biblioteca", "tansu" to "Tansu", "escuchar" to "Escuchar", "notas" to "Estudio", "sellos" to "Sellos")

@Composable
fun ZerkaloRoot(onReady: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ZerkaloApp
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Screen>() }
    val haze = rememberHazeState()
    val books by Library.books.collectAsState()

    /**
     * Un archivo abierto o compartido desde otra app: se trae y se abre directo para leer.
     * Los PDF llegan como documento (aparte de los estantes); los EPUB, a la Biblioteca.
     */
    suspend fun doImport(uri: Uri, openAfter: Boolean) {
        AppEvents.toast("Abriendo…")
        Importer.fromUri(ctx, app.readium, uri, asDoc = true)
            .onSuccess { b ->
                val now = Library.book(b.id) ?: b
                AppEvents.toast(if (now.doc) "«${now.title}» está en Documentos" else "«${now.title}» llegó a tu biblioteca")
                if (openAfter) openReader(ctx, now)
            }
            .onFailure { e -> AppEvents.toast(e.message ?: "No se pudo abrir el archivo") }
    }
    // Documentos viejos: los que no tienen notas se quitan solos; por los que tienen notas se pregunta
    var staleDocs by remember { mutableStateOf<List<Book>>(emptyList()) }
    LaunchedEffect(Unit) { staleDocs = withContext(Dispatchers.IO) { com.sebas.zerkalo.data.Docs.cleanup(ctx) } }
    staleDocs.firstOrNull()?.let { d ->
        ConfirmDialog(
            title = "¿Quitar «${d.title}»?",
            text = "Es un documento que no abres hace más de ${com.sebas.zerkalo.data.Docs.DAYS} días y tiene notas tuyas. Si lo quitas, se borran también.",
            ok = "Quitar", onOk = { Library.remove(d); staleDocs = staleDocs.drop(1) },
            other = "A la biblioteca", onOther = { com.sebas.zerkalo.data.Docs.toLibrary(d); staleDocs = staleDocs.drop(1) },
            cancel = "Conservar", onDismiss = { com.sebas.zerkalo.data.Docs.keep(d); staleDocs = staleDocs.drop(1) },
        )
    }
    // La importación sigue aunque cambie la pantalla (antes se cancelaba a medias y el libro nunca llegaba)
    val importScope = rememberCoroutineScope()

    // Elegir varios EPUB a la vez con el selector de Android
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Scanner.importUris(ctx, app.readium, uris)
    }
    var importSheet by remember { mutableStateOf(false) }
    // Primer arranque: un libro corto de bienvenida, para empezar a leer de inmediato.
    LaunchedEffect(Unit) {
        if (!Prefs.welcomed && Library.books.value.isEmpty()) {
            Prefs.welcomed = true
            // Sin cancelar: si la pantalla se vuelve a crear a mitad de camino, el libro igual termina de llegar.
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                val dest = File(Library.booksDir, "bienvenida.epub")
                ctx.assets.open("bienvenida.epub").use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                Importer.fromFile(app.readium, dest, "bienvenida", key = "zerkalo:bienvenida", source = "Zerkalo")
                    .onFailure { Log.e("Zerkalo", "bienvenida: ${it.message}") }
            }
        }
    }
    val toOpen by AppEvents.openBook.collectAsState()
    LaunchedEffect(toOpen, books) {
        val id = toOpen ?: return@LaunchedEffect
        // Prueba automática del lector de PDF: trae un PDF corto que viene dentro de la app
        if (id == "prueba-pdf" && books.none { it.id == id }) {
            withContext(Dispatchers.IO) {
                val dest = File(Library.booksDir, "prueba-pdf.pdf")
                ctx.assets.open("prueba.pdf").use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                Importer.fromPdf(dest, "prueba-pdf", "Prueba de PDF").onFailure { Log.e("Zerkalo", "ZK_PDF prueba: ${it.message}") }
            }
            return@LaunchedEffect
        }
        books.firstOrNull { it.id == id }?.let { AppEvents.openBook.value = null; openReader(ctx, it) }
    }
    val wantTab by AppEvents.tab.collectAsState()
    LaunchedEffect(wantTab) { wantTab?.let { stack.clear(); tab = it; AppEvents.tab.value = null } }
    val incoming by AppEvents.incoming.collectAsState()
    LaunchedEffect(incoming) {
        incoming?.let { u -> AppEvents.incoming.value = null; importScope.launch { doImport(u, openAfter = true) } }
    }

    // Imagen compartida (por ejemplo, desde Google Imágenes): portada del libro que la pidió, o se elige el libro.
    val coverIn by AppEvents.coverIncoming.collectAsState()
    var coverFor by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(coverIn) {
        val uri = coverIn ?: return@LaunchedEffect
        AppEvents.coverIncoming.value = null
        val pending = Prefs.pendingCover?.takeIf { System.currentTimeMillis() - it.second < 3 * 3600_000L }?.let { Library.book(it.first) }
        if (pending != null) {
            val ok = com.sebas.zerkalo.data.CoverSearch.applyUri(ctx, pending, uri)
            Prefs.pendingCover = null
            AppEvents.toast(if (ok) "Nueva portada para «${pending.title}»" else "No se pudo usar esa imagen")
            if (ok) { stack.clear(); tab = 0; stack.add(Screen.BookDetail(pending.id)) }
        } else coverFor = uri
    }

    val actions = remember {
        AppActions(
            open = { stack.add(it) },
            back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
            read = { openReader(ctx, it) },
            importEpub = { importSheet = true },
            toast = { AppEvents.toast(it) },
            goTab = { tab = it },
        )
    }

    BackHandler(enabled = stack.isNotEmpty() || tab != 0) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = 0
    }

    CompositionLocalProvider(LocalActions provides actions) {
        val p = LocalPalette.current
        Box(Modifier.fillMaxSize().paperBackground(p)) {
            // Pestañas: se cruzan con un fundido corto.
            AnimatedContent(
                targetState = tab, label = "tabs",
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            ) { t ->
                when (t) {
                    0 -> LibraryScreen(haze, onReady)
                    1 -> TansuScreen(haze)
                    2 -> EscucharScreen(haze)
                    3 -> NotasScreen(haze)
                    else -> SellosScreen(haze)
                }
            }
            // Reproductor pequeño mientras suena un audiolibro
            if (stack.isEmpty()) MiniPlayer(Modifier.align(Alignment.BottomCenter))
            TabBar(tab, haze, Modifier.align(Alignment.BottomCenter)) { i ->
                stack.clear(); tab = i
            }
            // Pantallas encima (ficha del libro, ajustes, taller, catálogos)
            stack.forEachIndexed { i, s ->
                androidx.compose.runtime.key(s, i) {
                    val vis = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = vis,
                        enter = slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(240)),
                        exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(200)),
                    ) {
                        Box(Modifier.fillMaxSize().paperBackground(p).clickable(remember { MutableInteractionSource() }, null) {}) {
                            when (s) {
                                is Screen.BookDetail -> BookScreen(s.id)
                                is Screen.Taller -> TallerScreen(s.id)
                                Screen.Settings -> SettingsScreen()
                                is Screen.Catalog -> CatalogScreen(s.url, s.name)
                                is Screen.Shelf -> ShelfScreen(s.lang, s.kind, s.value, s.title)
                                is Screen.Review -> ReviewScreen(s.practice, s.introOnly)
                                Screen.CardList -> CardListScreen()
                                Screen.Documents -> DocumentsScreen()
                            }
                        }
                    }
                }
            }
            Toast(Modifier.align(Alignment.BottomCenter))
        }
        if (importSheet) ImportSheet(
            onPickFiles = { importSheet = false; picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream", "application/zip")) },
            onClose = { importSheet = false },
        )
        coverFor?.let { uri -> PickBookForCover(uri) { coverFor = null } }
    }
}

/** Llegó una imagen compartida sin libro pendiente: se elige a qué libro ponérsela de portada. */
@Composable
private fun PickBookForCover(uri: Uri, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by Library.books.collectAsState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface,
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text("¿Para qué libro es esta portada?", style = Type.title.copy(fontSize = 20.sp), color = p.text)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp).padding(top = 10.dp)) {
                items(books.sortedByDescending { maxOf(it.lastOpened, it.addedAt) }, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            scope.launch {
                                val ok = com.sebas.zerkalo.data.CoverSearch.applyUri(ctx, b, uri)
                                AppEvents.toast(if (ok) "Nueva portada para «${b.title}»" else "No se pudo usar esa imagen")
                                onClose()
                            }
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(b, 36.dp, 52.dp, elevation = 2.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(b.title, style = Type.ui, color = p.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(b.author, style = Type.uiSmall, color = p.text2, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Barra de pestañas flotante de vidrio esmerilado. */
@Composable
private fun TabBar(selected: Int, haze: HazeState, modifier: Modifier, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(66.dp)
            .shadow(18.dp, RoundedCornerShape(33.dp), ambientColor = Color(0x40000000), spotColor = Color(0x55000000))
            .clip(RoundedCornerShape(33.dp)).hazeEffect(haze, glassStyle(p)),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZIcon(icon, if (on) p.text else p.text2, 23.dp)
                Text(name, style = Type.uiSmall.copy(fontSize = 11.5.sp), color = if (on) p.text else p.text2, modifier = Modifier.padding(top = 3.dp))
                Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(if (on) p.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun Toast(modifier: Modifier) {
    val p = LocalPalette.current
    val t by AppEvents.toast.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(t) {
        val cur = t ?: return@LaunchedEffect
        text = cur.first; visible = true
        delay(if (cur.first.length > 50) 4200 else 2600)
        visible = false
    }
    AnimatedVisibility(
        visible, modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 96.dp, start = 28.dp, end = 28.dp),
        enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        Box(
            Modifier.shadow(12.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(p.text)
                .padding(horizontal = 20.dp, vertical = 13.dp),
        ) { Text(text, style = Type.ui, color = p.bg) }
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
