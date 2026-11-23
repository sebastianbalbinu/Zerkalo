package com.sebas.zerkalo.reader

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Mark
import com.sebas.zerkalo.ui.Caption
import com.sebas.zerkalo.ui.EnsoImage
import com.sebas.zerkalo.ui.Fonts
import com.sebas.zerkalo.ui.LocalPalette
import com.sebas.zerkalo.ui.PillButton
import com.sebas.zerkalo.ui.SmallPill
import com.sebas.zerkalo.ui.Seal
import com.sebas.zerkalo.ui.Segmented
import com.sebas.zerkalo.ui.Type
import com.sebas.zerkalo.ui.ZIcon
import com.sebas.zerkalo.ui.ZSwitch
import com.sebas.zerkalo.ui.ZSlider
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.sebas.zerkalo.ui.ZerkaloTheme
import com.sebas.zerkalo.ui.highlightColor
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

@Composable
fun ReaderOverlay(ui: ReaderUi, act: ReaderActions) {
    ZerkaloTheme {
        CompositionLocalProvider(LocalPalette provides ui.palette) { Overlay(ui, act) }
    }
}

@Composable
private fun Overlay(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val listenState by com.sebas.zerkalo.audio.Listen.state.collectAsState()
    val linkedAudio = remember(listenState.book?.id) { act.linkedAudio() }
    val listening = linkedAudio != null && listenState.book?.id == linkedAudio.id && listenState.playing
    Box(Modifier.fillMaxSize()) {
        if (ui.loading || ui.error != null) {
            Column(Modifier.fillMaxSize().background(p.bg).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EnsoImage(p.isDark, 72.dp)
                Spacer(Modifier.height(16.dp))
                if (ui.error == null) Caption("Abriendo el libro")
                else {
                    Text("No se pudo abrir", style = Type.title, color = p.text)
                    Text(ui.error ?: "", style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(20.dp))
                    PillButton("Volver", act.close)
                }
            }
            return@Box
        }

        // Regla de lectura: atenúa todo menos la franja que estás leyendo.
        if (ui.settings.ruler && !ui.controls) ReadingRuler(ui)

        // Oscurecer por debajo del mínimo del teléfono (no recibe toques).
        if (ui.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ui.dim)))

        // Brillo deslizando el dedo por el borde izquierdo, como en Moon+ Reader.
        BrightnessEdge(ui, act, Modifier.align(Alignment.CenterStart))

        // Cinta del shiori en la esquina, como un marcapáginas de tela.
        AnimatedVisibility(ui.bookmarked, Modifier.align(Alignment.TopEnd).padding(end = 28.dp), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            Box(Modifier.size(14.dp, 54.dp).background(p.accent))
        }

        // Hora y avance junto a la cámara, en la franja de arriba (así el texto llega hasta abajo)
        AnimatedVisibility(!ui.controls && ui.settings.info, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
            val info = Type.uiSmall.copy(fontSize = 11.sp)
            val c = p.text2.copy(alpha = 0.65f)
            val gap = (ui.camHalf + 10f).dp
            Row(Modifier.fillMaxWidth().height(ui.band.coerceAtLeast(18f).dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(ui.camX).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    Text(ui.clock, style = info, color = c, maxLines = 1, modifier = Modifier.padding(end = gap))
                }
                Box(Modifier.weight(1f - ui.camX).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text("${(ui.progress * 100).toInt()} %", style = info, color = c, maxLines = 1, modifier = Modifier.padding(start = gap))
                }
            }
        }

        // Modo traducir: aviso y botón para salir sin tener que abrir el menú
        AnimatedVisibility(!ui.controls && ui.studyTap && ui.lookup == null, Modifier.align(Alignment.BottomStart), enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = 0.92f)).clickable { act.toggleStudy() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZIcon("close", Color.White, 12.dp)
                Text("Salir de traducir", style = Type.uiSmall.copy(fontSize = 12.sp), color = Color.White, modifier = Modifier.padding(start = 6.dp))
            }
        }

        // Audiolibro de este libro: un reproductor pequeño flota sobre el texto mientras lees
        val ownAudio = listenState.book?.takeIf { linkedAudio != null && it.id == linkedAudio.id && !listenState.sample }
        AnimatedVisibility(
            ownAudio != null && !ui.controls && ui.sheet == null && ui.lookup == null, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(300)) + slideInVertically { it / 2 }, exit = fadeOut(tween(150)) + slideOutVertically { it / 2 },
        ) {
            ownAudio?.let { MiniPlayer(it, listenState, act, Modifier.padding(bottom = if (ui.studyTap) 40.dp else 0.dp)) { ui.sheet = "player" } }
        }

        // Barra superior
        AnimatedVisibility(ui.controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(200)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(180)) + slideOutVertically { -it / 3 }) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0.92f), p.bg.copy(alpha = 0f))))) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundPill("back", act.close)
                    Text(
                        ui.chapter, style = Type.uiSmall.copy(fontSize = 14.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    RoundPill("translate", act.toggleStudy, active = ui.studyTap)
                    Spacer(Modifier.width(8.dp))
                    RoundPill("escuchar", { ui.sheet = "listen" }, active = listening)
                    Spacer(Modifier.width(8.dp))
                    RoundPill("pair", { ui.sheet = "pair" }, active = ui.pairOn)
                }
            }
        }

        // Barra inferior: cápsulas flotantes
        AnimatedVisibility(ui.controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(200)) + slideInVertically { it / 3 }, exit = fadeOut(tween(180)) + slideOutVertically { it / 3 }) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg.copy(alpha = 0.94f), p.bg)))
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 10.dp),
            ) {
                // Audiolibro sonando: controles a mano y "ir al capítulo que suena"
                listenState.book?.let { ab ->
                    val track = ab.tracks.getOrNull(listenState.index)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 14.dp).shadow(6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
                            .background(p.surface).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZIcon("escuchar", p.accent, 18.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(track?.title ?: ab.title, style = Type.uiSmall, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Controles y sincronizar", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.accent,
                                modifier = Modifier.clickable { ui.sheet = "player" },
                            )
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { com.sebas.zerkalo.audio.Listen.seekBy(-15_000) }, contentAlignment = Alignment.Center) {
                            Text("−15", style = Type.uiSmall, color = p.text2)
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(p.text).clickable { com.sebas.zerkalo.audio.Listen.toggle() }, contentAlignment = Alignment.Center) {
                            ZIcon(if (listenState.playing) "pause" else "play", p.bg, 18.dp)
                        }
                    }
                }
                var drag by remember { mutableStateOf<Float?>(null) }
                ZSlider(
                    value = drag ?: ui.progress, onChange = { drag = it },
                    onFinished = { drag?.let(act.seek); drag = null },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(ui.chapterLeft, style = Type.uiSmall.copy(fontSize = 12.sp), color = p.text2, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${((drag ?: ui.progress) * 100).toInt()} %", style = Type.uiSmall.copy(fontSize = 12.sp), color = p.text2)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.height(48.dp).shadow(6.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(p.text)
                            .clickable { ui.say("Hoy llevas ${fmt(ui.minutesToday)} de lectura") }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZIcon("clock", p.bg, 19.dp, 1.7f)
                        Text(fmt(ui.minutesToday), style = Type.ui.copy(fontSize = 15.sp), color = p.bg, modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    RoundPill("toc", { ui.sheet = "toc" })
                    Spacer(Modifier.width(8.dp))
                    RoundPill("sun", { ui.sheet = "luz" })
                    Spacer(Modifier.width(8.dp))
                    RoundPill("aa", { ui.sheet = "type" })
                    Spacer(Modifier.width(8.dp))
                    RoundPill("shiori", act.toggleShiori, active = ui.bookmarked)
                }
            }
        }

        ReaderToast(ui, Modifier.align(Alignment.BottomCenter))

        // Aviso al llegar al borde de un capítulo en desplazamiento continuo
        AnimatedVisibility(ui.edgeHint != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 34.dp, start = 24.dp, end = 24.dp)
                    .shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                    .background(if (ui.edgeReady) p.accent else p.text).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ui.edgeHint ?: "", style = Type.ui, color = p.bg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        // Avance automático: control flotante (pausa y velocidad) aunque los controles estén ocultos
        AnimatedVisibility(ui.auto && !ui.controls, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(end = 12.dp, bottom = 26.dp)
                    .clip(RoundedCornerShape(22.dp)).background(p.text.copy(alpha = 0.82f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val s = ui.settings
                fun change(f: Float) {
                    act.settings(
                        if (s.scroll) s.copy(autoSpeed = (s.autoSpeed * f).coerceIn(4f, 400f))
                        else s.copy(autoPage = (s.autoPage / f).coerceIn(3f, 300f)),
                    )
                }
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { change(1 / 1.15f) }, contentAlignment = Alignment.Center) { Text("−", style = Type.ui.copy(fontSize = 20.sp), color = p.bg) }
                Text(if (s.scroll) "${s.autoSpeed.toInt()}" else "${s.autoPage.toInt()} s", style = Type.uiSmall, color = p.bg, modifier = Modifier.padding(horizontal = 4.dp))
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { change(1.15f) }, contentAlignment = Alignment.Center) { Text("+", style = Type.ui.copy(fontSize = 20.sp), color = p.bg) }
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { act.toggleAuto() }, contentAlignment = Alignment.Center) { ZIcon("pause", p.bg, 18.dp) }
            }
        }

        AnimatedVisibility(
            ui.lookup != null, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(160)) + slideInVertically { it / 2 }, exit = fadeOut(tween(140)) + slideOutVertically { it / 2 },
        ) {
            ui.lookup?.let { LookupCard(it, act) }
        }
    }

    when (ui.sheet) {
        "toc" -> TocSheet(ui, act) { ui.sheet = null }
        "type" -> TypeSheet(ui, act) { ui.sheet = null }
        "luz" -> LightSheet(ui, act) { ui.sheet = null }
        "fonts" -> FontSheet(ui, act) { ui.sheet = "type" }
        "pair" -> PairSheet(ui, act) { ui.sheet = null }
        "listen" -> ListenSheet(act) { ui.sheet = null }
        "player" -> PlayerSheet(act) { ui.sheet = null }
        "inks" -> InksSheet(act) { ui.sheet = null }
    }
    ui.activeMark?.let { MarkSheet(it, act) { ui.activeMark = null } }
    ui.noteFor?.let { NoteSheet(it, act) { ui.noteFor = null } }
    if (ui.finishedNow) FinishedSheet(ui.finishedStamp) { ui.finishedNow = false }
    ui.cardDraft?.let { CardSheet(it, act) { ui.cardDraft = null } }
}

