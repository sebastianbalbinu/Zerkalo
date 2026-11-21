package com.sebas.zerkalo.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Íconos de trazo fino de Zerkalo (los mismos dibujos de siempre, ahora nativos). */
object Ic {
    private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private val paths = mapOf(
        "home" to "M4 4h3.2v16H4zM9.4 6h3.2v14H9.4zM15 5.6l3-.8 3.4 14.4-3 .8z",
        "tansu" to rr(3.5f, 4f, 17f, 16f, 2f) + "M3.5 12h17M10 8h4M10 16h4",
        "taller" to "M7 3.5h10v17l-5-3.8-5 3.8z",
        "cuaderno" to rr(4.5f, 3.5f, 15f, 17f, 2f) + c(12f, 11f, 3.2f),
        "search" to c(11f, 11f, 6.5f) + "M16 16l4.5 4.5",
        "back" to "M15 5l-7 7 7 7",
        "close" to "M6 6l12 12M18 6L6 18",
        "more" to c(6f, 12f, 1.1f) + c(12f, 12f, 1.1f) + c(18f, 12f, 1.1f),
        "clock" to c(12f, 12f, 8f) + "M12 7.5V12l3 2",
        "sun" to c(12f, 12f, 4f) + "M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8",
        "moon" to "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z",
        "aa" to "M3 18l4.5-12L12 18M4.6 14h5.8M14 18l3-8 3 8M15 15.5h4",
        "shiori" to "M8 3.5h8v17l-4-3.2-4 3.2z",
        "toc" to "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01",
        "share" to "M4.5 13v6a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-6M12 3.5v12M7.5 8L12 3.5 16.5 8",
        "gear" to c(12f, 12f, 3f) + "M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z",
        "plus" to "M12 5v14M5 12h14",
        "minus" to "M5 12h14",
        "download" to "M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14",
        "trash" to "M4.5 7h15M10 11v6M14 11v6M6 7l1 12.5a1.5 1.5 0 0 0 1.5 1.4h7a1.5 1.5 0 0 0 1.5-1.4L18 7M9 7V4.5h6V7",
        "check" to "M5 12.5l4.5 4.5L19 7.5",
        "note" to "M4 20l4-1 11-11-3-3L5 16z",
        "copy" to rr(8f, 8f, 12f, 12f, 2f) + "M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8",
        "folder" to "M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z",
        "import" to "M12 15V4M7.5 8.5L12 4l4.5 4.5M5 14v4.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V14",
        "chevron" to "M9 5l7 7-7 7",
        // Traducir: una A y un trazo de otro alfabeto
        "translate" to "M3.5 5.5h9M8 3.5v2M5.5 5.5c.6 3.2 2.7 5.6 6 7M10.5 5.5c-.7 3.4-3 6.1-6.5 7.6M12.5 20.5l4-10 4 10M13.8 17.3h5.4",
        // Dos páginas abiertas
        "pair" to "M12 6.5v13M12 6.5C10 5 7 4.5 3.5 5v13c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5z",
        "speaker" to "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11",
        "swap" to "M7 4.5L4 7.5l3 3M4 7.5h12.5M17 13.5l3 3-3 3M20 16.5H7.5",
        "notas" to rr(4.5f, 3.5f, 15f, 17f, 2f) + "M8.5 8.5h7M8.5 12h7M8.5 15.5h4",
    )

    private val cache = HashMap<String, ImageVector>()

    fun get(name: String, stroke: Float = 1.6f): ImageVector = cache.getOrPut("$name/$stroke") {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(paths[name] ?: ""),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

@Composable
fun ZIcon(name: String, tint: Color, size: Dp = 22.dp, stroke: Float = 1.6f, modifier: Modifier = Modifier) {
    Icon(Ic.get(name, stroke), contentDescription = null, tint = tint, modifier = modifier.size(size))
}
