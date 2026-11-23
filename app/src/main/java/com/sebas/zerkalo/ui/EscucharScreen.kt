package com.sebas.zerkalo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sebas.zerkalo.audio.Listen
import com.sebas.zerkalo.data.AudioBook
import com.sebas.zerkalo.data.AudioSort
import com.sebas.zerkalo.data.AudioStore
import com.sebas.zerkalo.data.LibriVox
import com.sebas.zerkalo.data.Prefs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

private const val PAGE = 40

/** Resultados de LibriVox por idioma y búsqueda, guardados mientras la app está abierta. */
private object AudioCache {
    val pages = HashMap<String, List<AudioBook>>()
}

/** Escuchar: audiolibros de LibriVox, leídos por personas (nunca voces sintéticas). */
@Composable
fun EscucharScreen(haze: HazeState) {
    val p = LocalPalette.current
    val mine by AudioStore.books.collectAsState()
    var lang by rememberSaveable { mutableStateOf(Prefs.tansuLang) }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AudioSort.POPULAR) }
    var open by remember { mutableStateOf<AudioBook?>(null) }
    var list by remember { mutableStateOf<List<AudioBook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }      // hay más resultados por cargar
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val key = "$lang|$query|$sort"

    LaunchedEffect(key) {
        AudioCache.pages[key]?.let { list = it; error = null; more = it.size % PAGE == 0 && it.isNotEmpty(); return@LaunchedEffect }
        list = null; error = null; more = false
        runCatching { LibriVox.search(lang, query.ifBlank { null }, rows = PAGE, sort = sort) }
            .onSuccess { AudioCache.pages[key] = it; list = it; more = it.size == PAGE }
            .onFailure { error = it.message ?: "LibriVox no respondió" }
    }

    fun loadMore() {
        val now = list ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            runCatching { LibriVox.search(lang, query.ifBlank { null }, rows = PAGE, sort = sort, page = now.size / PAGE + 1) }
                .onSuccess { next ->
                    val all = (now + next).distinctBy { it.id }
                    AudioCache.pages[key] = all; list = all; more = next.size == PAGE
                }
                .onFailure { AppEvents.toast("No se pudieron cargar más") }
            loadingMore = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), state = ScrollMemory.escuchar, contentPadding = PaddingValues(top = top + 18.dp, bottom = 230.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Caption("Audiolibros leídos por personas")
                    Text("Escuchar", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                }
                SearchField(input, { input = it; if (it.isBlank()) query = "" }, "Título o autor", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    query = input.trim()
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                    items(LibriVox.LANGS, key = { it.id }) { l -> SmallPill(l.name, l.id == lang) { lang = l.id } }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(AudioSort.values().toList(), key = { it.name }) { o -> SmallPill(o.label, o == sort) { sort = o } }
                }
            }
            if (mine.isNotEmpty() && query.isBlank()) {
                item(key = "mine-t") { SectionTitle("Escuchando", modifier = Modifier.padding(top = 26.dp)) }
                item(key = "mine") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(mine, key = { it.id }) { b -> AudioCard(b) { open = b } }
                    }
                }
            }
            item(key = "all-t") {
                SectionTitle(if (query.isNotBlank()) "Resultados" else sort.label, kicker = LibriVox.langName(lang).ifBlank { "Todos los idiomas" }, modifier = Modifier.padding(top = 26.dp))
            }
            when {
                error != null -> item(key = "err") {
                    Text(error ?: "", style = Type.ui, color = p.text2, modifier = Modifier.padding(20.dp))
                }
                list == null -> item(key = "load") {
                    Text("Buscando audiolibros…", style = Type.ui, color = p.text2, modifier = Modifier.padding(20.dp))
                }
                list!!.isEmpty() -> item(key = "none") {
                    Column(Modifier.padding(20.dp)) {
                        Text(if (lang == "all") "No hay audiolibros con eso." else "No hay audiolibros con eso en este idioma.", style = Type.ui, color = p.text2)
                        if (lang != "all" && query.isNotBlank()) PillButton("Buscar en todos los idiomas", { lang = "all" }, Modifier.padding(top = 12.dp), ghost = true, icon = "search")
                    }
                }
                else -> {
                    items(list!!, key = { "r" + it.id }) { b -> AudioRow(b, showLang = lang == "all") { open = b } }
                    if (more) item(key = "more") {
                        PillButton(if (loadingMore) "Cargando…" else "Cargar más", { loadMore() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), ghost = true, enabled = !loadingMore)
                    }
                }
            }
            item(key = "note") {
                Text(
                    "Los audiolibros vienen de LibriVox: voluntarios que graban con su propia voz libros de dominio público. Zerkalo no usa voces de IA.",
                    style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 30.dp),
                )
            }
        }
        CollapsingTop("Escuchar", ScrollMemory.escuchar, Modifier.align(Alignment.TopCenter))
    }
    open?.let { AudioSheet(it) { open = null } }
}