private fun fmt(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60}"

@Composable
private fun RoundPill(icon: String, onClick: () -> Unit, active: Boolean = false) {
    val p = LocalPalette.current
    Box(
        Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(if (active) p.accent else p.text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ZIcon(icon, if (active) Color.White else p.bg, 21.dp) }
}

@Composable
private fun ReaderToast(ui: ReaderUi, modifier: Modifier) {
    val p = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(ui.toast) {
        val t = ui.toast ?: return@LaunchedEffect
        text = t.first; visible = true; delay(2200); visible = false
    }
    AnimatedVisibility(visible, modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 150.dp), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(p.text).padding(horizontal = 18.dp, vertical = 11.dp)) {
            Text(text, style = Type.ui, color = p.bg)
        }
    }
}

@Composable
private fun Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) { content() }
    }
}

@Composable
private fun TocSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val tab = ui.tocTab
    val shioris = ui.marks.filter { it.type == "shiori" }.sortedBy { it.progress }
    val allHls = ui.marks.filter { it.type == "hl" }.sortedBy { it.progress }
    val colors = allHls.map { it.color }.distinct()
    val hls = ui.marksFilter?.let { f -> allHls.filter { it.color == f } } ?: allHls
    // El índice se abre mostrando el capítulo actual; las marcas, donde las dejaste.
    LaunchedEffect(Unit) {
        if (tab == 0) ui.toc.indexOfFirst { it.title == ui.chapter }.takeIf { it > 2 }?.let { ui.tocState.scrollToItem(it - 2) }
    }
    Sheet(onClose) {
        Segmented(listOf("Índice", "Marcas · ${shioris.size + allHls.size}"), tab, { ui.tocTab = it })
        Spacer(Modifier.height(10.dp))
        if (tab == 1 && colors.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                item {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (ui.marksFilter == null) p.text else p.card.copy(alpha = 0.7f))
                            .clickable { ui.marksFilter = null }.padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text("Todas", style = Type.uiSmall, color = if (ui.marksFilter == null) p.bg else p.text) }
                }
                items(colors) { c ->
                    val on = ui.marksFilter == c
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) p.text else p.card.copy(alpha = 0.7f))
                            .clickable { ui.marksFilter = if (on) null else c }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(highlightColor(c)))
                        com.sebas.zerkalo.ui.inkName(c).takeIf { it.isNotBlank() }?.let { Text("  $it", style = Type.uiSmall, color = if (on) p.bg else p.text) }
                    }
                }
            }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), state = if (tab == 0) ui.tocState else ui.marksState) {
            if (tab == 0) {
                if (ui.toc.isEmpty()) item { Text("Este libro no trae índice.", style = Type.ui, color = p.text2, modifier = Modifier.padding(12.dp)) }
                items(ui.toc) { t ->
                    val cur = t.title == ui.chapter
                    Text(
                        t.title, style = Type.body.copy(fontSize = 16.sp), color = if (cur) p.accent else p.text,
                        modifier = Modifier.fillMaxWidth().clickable { act.goLink(t.link); onClose() }
                            .padding(start = (12 + t.depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    )
                }
            } else {
                if (shioris.isEmpty() && hls.isEmpty()) item {
                    Text("Aún no hay marcas. Pon un shiori con la cinta o mantén presionado el texto para subrayar.", style = Type.ui, color = p.text2, modifier = Modifier.padding(12.dp))
                }
                if (ui.marksFilter == null) items(shioris) { m ->
                    Row(Modifier.fillMaxWidth().clickable { locatorOf(m)?.let(act.go); onClose() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ZIcon("shiori", p.accent, 16.dp)
                        Text(m.chapter.ifBlank { "Shiori" }, style = Type.ui, color = p.text, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(m.progress * 100).toInt()} %", style = Type.uiSmall, color = p.text2)
                    }
                }
                items(hls) { m ->
                    Row(Modifier.fillMaxWidth().clickable { locatorOf(m)?.let(act.go); onClose() }.padding(12.dp)) {
                        Box(Modifier.width(3.dp).height(40.dp).background(highlightColor(m.color)))
                        Column(Modifier.padding(start = 12.dp)) {
                            com.sebas.zerkalo.ui.inkName(m.color).takeIf { it.isNotBlank() }?.let {
                                Text(it.uppercase(), style = Type.caption.copy(fontSize = 9.5.sp), color = highlightColor(m.color))
                            }
                            Text(m.text.take(180), style = Type.body.copy(fontSize = 14.5.sp), color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (m.note.isNotBlank()) Text(m.note, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun locatorOf(m: Mark): Locator? = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull()

@Composable
private fun FontSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = ui.settings
    val avail = remember { FontFiles.available(ctx) }
    val groups = listOf(
        "Sin serifa · las más cómodas en pantalla" to avail.filter { !ReaderFonts[it].serif },
        "Con serifa · de libro" to avail.filter { ReaderFonts[it].serif && ReaderFonts[it].css != null },
        "Del libro" to avail.filter { ReaderFonts[it].css == null },
    )
    Sheet(onClose) {
        Text("Letra", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Text("Todas son libres y gratuitas. Las marcadas con ● se eligieron por estudios de legibilidad.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp).padding(top = 6.dp)) {
            groups.forEach { (title, list) ->
                if (list.isEmpty()) return@forEach
                item(key = title) { Label(title) }
                items(list, key = { it }) { i ->
                    val f = ReaderFonts[i]
                    val sel = i == s.font
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (sel) p.text else p.card.copy(alpha = 0.55f))
                            .clickable { act.settings(ui.settings.copy(font = i)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name + if (i in setOf(5, 6, 10)) "  ●" else "", fontFamily = FontFiles.preview(ctx, i), fontSize = 18.sp, color = if (sel) p.bg else p.text)
                            Text(
                                "Así se ve un párrafo. " + f.note + if (FontFiles.hasSmallCaps(ctx, i)) " · versalitas reales" else "", fontFamily = FontFiles.preview(ctx, i), fontSize = 13.5.sp,
                                color = if (sel) p.bg.copy(alpha = 0.75f) else p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (sel) ZIcon("check", p.bg, 18.dp)
                    }
                }
            }
        }
    }
}

/** Tarjeta de traducción: aparece abajo sin tapar el texto que se está leyendo. */
@Composable
private fun LookupCard(l: Lookup, act: ReaderActions) {
    val p = LocalPalette.current
    var choose by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(10.dp)
            .shadow(18.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(p.surface)
            .clickable(enabled = false) {}.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                l.text, style = Type.title.copy(fontSize = if (l.text.length > 40) 17.sp else 22.sp, lineHeight = 26.sp), color = p.text,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { act.speak(l.text, l.from) }, contentAlignment = Alignment.Center) { ZIcon("speaker", p.text2, 20.dp) }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = act.closeLookup), contentAlignment = Alignment.Center) { ZIcon("close", p.text2, 18.dp) }
        }
        Row(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).clickable { choose = !choose }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Traductor.name(l.from.substringBefore('-'))}  →  ${Traductor.name(l.to)}", style = Type.uiSmall, color = p.accent)
            Text(if (choose) "  ▴" else "  ▾", style = Type.uiSmall, color = p.accent)
        }
        if (choose) LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Traductor.TARGETS) { (code, name) ->
                val on = code == l.to
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) p.text else p.card.copy(alpha = 0.7f))
                        .clickable { choose = false; act.retarget(code) }.padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text(name, style = Type.uiSmall, color = if (on) p.bg else p.text) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            l.error != null -> Text(l.error, style = Type.ui, color = p.text2)
            l.translation != null -> Text(l.translation, style = Type.body.copy(fontSize = 19.sp), color = p.text)
            else -> Text(l.status ?: "Traduciendo…", style = Type.ui, color = p.text2)
        }
        if (l.sentence.isNotBlank() && l.sentence != l.text) {
            if (l.sentenceTranslation == null) {
                Text(
                    "Traducir la frase", style = Type.uiSmall, color = p.accent,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = act.translateSentence).padding(vertical = 4.dp),
                )
            } else {
                Column(Modifier.padding(top = 10.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(l.sentence, style = Type.body.copy(fontSize = 14.sp, fontStyle = FontStyle.Italic), color = p.text2)
                    Text(l.sentenceTranslation, style = Type.body.copy(fontSize = 15.sp), color = p.text, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (l.translation != null) {
            Row(Modifier.padding(top = 12.dp, end = 8.dp)) {
                PillButton(
                    if (l.saved) "Guardada" else "Guardar palabra", act.saveWord, Modifier.weight(1f),
                    ghost = l.saved, icon = if (l.saved) "check" else "plus",
                )
                Spacer(Modifier.width(8.dp))
                PillButton(
                    if (l.carded) "Tarjeta lista" else "Hacer tarjeta", { if (!l.carded) act.wordCard() }, Modifier.weight(1f),
                    ghost = true, icon = if (l.carded) "check" else "notas",
                )
            }
        }
    }
}

/** Elegir la versión en otro idioma del mismo libro. */
@Composable
private fun PairSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val candidates = remember { act.pairCandidates() }
    Sheet(onClose) {
        Text("Dos idiomas", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Text(
            "Lee el libro junto a su versión en otro idioma: debajo con el teléfono vertical, al lado si lo giras. Las dos avanzan juntas.",
            style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 6.dp),
        )
        if (ui.hasPair) {
            Card2 {
                Caption("Pareja")
                Text(ui.pairTitle, style = Type.ui, color = p.text, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (ui.pairOn) "Ocultar" else "Mostrar", { act.togglePair(); onClose() }, Modifier.weight(1f), icon = "pair")
                if (ui.pairOn) PillButton("Alinear", { act.syncPair(); onClose() }, Modifier.weight(1f), ghost = true, icon = "swap")
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Casadas", style = Type.ui, color = p.text)
                    Text("Al pasar página en una, la otra también pasa", style = Type.uiSmall, color = p.text2)
                }
                ZSwitch(ui.pairLinked) { act.togglePairLink() }
            }
            Label("Orden y tamaño")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SmallPill("Intercambiar", false) { act.swapPair() } }
                items(listOf(50 to "Mitad y mitad", 60 to "60 / 40", 40 to "40 / 60")) { (v, name) ->
                    SmallPill(name, ui.pairRatio == v) { act.setPairRatio(v) }
                }
            }
            Text(
                "Quitar la pareja", style = Type.uiSmall, color = p.text2,
                modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { act.setPair(null); onClose() }.padding(6.dp),
            )
        }
        Label(if (ui.hasPair) "Cambiar por otro libro" else "Elige la otra versión")
        if (candidates.isEmpty()) {
            Text("Primero baja la otra versión desde el Tansu (por ejemplo, el mismo título en inglés) y vuelve aquí.", style = Type.ui, color = p.text2)
        } else LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(candidates, key = { it.id }) { b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { act.setPair(b); onClose() }.padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.sebas.zerkalo.ui.BookCover(b, 36.dp, 52.dp, elevation = 2.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(b.title, style = Type.ui, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(b.author, b.lang.takeIf { it.isNotBlank() }?.let { Traductor.name(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Escuchar el libro que estás leyendo, con la voz de una persona (LibriVox). */
@Composable
private fun ListenSheet(act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val linked = remember { act.linkedAudio() }
    var found by remember { mutableStateOf<List<com.sebas.zerkalo.data.AudioBook>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var other by remember { mutableStateOf(false) }   // buscar otro audiolibro aunque ya haya uno unido
    var q by remember { mutableStateOf("") }
    LaunchedEffect(other) { if (linked == null || other) found = act.findAudio() }
    var follow by remember { mutableStateOf(com.sebas.zerkalo.data.Prefs.audioFollow) }
    fun start(ab: com.sebas.zerkalo.data.AudioBook, here: Boolean = true) {
        if (ab.tracks.isNotEmpty()) { act.listen(ab, here); return }
        busy = ab.id
        scope.launch {
            val t = runCatching { com.sebas.zerkalo.data.LibriVox.tracks(ab.id) }.getOrDefault(emptyList())
            busy = null
            if (t.isNotEmpty()) act.listen(ab.copy(tracks = t), here)
        }
    }
    Sheet(onClose) {
        Text("Escuchar este libro", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Text("Audiolibros de LibriVox, leídos por personas. Empieza en el capítulo donde vas.", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        when {
            linked != null && !other -> {
                Card2 {
                    Caption("Tu audiolibro")
                    Text(linked.title, style = Type.ui, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    if (linked.author.isNotBlank()) Text(linked.author, style = Type.uiSmall, color = p.text2)
                }
                val started = linked.tracks.isNotEmpty() && (linked.position > 0 || linked.index > 0)
                if (started) PillButton(
                    "Seguir donde lo dejé · cap. ${linked.index + 1}", { start(linked, here = false) },
                    Modifier.fillMaxWidth().padding(top = 12.dp), icon = "play",
                )
                PillButton(
                    "Escuchar desde aquí", { start(linked) },
                    Modifier.fillMaxWidth().padding(top = if (started) 8.dp else 12.dp), icon = if (started) null else "play", ghost = started,
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("La página sigue al narrador", style = Type.ui, color = p.text, modifier = Modifier.weight(1f))
                    SmallPill(if (follow) "Sí" else "No", follow) {
                        follow = !follow
                        com.sebas.zerkalo.data.Prefs.audioFollow = follow
                    }
                }
                Text("Cuando el audio pasa de capítulo, el texto salta a ese capítulo.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "¿No es este? Unir otro audiolibro", style = Type.uiSmall, color = p.accent,
                    modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { other = true }.padding(vertical = 6.dp),
                )
            }
            found == null -> Text("Buscando en LibriVox…", style = Type.ui, color = p.text2)
            found!!.isEmpty() -> Text(
                "No encontré este libro en audio. LibriVox solo graba obras de dominio público. Prueba buscando con otras palabras:",
                style = Type.ui, color = p.text2,
            )
            else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(found!!, key = { it.id }) { ab ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { start(ab) }.padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        coil.compose.AsyncImage(
                            model = ab.cover, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(p.card),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(ab.title, style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (ab.author.isNotBlank()) Text(ab.author, style = Type.uiSmall, color = p.text2, maxLines = 1)
                        }
                        Text(if (busy == ab.id) "…" else "Escuchar", style = Type.uiSmall, color = p.accent)
                    }
                }
            }
        }
        if (linked == null || other) {
            com.sebas.zerkalo.ui.SearchField(q, { q = it }, "Buscar con otras palabras (título o autor)", Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (q.isNotBlank()) { found = null; scope.launch { found = act.searchAudio(q.trim()) } }
            }
        }
    }
}

/** Tus colores de subrayado: cambiar el nombre, el tono, agregar o quitar. */
@Composable
private fun InksSheet(act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val inks by com.sebas.zerkalo.data.Prefs.inks.collectAsState()
    var editing by remember { mutableStateOf<Int?>(null) } // índice; -1 = nuevo
    Sheet(onClose) {
      Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
        val rs by com.sebas.zerkalo.data.Prefs.reader.collectAsState()
        Text("Subrayados", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Label("Cómo se ven")
        Segmented(listOf("Marcador", "Lápiz", "Ondulado"), rs.markStyle, { act.settings(rs.copy(markStyle = it)) })
        Label("Colores")
        Text("Ponle un significado a cada color: por ejemplo Cita, Duda o Para el examen. Así puedes filtrarlos en Marcas.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        inks.forEachIndexed { i, ink ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { editing = i }.padding(vertical = 10.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(ink.argb)))
                Text(ink.name, style = Type.ui, color = p.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (inks.size > 1) Text("Quitar", style = Type.uiSmall, color = p.text2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    act.setInks(inks.filterIndexed { j, _ -> j != i }); editing = null
                }.padding(6.dp))
            }
            if (editing == i) InkEditor(ink, onSave = { n -> act.setInks(inks.mapIndexed { j, x -> if (j == i) n else x }); editing = null })
        }
        if (editing == -1) InkEditor(com.sebas.zerkalo.data.InkColor("", 0xFFB07CC6.toInt()), onSave = { n -> act.setInks(inks + n); editing = null })
        else PillButton("Agregar color", { editing = -1 }, Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true, icon = "plus")
      }
    }
}

@Composable
private fun InkEditor(start: com.sebas.zerkalo.data.InkColor, onSave: (com.sebas.zerkalo.data.InkColor) -> Unit) {
    val p = LocalPalette.current
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start.argb, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceIn(0.15f, 1f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceIn(0.3f, 1f)) }
    var name by remember { mutableStateOf(start.name) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(argb)))
            Box(Modifier.padding(start = 12.dp).weight(1f).clip(RoundedCornerShape(12.dp)).background(p.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (name.isEmpty()) Text("Nombre (por ejemplo: Cita)", style = Type.ui, color = p.text2)
                BasicTextField(name, { name = it.take(24) }, singleLine = true, textStyle = Type.ui.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
            }
        }
        Text("Tono", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 0.9f))) })))
        ZSlider(hue, { hue = it }, range = 0f..359f)
        Text("Intensidad", style = Type.uiSmall, color = p.text2)
        ZSlider(sat, { sat = it }, range = 0.15f..1f)
        Text("Luz", style = Type.uiSmall, color = p.text2)
        ZSlider(value, { value = it }, range = 0.3f..1f)
        PillButton("Guardar", { onSave(com.sebas.zerkalo.data.InkColor(name.trim().ifBlank { "Color" }, argb)) }, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp)) { content() }
}

@Composable
private fun TypeSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val s = ui.settings
    Sheet(onClose) {
      Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
        Text("Lectura", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Spacer(Modifier.height(12.dp))
        Segmented(listOf("Texto", "Página", "Comodidad"), ui.typeTab, { ui.typeTab = it })
        when (ui.typeTab) {
            // ---------- Texto: cómo se ven las letras ----------
            0 -> {
                Label("Letra")
                val ctx = LocalContext.current
                val font = ReaderFonts.getOrElse(s.font) { ReaderFonts[0] }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.7f)).clickable { ui.sheet = "fonts" }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(font.name, fontFamily = FontFiles.preview(ctx, s.font), fontSize = 19.sp, color = p.text)
                        Text(font.note, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Cambiar", style = Type.uiSmall, color = p.accent)
                    ZIcon("chevron", p.accent, 16.dp)
                }
                Label("Tamaño", "${s.size} %")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundPill("minus", { act.settings(s.copy(size = (s.size - 10).coerceAtLeast(70))) })
                    var v by remember(s.size) { mutableFloatStateOf(s.size.toFloat()) }
                    ZSlider(
                        value = v, onChange = { v = it }, range = 70f..200f,
                        onFinished = { act.settings(ui.settings.copy(size = (v / 5).toInt() * 5)) },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    RoundPill("plus", { act.settings(s.copy(size = (s.size + 10).coerceAtMost(200))) })
                }
                Label("Interlineado")
                val lhs = listOf(1.4f, 1.6f, 1.85f)
                Segmented(listOf("Compacto", "Medio", "Amplio"), lhs.indexOfFirst { kotlin.math.abs(it - s.lineHeight) < 0.05f }.coerceAtLeast(0), { act.settings(s.copy(lineHeight = lhs[it])) })
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Justificar el texto", style = Type.ui, color = p.text)
                        Text(if (s.justify) "Con guiones, como un libro impreso. Los poemas y las líneas angostas no se justifican" else "Apagado en todo el libro: alineado a la izquierda", style = Type.uiSmall, color = p.text2)
                    }
                    ZSwitch(s.justify) { act.settings(s.copy(justify = it)) }
                }
                InkPicker(ui, act)
                Label("Espacio entre párrafos")
                Segmented(listOf("Como el libro", "Separados", "Aireados"), s.paragraph, { act.settings(s.copy(paragraph = it)) })
                Label("Espacio entre letras", "más amplio ayuda con la dislexia")
                Segmented(listOf("Normal", "Amplio", "Muy amplio"), s.spacing, { act.settings(s.copy(spacing = it)) })
                SwitchRow(
                    "Modo dislexia",
                    "Letra Lexend, más espacio entre letras y palabras, interlineado amplio y sin justificar",
                    s.dyslexia,
                ) { on ->
                    act.settings(
                        if (on) ui.settings.copy(dyslexia = true, font = 6, spacing = 1, justify = false, lineHeight = 1.85f, paragraph = maxOf(ui.settings.paragraph, 1))
                        else ui.settings.copy(dyslexia = false, spacing = 0, justify = true, lineHeight = 1.6f),
                    )
                }
            }
            // ---------- Página: cómo se arma la página ----------
            1 -> {
                Label("Diagramación")
                val order = listOf(3, 0, 1, 2)
                Segmented(listOf("Ninguna", "Original", "Zerkalo", "Mía"), order.indexOf(s.layout).coerceAtLeast(0), { act.settings(s.copy(layout = order[it])) })
                Text(
                    when (s.layout) {
                        3 -> "El libro tal cual, sin ningún arreglo de Zerkalo."
                        0 -> "El diseño de la editorial. Zerkalo solo arregla lo que se rompe en el teléfono: versalitas, palabras de otro tamaño y huecos en líneas angostas."
                        2 -> "Tus ajustes se aplican a todo el libro, sin reglas automáticas."
                        else -> "Diagramación cuidada: poemas verso a verso, títulos proporcionados y justificado solo donde queda bien."
                    },
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp),
                )
                Label("Márgenes", "en milímetros")
                // Atajos que llenan los cuatro valores; luego se afina cada lado
                val presets = listOf(Triple("Estrechos", 2.5f, 0.8f), Triple("Normales", 4f, 1.5f), Triple("Amplios", 7f, 4f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { (name, side, tb) ->
                        val on = s.mmLeft == side && s.mmRight == side && s.mmTop == tb && s.mmBottom == tb
                        SmallPill(name, on) { act.settings(ui.settings.copy(mmLeft = side, mmRight = side, mmTop = tb, mmBottom = tb)) }
                    }
                }
                MmRow("Izquierda", s.mmLeft) { act.settings(ui.settings.copy(mmLeft = it)) }
                MmRow("Derecha", s.mmRight) { act.settings(ui.settings.copy(mmRight = it)) }
                MmRow("Arriba", s.mmTop) { act.settings(ui.settings.copy(mmTop = it)) }
                MmRow("Abajo", s.mmBottom) { act.settings(ui.settings.copy(mmBottom = it)) }
                SwitchRow(
                    "Respetar la cámara",
                    if (s.respectCamera) "El margen de arriba empieza debajo de la cámara" else "El texto sube hasta el borde de la pantalla; la hora queda encima",
                    s.respectCamera,
                ) { act.settings(ui.settings.copy(respectCamera = it)) }
                Text(
                    "En desplazamiento, el margen de arriba y el de abajo son la zona donde el texto se difumina al pasar.",
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp),
                )
                Label("Pasar página")
                Segmented(
                    listOf("Páginas", "Desplazamiento", "Continuo"), if (s.continuous) 2 else if (s.scroll) 1 else 0,
                    { act.settings(s.copy(scroll = it >= 1, continuous = it == 2)) },
                )
                Text(
                    when {
                        s.continuous -> "Todo el libro seguido, como en Books: bajas y encuentras todo. Por ahora, traducir tocando, dos idiomas y subrayar nuevo funcionan en Páginas y Desplazamiento."
                        s.scroll -> "Capítulo por capítulo; al final del capítulo, sigue deslizando."
                        else -> "Como un libro de papel: una página a la vez."
                    },
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp),
                )
                SwitchRow("Animación al pasar página", "Apágala si prefieres un cambio instantáneo", s.animate) { act.settings(ui.settings.copy(animate = it)) }
                SwitchRow("Hora y avance junto a la cámara", "Se ven arriba mientras lees", s.info) { act.settings(ui.settings.copy(info = it)) }
            }
            // ---------- Comodidad ----------
            else -> {
                SwitchRow("Regla de lectura", "Resalta la línea que lees; arrástrala desde la derecha", s.ruler) { act.settings(ui.settings.copy(ruler = it)) }
                SwitchRow("Botones de volumen", "Bajar volumen: siguiente · subir: anterior", s.volumeKeys) { act.settings(ui.settings.copy(volumeKeys = it)) }
                SwitchRow("Vibración suave al pasar página", "", s.haptic) { act.settings(ui.settings.copy(haptic = it)) }
                SwitchRow("Pausas para los ojos", "Un aviso suave cada 20 minutos de lectura", s.breaks) { act.settings(ui.settings.copy(breaks = it)) }
                Label("Avance automático", if (s.scroll) "${s.autoSpeed.toInt()} píxeles por segundo" else "una página cada ${s.autoPage.toInt()} s")
                if (s.scroll) {
                    var v by remember(s.autoSpeed) { mutableFloatStateOf(s.autoSpeed) }
                    ZSlider(v, { v = it }, range = 4f..200f, onFinished = { act.settings(ui.settings.copy(autoSpeed = v)) })
                } else {
                    var v by remember(s.autoPage) { mutableFloatStateOf(s.autoPage) }
                    ZSlider(v, { v = it }, range = 5f..180f, onFinished = { act.settings(ui.settings.copy(autoPage = v)) })
                }
                Text(
                    if (s.scroll) "El texto sube solo; tocar o deslizar lo pausa. Al acabar un capítulo sigue con el siguiente."
                    else "Pasa de página solo. En desplazamiento el texto sube suave, como un teleprompter.",
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 2.dp),
                )
                PillButton(if (ui.auto) "Detener avance automático" else "Empezar avance automático", { act.toggleAuto(); onClose() }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = if (ui.auto) "pause" else "auto")
                Text(
                    "Toques: izquierda o arriba, página anterior · derecha o abajo, siguiente · centro, menú. Con dos idiomas, las páginas se pasan por los lados. En modo traducir, toca una palabra; arriba o abajo abre el menú. El borde izquierdo cambia el brillo y atrás cierra lo abierto antes de salir del libro.",
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
      }
    }
}

