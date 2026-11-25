package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Dónde quedó la columnita de la pluma (fracción de la altura), para todos los libros. */
private var pillY = 0.42f

/**
 * La pluma mínima: una columnita al borde derecho con la herramienta, el color, deshacer y "listo".
 * Al tocar la herramienta o el color se abre al lado una fila con las opciones; así la página queda libre.
 * Se arrastra hacia arriba o abajo desde la rayita de arriba. En los EPUB trae ‹ › para pasar de página.
 */
@Composable
fun InkPill(ui: InkUi, onUndo: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, onPage: ((Int) -> Unit)? = null) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var open by remember { mutableStateOf<String?>(null) } // "tool" o "color"
    var y by remember { mutableFloatStateOf(pillY) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxH = with(density) { maxHeight.toPx() }
        val colH = with(density) { (if (onPage != null) 300.dp else 220.dp).toPx() }
        val top = (y * maxH).coerceIn(0f, (maxH - colH).coerceAtLeast(0f))
        Row(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top.roundToInt()) }.padding(end = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Fila de opciones al lado de la columnita
            if (open != null) {
                Row(
                    Modifier.padding(top = if (open == "tool") 18.dp else 66.dp, end = 8.dp)
                        .shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(p.bg.copy(alpha = 0.97f))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (open == "tool") {
                        ToolChip("Pluma", ui.tool == InkTool.PEN) { ui.tool = InkTool.PEN; open = null }
                        ToolChip("Resaltador", ui.tool == InkTool.MARKER) {
                            ui.tool = InkTool.MARKER
                            if (ui.color == InkColors[0]) ui.color = InkColors[1] // el resaltador negro tapa la letra
                            open = null
                        }
                        ToolChip("Borrar", ui.tool == InkTool.ERASER) { ui.tool = InkTool.ERASER; open = null }
                    } else {
                        InkColors.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                    .then(if (ui.color == c) Modifier.border(3.dp, p.text, CircleShape) else Modifier.border(1.dp, p.line, CircleShape))
                                    .clickable { ui.color = c; if (ui.tool == InkTool.ERASER) ui.tool = InkTool.PEN; open = null },
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(p.bg.copy(alpha = 0.95f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Rayita para arrastrar la columnita
                Box(
                    Modifier.size(40.dp, 14.dp).pointerInput(Unit) {
                        detectVerticalDragGestures { change, dy ->
                            change.consume()
                            y = ((y * maxH + dy) / maxH).coerceIn(0f, ((maxH - colH) / maxH).coerceAtLeast(0f))
                            pillY = y
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(22.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(p.text2.copy(alpha = 0.5f))) }
                val glyph = when (ui.tool) { InkTool.PEN -> "✎"; InkTool.MARKER -> "▌"; InkTool.ERASER -> "⌫" }
                Round(glyph, p.accent, Color.White) { open = if (open == "tool") null else "tool" }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(p.surface).clickable { open = if (open == "color") null else "color" },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(24.dp).clip(CircleShape).background(Color(ui.color)).border(1.dp, p.line, CircleShape)) }
                Round("↶", p.surface, p.text.copy(alpha = if (ui.canUndo) 1f else 0.3f), enabled = ui.canUndo, onClick = onUndo)
                if (onPage != null) {
                    Round("‹", p.surface, p.text) { onPage(-1) }
                    Round("›", p.surface, p.text) { onPage(1) }
                }
                Round("✓", p.text, p.bg, onClick = onDone)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun Round(label: String, bg: Color, fg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = Type.ui.copy(fontSize = 17.sp), color = fg)
    }
}

@Composable
private fun ToolChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) p.accent else p.surface).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, style = Type.uiSmall.copy(fontSize = 13.sp), color = if (on) Color.White else p.text) }
}

/** Cuadrito para la bandeja de herramientas: un ícono o letra grande y su nombre debajo. */
@Composable
fun ToolTile(glyph: String, label: String, modifier: Modifier = Modifier, active: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(tint ?: if (active) p.accent else p.card.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = Type.title.copy(fontSize = 21.sp), color = if (active) Color.White else if (tint != null) Color(0xFF3B2B1A) else p.text) }
        Text(label, style = Type.uiSmall.copy(fontSize = 11.5.sp), color = p.text2, maxLines = 1, modifier = Modifier.padding(top = 6.dp).width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
