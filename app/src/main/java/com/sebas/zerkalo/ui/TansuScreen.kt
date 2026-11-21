package com.sebas.zerkalo.ui

import android.util.Log
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.ZerkaloApp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.CatalogItem
import com.sebas.zerkalo.data.Catalogs
import com.sebas.zerkalo.data.Importer
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.OpdsCatalog
import com.sebas.zerkalo.data.Page
import com.sebas.zerkalo.data.Prefs
import com.sebas.zerkalo.data.ReadiumCore
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RowState(val items: List<CatalogItem> = emptyList(), val loading: Boolean = true, val error: String? = null, val next: String? = null)

/** Descargas en curso: siguen aunque se cierre la hoja del libro. */
object Downloads {
    data class State(val progress: Float? = null, val book: Book? = null, val error: String? = null, val running: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val states = MutableStateFlow<Map<String, State>>(emptyMap())

    fun start(core: ReadiumCore, item: CatalogItem) {
        if (states.value[item.key]?.running == true) return
        set(item.key, State(running = true))
        scope.launch {
            Importer.fromCatalog(core, item) { p -> set(item.key, State(progress = p, running = true)) }
                .onSuccess { b -> set(item.key, State(book = b)); AppEvents.toast("«${b.title}» está en tu estante") }
                .onFailure { e -> set(item.key, State(error = e.message ?: "No se pudo descargar")) }
        }
    }

    private fun set(k: String, s: State) = states.update { it + (k to s) }
}

/**
 * Las filas del Tansu se cargan una sola vez por sesión, fuera de la pantalla:
 * si deslizas rápido o cambias de pestaña, la carga sigue y no vuelve a empezar.
 */
object CatalogStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val rows = MutableStateFlow<Map<String, RowState>>(emptyMap())
    private val running = HashSet<String>()

    fun ensure(key: String, fetch: suspend () -> Page, force: Boolean = false) {
        synchronized(running) {
            val cur = rows.value[key]
            if (!force && cur != null && (cur.error == null) && (!cur.loading || key in running)) return
            if (key in running) return
            running += key
        }
        val cached = rows.value[key]?.takeIf { it.items.isNotEmpty() } ?: Catalogs.cached(key)?.let { RowState(it.items, next = it.next) }
        rows.update { it + (key to (cached ?: RowState()).copy(loading = true, error = null)) }
        scope.launch {
            val t0 = System.currentTimeMillis()
            val st = try {
                val p = Catalogs.load(key, fetch)
                Log.i("Zerkalo", "ZK_TANSU $key ${p.items.size} en ${System.currentTimeMillis() - t0} ms")
                RowState(p.items, loading = false, next = p.next)
            } catch (e: Exception) {
                Log.w("Zerkalo", "ZK_TANSU_ERROR $key ${e.message}")
                (rows.value[key] ?: RowState()).copy(loading = false, error = e.message ?: "No se pudo conectar")
            }
            rows.update { it + (key to st) }
            synchronized(running) { running -= key }
        }
    }
}

@Composable
private fun rememberRow(key: String, fetch: suspend () -> Page): RowState {
    val all by CatalogStore.rows.collectAsState()
    LaunchedEffect(key) { CatalogStore.ensure(key, fetch) }
    return all[key] ?: RowState()
}

