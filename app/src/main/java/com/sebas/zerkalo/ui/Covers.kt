package com.sebas.zerkalo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sebas.zerkalo.data.Binding
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.CatalogItem
import com.sebas.zerkalo.data.Library
import java.io.File

data class Swatch(val name: String, val color: Color)

object Taller {
    val WASHI = listOf(
        Swatch("Koke · musgo", Color(0xFF5E6B3F)), Swatch("Ai · índigo", Color(0xFF3D4F66)),
        Swatch("Kuri · castaño", Color(0xFF6B4A3A)), Swatch("Enji · granate", Color(0xFF7A3B2E)),
        Swatch("Nezumi · gris", Color(0xFF9C8F86)), Swatch("Matsuba · pino", Color(0xFF2F3D2A)),
        Swatch("Sumi · tinta", Color(0xFF1F1D1B)), Swatch("Kinari · crudo", Color(0xFFEFE8DA)),
    )
    val THREAD = listOf(
        Swatch("Shu · bermellón", Color(0xFFC4432A)), Swatch("Kinari · crudo", Color(0xFFF4EFE4)),
        Swatch("Sumi · tinta", Color(0xFF1F1D1B)), Swatch("Kin · oro viejo", Color(0xFFB08D4C)),
    )
    val PATTERNS = listOf("Liso", "Seigaiha", "Asanoha")
    val LABELS = listOf(Swatch("Washi", Color(0xFFFBF8F2)), Swatch("Kinari", Color(0xFFEADFC8)), Swatch("Sin etiqueta", Color.Transparent))
}

private val coverShape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp)

/** Portada de un libro de la biblioteca: la original o la encuadernación japonesa. */
@Composable
fun BookCover(book: Book, width: Dp, height: Dp, modifier: Modifier = Modifier, ribbon: Boolean = false, elevation: Dp = 6.dp) {
    val file = remember(book.cover) { book.cover?.let { File(Library.coversDir, it) }?.takeIf { it.exists() } }
    CoverFrame(width, height, modifier, elevation) {
        if (book.useBinding || file == null) BindingCover(book.title, book.binding, Modifier.fillMaxSize())
        else {
            // Si la portada no tiene la forma del libro, se muestra entera (sin recortar el título ni el sello)
            // sobre una versión desenfocada de sí misma, en vez de cortarle los bordes.
            var ratio by androidx.compose.runtime.remember(file) { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val frame = width.value / height.value
            val fit = ratio > 0f && kotlin.math.abs(ratio - frame) / frame > 0.07f
            val req = ImageRequest.Builder(LocalContext.current).data(file).size(360).build()
            if (fit) {
                AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(14.dp))
                Box(Modifier.fillMaxSize().background(Color(0x40000000)))
            }
            AsyncImage(
                model = req, contentDescription = null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onSuccess = { s -> val d = s.result.drawable; if (d.intrinsicHeight > 0) ratio = d.intrinsicWidth.toFloat() / d.intrinsicHeight },
            )
        }
        if (ribbon) Box(
            Modifier.align(Alignment.TopEnd).padding(end = width * 0.12f).size(width * 0.09f, height * 0.26f)
                .background(LocalPalette.current.accent),
        )
    }
}

/** Portada de un resultado del catálogo: la encuadernación aparece mientras llega la imagen (o si no hay). */
@Composable
fun ItemCover(item: CatalogItem, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val binding = remember(item.key) { Binding.forSeed(item.key) }
    CoverFrame(width, height, modifier, 5.dp) {
        BindingCover(item.title, binding, Modifier.fillMaxSize())
        if (item.cover.isNotEmpty()) AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.cover).size(360).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CoverFrame(width: Dp, height: Dp, modifier: Modifier, elevation: Dp, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier.size(width, height).shadow(elevation, coverShape, clip = false, ambientColor = Color(0x66000000), spotColor = Color(0x88000000))
            .clip(coverShape).background(Color(0xFF6B4A3A)),
    ) {
        content()
        // Lomo: un brillo y una sombra suaves a la izquierda, como un libro de verdad.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0x33000000), 0.025f to Color(0x22FFFFFF), 0.05f to Color(0x14000000), 0.09f to Color.Transparent,
                ),
            ),
        )
    }
}