/** Un margen en milímetros: − valor +, de medio en medio milímetro. */
@Composable
private fun MmRow(name: String, value: Float, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.ui, color = p.text, modifier = Modifier.weight(1f))
        Box(Modifier.size(38.dp).clip(CircleShape).background(p.card.copy(alpha = 0.7f)).clickable { onChange((value - 0.5f).coerceAtLeast(0f)) }, contentAlignment = Alignment.Center) {
            ZIcon("minus", p.text, 16.dp)
        }
        Text(
            (if (value % 1f == 0f) "${value.toInt()}" else "$value").replace('.', ',') + " mm",
            style = Type.ui, color = p.text, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp),
        )
        Box(Modifier.size(38.dp).clip(CircleShape).background(p.card.copy(alpha = 0.7f)).clickable { onChange((value + 0.5f).coerceAtMost(25f)) }, contentAlignment = Alignment.Center) {
            ZIcon("plus", p.text, 16.dp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.ui, color = p.text)
            if (sub.isNotBlank()) Text(sub, style = Type.uiSmall, color = p.text2)
        }
        ZSwitch(on, onChange)
    }
}

/** Franja de lectura: lo de arriba y abajo queda en penumbra. La manija de la derecha la mueve. */
@Composable
private fun ReadingRuler(ui: ReaderUi) {
    val p = LocalPalette.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxHeight
        // Unas dos líneas y media del tamaño actual de letra
        val band = (16f * ui.settings.size / 100f * ui.settings.lineHeight * 2.5f).dp
        val center = total * ui.rulerY
        val top = (center - band / 2).coerceAtLeast(0.dp)
        val shade = p.bg.copy(alpha = if (p.isDark) 0.62f else 0.55f)
        Box(Modifier.fillMaxWidth().height(top).background(shade))
        Box(Modifier.fillMaxWidth().padding(top = top + band).fillMaxHeight().background(shade))
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = top + band / 2 - 22.dp, end = 2.dp).size(26.dp, 44.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { ch, dy ->
                        ch.consume()
                        val h = with(density) { total.toPx() }
                        ui.rulerY = (ui.rulerY + dy / h).coerceIn(0.08f, 0.92f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(5.dp, 30.dp).clip(RoundedCornerShape(3.dp)).background(p.accent.copy(alpha = 0.8f))) }
    }
}