@Composable
fun TansuScreen(haze: HazeState) {
    val p = LocalPalette.current
    val a = LocalActions.current
    var lang by rememberSaveable { mutableStateOf(Prefs.tansuLang) }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<CatalogItem?>(null) }
    var catalogsSheet by remember { mutableStateOf(false) }
    val cats by Prefs.catalogs.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), state = listState, contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Caption("Libros libres del mundo")
                        Text("Tansu", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    }
                    CircleButton("folder", { catalogsSheet = true })
                }
                SearchField(input, { input = it; if (it.isBlank()) query = "" }, "Título, autor o tema", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    query = input.trim()
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                    items(Catalogs.LANGS, key = { it.id }) { l ->
                        SmallPill(l.name, l.id == lang) { lang = l.id; Prefs.tansuLang = l.id }
                    }
                }
            }
            if (query.isNotEmpty()) {
                item(key = "sg$query$lang") { TansuRow("En Project Gutenberg", "", "pg-s:$lang:$query", { Catalogs.gutenberg(lang, search = query) }, "Sin resultados en este idioma.") { preview = it } }
                item(key = "sw$query$lang") { TansuRow("En Wikisource", "", "ws:$lang:$query", { Catalogs.wikisource(query, lang) }) { preview = it } }
                item(key = "si$query$lang") { TansuRow("En Internet Archive", "Dominio público", "ia-s:$lang:$query", { Catalogs.internetArchive(lang, query) }) { preview = it } }
                item(key = "so$query") { TansuRow("Libros académicos", "OAPEN", "oa:$query", { Catalogs.oapen(query) }) { preview = it } }
            } else {
                item(key = "pop$lang") { TansuRow("Los más leídos", "Project Gutenberg", "pg:$lang", { Catalogs.gutenberg(lang) }) { preview = it } }
                if (lang == "es") item(key = "tx") { TansuRow("Biblioteca en español", "textos.info", "tx", { Catalogs.opdsBooks(Catalogs.TEXTOS, "textos.info") }) { preview = it } }
                Catalogs.TOPICS.take(6).forEach { t ->
                    item(key = "t${t.id}$lang") { TansuRow(t.name, "", "pg:$lang:${t.id}", { Catalogs.gutenberg(lang, topic = t.id) }) { preview = it } }
                }
                item(key = "ug") { TansuRow("Licencias abiertas", "Unglue.it", "ug", { Catalogs.opdsBooks(Catalogs.UNGLUE, "Unglue.it") }) { preview = it } }
                item(key = "ia$lang") { TansuRow("Dominio público escaneado", "Internet Archive", "ia:$lang", { Catalogs.internetArchive(lang) }) { preview = it } }
                if (lang == "en") item(key = "se") { TansuRow("Recién editados", "Standard Ebooks", "se", { Catalogs.standardEbooks() }) { preview = it } }
                item(key = "oa$lang") {
                    val term = mapOf("es" to "historia", "fr" to "histoire", "pt" to "história", "de" to "Geschichte", "it" to "storia")[lang] ?: "history"
                    TansuRow(if (lang == "es") "Ensayo y ciencia abiertos" else "Open access", "OAPEN", "oa:$term", { Catalogs.oapen(term) }) { preview = it }
                }
                item(key = "mine") {
                    val all = listOf(OpdsCatalog("Unglue.it", Catalogs.UNGLUE), OpdsCatalog("textos.info", Catalogs.TEXTOS)) + cats
                    Column(Modifier.padding(top = 30.dp)) {
                        SectionTitle("Explorar catálogos")
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            items(all) { c -> SmallPill(c.name, false) { a.open(Screen.Catalog(c.url, c.name)) } }
                        }
                    }
                }
            }
            item(key = "note") {
                Text(
                    "Zerkalo no guarda libros: los trae directo de Project Gutenberg, Wikisource, textos.info, Unglue.it, Internet Archive, OAPEN, Standard Ebooks y los catálogos que agregues. Todo es de dominio público o con licencia abierta.",
                    style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 30.dp),
                )
            }
        }
        CollapsingTop("Tansu", listState, Modifier.align(Alignment.TopCenter))
        ProgressiveEdge(haze, top = false, height = 150.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }
    preview?.let { PreviewSheet(it) { preview = null } }
    if (catalogsSheet) CatalogsSheet { catalogsSheet = false }
}

@Composable
private fun TansuRow(title: String, kicker: String, key: String, fetch: suspend () -> Page, empty: String? = null, onItem: (CatalogItem) -> Unit) {
    val st = rememberRow(key, fetch)
    // Una biblioteca que no responde o no tiene nada se oculta sola (antes mostraba un error).
    if (empty == null && !st.loading && st.items.isEmpty()) return
    CatalogRow(title, kicker, st, empty ?: "", onRetry = { CatalogStore.ensure(key, fetch, force = true) }, onItem = onItem)
}

