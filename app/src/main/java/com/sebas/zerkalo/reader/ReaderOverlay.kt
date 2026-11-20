package com.sebas.zerkalo.reader

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.sebas.zerkalo.ui.Seal
import com.sebas.zerkalo.ui.Segmented
import com.sebas.zerkalo.ui.Type
import com.sebas.zerkalo.ui.ZIcon
import com.sebas.zerkalo.ui.ZSwitch
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

        // Filtro cálido: un velo ámbar muy suave sobre la página (no recibe toques).
        if (ui.settings.warmth > 0f) Box(Modifier.fillMaxSize().background(Color(0xFFFF8A2B).copy(alpha = ui.settings.warmth * 0.24f)))

        // Cinta del shiori en la esquina, como un marcapáginas de tela.
        AnimatedVisibility(ui.bookmarked, Modifier.align(Alignment.TopEnd).padding(end = 28.dp), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            Box(Modifier.size(14.dp, 54.dp).background(p.accent))
        }

        // Pie discreto mientras se lee
        AnimatedVisibility(!ui.controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(400)), exit = fadeOut(tween(150))) {
            Text(
                "${(ui.progress * 100).toInt()} %", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2.copy(alpha = 0.6f),
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 6.dp),
            )
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
                    Spacer(Modifier.width(46.dp))
                }
            }
        }

        // Barra inferior: cápsulas flotantes
        AnimatedVisibility(ui.controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(200)) + slideInVertically { it / 3 }, exit = fadeOut(tween(180)) + slideOutVertically { it / 3 }) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg.copy(alpha = 0.94f), p.bg)))
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 10.dp),
            ) {
                var drag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: ui.progress, onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let(act.seek); drag = null },
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line),
                    modifier = Modifier.height(30.dp),
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
    }

    when (ui.sheet) {
        "toc" -> TocSheet(ui, act) { ui.sheet = null }
        "type" -> TypeSheet(ui, act) { ui.sheet = null }
        "luz" -> LightSheet(ui, act) { ui.sheet = null }
    }
    ui.activeMark?.let { MarkSheet(it, act) { ui.activeMark = null } }
    ui.noteFor?.let { NoteSheet(it, act) { ui.noteFor = null } }
    if (ui.finishedNow) FinishedSheet { ui.finishedNow = false }
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
    var tab by remember { mutableIntStateOf(0) }
    val shioris = ui.marks.filter { it.type == "shiori" }.sortedBy { it.progress }
    val hls = ui.marks.filter { it.type == "hl" }.sortedBy { it.progress }
    Sheet(onClose) {
        Segmented(listOf("Índice", "Marcas · ${shioris.size + hls.size}"), tab, { tab = it })
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
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
                items(shioris) { m ->
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

private val fontPreview: List<FontFamily> = listOf(Fonts.literata, Fonts.mincho, Fonts.garamond, Fonts.gothic, Fonts.gothic)

@Composable
private fun TypeSheet(ui: ReaderUi, act: ReaderActions, onClose: () -> Unit) {
    val p = LocalPalette.current
    val s = ui.settings
    Sheet(onClose) {
        Text("Lectura", style = Type.title.copy(fontSize = 21.sp), color = p.text)
        Label("Modo")
        Segmented(listOf("Páginas", "Desplazamiento continuo"), if (s.scroll) 1 else 0, { act.settings(s.copy(scroll = it == 1)) })
        Label("Letra")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ReaderFonts.size) { i ->
                Box(
                    Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(if (i == s.font) p.text else p.card.copy(alpha = 0.7f))
                        .clickable { act.settings(s.copy(font = i)) }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(ReaderFonts[i], fontFamily = fontPreview[i], fontSize = 16.sp, color = if (i == s.font) p.bg else p.text) }
            }
        }
        Label("Tamaño", "${s.size} %")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundPill("minus", { act.settings(s.copy(size = (s.size - 10).coerceAtLeast(70))) })
            var v by remember(s.size) { mutableFloatStateOf(s.size.toFloat()) }
            Slider(
                value = v, onValueChange = { v = it }, valueRange = 70f..200f,
                onValueChangeFinished = { act.settings(s.copy(size = (v / 5).toInt() * 5)) },
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            RoundPill("plus", { act.settings(s.copy(size = (s.size + 10).coerceAtMost(200))) })
        }
        Label("Interlineado")
        val lhs = listOf(1.4f, 1.6f, 1.85f)
        Segmented(listOf("Compacto", "Medio", "Amplio"), lhs.indexOfFirst { kotlin.math.abs(it - s.lineHeight) < 0.05f }.coerceAtLeast(0), { act.settings(s.copy(lineHeight = lhs[it])) })
        Label("Márgenes")
        Segmented(listOf("Estrechos", "Medios", "Amplios"), s.margin, { act.settings(s.copy(margin = it)) })
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Justificar el texto", style = Type.ui, color = p.text)
                Text("Con guiones, como un libro impreso", style = Type.uiSmall, color = p.text2)
            }
            ZSwitch(s.justify) { act.settings(s.copy(justify = it)) }
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
            ZSwitch(s.brightness < 0f) { act.settings(s.copy(brightness = if (it) -1f else 0.5f)) }
        }
        if (s.brightness >= 0f) {
            var b by remember { mutableFloatStateOf(s.brightness) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ZIcon("moon", p.text2, 18.dp)
                Slider(
                    value = b, onValueChange = { b = it; act.settings(ui.settings.copy(brightness = it)) }, valueRange = 0.02f..1f,
                    colors = sliderColors, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                ZIcon("sun", p.text2, 20.dp)
            }
        }
        Label("Filtro cálido", if (s.warmth <= 0f) "apagado" else "${(s.warmth * 100).toInt()} %")
        var w by remember { mutableFloatStateOf(s.warmth) }
        Slider(value = w, onValueChange = { w = it; act.settings(ui.settings.copy(warmth = it)) }, valueRange = 0f..1f, colors = sliderColors)
        Label("Papel")
        Segmented(listOf("Crema", "Nogal", "Sumi", "Como la app"), if (s.paper in 0..2) s.paper else 3, { act.settings(s.copy(paper = if (it == 3) -1 else it)) })
        if (ui.palette.isDark) {
            Label("Color de la letra")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(InkNames.size) { i ->
                    val c = when (i) { 0 -> null; 5 -> Color(s.customInk); else -> Color(InkColors[i]) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { act.settings(s.copy(ink = i)) }) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(p.bg)
                                .then(if (i == s.ink) Modifier.background(p.accent.copy(alpha = 0.25f)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", fontFamily = Fonts.literata, fontSize = 17.sp, color = c ?: originalInk(s)) }
                        Text(InkNames[i], style = Type.uiSmall.copy(fontSize = 11.sp), color = if (i == s.ink) p.accent else p.text2, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (s.ink == 5) {
                // Color propio: tono y claridad; la saturación se mantiene suave para no cansar la vista.
                val hsv = remember(s.customInk) { FloatArray(3).also { android.graphics.Color.colorToHSV(s.customInk, it) } }
                var hue by remember { mutableFloatStateOf(hsv[0]) }
                var light by remember { mutableFloatStateOf(hsv[2]) }
                fun push() = act.settings(ui.settings.copy(customInk = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.22f, light))))
                Label("Tono")
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 0.35f, 0.9f) })))
                Slider(value = hue, onValueChange = { hue = it }, onValueChangeFinished = { push() }, valueRange = 0f..359f, colors = sliderColors)
                Label("Claridad")
                Slider(value = light, onValueChange = { light = it }, onValueChangeFinished = { push() }, valueRange = 0.55f..1f, colors = sliderColors)
            }
        }
    }
}

private fun originalInk(s: com.sebas.zerkalo.data.ReaderSettings): Color = when (s.paper) {
    2 -> Color(0xFFE9CDA4)
    else -> Color(0xFFF0E6D4)
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
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (0..3).forEach { c ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(highlightColor(c)).clickable { act.recolor(m, c) })
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

@Composable
private fun FinishedSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onClose) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Seal("読了", 104.dp, -4f, fontSize = 22f)
            Text("Terminaste el libro", style = Type.title, color = p.text, modifier = Modifier.padding(top = 18.dp))
            Text("Su sello quedó en tu Cuaderno.", style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            PillButton("Gracias", onClose, Modifier.fillMaxWidth())
        }
    }
}