/** Borde izquierdo: deslizar arriba o abajo cambia el brillo; un toque pasa a la página anterior. */
@Composable
private fun BrightnessEdge(ui: ReaderUi, act: ReaderActions, modifier: Modifier) {
    val p = LocalPalette.current
    var live by remember { mutableStateOf<Float?>(null) }
    var height by remember { mutableFloatStateOf(1f) }
    Box(
        modifier.width(26.dp).fillMaxHeight(0.72f)
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { act.turn(-1) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { live = ui.settings.brightness.takeIf { it >= 0f } ?: 0.65f },
                    onDragEnd = { live?.let { act.settings(ui.settings.copy(brightness = it)) }; live = null },
                    onDragCancel = { live?.let { act.settings(ui.settings.copy(brightness = it)) }; live = null },
                ) { ch, dy ->
                    ch.consume()
                    val v = ((live ?: 0.65f) - dy / height * 1.2f).coerceIn(0f, 1f)
                    live = v
                    act.brightnessLive(v)
                }
            },
    )
    live?.let { v ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(p.text).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZIcon(if (v < 0.3f) "moon" else "sun", p.bg, 18.dp)
                Text(if (v < 0.3f) "  Más oscuro que el mínimo" else "  Brillo ${((v - 0.3f) / 0.7f * 100).toInt()} %", style = Type.ui, color = p.bg)
            }
        }
    }
}

