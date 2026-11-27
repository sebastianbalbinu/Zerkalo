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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Mark
import com.sebas.zerkalo.data.withAlign
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
        if ((ui.settings.ruler || ui.guide) && !ui.controls) ReadingRuler(ui)

        // Oscurecer por debajo del mínimo del teléfono (no recibe toques).
        if (ui.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ui.dim)))

        // Brillo deslizando el dedo por el borde izquierdo, como en Moon+ Reader.
        BrightnessEdge(ui, act, Modifier.align(Alignment.CenterStart))

        // Cinta del shiori en la esquina, como un marcapáginas de tela.
        AnimatedVisibility(ui.bookmarked, Modifier.align(Alignment.TopEnd).padding(end = 28.dp), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            Box(Modifier.size(14.dp, 54.dp).background(if (p.isDark) p.accent.copy(alpha = 0.45f) else p.accent))
        }

        // Hora y avance junto a la cámara, en la franja de arriba (así el texto llega hasta abajo)
        AnimatedVisibility(!ui.controls && ui.settings.info, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
            val info = Type.uiSmall.copy(fontSize = 11.sp)
            val c = p.text2.copy(alpha = 0.65f)
            val gap = (ui.camHalf + 10f).dp
            Row(
                Modifier.fillMaxWidth().height(ui.band.coerceAtLeast(18f).dp).pointerInput(Unit) { detectTapGestures(onDoubleTap = { act.nextProfile() }) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    .clip(RoundedCornerShape(16.dp)).background(if (p.isDark) p.text.copy(alpha = 0.14f) else p.accent.copy(alpha = 0.92f)).clickable { act.toggleStudy() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (p.isDark) p.text2 else Color.White
                ZIcon("close", fg, 12.dp)
                Text("Salir de traducir", style = Type.uiSmall.copy(fontSize = 12.sp), color = fg, modifier = Modifier.padding(start = 6.dp))
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

        // Barra superior: volver, el capítulo, la lupa, el índice y el shiori
        AnimatedVisibility(ui.controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(200)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(180)) + slideOutVertically { -it / 3 }) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0.92f), p.bg.copy(alpha = 0f))))) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundPill("back", act.close)
                    Text(
                        ui.chapter, style = Type.uiSmall.copy(fontSize = 14.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    RoundPill("search", { ui.sheet = "find" }, size = 44.dp)
                    Spacer(Modifier.width(8.dp))
                    RoundPill("toc", { ui.sheet = "toc" }, size = 44.dp)
                    Spacer(Modifier.width(8.dp))
                    RoundPill("shiori", act.toggleShiori, active = ui.bookmarked, size = 44.dp)
                }
            }
        }

        // Barra inferior: avance, luz a la mano y los cuatro botones (Luz · Letra · Página · Herramientas)
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
                // La luz, sin abrir nada
                val s0 = ui.settings
                var light by remember(s0.brightness) { mutableStateOf<Float?>(null) }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ZIcon("moon", p.text2, 16.dp)
                    ZSlider(
                        value = light ?: s0.brightness.takeIf { it >= 0f } ?: 0.65f,
                        onChange = { light = it; act.brightnessLive(it) },
                        onFinished = { light?.let { act.settings(ui.settings.copy(brightness = it)) }; light = null },
                        marker = 0.3f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    ZIcon("sun", p.text2, 18.dp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DockBtn("sun", null, "Luz", onLong = { act.profile("oscuro") }) { ui.setTab = 0; ui.sheet = "ajustes" }
                    DockBtn("aa", null, "Letra") { ui.setTab = 1; ui.sheet = "ajustes" }
                    DockBtn("full", null, "Página") { ui.setTab = 2; ui.sheet = "ajustes" }
                    DockBtn("pluma", null, "Herramientas", active = ui.inking || ui.studyTap) { ui.sheet = "tools" }
                    if (ui.settings.noteButton) DockBtn("", "記", "Apunte", onClick = act.openNote)
                }
            }
        }

        ReaderToast(ui, Modifier.align(Alignment.BottomCenter))

        // Trazos de la pluma hechos con otra letra en este capítulo: se ofrece volver a esa letra para verlos
        AnimatedVisibility(ui.inkOther != null && ui.sheet == null, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            val o = ui.inkOther
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = if (ui.controls) 70.dp else 14.dp)
                    .shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(p.surface)
                    .clickable { o?.let { act.useLook(it.second) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✎", style = Type.ui, color = p.accent)
                Text(
                    "${o?.first ?: 0} ${if ((o?.first ?: 0) == 1) "trazo" else "trazos"} con otra letra · ",
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(start = 6.dp),
                )
                Text("Verlos", style = Type.uiSmall, color = p.accent)
                Box(Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape).clickable { act.hideInkHint() }, contentAlignment = Alignment.Center) {
                    ZIcon("close", p.text2, 12.dp)
                }
            }
        }

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

        // Guía de lectura rápida: palabras por minuto y pausa, sin abrir el menú
        AnimatedVisibility(ui.guide && !ui.controls, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(end = 12.dp, bottom = 26.dp)
                    .clip(RoundedCornerShape(22.dp)).background(p.text.copy(alpha = if (p.isDark) 0.35f else 0.82f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val s = ui.settings
                fun change(d: Int) = act.settings(s.copy(wpm = (s.wpm + d).coerceIn(120, 600)))
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { change(-20) }, contentAlignment = Alignment.Center) { Text("−", style = Type.ui.copy(fontSize = 20.sp), color = p.bg) }
                Text("${s.wpm} p/min", style = Type.uiSmall, color = p.bg, modifier = Modifier.padding(horizontal = 4.dp))
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { change(20) }, contentAlignment = Alignment.Center) { Text("+", style = Type.ui.copy(fontSize = 20.sp), color = p.bg) }
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { act.toggleGuide() }, contentAlignment = Alignment.Center) { ZIcon("pause", p.bg, 18.dp) }
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
        "find" -> FindSheet(ui, act) { ui.sheet = null }
        "type", "luz", "ajustes" -> {
            // "type" y "luz" son los nombres de antes: abren la misma hoja, en su pestaña
            val sheet = ui.sheet
            LaunchedEffect(sheet) { if (sheet == "luz") ui.setTab = 0; if (sheet != "ajustes") ui.sheet = "ajustes" }
            SettingsSheet(ui, act) { ui.sheet = null }
        }
        "fonts" -> FontSheet(ui, act) { ui.sheet = "type" }
        "gfonts" -> GoogleFontSheet(ui, act) { ui.sheet = "fonts" }
        "pair" -> PairSheet(ui, act) { ui.sheet = null }
        "listen" -> ListenSheet(act) { ui.sheet = null }
        "player" -> PlayerSheet(act) { ui.sheet = null }
        "inks" -> InksSheet(act) { ui.sheet = null }
        "tools" -> ToolsSheet(ui, act) { ui.sheet = null }
        "export" -> ExportSheet(act) { ui.sheet = null }
    }
    ui.activeMark?.let { MarkSheet(it, act) { ui.activeMark = null } }
    ui.noteFor?.let { NoteSheet(it, act) { ui.noteFor = null } }
    if (ui.finishedNow) FinishedSheet(ui.finishedStamp) { ui.finishedNow = false }
    ui.cardDraft?.let { CardSheet(it, act) { ui.cardDraft = null } }
}

