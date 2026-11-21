package com.sebas.zerkalo.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag

import androidx.compose.foundation.layout.offset

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.R
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Vidrio esmerilado (cápsulas flotantes, barra de pestañas). */
fun glassStyle(p: Palette) = HazeStyle(
    backgroundColor = p.bg,
    tints = listOf(HazeTint(p.bg.copy(alpha = if (p.isDark) 0.55f else 0.62f))),
    blurRadius = 22.dp,
    noiseFactor = 0.06f,
)

/**
 * Desenfoque progresivo arriba o abajo de la pantalla: el contenido se desvanece sin escalones.
 * En Android 12 o más es desenfoque real; en teléfonos más viejos, un velo de color suave.
 */
@Composable
fun ProgressiveEdge(@Suppress("UNUSED_PARAMETER") state: HazeState, top: Boolean, height: Dp, modifier: Modifier = Modifier) {
    // Antes era un desenfoque progresivo que se recalculaba en cada cuadro al desplazar (caro en teléfonos medios).
    // Un degradado del color del papel da casi el mismo efecto y no cuesta nada.
    val p = LocalPalette.current
    val brush = remember(p.bg, top) {
        if (top) Brush.verticalGradient(listOf(p.bg.copy(alpha = 0.9f), p.bg.copy(alpha = 0.55f), Color.Transparent))
        else Brush.verticalGradient(listOf(Color.Transparent, p.bg.copy(alpha = 0.35f), p.bg.copy(alpha = 0.8f)))
    }
    Box(modifier.fillMaxWidth().height(height).background(brush))
}

/** Texturas de madera: se decodifican una sola vez para toda la app. */
object WoodBitmaps {
    private val cache = HashMap<Int, ImageBitmap>()
    fun get(res: android.content.res.Resources, id: Int): ImageBitmap = synchronized(cache) {
        cache.getOrPut(id) { ImageBitmap.imageResource(res, id) }
    }
}