/** Luz: brillo, filtro cálido, papel y color de la letra. */
@Composable
private fun LightSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val s = ui.settings
    val sliderColors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line)
    Sheet(onClose) {
        Text("Luz", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Brillo automático", style = Type.ui, color = p.text)
                Text("El del teléfono", style = Type.uiSmall, color = p.text2)
            }
            ZSwitch(s.brightness < 0f) { act.settings(s.copy(brightness = if (it) -1f else 0.65f)) }
        }
        if (s.brightness >= 0f) {
            var b by remember { mutableFloatStateOf(s.brightness) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ZIcon("moon", p.text2, 18.dp)
                ZSlider(
                    value = b, onChange = { b = it; act.brightnessLive(it) }, onFinished = { act.settings(ui.settings.copy(brightness = b)) },
                    marker = 0.3f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                ZIcon("sun", p.text2, 20.dp)
            }
            Text(
                if (b < 0.3f) "Más oscuro que el mínimo del teléfono" else "A la izquierda de la marca oscurece más que el mínimo del teléfono",
                style = Type.uiSmall, color = p.text2,
            )
        }
        var veil by remember { mutableFloatStateOf(s.veil) }
        Label("Velo", if (veil <= 0f) "apagado" else "${(veil / 0.85f * 100).toInt()} %")
        ZSlider(
            value = veil, onChange = { veil = it; act.veilLive(it) }, onFinished = { act.settings(ui.settings.copy(veil = veil)) },
            range = 0f..0.85f,
        )
        Text("Atenúa la página sin cambiar el brillo de la pantalla.", style = Type.uiSmall, color = p.text2)
        Label("Filtro cálido", if (s.warmth <= 0f) "apagado" else "${(s.warmth * 100).toInt()} %")
        var w by remember { mutableFloatStateOf(s.warmth) }
        ZSlider(value = w, onChange = { w = it }, onFinished = { act.settings(ui.settings.copy(warmth = w)) })
        Label("Papel", "el claro se lee mejor de día")
        val papers = listOf("Crema" to 0, "Sepia" to 3, "Gris" to 4, "Nogal" to 1, "Negro" to 2, "Según la hora" to -2, "Como la app" to -1)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(papers) { (name, id) ->
                val on = s.paper == id
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) p.text else p.card.copy(alpha = 0.7f))
                        .clickable { act.settings(ui.settings.copy(paper = id)) }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) { Text(name, style = Type.ui, color = if (on) p.bg else p.text) }
            }
        }
    }
}

