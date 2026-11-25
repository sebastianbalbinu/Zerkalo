package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La barrita de la pluma: pluma o resaltador, cuatro colores, borrar, deshacer y "Listo".
 * En los EPUB trae también flechas para pasar de página sin soltar la pluma.
 */
@Composable
fun InkBar(ui: InkUi, onUndo: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, onPage: ((Int) -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier.shadow(12.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(p.bg.copy(alpha = 0.96f))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPage != null) InkChip("‹", false) { onPage(-1) }
        InkChip("Pluma", ui.tool == InkTool.PEN) { ui.tool = InkTool.PEN }
        InkChip("Resaltador", ui.tool == InkTool.MARKER) { ui.tool = InkTool.MARKER }
        InkColors.forEach { c ->
            val on = ui.color == c && ui.tool != InkTool.ERASER
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .then(if (on) Modifier.border(3.dp, p.text, CircleShape) else Modifier)
                    .clickable { ui.color = c; if (ui.tool == InkTool.ERASER) ui.tool = InkTool.PEN },
            )
        }
        InkChip("Borrar", ui.tool == InkTool.ERASER) { ui.tool = InkTool.ERASER }
        InkChip("↶", false, enabled = ui.canUndo, onClick = onUndo)
        InkChip("Listo", false, accent = true, onClick = onDone)
        if (onPage != null) InkChip("›", false) { onPage(1) }
    }
}

@Composable
private fun InkChip(label: String, on: Boolean, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg = when { on -> p.accent; accent -> p.text; else -> p.surface }
    val fg = when { on -> Color.White; accent -> p.bg; else -> p.text.copy(alpha = if (enabled) 1f else 0.35f) }
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.uiSmall.copy(fontSize = 13.sp), color = fg) }
}