private fun fmt(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60}"

@Composable
private fun RoundPill(icon: String, onClick: () -> Unit, active: Boolean = false, glyph: String? = null, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val p = LocalPalette.current
    Box(
        Modifier.size(size).shadow(6.dp, CircleShape).clip(CircleShape).background(if (active) p.accent else p.text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) Text(glyph, style = Type.title.copy(fontSize = 20.sp), color = if (active) Color.White else p.bg)
        else ZIcon(icon, if (active) Color.White else p.bg, 21.dp)
    }
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
                if (ui.tocMade) item { Text("Este libro no traía índice: Zerkalo armó uno con sus partes.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
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
        val google = remember { GoogleFonts.installed(ctx) }
        LazyColumn(Modifier.heightIn(max = 560.dp).padding(top = 6.dp)) {
            item(key = "gmore") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { ui.sheet = "gfonts" }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Más letras de Google", style = Type.ui, color = p.text)
                        Text("Elige y se descargan una vez; después funcionan sin internet", style = Type.uiSmall, color = p.text2)
                    }
                    ZIcon("chevron", p.accent, 16.dp)
                }
            }
            if (google.isNotEmpty()) {
                item(key = "gtitle") { Label("Descargadas de Google") }
                items(google, key = { "g" + it.first }) { (i, g) ->
                    val sel = i == s.font
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (sel) p.text else p.card.copy(alpha = 0.55f))
                            .clickable { act.settings(ui.settings.copy(font = i)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.family, fontFamily = GoogleFonts.preview(ctx, i), fontSize = 18.sp, color = if (sel) p.bg else p.text)
                            Text("Así se ve un párrafo. De Google Fonts", fontFamily = GoogleFonts.preview(ctx, i), fontSize = 13.5.sp, color = if (sel) p.bg.copy(alpha = 0.75f) else p.text2, maxLines = 1)
                        }
                        if (sel) ZIcon("check", p.bg, 18.dp)
                    }
                }
            }
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