private fun originalInk(s: com.sebas.zerkalo.data.ReaderSettings, dark: Boolean): Color = when {
    !dark && s.paper == 3 -> Color(0xFF3B2A1A)
    !dark -> Color(0xFF2E231D)
    s.paper == 2 -> Color(0xFFE9CDA4)
    s.paper == 4 -> Color(0xFFDAD6CF)
    else -> Color(0xFFF0E6D4)
}

/** Color de la letra, en todos los papeles (claras para papel oscuro, oscuras para papel claro). */
@Composable
private fun InkPicker(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val s = ui.settings
    val dark = ui.palette.isDark
    val names = if (dark) InkNames else InkNamesLight
    val colors = if (dark) InkColors else InkColorsLight
    val custom = if (dark) s.customInk else s.customInkLight
    Label("Color de la letra", if (dark) "para papel oscuro" else "para papel claro")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(names.size) { i ->
            val c = when (i) { 0 -> null; 5 -> Color(custom); else -> Color(colors[i]) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { act.settings(ui.settings.copy(ink = i)) }) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(ui.palette.bg)
                        .then(if (i == s.ink) Modifier.border(2.dp, p.accent, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Text("Aa", fontFamily = Fonts.literata, fontSize = 17.sp, color = c ?: originalInk(s, dark)) }
                Text(names[i], style = Type.uiSmall.copy(fontSize = 11.sp), color = if (i == s.ink) p.accent else p.text2, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (s.ink == 5) {
        // Color propio: tono y claridad; la saturación se mantiene suave para no cansar la vista.
        val hsv = remember(custom, dark) { FloatArray(3).also { android.graphics.Color.colorToHSV(custom, it) } }
        var hue by remember(dark) { mutableFloatStateOf(hsv[0]) }
        var light by remember(dark) { mutableFloatStateOf(hsv[2]) }
        fun push() {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, if (dark) 0.22f else 0.4f, light))
            act.settings(if (dark) ui.settings.copy(customInk = argb) else ui.settings.copy(customInkLight = argb))
        }
        Label("Tono")
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.35f, 0.9f) })))
        ZSlider(value = hue, onChange = { hue = it }, onFinished = { push() }, range = 0f..359f)
        Label("Claridad")
        ZSlider(value = light, onChange = { light = it }, onFinished = { push() }, range = if (dark) 0.55f..1f else 0.12f..0.45f)
    }
}