@Composable
private fun AudioCover(b: AudioBook, size: Dp, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.size(size).shadow(4.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(p.surface)) {
        AsyncImage(model = b.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AudioCard(b: AudioBook, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(120.dp).clickable(onClick = onClick)) {
        AudioCover(b, 120.dp)
        Text(b.title, style = Type.uiSmall.copy(fontSize = 13.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (b.tracks.isNotEmpty()) Text("Capítulo ${b.index + 1} de ${b.tracks.size}", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2)
    }
}

@Composable
private fun AudioRow(b: AudioBook, showLang: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AudioCover(b, 64.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(b.title, style = Type.ui.copy(fontSize = 16.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(b.author.ifBlank { null }, if (showLang) LibriVox.langName(b.lang).ifBlank { null } else null).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = Type.uiSmall, color = p.text2, maxLines = 1)
        }
        ZIcon("chevron", p.text2, 16.dp)
    }
}

private fun hms(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
}

/** Ficha de un audiolibro: muestra de 30 s, capítulos, descargar y escuchar. */
@Composable
fun AudioSheet(start: AudioBook, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by AudioStore.books.collectAsState()
    val dl by AudioStore.downloads.collectAsState()
    val now by Listen.state.collectAsState()
    var b by remember { mutableStateOf(stored.firstOrNull { it.id == start.id } ?: start) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(start.id) {
        if (b.tracks.isEmpty()) runCatching { LibriVox.tracks(b.id) }
            .onSuccess { t -> b = b.copy(tracks = t); if (t.isEmpty()) err = "Este audiolibro no tiene capítulos disponibles" }
            .onFailure { err = "No se pudieron cargar los capítulos" }
    }
    val playingThis = now.book?.id == b.id
    val started = b.position > 0 || b.index > 0
    val books by com.sebas.zerkalo.data.Library.books.collectAsState()
    var readable by remember(b.id) { mutableStateOf(AudioStore.bookFor(b)) }
    LaunchedEffect(b.id, b.linkedBook, books) { readable = AudioStore.findBook(b) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Row {
                AudioCover(b, 110.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Caption("LibriVox · voz humana", accent = true)
                    Text(b.title, style = Type.title.copy(fontSize = 20.sp, lineHeight = 25.sp), color = p.text, modifier = Modifier.padding(top = 4.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (b.author.isNotBlank()) Text(b.author, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
                    if (b.totalSeconds > 0) Text("${b.tracks.size} capítulos · ${hms(b.totalSeconds * 1000L)}", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (b.about.isNotBlank()) Text(b.about, style = Type.uiSmall, color = p.text2, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            err?.let { Text(it, style = Type.ui, color = p.accent, modifier = Modifier.padding(top = 12.dp)) }
            if (b.tracks.isNotEmpty()) {
                if (started && !playingThis) Text(
                    "Vas en el capítulo ${b.index + 1} de ${b.tracks.size} · ${hms(b.position)}",
                    style = Type.uiSmall, color = p.accent, modifier = Modifier.padding(top = 14.dp),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        if (playingThis && now.playing && !now.sample) "Pausar" else if (started) "Seguir escuchando" else "Escuchar",
                        {
                            when {
                                playingThis && now.sample -> Listen.keepListening()
                                playingThis -> Listen.toggle()
                                else -> Listen.play(ctx, b)
                            }
                        },
                        Modifier.weight(1f),
                        icon = if (playingThis && now.playing && !now.sample) "pause" else "play",
                    )
                    if (!playingThis && !started) PillButton("Muestra", { Listen.play(ctx, b, 0, 0, sample = true) }, ghost = true, icon = "speaker")
                }
                // Leer el mismo libro mientras suena: abre el EPUB de tu biblioteca y queda unido al audio.
                fun readAlong(book: com.sebas.zerkalo.data.Book) {
                    AudioStore.put(stored.firstOrNull { it.id == b.id } ?: b)
                    AudioStore.link(b.id, book.id)
                    val linked = (AudioStore.get(b.id) ?: b).copy(tracks = b.tracks)
                    if (!playingThis || now.sample) {
                        Listen.play(ctx, linked)
                    } else {
                        Listen.state.value = now.copy(book = linked)
                    }
                    onClose()
                    openReader(ctx, book)
                }
                val mine = readable
                var pick by remember { mutableStateOf(false) }
                if (mine != null) {
                    PillButton("Leer mientras escuchas", { readAlong(mine) }, Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true, icon = "home")
                    Text(
                        "¿No es «${mine.title}»? Elegir otro de mi biblioteca", style = Type.uiSmall, color = p.text2,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { pick = !pick }.padding(6.dp),
                    )
                } else if (b.tracks.isNotEmpty()) {
                    GetText(b) { book -> readAlong(book) }
                    Text(
                        "Ya lo tengo: elegirlo de mi biblioteca", style = Type.uiSmall, color = p.accent,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { pick = !pick }.padding(6.dp),
                    )
                }
                if (pick) PickFromLibrary(b, books) { book -> pick = false; readAlong(book) }
                val d = dl[b.id]
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        d == null -> PillButton("Descargar para oír sin internet", { AudioStore.put(b); AudioStore.download(b) }, Modifier.fillMaxWidth(), ghost = true, icon = "download")
                        d < 1f -> Text("Descargando… ${(d * 100).toInt()} %", style = Type.ui, color = p.text2)
                        else -> {
                            Text("Descargado en el teléfono", style = Type.ui, color = p.text2, modifier = Modifier.weight(1f))
                            Text("Borrar", style = Type.uiSmall, color = p.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AudioStore.deleteDownload(b.id) }.padding(6.dp))
                        }
                    }
                }
                if (stored.any { it.id == b.id }) Text(
                    "Quitar de Escuchando", style = Type.uiSmall, color = p.text2,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable {
                        if (playingThis) Listen.stop(); AudioStore.remove(b.id); onClose()
                    }.padding(6.dp),
                )
                Label("Capítulos")
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    itemsIndexed(b.tracks) { i, t ->
                        val cur = (playingThis && now.index == i) || (!playingThis && b.index == i && b.position > 0)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                if (playingThis && !now.sample) Listen.track(i) else Listen.play(ctx, b, i, b.spot(i))
                            }.padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", style = Type.uiSmall, color = if (cur) p.accent else p.text2, modifier = Modifier.width(30.dp))
                            Text(t.title, style = Type.ui, color = if (cur) p.accent else p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val at = if (playingThis) 0L else b.spot(i)
                            if (at > 0) Text("vas en ${hms(at)} · ", style = Type.uiSmall, color = p.accent)
                            if (t.seconds > 0) Text(hms(t.seconds * 1000L), style = Type.uiSmall, color = p.text2)
                        }
                    }
                }
            } else if (err == null) {
                Text("Cargando capítulos…", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

/** Reproductor pequeño, flotando sobre la barra de pestañas mientras suena un audiolibro. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val st by Listen.state.collectAsState()
    var full by remember { mutableStateOf(false) }
    val b = st.book
    AnimatedVisibility(b != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        if (b == null) return@AnimatedVisibility
        Row(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 86.dp).fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(p.surface)
                .clickable { full = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioCover(b, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(b.tracks.getOrNull(st.index)?.title ?: b.title, style = Type.ui, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b.title, style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { Listen.toggle() }, contentAlignment = Alignment.Center) {
                ZIcon(if (st.playing) "pause" else "play", p.text, 22.dp)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { Listen.stop() }, contentAlignment = Alignment.Center) {
                ZIcon("close", p.text2, 16.dp)
            }
        }
    }
    if (full) PlayerSheet { full = false }
}

/** Reproductor completo: avance, saltos de 30 s, velocidad y temporizador para dormir. */
@Composable
fun PlayerSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val st by Listen.state.collectAsState()
    val b = st.book ?: run { onClose(); return }
    var drag by remember { mutableStateOf<Float?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AudioCover(b, 200.dp)
            Text(b.tracks.getOrNull(st.index)?.title ?: "", style = Type.title.copy(fontSize = 19.sp), color = p.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
            Text("${b.title} · ${b.author}", style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            Text("Capítulo ${st.index + 1} de ${b.tracks.size}", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 2.dp))
            val dur = st.duration.coerceAtLeast(1)
            ZSlider(
                value = drag ?: (st.position / dur.toFloat()), onChange = { drag = it },
                onFinished = { drag?.let { Listen.seekTo((it * dur).toLong()) }; drag = null },
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(hms(drag?.let { (it * dur).toLong() } ?: st.position), style = Type.uiSmall, color = p.text2, modifier = Modifier.weight(1f))
                Text(hms(st.duration), style = Type.uiSmall, color = p.text2)
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RoundText("−30") { Listen.seekBy(-30_000) }
                Box(Modifier.size(72.dp).clip(CircleShape).background(p.text).clickable { Listen.toggle() }, contentAlignment = Alignment.Center) {
                    ZIcon(if (st.playing) "pause" else "play", p.bg, 30.dp)
                }
                RoundText("+30") { Listen.seekBy(30_000) }
            }
            if (st.buffering) Text("Cargando…", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            st.error?.let { Text(it, style = Type.uiSmall, color = p.accent, modifier = Modifier.padding(top = 6.dp)) }
            Label("Velocidad")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0.8f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f)) { v ->
                    SmallPill(if (v == 1f) "1×" else "${v}×".replace(".0×", "×"), kotlin.math.abs(st.speed - v) < 0.01f) { Listen.speed(v) }
                }
            }
            Label("Dormir", when {
                st.sleepEndOfTrack -> "al terminar el capítulo"
                st.sleepAt > 0 -> "en ${((st.sleepAt - System.currentTimeMillis()) / 60_000 + 1)} min"
                else -> "apagado"
            })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0 to "No", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hora", -1 to "Fin del capítulo")) { (m, name) ->
                    val on = (m == 0 && st.sleepAt == 0L && !st.sleepEndOfTrack) || (m == -1 && st.sleepEndOfTrack)
                    SmallPill(name, on) { Listen.sleep(m) }
                }
            }
        }
    }
}

@Composable
private fun RoundText(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(52.dp).clip(CircleShape).background(p.card.copy(alpha = 0.7f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Type.ui, color = p.text)
    }
}

@Composable
private fun Label(text: String, right: String = "") {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp)) {
        Text(text, style = Type.ui.copy(fontSize = 15.sp), color = p.text, modifier = Modifier.weight(1f))
        if (right.isNotBlank()) Text(right, style = Type.uiSmall, color = p.text2)
    }
}


/** Conseguir el libro en texto del audiolibro, sin parar el audio. */
@Composable
private fun GetText(b: AudioBook, onReady: (com.sebas.zerkalo.data.Book) -> Unit) {
    val p = LocalPalette.current
    val core = (LocalContext.current.applicationContext as com.sebas.zerkalo.ZerkaloApp).readium
    var open by remember { mutableStateOf(false) }
    var options by remember(b.id) { mutableStateOf<List<com.sebas.zerkalo.data.AudioText.Option>?>(null) }
    var chosen by remember(b.id) { mutableStateOf<String?>(null) }
    val states by Downloads.states.collectAsState()
    if (!open) {
        PillButton("Conseguir el libro para leer mientras escuchas", { open = true }, Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true, icon = "download")
        return
    }
    LaunchedEffect(b.id) { options = runCatching { com.sebas.zerkalo.data.AudioText.find(b) }.getOrDefault(emptyList()) }
    // Cuando termina la descarga elegida: queda unido al audio y se abre
    val done = chosen?.let { states[it]?.book }
    LaunchedEffect(done?.id) { done?.let(onReady) }
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp)) {
        Text("Leer mientras escuchas", style = Type.title.copy(fontSize = 17.sp), color = p.text)
        val list = options
        when {
            list == null -> Text("Buscando el texto en Project Gutenberg y Wikisource…", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            list.isEmpty() -> Text("No encontré este libro en texto libre. Si lo tienes, impórtalo y tócalo aquí de nuevo.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
            else -> {
                if (list.any { it.exact }) Text("La primera es la misma edición que leyó el narrador: así el texto cuadra mejor con el audio.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                list.forEach { o ->
                    val st = states[o.item.key]
                    Row(
                        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = st?.running != true) {
                                chosen = o.item.key
                                st?.book?.let(onReady) ?: Downloads.start(core, o.item)
                            }.padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(o.item.title, style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(o.item.source, if (o.exact) "la edición del audio" else o.item.author).filter { it.isNotBlank() }.joinToString(" · "),
                                style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            when {
                                st?.running == true -> st!!.progress?.let { "${(it * 100).toInt()} %" } ?: "…"
                                st?.error != null -> "Reintentar"
                                st?.book != null -> "Abrir"
                                else -> "Descargar"
                            },
                            style = Type.uiSmall, color = p.accent,
                        )
                    }
                    st?.error?.let { Text(it, style = Type.uiSmall, color = p.accent) }
                }
                Text("El audio sigue sonando mientras se descarga.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}


/** Unir a mano: los libros de tu biblioteca, los más parecidos primero, con un buscador con margen de error. */
@Composable
private fun PickFromLibrary(b: AudioBook, books: List<com.sebas.zerkalo.data.Book>, onPick: (com.sebas.zerkalo.data.Book) -> Unit) {
    val p = LocalPalette.current
    var q by remember { mutableStateOf("") }
    val list = remember(books, q) {
        val ref = q.ifBlank { b.title + " " + b.author }
        val want = com.sebas.zerkalo.data.Fuzzy.tokens(ref)
        books.map { it to com.sebas.zerkalo.data.Fuzzy.score(want, com.sebas.zerkalo.data.Fuzzy.tokens(it.title + " " + it.author)) }
            .filter { q.isBlank() || it.second >= 0.5 }.sortedByDescending { it.second }.take(8).map { it.first }
    }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.6f)).padding(12.dp)) {
        SearchField(q, { q = it }, "Buscar en mi biblioteca", Modifier.fillMaxWidth()) {}
        if (list.isEmpty()) Text("No encontré nada parecido.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp))
        list.forEach { book ->
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(book) }.padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = Type.uiSmall, color = p.text2, maxLines = 1)
                }
                Text("Unir", style = Type.uiSmall, color = p.accent)
            }
        }
    }
}