/** Letras de Google: una lista elegida para leer y un buscador con todo el catálogo. */
@Composable
private fun GoogleFontSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<GFont>>(emptyList()) }
    var loadingCat by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(query.isNotBlank()) {
        if (query.isNotBlank() && catalog.isEmpty() && !loadingCat) {
            loadingCat = true
            catalog = GoogleFonts.catalog(ctx)
            loadingCat = false
        }
    }
    val shown = remember(query, catalog) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) GoogleFonts.CURATED
        else (GoogleFonts.CURATED + catalog).distinctBy { it.family }.filter { it.family.lowercase().contains(q) }.take(60)
            .ifEmpty { if (catalog.isEmpty() && !loadingCat) listOf(GFont(query.trim(), true, "Probar con este nombre exacto")) else emptyList() }
    }
    Sheet(onClose) {
        Text("Letras de Google", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Text("Todas son libres y gratuitas. Cada una pesa entre 100 y 600 KB.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) Text("Buscar en todo Google Fonts…", style = Type.ui, color = p.text2)
            BasicTextField(query, { query = it.take(40) }, singleLine = true, textStyle = Type.ui.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, style = Type.uiSmall, color = p.accent, modifier = Modifier.padding(top = 6.dp)) }
        if (loadingCat) Text("Cargando el catálogo…", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp).padding(top = 6.dp)) {
            if (query.isBlank()) item(key = "t") { Label("Buenas para leer") }
            items(shown, key = { it.family }) { g ->
                val idx = remember(version, g.family) { GoogleFonts.indexOf(ctx, g.family) }
                val sel = idx != null && idx == ui.settings.font
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (sel) p.text else p.card.copy(alpha = 0.55f))
                        .clickable(enabled = busy == null) {
                            if (idx != null) { act.settings(ui.settings.copy(font = idx)); return@clickable }
                            busy = g.family; error = null
                            scope.launch {
                                GoogleFonts.download(ctx, g.family, g.serif)
                                    .onSuccess { act.settings(ui.settings.copy(font = it)); version++ }
                                    .onFailure { error = "No pude descargar «${g.family}». Revisa el internet e intenta de nuevo." }
                                busy = null
                            }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.family, fontFamily = idx?.let { GoogleFonts.preview(ctx, it) } ?: FontFamily.Default, fontSize = 17.sp, color = if (sel) p.bg else p.text)
                        val sub = when { busy == g.family -> "Descargando…"; g.note.isNotBlank() -> g.note; else -> if (g.serif) "Con serifa" else "Sin serifa" }
                        Text(sub, style = Type.uiSmall, color = if (sel) p.bg.copy(alpha = 0.75f) else p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        sel -> ZIcon("check", p.bg, 18.dp)
                        idx != null -> Text("Quitar", style = Type.uiSmall, color = p.text2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            GoogleFonts.remove(ctx, idx); version++
                        }.padding(6.dp))
                        else -> Text("Descargar", style = Type.uiSmall, color = p.accent)
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

/** Aspecto: letra y luz en la misma hoja, con dos pestañas. */
/** Herramientas: todo lo que no cabe en los cuatro botones, en cuadritos. */
@Composable
private fun ToolsSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("Herramientas", accent = true, modifier = Modifier.weight(1f))
            Text("Hoy llevas ${fmt(ui.minutesToday)} de lectura", style = Type.uiSmall, color = p.text2)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            com.sebas.zerkalo.ui.ToolTile("pluma", "Pluma", Modifier.weight(1f)) { onClose(); act.ink(com.sebas.zerkalo.ui.InkTool.PEN) }
            com.sebas.zerkalo.ui.ToolTile("resaltador", "Resaltador", Modifier.weight(1f)) { onClose(); act.ink(com.sebas.zerkalo.ui.InkTool.MARKER) }
            com.sebas.zerkalo.ui.ToolTile("nota-adhesiva", "Nota adhesiva", Modifier.weight(1f), tint = com.sebas.zerkalo.ui.StickyPaper) {
                onClose(); ui.say("Mantén presionada una frase y elige «Nota adhesiva»")
            }
            com.sebas.zerkalo.ui.ToolTile("borrar", "Borrar", Modifier.weight(1f)) { onClose(); act.ink(com.sebas.zerkalo.ui.InkTool.ERASER) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            com.sebas.zerkalo.ui.ToolTile("share", "Exportar", Modifier.weight(1f)) { onClose(); act.export() }
            com.sebas.zerkalo.ui.ToolTile("escuchar", "Escuchar", Modifier.weight(1f)) { ui.sheet = "listen" }
            com.sebas.zerkalo.ui.ToolTile("translate", "Traducir", Modifier.weight(1f), active = ui.studyTap) { onClose(); act.toggleStudy() }
            com.sebas.zerkalo.ui.ToolTile("lado", "Lado a lado", Modifier.weight(1f), active = ui.pairOn) { ui.sheet = "pair" }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            com.sebas.zerkalo.ui.ToolTile("note", "Apunte", Modifier.weight(1f)) { onClose(); act.openNote() }
            com.sebas.zerkalo.ui.ToolTile("search", "Buscar", Modifier.weight(1f)) { ui.sheet = "find" }
            com.sebas.zerkalo.ui.ToolTile("auto", "Lectura rápida", Modifier.weight(1f), active = ui.guide) { onClose(); act.toggleGuide() }
            com.sebas.zerkalo.ui.ToolTile("toc", "Índice", Modifier.weight(1f)) { ui.sheet = "toc" }
        }
    }
}

/** Exportar: el libro con mis notas, las páginas rayadas con la pluma y el apunte. */
@Composable
private fun ExportSheet(act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Text("Exportar", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Text("Se crea una copia nueva; tu libro en Zerkalo no cambia.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        ExportRow("EPUB con mis notas", "Subrayados marcados en el texto y notas adhesivas en su sitio") { onClose(); act.exportEpub() }
        ExportRow("Páginas rayadas (PDF)", "Cada página que rayaste con la pluma, tal como la dejaste") { onClose(); act.exportInk() }
        ExportRow("Apunte (.txt)", "Las frases y lo que escribiste en el apunte") { onClose(); act.exportNote() }
    }
}

@Composable
private fun ExportRow(title: String, sub: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.ui, color = p.text)
            Text(sub, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 2.dp))
        }
        ZIcon("share", p.text2, 18.dp)
    }
}

/** Letras por línea, con una sugerencia (que solo se aplica si la tocas). */
@Composable
private fun LineMeter(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    if (ui.cpl <= 0) return
    var open by remember { mutableStateOf(false) }
    val ok = ui.cpl in 38..80
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(p.card.copy(alpha = 0.7f)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF7FA35A) else p.accent))
            Text("≈ ${ui.cpl} letras por línea · ${Fit.label(ui.cpl)}", style = Type.uiSmall.copy(fontSize = 13.sp), color = p.text, modifier = Modifier.padding(start = 10.dp).weight(1f))
            InfoDot(open) { open = !open }
        }
        if (open) Box(Modifier.padding(end = 10.dp)) { InfoCard(Info.CPL) }
        if (ui.fitMm > 0f) Row(Modifier.padding(bottom = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val mm = (if (ui.fitMm % 1f == 0f) "${ui.fitMm.toInt()}" else "${ui.fitMm}").replace('.', ',')
            Text("Con márgenes de $mm mm caben más palabras", style = Type.uiSmall, color = p.text2, modifier = Modifier.weight(1f))
            Text("Ajustar por mí", style = Type.uiSmall, color = p.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { act.settings(ui.settings.copy(mmLeft = ui.fitMm, mmRight = ui.fitMm)) }.padding(6.dp))
        }
    }
}