@Composable
private fun Label(t: String, sub: String = "") {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, style = Type.ui, color = p.text)
        if (sub.isNotEmpty()) Text("  $sub", style = Type.uiSmall, color = p.text2)
    }
}

@Composable
private fun MarkSheet(m: Mark, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Sheet(onClose) {
        Text("Subrayado", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Row(Modifier.padding(top = 12.dp)) {
            Box(Modifier.width(3.dp).height(64.dp).background(highlightColor(m.color)))
            Text(m.text, style = Type.body, color = p.text, modifier = Modifier.padding(start = 12.dp), maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        if (m.note.isNotBlank()) Text(m.note, style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 10.dp))
        val inks by com.sebas.zerkalo.data.Prefs.inks.collectAsState()
        val cur = highlightColor(m.color)
        LazyRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(inks) { ink ->
                val sel = Color(ink.argb) == cur
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp).clickable { act.recolor(m, ink.argb) }) {
                    Box(
                        Modifier.size(if (sel) 38.dp else 34.dp).clip(CircleShape).background(Color(ink.argb)),
                        contentAlignment = Alignment.Center,
                    ) { if (sel) ZIcon("check", Color.White, 16.dp) }
                    Text(ink.name, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp).clickable { act.openInks() }) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) { ZIcon("plus", p.text, 16.dp) }
                    Text("Editar", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.padding(top = 20.dp)) {
            PillButton("Compartir", {
                val t = "«${m.text.trim()}»\n\nLeído en Zerkalo"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Compartir"))
            }, Modifier.weight(1f), ghost = true, icon = "share")
            Spacer(Modifier.width(10.dp))
            PillButton("Quitar", { act.removeMark(m) }, Modifier.weight(1f), ghost = true, icon = "trash")
        }
        val hasCard = remember(m.id) { com.sebas.zerkalo.data.Cards.hasFor(m.id) }
        PillButton(
            if (hasCard) "Ya tiene tarjeta" else "Hacer tarjeta para estudiarlo", { if (!hasCard) act.cardFromMark(m) },
            Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true, icon = if (hasCard) "check" else "notas",
        )
    }
}

@Composable
private fun NoteSheet(loc: Locator, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    Sheet(onClose) {
        Text("Nota", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        loc.text.highlight?.let { Text("«${it.trim().take(200)}»", style = Type.body.copy(fontSize = 14.sp), color = p.text2, modifier = Modifier.padding(top = 8.dp)) }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp)) {
            if (text.isEmpty()) Text("Lo que te hizo pensar…", style = Type.body, color = p.text2)
            BasicTextField(text, { text = it }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(14.dp))
        PillButton("Guardar nota", { act.saveNote(loc, text.trim()) }, Modifier.fillMaxWidth())
    }
}

/** Hacer una tarjeta: tú escribes la pregunta (o eliges una sugerida); la respuesta es lo que subrayaste. */
@Composable
private fun CardSheet(d: CardDraft, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val options = remember(d.text) { com.sebas.zerkalo.data.Suggest.options(d.text) }
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf(d.text) }
    var kind by remember { mutableStateOf("hl") }
    Sheet(onClose) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Text("Nueva tarjeta", style = Type.title.copy(fontSize = 21.sp), color = p.text)
            Text(
                "Al repasar verás la pregunta, intentarás recordar y luego verás la respuesta. Así se recuerda de verdad.",
                style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp),
            )
            Label("Pregunta · la escribes tú")
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp)) {
                if (front.isEmpty()) Text("¿Qué quieres recordar de esto?", style = Type.body, color = p.text2)
                BasicTextField(front, { front = it; kind = "hl"; back = d.text }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
            }
            if (options.isNotEmpty()) {
                Label("¿No sabes qué preguntar? Toca una")
                options.forEach { o ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (front == o.front) p.accent.copy(alpha = 0.14f) else p.card.copy(alpha = 0.45f))
                            .clickable { front = o.front; back = o.back; kind = o.kind }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(o.label, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.accent)
                        Text(o.front, style = Type.ui, color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Label(if (kind == "cloze") "Respuesta · la palabra oculta" else "Respuesta · lo que subrayaste")
            Text(back, style = Type.body.copy(fontSize = 15.sp), color = p.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.45f)).padding(14.dp))
            Spacer(Modifier.height(14.dp))
            PillButton("Guardar tarjeta", { if (front.isNotBlank()) act.saveCard(d, front.trim(), back, kind) }, Modifier.fillMaxWidth(), enabled = front.isNotBlank())
        }
    }
}

@Composable
private fun FinishedSheet(stamp: com.sebas.zerkalo.data.Stamps.BookStamp?, onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (stamp != null) com.sebas.zerkalo.ui.Hanko(stamp.kanji, 112.dp, ink = stamp.ink, wax = stamp.wax, tilt = -4f, seed = stamp.book.id.hashCode())
            else Seal("読了", 104.dp, -4f, fontSize = 22f)
            Text("Terminaste el libro", style = Type.title, color = p.text, modifier = Modifier.padding(top = 18.dp))
            Text(
                when {
                    stamp == null -> "Su sello quedó en tu libro de sellos."
                    stamp.wax != null -> "¡Sello de cera! Salió con los colores de su portada. Está en tu libro de sellos (libro nº ${stamp.n})."
                    else -> "${stamp.kanji} ${stamp.genre} · libro nº ${stamp.n}. Su sello quedó en tu libro de sellos."
                },
                style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )
            PillButton("Gracias", onClose, Modifier.fillMaxWidth())
        }
    }
}