@Composable
private fun CatalogRow(title: String, kicker: String, st: RowState, empty: String = "Nada por aquí todavía.", onRetry: () -> Unit = {}, onItem: (CatalogItem) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 28.dp)) {
        SectionTitle(title, kicker = kicker)
        Gap(14)
        when {
            st.items.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(st.items, key = { it.key }) { ItemCard(it) { onItem(it) } }
            }
            st.loading -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), userScrollEnabled = false) {
                items(4) { Box(Modifier.size(100.dp, 148.dp).clip(RoundedCornerShape(6.dp)).background(p.surface.copy(alpha = 0.8f))) }
            }
            st.error != null -> Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(st.error, style = Type.uiSmall, color = p.text2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                SmallPill("Reintentar", false) { onRetry() }
            }
            else -> Text(empty, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun ItemCard(it: CatalogItem, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        ItemCover(it, 100.dp, 148.dp)
        Text(it.title, style = Type.uiSmall.copy(fontSize = 13.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(it.author.ifBlank { it.source }, style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PreviewSheet(item: CatalogItem, onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val core = (LocalContext.current.applicationContext as ZerkaloApp).readium
    val books by Library.books.collectAsState()
    val have = books.firstOrNull { it.key == item.key }
    val dl by Downloads.states.collectAsState()
    val st = dl[item.key]
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Row {
                ItemCover(item, 110.dp, 162.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Caption(item.source, accent = true)
                    Text(item.title, style = Type.title.copy(fontSize = 21.sp, lineHeight = 26.sp), color = p.text, modifier = Modifier.padding(top = 4.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (item.author.isNotBlank()) Text(item.author, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Catalogs.LANGS.firstOrNull { it.id == item.lang }?.let { Chip(it.name) }
                        Chip("EPUB libre")
                    }
                }
            }
            if (item.summary.isNotBlank()) Text(item.summary.take(700), style = Type.body.copy(fontSize = 14.5.sp), color = p.text.copy(alpha = 0.88f), modifier = Modifier.padding(top = 16.dp))
            Gap(20)
            when {
                have != null -> PillButton("Leer ahora", { onClose(); a.read(have) }, Modifier.fillMaxWidth())
                st?.running == true -> {
                    PillButton("Descargando…", {}, Modifier.fillMaxWidth(), enabled = false)
                    Gap(12)
                    ProgressLine(st.progress ?: 0.3f, Modifier.fillMaxWidth())
                }
                else -> {
                    PillButton(if (st?.error != null) "Intentar de nuevo" else "Agregar a mi biblioteca", { Downloads.start(core, item) }, Modifier.fillMaxWidth(), icon = "download")
                    st?.error?.let { Text(it, style = Type.uiSmall, color = p.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) }
                }
            }
            Text(
                when (item.source) {
                    "Wikisource" -> "Wikisource prepara el EPUB al momento: puede tardar un poco."
                    "Internet Archive" -> "Es un escaneo con el texto reconocido automáticamente: puede traer algunos errores."
                    else -> "Se descarga directo de la biblioteca de origen."
                },
                style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun CatalogsSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val scope = rememberCoroutineScope()
    val cats by Prefs.catalogs.collectAsState()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 34.dp)) {
            Text("Mis catálogos OPDS", style = Type.title.copy(fontSize = 21.sp), color = p.text)
            Text("Muchas bibliotecas libres publican un catálogo OPDS. Pega su dirección y aparecerá en el Tansu.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            cats.forEachIndexed { i, c ->
                ListRow(c.name, c.url, onClick = { onClose(); a.open(Screen.Catalog(c.url, c.name)) }) {
                    ZIcon("trash", p.text2, 20.dp, modifier = Modifier.clickable { Prefs.setCatalogs(cats.filterIndexed { j, _ -> j != i }) })
                }
            }
            Gap(12)
            SearchField(url, { url = it }, "https://… catálogo OPDS")
            Gap(12)
            PillButton(if (busy) "Revisando…" else "Agregar catálogo", {
                val u = url.trim()
                if (!u.startsWith("http")) { a.toast("Escribe una dirección completa (https://…)"); return@PillButton }
                busy = true
                scope.launch {
                    try {
                        val r = Catalogs.opds(u)
                        Prefs.setCatalogs(cats + OpdsCatalog(r.title.ifBlank { java.net.URI(u).host ?: u }, u))
                        url = ""; a.toast("Catálogo agregado")
                    } catch (e: Exception) {
                        a.toast("No parece un catálogo OPDS válido")
                    }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/** Navegar un catálogo OPDS (secciones y libros). */
@Composable
fun CatalogScreen(url: String, name: String) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val st = rememberRow("opds:$url") { Catalogs.opds(url, name) }
    val next = st.next
    var preview by remember { mutableStateOf<CatalogItem?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navs = st.items.filter { it.isNav }
    val books = st.items.filter { !it.isNav }
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 10.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column {
                CircleButton("back", a.back)
                Text(name, style = Type.title.copy(fontSize = 28.sp), color = p.text, modifier = Modifier.padding(top = 12.dp))
                if (st.loading && st.items.isEmpty()) Text("Abriendo el catálogo…", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 20.dp))
                st.error?.let { Text("No se pudo abrir: $it", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 20.dp)) }
                if (navs.isNotEmpty()) Card(Modifier.padding(top = 16.dp), PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                    Column { navs.forEach { n -> ListRow(n.title, onClick = { a.open(Screen.Catalog(n.navUrl!!, n.title)) }) { ZIcon("chevron", p.text2, 16.dp) } } }
                }
            }
        }
        items(books, key = { it.key }) { ItemCard(it) { preview = it } }
        next?.let { n ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                PillButton("Siguiente página", { a.open(Screen.Catalog(n, name)) }, ghost = true)
            }
        }
    }
    preview?.let { PreviewSheet(it) { preview = null } }
}