@Composable
private fun HomeRow(title: String, info: Info, value: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.7f))
            .clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.ui, color = p.text)
        InfoDot(open) { open = !open }
        Text(value, style = Type.uiSmall, color = p.text2, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        ZIcon("chevron", p.text2, 14.dp)
    }
    if (open) Box(Modifier.padding(top = 6.dp)) { InfoCard(info) }
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
        val band = (16f * ui.settings.size / 100f * ui.settings.lineHeight * (if (ui.guide) 1.35f else 2.5f)).dp
        val center = total * ui.rulerY
        val top = (center - band / 2).coerceAtLeast(0.dp)
        val shade = p.bg.copy(alpha = if (ui.guide) (if (p.isDark) 0.5f else 0.42f) else if (p.isDark) 0.62f else 0.55f)
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
        ) { if (!ui.guide) Box(Modifier.size(5.dp, 30.dp).clip(RoundedCornerShape(3.dp)).background(if (p.isDark) p.text2.copy(alpha = 0.45f) else p.accent.copy(alpha = 0.8f))) }
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

private fun originalInk(s: com.sebas.zerkalo.data.ReaderSettings, dark: Boolean): Color = when {
    !dark && s.paper == 3 -> Color(0xFF3B2A1A)
    !dark -> Color(0xFF2E231D)
    s.paper == 2 -> Color(0xFFE9CDA4)
    s.paper == 4 -> Color(0xFFE6DFD4)
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
private fun Label(t: String, sub: String = "", info: Info? = null) {
    val p = LocalPalette.current
    var open by remember(t) { mutableStateOf(false) }
    Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, style = Type.ui, color = p.text)
        if (info != null) InfoDot(open) { open = !open }
        if (sub.isNotEmpty()) Text("  $sub", style = Type.uiSmall, color = p.text2)
    }
    if (info != null && open) InfoCard(info)
}

/** La ⓘ pequeña: un toque explica para qué sirve el ajuste. */
@Composable
private fun InfoDot(open: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.padding(start = 6.dp).size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(17.dp).clip(CircleShape).border(1.3.dp, if (open) p.accent else p.text2, CircleShape)
                .background(if (open) p.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Text("i", style = Type.uiSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = if (open) p.bg else p.text2) }
    }
}