@Composable
private fun MiniPlayer(ab: com.sebas.zerkalo.data.AudioBook, st: com.sebas.zerkalo.audio.Listen.State, act: ReaderActions, modifier: Modifier, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val track = ab.tracks.getOrNull(st.index)
    Box(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(p.surface.copy(alpha = 0.96f)).clickable(onClick = onOpen),
    ) {
        if (st.duration > 0) Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(2.dp).background(p.text.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth((st.position.toFloat() / st.duration).coerceIn(0f, 1f)).height(2.dp).background(p.accent))
        }
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: ab.title, style = Type.uiSmall, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (com.sebas.zerkalo.data.Prefs.audioFollow) "● siguiendo al narrador" else "toca para sincronizar",
                    style = Type.uiSmall.copy(fontSize = 11.sp), color = p.accent, maxLines = 1,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { com.sebas.zerkalo.audio.Listen.seekBy(-15_000) }, contentAlignment = Alignment.Center) {
                Text("−15", style = Type.uiSmall, color = p.text2)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(p.text).clickable { com.sebas.zerkalo.audio.Listen.toggle() }, contentAlignment = Alignment.Center) {
                ZIcon(if (st.playing) "pause" else "play", p.bg, 18.dp)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { com.sebas.zerkalo.audio.Listen.seekBy(15_000) }, contentAlignment = Alignment.Center) {
                Text("+15", style = Type.uiSmall, color = p.text2)
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** El reproductor completo: tiempo, capítulos, velocidad, temporizador y sincronizar con el texto. */
@Composable
private fun PlayerSheet(act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val st by com.sebas.zerkalo.audio.Listen.state.collectAsState()
    val ab = st.book
    var drag by remember { mutableStateOf<Float?>(null) }
    var chapters by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(com.sebas.zerkalo.data.Prefs.audioFollow) }
    var ear by remember { mutableStateOf<String?>(null) }
    var earBusy by remember { mutableStateOf(false) }
    Sheet(onClose) {
        if (ab == null || ab.tracks.isEmpty()) {
            Text("No está sonando ningún audiolibro", style = Type.title.copy(fontSize = 20.sp), color = p.text)
            return@Sheet
        }
        val i = st.index
        Caption("Capítulo ${i + 1} de ${ab.tracks.size}", accent = true)
        Text(ab.tracks.getOrNull(i)?.title ?: ab.title, style = Type.title.copy(fontSize = 20.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        val frac = drag ?: if (st.duration > 0) st.position.toFloat() / st.duration else 0f
        ZSlider(frac, { drag = it }, Modifier.padding(top = 10.dp), onFinished = { drag?.let { com.sebas.zerkalo.audio.Listen.seekTo((it * st.duration).toLong()) }; drag = null })
        Row(Modifier.fillMaxWidth()) {
            Text(clock((frac * st.duration).toLong()), style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.weight(1f))
            Text("−" + clock(((1 - frac) * st.duration).toLong()), style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("⏮") { com.sebas.zerkalo.audio.Listen.track(i - 1) }
            RoundBtn("−15") { com.sebas.zerkalo.audio.Listen.seekBy(-15_000) }
            Box(Modifier.size(60.dp).clip(CircleShape).background(p.text).clickable { com.sebas.zerkalo.audio.Listen.toggle() }, contentAlignment = Alignment.Center) {
                ZIcon(if (st.playing) "pause" else "play", p.bg, 22.dp)
            }
            RoundBtn("+15") { com.sebas.zerkalo.audio.Listen.seekBy(15_000) }
            RoundBtn("⏭") { com.sebas.zerkalo.audio.Listen.track(i + 1) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.8f, 1f, 1.25f, 1.5f).forEach { v ->
                SmallPill(if (v == 1f) "1×" else v.toString().replace('.', ',') + "×", kotlin.math.abs(st.speed - v) < 0.01f) { com.sebas.zerkalo.audio.Listen.speed(v) }
            }
            SmallPill("☾ 30 min", st.sleepAt > 0) { com.sebas.zerkalo.audio.Listen.sleep(if (st.sleepAt > 0) 0 else 30) }
            SmallPill("Fin del capítulo", st.sleepEndOfTrack) { com.sebas.zerkalo.audio.Listen.sleep(if (st.sleepEndOfTrack) 0 else -1) }
            SmallPill("Capítulos", chapters) { chapters = !chapters }
        }
        if (chapters) LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 240.dp)) {
            items(ab.tracks.size) { k ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { com.sebas.zerkalo.audio.Listen.track(k) }.padding(vertical = 9.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${k + 1}", style = Type.uiSmall, color = if (k == i) p.accent else p.text2, modifier = Modifier.width(30.dp))
                    Text(ab.tracks[k].title, style = Type.ui, color = if (k == i) p.accent else p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (ab.tracks[k].seconds > 0) Text(clock(ab.tracks[k].seconds * 1000L), style = Type.uiSmall, color = p.text2)
                }
            }
        }
        Caption("Sincronizar", accent = true, modifier = Modifier.padding(top = 16.dp))
        PillButton("Llevar el texto a donde va el audio", act.syncToAudio, Modifier.fillMaxWidth().padding(top = 8.dp), ghost = true)
        PillButton("Que el audio siga desde donde leo", act.audioFromHere, Modifier.fillMaxWidth().padding(top = 8.dp), ghost = true)
        val lang = ab.lang.take(2).lowercase()
        val earOk = com.sebas.zerkalo.audio.Ear.supports(lang)
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)
                .clickable(enabled = !earBusy) {
                    earBusy = true; ear = "Preparando…"
                    act.syncByEar { msg -> if (msg == null) { earBusy = false; ear = null } else { ear = msg; if (!msg.endsWith("…") && !msg.contains("%")) earBusy = false } }
                }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZIcon("escuchar", p.accent, 22.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("A oído", style = Type.ui.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                Text(
                    ear ?: when {
                        !earOk -> "Todavía no hay diccionario de voz para este idioma"
                        com.sebas.zerkalo.audio.Ear.hasModel(ctx, lang) -> "Escucha unos segundos al narrador y encuentra la frase exacta"
                        else -> "Escucha unos segundos al narrador y encuentra la frase exacta. La primera vez baja un diccionario de voz (unos 40 MB)."
                    },
                    style = Type.uiSmall, color = p.text2,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("La página sigue al narrador", style = Type.ui, color = p.text, modifier = Modifier.weight(1f))
            SmallPill(if (follow) "Sí" else "No", follow) { follow = !follow; com.sebas.zerkalo.data.Prefs.audioFollow = follow }
        }
        Text("Pasa de capítulo y de página con el audio, y marca suave el párrafo que va sonando.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundBtn(label: String, on: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(46.dp).clip(CircleShape).background(p.card).clickable(onClick = on), contentAlignment = Alignment.Center) {
        Text(label, style = Type.ui, color = p.text)
    }
}