/** Deslizador propio: pista fina, perilla redonda y una marca opcional (por ejemplo, el mínimo del brillo). */
@Composable
fun ZSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onFinished: (() -> Unit)? = null,
    marker: Float? = null,
) {
    val p = LocalPalette.current
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val finished by androidx.compose.runtime.rememberUpdatedState(onFinished)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).pointerInput(range) {
            fun set(x: Float) = change(range.start + (x / size.width).coerceIn(0f, 1f) * span)
            awaitEachGesture {
                val down = awaitFirstDown()
                set(down.position.x); down.consume()
                drag(down.id) { set(it.position.x); it.consume() }
                finished?.invoke()
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val f = ((value - range.start) / span).coerceIn(0f, 1f)
        val knob = 22.dp
        Box(Modifier.padding(horizontal = knob / 2).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.line.copy(alpha = 0.5f)))
        Box(Modifier.padding(start = knob / 2).width((maxWidth - knob) * f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
        marker?.let { m ->
            val mf = ((m - range.start) / span).coerceIn(0f, 1f)
            Box(Modifier.offset(x = knob / 2 + (maxWidth - knob) * mf - 1.dp).size(2.dp, 12.dp).background(p.text2.copy(alpha = 0.6f)))
        }
        Box(
            Modifier.offset(x = (maxWidth - knob) * f).size(knob).shadow(4.dp, CircleShape).clip(CircleShape).background(p.surface)
                .border(2.5.dp, p.accent, CircleShape),
        )
    }
}

/** Botón redondo, con un pequeño "hundimiento" al tocarlo. */
@Composable
fun CircleButton(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, glass: HazeState? = null, desc: String = "") {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    var m = modifier.scale(s).size(size).clip(CircleShape)
    m = if (glass != null) m.hazeEffect(glass, glassStyle(p)) else m.background(p.surface.copy(alpha = if (p.isDark) 0.85f else 1f))
    Box(m.clickable(src, null, onClick = onClick), contentAlignment = Alignment.Center) {
        ZIcon(icon, p.text, size = size * 0.46f)
    }
}

/** Botón cápsula principal (tinta sobre papel). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, ghost: Boolean = false, enabled: Boolean = true, accent: Boolean = false) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val bg = when { accent -> p.accent; ghost -> p.surface; else -> p.text }
    val fg = when { accent -> Color.White; ghost -> p.text; else -> p.bg }
    Row(
        modifier.scale(s).height(52.dp).clip(RoundedCornerShape(26.dp)).background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(src, null, enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { ZIcon(icon, fg, 19.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.button, color = fg, maxLines = 1)
    }
}

@Composable
fun SmallPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (selected) p.text else p.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.uiSmall.copy(fontSize = 14.sp), color = if (selected) p.bg else p.text) }
}

@Composable
fun Chip(text: String) {
    val p = LocalPalette.current
    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(p.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = Type.uiSmall, color = p.text2)
    }
}

@Composable
fun Caption(text: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text.uppercase(), style = Type.caption, color = if (accent) p.accent else p.text2, modifier = modifier, maxLines = 1)
}

@Composable
fun SectionTitle(title: String, more: String = "", kicker: String = "", modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (kicker.isNotEmpty()) { Caption(kicker, accent = true); Spacer(Modifier.height(2.dp)) }
            Text(title, style = Type.section, color = p.text)
        }
        if (more.isNotEmpty()) Text(more, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.line)) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(3.dp).background(p.accent))
    }
}

/** Tarjeta redondeada de superficie. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(if (p.wood == Wood.NOGAL) p.surface.copy(alpha = 0.82f) else p.surface)
            .border(0.5.dp, p.line, RoundedCornerShape(22.dp)).padding(padding),
    ) { content() }
}

/** Sello bermellón (hanko). */
@Composable
fun Seal(text: String, size: Dp, tilt: Float = 0f, filled: Boolean = true, fontSize: Float = 13f) {
    val p = LocalPalette.current
    Box(
        Modifier.rotate(tilt).size(size).clip(RoundedCornerShape(size * 0.18f))
            .then(if (filled) Modifier.background(p.accent.copy(alpha = 0.92f)) else Modifier.border(1.dp, p.line, RoundedCornerShape(size * 0.18f))),
        contentAlignment = Alignment.Center,
    ) {
        if (filled) Text(
            text, color = Color(0xFFFBF3E6), fontFamily = Fonts.mincho, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp,
            textAlign = TextAlign.Center, lineHeight = (fontSize * 1.15f).sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(6.dp),
        )
    }
}

/** Estante de madera (keyaki en Crema, nogal en Nogal) con los libros apoyados encima. */
@Composable
fun Shelf(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    val (topRes, frontRes) = when (p.wood) {
        Wood.NOGAL -> R.drawable.shelf_nogal_top to R.drawable.shelf_nogal_front
        else -> R.drawable.shelf_keyaki_top to R.drawable.shelf_keyaki_front
    }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val top = woodBrush(WoodBitmaps.get(res, topRes))
    val front = woodBrush(WoodBitmaps.get(res, frontRes))
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(12.dp).background(top).background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x00000000)))))
            Box(
                Modifier.fillMaxWidth().height(16.dp).shadow(8.dp, RoundedCornerShape(0.dp), clip = false).background(front)
                    .background(Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x00000000), Color(0x40000000)))),
            )
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 22.dp)) { content() }
    }
}

@Composable
private fun woodBrush(img: ImageBitmap): Brush = remember(img) { ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Clamp)) }

@Composable
fun Logo(dark: Boolean, width: Dp) {
    Image(
        painter = androidx.compose.ui.res.painterResource(if (dark) R.drawable.logo_dark else R.drawable.logo_light),
        contentDescription = "Zerkalo", modifier = Modifier.width(width),
    )
}

@Composable
fun ListRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.ui.copy(fontSize = 16.sp), color = p.text)
            if (subtitle.isNotEmpty()) Text(subtitle, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

@Composable
fun ZSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (on) 1f else 0f, label = "sw")
    Box(
        Modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) p.accent else p.card)
            .clickable { onChange(!on) }.padding(3.dp),
    ) {
        Box(Modifier.padding(start = (20 * x).dp).size(24.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color(0xFFFBF8F2)))
    }
}

/** Selector segmentado (Letra, Interlineado, Papel…). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.6f)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (i == selected) p.bg else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Type.uiSmall.copy(fontSize = 14.sp), color = if (i == selected) p.text else p.text2, maxLines = 1) }
        }
    }
}