@Composable
private fun InfoCard(i: Info) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(i.text, style = Type.uiSmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = p.text)
        if (i.tips.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            i.tips.forEach { t ->
                Text(t, style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(p.accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

/** Qué hace cada ajuste, en palabras simples, y qué conviene según para qué lees. */
class Info(val text: String, val tips: List<String> = emptyList()) {
    companion object {
        val SIZE = Info(
            "Letra más grande cansa menos la vista, pero caben menos palabras por línea y pasas más páginas. En celular, entre 12 y 15 pt suele ser lo cómodo.",
            listOf("Leer rápido: 12–14", "Retener: 13–15", "Vista cansada: 16 o más"),
        )
        val FONT = Info(
            "Con serifa (Literata, Garamond) es la letra de los libros, buena para leer largo. Sin serifa (Atkinson, Inter) se ve más nítida en pantallas chicas o con poca luz. Ningún estudio encontró una letra que haga leer mucho más rápido: la mejor es la que te resulta cómoda.",
            listOf("Dislexia: Atkinson o Lexend", "Noche: sin serifa"),
        )
        val PAPER = Info(
            "De día, papel claro con letra oscura: es lo que se lee más fácil. De noche, el papel oscuro y el filtro cálido molestan menos a los ojos.",
        )
        val MODE = Info(
            "Páginas: como un libro de papel. Ayuda a recordar dónde estaba cada cosa, por eso conviene para estudiar. Continuo: bajas con el dedo sin cortes, cómodo para leer de corrido.",
            listOf("Leer de corrido: Continuo", "Estudiar: Páginas"),
        )
        val LINE = Info(
            "El espacio entre una línea y la siguiente. Con poco, el ojo se confunde al volver al comienzo de la línea; con mucho, el texto se desarma. Lo cómodo está entre 1,4 y 1,6.",
            listOf("Leer rápido: Medio", "Retener: Medio o Amplio", "Dislexia: Amplio"),
        )
        val PARA = Info(
            "Cómo se separa un párrafo del siguiente: con sangría (como en los libros) o con un espacio. Separados ayuda a ubicar las ideas al estudiar y a quien tiene dislexia.",
            listOf("Leer de corrido: Como el libro", "Estudiar: Separados"),
        )
        val LETTERS = Info(
            "Más espacio entre letras ayuda a muchas personas con dislexia a no confundir unas con otras. Si no lo necesitas, déjalo en Normal: las líneas quedan más cortas.",
            listOf("Dislexia: Amplio"),
        )
        val LAYOUT = Info(
            "Ninguna: el libro tal cual. Original: el diseño de la editorial, con arreglos mínimos. Zerkalo: texto a la izquierda y sin palabras cortadas, como JW Library; además acomoda interlineado, títulos y poemas a tu pantalla y a tu letra (tus márgenes no los toca).",
        )
        val ALIGN = Info(
            "Justificado deja los dos bordes parejos, como un libro. A la izquierda el borde derecho queda irregular, pero el espacio entre palabras es siempre igual. Para la velocidad casi no hay diferencia; con dislexia, a la izquierda es más fácil.",
            listOf("Dislexia: Izquierda"),
        )
        val MARGINS = Info(
            "El espacio libre en los bordes. Más margen: líneas más cortas y una página más tranquila. Menos margen: más palabras por línea. Zerkalo usa exactamente lo que pongas.",
        )
        val CPL = Info(
            "Cuántas letras caben en una línea: es lo que más influye en leer cómodo. Lo ideal en papel son 50 a 75; en celular se lee bien desde 35–40. Muy cortas hacen saltar el ojo; muy largas cuesta encontrar la línea siguiente.",
        )
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
                    "Capítulo ${st.index + 1} de ${ab.tracks.size}" + if (com.sebas.zerkalo.data.Prefs.audioFollow) " · el libro lo sigue" else "",
                    style = Type.uiSmall.copy(fontSize = 11.sp), color = p.accent, maxLines = 1,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { com.sebas.zerkalo.audio.Listen.track(st.index - 1) }, contentAlignment = Alignment.Center) {
                Text("⏮", style = Type.ui, color = p.text2)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(p.text).clickable { com.sebas.zerkalo.audio.Listen.toggle() }, contentAlignment = Alignment.Center) {
                ZIcon(if (st.playing) "pause" else "play", p.bg, 18.dp)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { com.sebas.zerkalo.audio.Listen.track(st.index + 1) }, contentAlignment = Alignment.Center) {
                Text("⏭", style = Type.ui, color = p.text2)
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
        Caption("Libro y audio", accent = true, modifier = Modifier.padding(top = 16.dp))
        PillButton("Ir al capítulo que suena", { act.goToTrack("") }, Modifier.fillMaxWidth().padding(top = 8.dp), ghost = true)
        PillButton("Que el narrador lea el capítulo donde voy", act.audioFromHere, Modifier.fillMaxWidth().padding(top = 8.dp), ghost = true)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("La página sigue al narrador", style = Type.ui, color = p.text, modifier = Modifier.weight(1f))
            SmallPill(if (follow) "Sí" else "No", follow) { follow = !follow; com.sebas.zerkalo.data.Prefs.audioFollow = follow }
        }
        Text("Cuando el narrador empieza un capítulo (o lo pasas con ⏮ ⏭), el libro va al inicio de ese capítulo.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundBtn(label: String, on: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(46.dp).clip(CircleShape).background(p.card).clickable(onClick = on), contentAlignment = Alignment.Center) {
        Text(label, style = Type.ui, color = p.text)
    }
}


/** La lupa: buscar una palabra o frase en todo el libro, sin importar tildes ni mayúsculas. */
@Composable
private fun FindSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Text("Buscar en el libro", style = Type.title.copy(fontSize = 20.sp), color = p.text)
        com.sebas.zerkalo.ui.SearchField(ui.findQuery, { act.find(it) }, "Palabra o frase", Modifier.fillMaxWidth().padding(top = 10.dp)) { act.find(ui.findQuery) }
        val hits = ui.findHits
        when {
            ui.findBusy -> Text("Buscando…", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 12.dp))
            hits == null -> Text("Escribe al menos dos letras. No importan las tildes ni las mayúsculas.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 12.dp))
            hits.isEmpty() -> Text("No aparece «${ui.findQuery.trim()}» en este libro.", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 12.dp))
            else -> {
                Text(if (hits.size >= 300) "Más de 300 resultados: escribe algo más largo para afinar." else "${hits.size} ${if (hits.size == 1) "resultado" else "resultados"}", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(hits.size) { k ->
                        val h = hits[k]
                        val chapter = ui.findTitles[h.chapter] ?: "Parte ${h.chapter + 1}"
                        if (k == 0 || hits[k - 1].chapter != h.chapter) Text(
                            chapter, style = Type.uiSmall.copy(fontWeight = FontWeight.SemiBold), color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 6.dp),
                        )
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(h.before)
                                pushStyle(androidx.compose.ui.text.SpanStyle(background = Color(0x66E2BE52), fontWeight = FontWeight.SemiBold, color = p.text)); append(h.match); pop()
                                append(h.after)
                            },
                            style = Type.body.copy(fontSize = 15.sp), color = p.text2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { act.goHit(h) }.padding(horizontal = 6.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}



/** Un botón grande de la barra de abajo, con su nombre debajo. */
@Composable
private fun DockBtn(icon: String, glyph: String?, label: String, active: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
        Box(
            Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                .background(if (active) p.text else p.surface)
                .pointerInput(onLong) {
                    val long: ((androidx.compose.ui.geometry.Offset) -> Unit)? = onLong?.let { f -> { _: androidx.compose.ui.geometry.Offset -> f() } }
                    detectTapGestures(onTap = { onClick() }, onLongPress = long)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) Text(glyph, style = Type.title.copy(fontSize = 19.sp), color = if (active) p.bg else p.text)
            else ZIcon(icon, if (active) p.bg else p.text, 21.dp)
        }
        Text(label, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}


// ================= Ajustes de lectura: Luz · Letra · Página · Ayudas =================

@Composable
private fun SettingsSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Column(Modifier.heightIn(max = 660.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.6f)).padding(4.dp)) {
                listOf("Luz", "Letra", "Página", "Ayudas").forEachIndexed { i, label ->
                    val on = i == ui.setTab
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) p.text else Color.Transparent)
                            .clickable { ui.setTab = i }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Type.ui, color = if (on) p.bg else p.text) }
                }
            }
            ProfileRow(ui, act)
            when (ui.setTab) {
                0 -> LightTab(ui, act)
                1 -> LetterTab(ui, act)
                2 -> PageTab(ui, act)
                else -> HelpTab(ui, act, onClose)
            }
            // Deshacer solo esta pestaña
            Text(
                "Restablecer esta pestaña", style = Type.uiSmall, color = p.text2,
                modifier = Modifier.padding(top = 22.dp).clip(RoundedCornerShape(10.dp)).clickable { act.settings(resetTab(ui.settings, ui.setTab)) }.padding(vertical = 6.dp),
            )
        }
    }
}

private fun resetTab(s: com.sebas.zerkalo.data.ReaderSettings, tab: Int): com.sebas.zerkalo.data.ReaderSettings {
    val d = com.sebas.zerkalo.data.ReaderSettings()
    return when (tab) {
        0 -> s.copy(paper = d.paper, ink = d.ink, brightness = d.brightness, warmth = d.warmth, veil = d.veil, texture = d.texture)
        1 -> s.copy(font = d.font, size = d.size, lineHeight = d.lineHeight, spacing = d.spacing, dyslexia = false, cpl = 0, weight = 0)
        2 -> s.copy(layout = d.layout, align = d.align, justify = d.justify, paragraph = d.paragraph, mmLeft = d.mmLeft, mmRight = d.mmRight, mmTop = d.mmTop, mmBottom = d.mmBottom, respectCamera = true, animate = true, info = true)
        else -> s.copy(ruler = false, volumeKeys = true, haptic = false, breaks = false, autoSpeed = d.autoSpeed, autoPage = d.autoPage, noteButton = true, wpm = d.wpm)
    }
}