/** Encuadernación japonesa (fukuro-toji): washi, patrón, costura y etiqueta daisen con el título vertical. */
@Composable
fun BindingCover(title: String, b: Binding, modifier: Modifier = Modifier) {
    val washi = Taller.WASHI.getOrElse(b.washi) { Taller.WASHI[0] }.color
    val thread = Taller.THREAD.getOrElse(b.thread) { Taller.THREAD[0] }.color
    val label = Taller.LABELS.getOrElse(b.label) { Taller.LABELS[0] }.color
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val ink = Color(0xFF2E231D)
    Box(
        modifier.drawWithCache {
            val w = size.width; val h = size.height
            val light = washi.luminance() > 0.5f
            val patColor = if (light) Color(0x1A000000) else Color(0x22FFFFFF)
            val pattern = Path()
            when (b.pattern) {
                1 -> { // seigaiha: olas
                    val r = w * 0.11f
                    var row = 0
                    var y = 0f
                    while (y < h + r) {
                        var x = if (row % 2 == 0) 0f else r
                        while (x < w + 2 * r) {
                            for (k in 1..3) {
                                val rk = r * k / 3f
                                pattern.addArc(Rect(Offset(x, y), rk), 180f, 180f)
                            }
                            x += 2 * r
                        }
                        y += r * 0.5f; row++
                    }
                }
                2 -> { // asanoha: hojas de cáñamo
                    val s = w * 0.2f
                    val hh = s * 0.866f
                    var y = 0f; var row = 0
                    while (y < h + hh) {
                        var x = if (row % 2 == 0) 0f else s / 2
                        while (x < w + s) {
                            val cx = x; val cy = y + hh / 3
                            pattern.moveTo(x - s / 2, y + hh); pattern.lineTo(x, y); pattern.lineTo(x + s / 2, y + hh); pattern.close()
                            listOf(Offset(x, y), Offset(x - s / 2, y + hh), Offset(x + s / 2, y + hh)).forEach {
                                pattern.moveTo(cx, cy + hh / 3); pattern.lineTo(it.x, it.y)
                            }
                            x += s
                        }
                        y += hh; row++
                    }
                }
            }
            val sw = w * 0.12f
            val lw = w * 0.30f
            val lh = h * 0.68f
            val lx = sw + w * 0.1f
            val ly = h * 0.07f
            val fs = (w * 0.105f).coerceAtLeast(7f)
            val style = TextStyle(fontFamily = Fonts.mincho, fontSize = (fs / density).sp, color = ink, lineHeight = (fs * 1.25f / density).sp)
            val layout = if (b.label != 2 && title.isNotBlank()) measurer.measure(
                title, style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (lh - fs).toInt().coerceAtLeast(1), maxHeight = (lw - 6).toInt().coerceAtLeast(1)),
            ) else null
            onDrawBehind {
                drawRect(washi)
                if (b.pattern != 0) drawPath(pattern, patColor, style = Stroke(width = w * 0.008f + 0.6f))
                // costura
                drawRect(Color(0x14000000), topLeft = Offset(0f, 0f), size = Size(sw, h))
                drawLine(Color(0x22000000), Offset(sw, 0f), Offset(sw, h), strokeWidth = 1f)
                listOf(0.12f, 0.37f, 0.63f, 0.88f).forEach { f ->
                    val y = h * f
                    drawLine(thread, Offset(0f, y), Offset(sw * 0.62f, y), strokeWidth = w * 0.014f + 0.8f)
                    drawCircle(Color(0x55000000), radius = w * 0.012f + 0.6f, center = Offset(sw * 0.62f, y))
                }
                if (b.label != 2) {
                    drawRect(label, topLeft = Offset(lx, ly), size = Size(lw, lh))
                    drawRect(Color(0x1A000000), topLeft = Offset(lx, ly), size = Size(lw, lh), style = Stroke(1f))
                    layout?.let { tl ->
                        // Título vertical, como en los libros cosidos japoneses.
                        rotate(90f, pivot = Offset(lx + lw / 2, ly + lh / 2)) {
                            val left = lx + lw / 2 - lh / 2 + fs / 2
                            val top = ly + lh / 2 - lw / 2 + (lw - tl.size.height) / 2
                            drawText(tl, topLeft = Offset(left, top))
                        }
                    }
                }
            }
        },
    )
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** El ensō de Zerkalo. */
@Composable
fun EnsoImage(dark: Boolean, size: Dp) {
    Image(
        painter = androidx.compose.ui.res.painterResource(if (dark) com.sebas.zerkalo.R.drawable.enso_cream else com.sebas.zerkalo.R.drawable.enso_ink),
        contentDescription = null, modifier = Modifier.size(size),
    )
}
