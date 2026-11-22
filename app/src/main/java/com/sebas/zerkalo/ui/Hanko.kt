package com.sebas.zerkalo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Stamps
import kotlin.math.sin
import kotlin.random.Random

private val Paper = Color(0xFFF7F2E8)

/**
 * El sello de Zerkalo: un cuadrado de tinta con el kanji en blanco (sello inverso).
 * [ink]: tinta del rango (bermellón, índigo, negra, púrpura u oro, que brilla).
 * [wax]: si viene, es un sello de cera mezclado con los colores de la portada (raro).
 * [ghost]: todavía no se gana (punteado, sin tinta).
 */
@Composable
fun Hanko(
    kanji: String,
    size: Dp,
    modifier: Modifier = Modifier,
    ink: Stamps.Ink = Stamps.INKS[0],
    wax: List<Int>? = null,
    tilt: Float = 0f,
    seed: Int = kanji.hashCode(),
    ghost: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val p = LocalPalette.current
    // Remolinos de cera: siempre los mismos para el mismo sello
    val waves = remember(seed, wax) {
        val r = Random(seed)
        List(16) { Triple(r.nextFloat() * 6.28f, 1.2f + r.nextFloat() * 2.2f, 0.05f + r.nextFloat() * 0.1f) }
    }
    Canvas(modifier.size(size).rotate(tilt)) {
        val w = this.size.width
        val corner = CornerRadius(w * 0.08f)
        val inset = w * 0.055f
        val box = Size(w - inset * 2, w - inset * 2)
        val start = Offset(inset, inset)
        if (ghost) {
            drawRoundRect(p.text2.copy(alpha = 0.45f), start, box, corner, style = Stroke(w * 0.025f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.05f, w * 0.05f))))
        } else if (wax != null && wax.size >= 2) {
            val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(start.x, start.y, start.x + box.width, start.y + box.height, corner)) }
            clipPath(clip) {
                drawRect(Color(wax[0]))
                // Bandas onduladas de cada color, torcidas como lacre mezclado
                waves.forEachIndexed { j, (phase, freq, amp) ->
                    val col = Color(wax[(j + 1) % wax.size])
                    val y0 = w * (j / 15f) * 1.2f - w * 0.1f
                    val path = Path()
                    var x = -w * 0.1f
                    path.moveTo(x, y0)
                    while (x <= w * 1.1f) {
                        val y = y0 + sin(x / w * freq * 6.28f + phase) * w * amp + sin(x / w * 2.1f + phase * 1.7f) * w * 0.06f
                        path.lineTo(x, y)
                        x += w / 40f
                    }
                    drawPath(path, col, style = Stroke(w * (0.07f + amp * 0.6f), cap = StrokeCap.Round))
                }
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent, Color.Black.copy(alpha = 0.2f)), Offset.Zero, Offset(w, w)))
            }
        } else {
            val brush = if (ink.metal) Brush.linearGradient(
                0f to Color(ink.dark), 0.42f to Color(ink.light), 0.55f to Color(0xFFB8891F), 1f to Color(ink.dark),
                start = Offset.Zero, end = Offset(w, w),
            ) else Brush.linearGradient(listOf(Color(ink.light), Color(ink.dark)), Offset.Zero, Offset(w, w))
            drawRoundRect(brush, start, box, corner)
        }
        // Filete interior y kanji en blanco
        val line = if (ghost) p.text2.copy(alpha = 0.25f) else Paper.copy(alpha = 0.55f)
        val i2 = w * 0.12f
        drawRoundRect(line, Offset(i2, i2), Size(w - i2 * 2, w - i2 * 2), CornerRadius(w * 0.04f), style = Stroke(w * 0.016f))
        val style = TextStyle(
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = (w * 0.5f / density).sp,
            color = if (ghost) p.text2.copy(alpha = 0.35f) else Paper,
            shadow = if (wax != null && !ghost) Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, w * 0.01f), w * 0.03f) else null,
        )
        val m = measurer.measure(kanji, style)
        drawText(m, topLeft = Offset((w - m.size.width) / 2f, (w - m.size.height) / 2f))
    }
}