/** Los colores de cada papel (para las muestras). */
private fun paperSwatch(id: Int): Pair<Color, Color> = when (id) {
    1 -> Color(0xFF2E231D) to Color(0xFFF0E6D4)
    2 -> Color(0xFF000000) to Color(0xFFE9CDA4)
    3 -> Color(0xFFF0E2C6) to Color(0xFF3B2A1A)
    4 -> Color(0xFF4A433C) to Color(0xFFE6DFD4)
    5 -> Color(0xFFF1EADB) to Color(0xFF2B241E)
    6 -> Color(0xFFD9C6A5) to Color(0xFF2A1F14)
    else -> Color(0xFFF7F2E8) to Color(0xFF2E231D)
}

/** Perfiles: un toque cambia todo; abajo, guardar los cambios o crear uno nuevo. */
@Composable
private fun ProfileRow(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val list by com.sebas.zerkalo.data.Profiles.list.collectAsState()
    val cur = list.firstOrNull { it.id == ui.profile }
    val same = cur?.let { com.sebas.zerkalo.data.Profiles.matches(ui.settings, it.values) } ?: false
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Label("Perfil", if (cur == null) "elige uno o guarda el tuyo" else if (same) cur.name else "${cur.name} · con cambios")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list.size) { k ->
            val pr = list[k]
            val on = pr.id == ui.profile
            val (bg, fg) = paperSwatch(pr.values.optInt("paper", ui.settings.paper.coerceAtLeast(0)))
            Column(
                Modifier.width(70.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, if (on) p.text else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { act.profile(pr.id) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp)).background(bg).border(1.dp, p.line, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text(
                        when (pr.id) { "rapida" -> "≡ ▸"; "estudio" -> "✎ Aa"; else -> "Aa" }, fontFamily = Fonts.literata,
                        fontSize = if (pr.id == "rapida" || pr.id == "estudio") 13.sp else 17.sp,
                        fontWeight = if (pr.values.optInt("weight", 0) > 0) FontWeight.Bold else FontWeight.Normal, color = fg,
                    )
                }
                Text(pr.name, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            Column(
                Modifier.width(70.dp).clip(RoundedCornerShape(14.dp)).clickable { naming = !naming }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp)).border(1.5.dp, p.text2.copy(alpha = 0.5f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text("＋", style = Type.title.copy(fontSize = 20.sp), color = p.accent)
                }
                Text("Nuevo", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (cur != null && !same) Text(
        "Guardar cambios en «${cur.name}»", style = Type.ui, color = p.accent,
        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { act.saveProfile(cur.id) }.padding(vertical = 6.dp),
    )
    if (naming) Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (name.isEmpty()) Text("Nombre del perfil (ej. «Bus»)", style = Type.ui, color = p.text2)
            BasicTextField(name, { name = it.take(20) }, singleLine = true, textStyle = Type.ui.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Guardar", style = Type.ui, color = p.accent,
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { act.newProfile(name); name = ""; naming = false }.padding(8.dp),
        )
    }
    if (cur != null && (cur.edited || !cur.builtin)) Text(
        if (cur.builtin) "Restaurar «${cur.name}» como venía" else "Borrar «${cur.name}»", style = Type.uiSmall, color = p.text2,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { act.deleteProfile(cur.id) }.padding(vertical = 6.dp),
    )
}

@Composable
private fun LightTab(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = ui.settings
    Label("Papel")
    val papers = com.sebas.zerkalo.reader.Paper.NAMES
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(papers.size) { k ->
            val (name, id) = papers[k]
            val on = s.paper == id
            val (bg, fg) = paperSwatch(id)
            Box(
                Modifier.size(64.dp, 52.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                    .border(if (on) 2.dp else 1.dp, if (on) p.accent else p.line, RoundedCornerShape(12.dp))
                    .clickable { act.settings(ui.settings.copy(paper = id)) },
                contentAlignment = Alignment.BottomCenter,
            ) { Text(name, style = Type.uiSmall.copy(fontSize = 10.5.sp), color = fg, modifier = Modifier.padding(bottom = 5.dp)) }
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallPill("Según la hora", s.paper == -2) { act.settings(ui.settings.copy(paper = -2)) }
        SmallPill("Como la app", s.paper == -1) { act.settings(ui.settings.copy(paper = -1)) }
    }
    if (com.sebas.zerkalo.reader.Paper.hasAny(ctx)) SwitchRow("Textura del papel", "Fibras y grano del papel de verdad", s.texture) { act.settings(ui.settings.copy(texture = it)) }

    Label("Luz")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Brillo automático", style = Type.ui, color = p.text)
            Text("El del teléfono", style = Type.uiSmall, color = p.text2)
        }
        ZSwitch(s.brightness < 0f) { act.settings(s.copy(brightness = if (it) -1f else 0.65f)) }
    }
    if (s.brightness >= 0f) {
        var b by remember(s.brightness) { mutableFloatStateOf(s.brightness) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            ZIcon("moon", p.text2, 18.dp)
            ZSlider(value = b, onChange = { b = it; act.brightnessLive(it) }, onFinished = { act.settings(ui.settings.copy(brightness = b)) }, marker = 0.3f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            ZIcon("sun", p.text2, 20.dp)
        }
        Text(if (b < 0.3f) "Más oscuro que el mínimo del teléfono" else "A la izquierda de la marca oscurece más que el mínimo del teléfono", style = Type.uiSmall, color = p.text2)
    }
    var veil by remember(s.veil) { mutableFloatStateOf(s.veil) }
    Label("Velo", if (veil <= 0f) "apagado" else "${(veil / 0.85f * 100).toInt()} %")
    ZSlider(value = veil, onChange = { veil = it; act.veilLive(it) }, onFinished = { act.settings(ui.settings.copy(veil = veil)) }, range = 0f..0.85f)
    var w by remember(s.warmth) { mutableFloatStateOf(s.warmth) }
    Label("Calidez", if (w <= 0f) "apagada" else "${(w * 100).toInt()} %")
    ZSlider(value = w, onChange = { w = it }, onFinished = { act.settings(ui.settings.copy(warmth = w)) })
    InkPicker(ui, act)

    // Cambio automático de perfil por hora
    var auto by remember { mutableStateOf(com.sebas.zerkalo.data.Prefs.profileAuto) }
    var hours by remember { mutableStateOf(com.sebas.zerkalo.data.Prefs.profileHours) }
    SwitchRow(
        "Cambiar solo según la hora",
        "Día desde las ${hours[0]}:00 · Noche desde las ${hours[1]}:00 · Oscuro total desde las ${hours[2]}:00",
        auto,
    ) { auto = it; com.sebas.zerkalo.data.Prefs.profileAuto = it; if (it) act.profile(com.sebas.zerkalo.data.Profiles.forHour(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))) }
    if (auto) listOf("Día" to 0, "Noche" to 1, "Oscuro total" to 2).forEach { (n, k) ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$n desde", style = Type.uiSmall, color = p.text2, modifier = Modifier.weight(1f))
            RoundPill("minus", { hours = hours.toMutableList().also { it[k] = (it[k] + 23) % 24 }; com.sebas.zerkalo.data.Prefs.profileHours = hours }, size = 32.dp)
            Text("${hours[k]}:00", style = Type.ui, color = p.text, textAlign = TextAlign.Center, modifier = Modifier.width(58.dp))
            RoundPill("plus", { hours = hours.toMutableList().also { it[k] = (it[k] + 1) % 24 }; com.sebas.zerkalo.data.Prefs.profileHours = hours }, size = 32.dp)
        }
    }
    Text("Atajos: desliza en el borde izquierdo para la luz · deja presionado ☀ para Oscuro total · dos toques en la hora para el siguiente perfil.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun LetterTab(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = ui.settings
    Label("Letra", info = Info.FONT)
    val gfont = GoogleFonts.get(ctx, s.font)
    val font = gfont?.let { ReaderFont(it.family, it.family, it.serif, note = "De Google Fonts") } ?: ReaderFonts.getOrElse(s.font) { ReaderFonts[0] }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.7f)).clickable { ui.sheet = "fonts" }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(font.name, fontFamily = if (gfont != null) GoogleFonts.preview(ctx, s.font) else FontFiles.preview(ctx, s.font.coerceIn(0, ReaderFonts.lastIndex)), fontSize = 19.sp, color = p.text)
            Text(font.note, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Cambiar", style = Type.uiSmall, color = p.accent)
        ZIcon("chevron", p.accent, 16.dp)
    }
    val pt = Letra.pt(s.size)
    Label("Tamaño", "$pt pt", Info.SIZE)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundPill("minus", { act.settings(s.copy(size = Letra.pct(pt - 1))) })
        var v by remember(s.size) { mutableFloatStateOf(pt.toFloat()) }
        ZSlider(
            value = v, onChange = { v = it }, range = Letra.PT_MIN.toFloat()..Letra.PT_MAX.toFloat(),
            onFinished = { act.settings(ui.settings.copy(size = Letra.pct(kotlin.math.round(v).toInt()))) },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        RoundPill("plus", { act.settings(s.copy(size = Letra.pct(pt + 1))) })
    }
    Label("Grosor", "más gruesa se lee mejor al sol")
    Segmented(listOf("Normal", "Media", "Gruesa"), s.weight.coerceIn(0, 2), { act.settings(ui.settings.copy(weight = it)) })

    // Letras por línea: automático (~66, lo ideal para leer de corrido) o las que elijas
    Label("Letras por línea", if (s.cpl == 0) "automático" else "${s.cpl} como máximo", Info.CPL)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallPill("Auto", s.cpl == 0) { act.settings(ui.settings.copy(cpl = 0)) }
        var c by remember(s.cpl) { mutableFloatStateOf(if (s.cpl == 0) 66f else s.cpl.toFloat()) }
        ZSlider(value = c, onChange = { c = it }, range = 25f..80f, onFinished = { act.settings(ui.settings.copy(cpl = kotlin.math.round(c).toInt())) }, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
    Text("Menos letras: el ojo recorre menos y no se pierde el renglón (bueno para leer rápido). 60–70: lo más cómodo para leer de corrido.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
    LineMeter(ui, act)

    Label("Interlineado", info = Info.LINE)
    val lhs = listOf(1.4f, 1.6f, 1.85f)
    Segmented(listOf("Compacto", "Medio", "Amplio"), lhs.indexOfFirst { kotlin.math.abs(it - s.lineHeight) < 0.05f }.coerceAtLeast(0), { act.settings(s.copy(lineHeight = lhs[it])) })
    Label("Espacio entre letras", info = Info.LETTERS)
    Segmented(listOf("Normal", "Amplio", "Muy amplio"), s.spacing, { act.settings(s.copy(spacing = it)) })
    SwitchRow("Modo dislexia", "Letra Lexend, más espacio entre letras y palabras, interlineado amplio y sin justificar", s.dyslexia) { on ->
        act.settings(
            if (on) ui.settings.withAlign(0).copy(dyslexia = true, font = 6, spacing = 1, lineHeight = 1.85f, paragraph = maxOf(ui.settings.paragraph, 1))
            else ui.settings.withAlign(2).copy(dyslexia = false, spacing = 0, lineHeight = 1.6f),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Solo para este libro", style = Type.ui, color = p.text)
            Text(if (ui.ownLook) "Letra y márgenes de este libro" else "Los mismos ajustes en todos los libros", style = Type.uiSmall, color = p.text2)
        }
        ZSwitch(ui.ownLook) { act.ownLook(it) }
    }
}

@Composable
private fun PageTab(ui: ReaderUi, act: ReaderActions) {
    val p = LocalPalette.current
    val s = ui.settings
    Label("Pasar página", info = Info.MODE)
    Segmented(listOf("Páginas", "Continuo"), if (s.continuous || s.scroll) 1 else 0, { act.settings(s.copy(scroll = it == 1, continuous = it == 1)) })
    Text(
        if (s.continuous || s.scroll) "Todo el libro seguido, como en Books: bajas con el dedo y encuentras todo, sin cortes entre capítulos." else "Como un libro de papel: una página a la vez.",
        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp),
    )
    Label("Diagramación", info = Info.LAYOUT)
    val order = listOf(3, 0, 1)
    Segmented(listOf("Ninguna", "Original", "Zerkalo"), order.indexOf(s.layout).let { if (it < 0) 2 else it }, { act.settings(s.copy(layout = order[it])) })
    Text(
        when (s.layout) {
            3 -> "El libro tal cual, sin ningún arreglo de Zerkalo."
            0 -> "El diseño de la editorial. Zerkalo solo arregla lo que se rompe en el teléfono."
            else -> "Cuidada por Zerkalo: títulos proporcionados, poemas verso a verso, sin líneas sueltas. Tus márgenes no los toca."
        },
        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 8.dp),
    )
    Label("Alineación", info = Info.ALIGN)
    val align = if (s.dyslexia) 0 else s.align
    Segmented(listOf("Izquierda", "Justificado", "Automático"), align, { act.settings(ui.settings.withAlign(it).copy(dyslexia = false)) })
    Label("Párrafos", info = Info.PARA)
    Segmented(listOf("Como el libro", "Separados", "Aireados"), s.paragraph, { act.settings(s.copy(paragraph = it)) })
    Label("Márgenes", "en milímetros", Info.MARGINS)
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
    SwitchRow("Animación al pasar página", "Apágala si prefieres un cambio instantáneo", s.animate) { act.settings(ui.settings.copy(animate = it)) }
    SwitchRow("Hora y avance junto a la cámara", "Se ven arriba mientras lees", s.info) { act.settings(ui.settings.copy(info = it)) }
}

@Composable
private fun HelpTab(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val s = ui.settings
    var blurLite by remember { mutableStateOf(com.sebas.zerkalo.data.Prefs.blurLite()) }
    Label("Lectura rápida", "${s.wpm} palabras por minuto")
    var w by remember(s.wpm) { mutableFloatStateOf(s.wpm.toFloat()) }
    ZSlider(value = w, onChange = { w = it }, range = 120f..600f, onFinished = { act.settings(ui.settings.copy(wpm = (kotlin.math.round(w / 10f) * 10).toInt())) })
    Text(
        "Una franja baja renglón por renglón a tu ritmo y pasa la página sola. Funciona mejor con líneas cortas (perfil «Rápida»). Tocar la pantalla la pausa.",
        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp),
    )
    PillButton(if (ui.guide) "Detener la guía" else "Empezar la guía", { act.toggleGuide(); onClose() }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = if (ui.guide) "pause" else "play")

    SwitchRow("Regla de lectura", "Resalta la línea que lees; arrástrala desde la derecha", s.ruler) { act.settings(ui.settings.copy(ruler = it)) }
    Label("Avance automático", if (s.scroll) "${s.autoSpeed.toInt()} píxeles por segundo" else "una página cada ${s.autoPage.toInt()} s")
    if (s.scroll) {
        var v by remember(s.autoSpeed) { mutableFloatStateOf(s.autoSpeed) }
        ZSlider(v, { v = it }, range = 4f..200f, onFinished = { act.settings(ui.settings.copy(autoSpeed = v)) })
    } else {
        var v by remember(s.autoPage) { mutableFloatStateOf(s.autoPage) }
        ZSlider(v, { v = it }, range = 5f..180f, onFinished = { act.settings(ui.settings.copy(autoPage = v)) })
    }
    PillButton(if (ui.auto) "Detener avance automático" else "Empezar avance automático", { act.toggleAuto(); onClose() }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = if (ui.auto) "pause" else "auto", ghost = true)
    SwitchRow("Botones de volumen", "Bajar volumen: siguiente · subir: anterior", s.volumeKeys) { act.settings(ui.settings.copy(volumeKeys = it)) }
    SwitchRow("Vibración suave al pasar página", "", s.haptic) { act.settings(ui.settings.copy(haptic = it)) }
    SwitchRow("Pausas para los ojos", "Un aviso suave cada 20 minutos de lectura", s.breaks) { act.settings(ui.settings.copy(breaks = it)) }
    SwitchRow("Botón del apunte (記)", "En la barra de abajo; si lo apagas, el apunte sigue en Herramientas", s.noteButton) { act.settings(ui.settings.copy(noteButton = it)) }
    SwitchRow("Desenfoque liviano en Continuo", "Menos capas: se mueve más fluido. Apágalo para el degradé completo.", blurLite) { on ->
        com.sebas.zerkalo.data.Prefs.setBlurLite(on); blurLite = on; act.settings(ui.settings)
    }
    Text(
        "Toques: izquierda o arriba, página anterior · derecha o abajo, siguiente · centro, menú. El borde izquierdo cambia la luz y atrás cierra lo abierto antes de salir del libro.",
        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 14.dp),
    )
}
