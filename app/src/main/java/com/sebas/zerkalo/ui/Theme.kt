package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.R
import com.sebas.zerkalo.data.PaperPref
import com.sebas.zerkalo.data.Prefs

/** Paleta de Zerkalo. Las portadas reales no cambian entre modos; el bermellón es solo para sellos y acentos. */
@Immutable
data class Palette(
    val name: String,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val text: Color,
    val text2: Color,
    val accent: Color = Color(0xFFC4432A),
    val line: Color,
    val isDark: Boolean,
    val wood: Wood,
)

enum class Wood { KEYAKI, NOGAL, NONE }

val Chashitsu = Palette(
    "Crema", bg = Color(0xFFF7F2E8), surface = Color(0xFFEDE4D4), card = Color(0xFFDFD3C1),
    text = Color(0xFF2E231D), text2 = Color(0xFF5C4B3F), line = Color(0x1F2E231D), isDark = false, wood = Wood.KEYAKI,
)
val Nogal = Palette(
    "Nogal", bg = Color(0xFF2E231D), surface = Color(0xFF3B2B24), card = Color(0xFF4A362C),
    text = Color(0xFFF0E6D4), text2 = Color(0xFFCDBAA3), line = Color(0x24F0E6D4), isDark = true, wood = Wood.NOGAL,
)
val SumiPalette = Palette(
    "Sumi", bg = Color(0xFF000000), surface = Color(0xFF0F0C0A), card = Color(0xFF1C1713),
    text = Color(0xFFE9CDA4), text2 = Color(0xFFA48D6C), line = Color(0x26E9CDA4), isDark = true, wood = Wood.NONE,
)

/** Papeles extra del lector. */
val SepiaPalette = Palette(
    "Sepia", bg = Color(0xFFF0E2C6), surface = Color(0xFFE6D5B5), card = Color(0xFFD9C6A2),
    text = Color(0xFF3B2A1A), text2 = Color(0xFF6A5640), line = Color(0x1F3B2A1A), isDark = false, wood = Wood.KEYAKI,
)
val GrisPalette = Palette(
    "Gris", bg = Color(0xFF4A433C), surface = Color(0xFF554D45), card = Color(0xFF625950),
    text = Color(0xFFE6DFD4), text2 = Color(0xFFB5ADA1), line = Color(0x24E6DFD4), isDark = true, wood = Wood.NONE,
)

val WashiPalette = Palette(
    "Washi", bg = Color(0xFFF1EADB), surface = Color(0xFFE7DECB), card = Color(0xFFD9CDB6),
    text = Color(0xFF2B241E), text2 = Color(0xFF5A4C40), line = Color(0x1F2B241E), isDark = false, wood = Wood.KEYAKI,
)
val KraftPalette = Palette(
    "Kraft", bg = Color(0xFFD9C6A5), surface = Color(0xFFCDB894), card = Color(0xFFBFA883),
    text = Color(0xFF2A1F14), text2 = Color(0xFF55432F), line = Color(0x222A1F14), isDark = false, wood = Wood.KEYAKI,
)

val LocalPalette = staticCompositionLocalOf { Chashitsu }

object Fonts {
    val mincho = FontFamily(
        Font(R.font.shippori_mincho_400_normal, FontWeight.Normal),
        Font(R.font.shippori_mincho_500_normal, FontWeight.Medium),
        Font(R.font.shippori_mincho_600_normal, FontWeight.SemiBold),
    )
    val gothic = FontFamily(
        Font(R.font.zen_kaku_gothic_new_400_normal, FontWeight.Normal),
        Font(R.font.zen_kaku_gothic_new_500_normal, FontWeight.Medium),
        Font(R.font.zen_kaku_gothic_new_700_normal, FontWeight.Bold),
    )
    val literata = FontFamily(
        Font(R.font.literata_400_normal, FontWeight.Normal),
        Font(R.font.literata_500_normal, FontWeight.Medium),
        Font(R.font.literata_400_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val garamond = FontFamily(
        Font(R.font.eb_garamond_400_normal, FontWeight.Normal),
        Font(R.font.eb_garamond_400_italic, FontWeight.Normal, FontStyle.Italic),
    )
}

object Type {
    val display = TextStyle(fontFamily = Fonts.mincho, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontFamily = Fonts.mincho, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
    val section = TextStyle(fontFamily = Fonts.mincho, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp)
    val bookTitle = TextStyle(fontFamily = Fonts.mincho, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Fonts.literata, fontSize = 15.sp, lineHeight = 24.sp)
    val ui = TextStyle(fontFamily = Fonts.gothic, fontSize = 15.sp, lineHeight = 20.sp)
    val uiSmall = TextStyle(fontFamily = Fonts.gothic, fontSize = 13.sp, lineHeight = 17.sp)
    val caption = TextStyle(fontFamily = Fonts.gothic, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 2.2.sp)
    val button = TextStyle(fontFamily = Fonts.gothic, fontWeight = FontWeight.Medium, fontSize = 16.sp)
}

@Composable
fun currentPalette(): Palette {
    val pref by Prefs.paper.collectAsState()
    val sys = isSystemInDarkTheme()
    return when (pref) {
        PaperPref.CREMA -> Chashitsu
        PaperPref.NOGAL -> Nogal
        PaperPref.SUMI -> SumiPalette
        PaperPref.SISTEMA -> if (sys) Nogal else Chashitsu
    }
}

@Composable
fun ZerkaloTheme(content: @Composable () -> Unit) {
    val p = currentPalette()
    val scheme = if (p.isDark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bg, surface = p.surface, onSurface = p.text,
        onBackground = p.text, surfaceContainer = p.surface, surfaceContainerHigh = p.card, surfaceContainerLow = p.surface,
        onSurfaceVariant = p.text2, outline = p.line,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bg, surface = p.surface, onSurface = p.text,
        onBackground = p.text, surfaceContainer = p.surface, surfaceContainerHigh = p.card, surfaceContainerLow = p.surface,
        onSurfaceVariant = p.text2, outline = p.line,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Fondo de la app. En Nogal es madera de nogal de verdad (una textura repetida, se dibuja una vez);
 * en Crema, papel washi; en Sumi, negro puro para pantallas OLED.
 */
@Composable
fun Modifier.paperBackground(p: Palette = LocalPalette.current): Modifier {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val bmp: ImageBitmap? = when (p.wood) {
        Wood.NOGAL -> WoodBitmaps.get(res, R.drawable.wood_nogal_bg)
        Wood.KEYAKI -> WoodBitmaps.get(res, R.drawable.washi_bg)
        Wood.NONE -> null
    }
    val brush: Brush? = remember(bmp) { bmp?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    if (brush == null) return this.background(p.bg)
    // Luz suave que cae desde arriba y bordes un poco más oscuros, como un mueble bajo una lámpara.
    val glow = if (p.isDark) Color(0x1FFFE2B8) else Color(0x22FFFFFF)
    val shade = if (p.isDark) Color(0x8C0E0805) else Color(0x1A5C4B3F)
    return this.background(p.bg).background(brush).drawWithCache {
        val light = Brush.radialGradient(
            listOf(glow, Color.Transparent),
            center = Offset(size.width * 0.5f, -size.height * 0.08f),
            radius = size.maxDimension * 0.75f,
        )
        val vignette = Brush.radialGradient(
            0.55f to Color.Transparent, 1f to shade,
            center = Offset(size.width * 0.5f, size.height * 0.42f),
            radius = size.maxDimension * 0.78f,
        )
        onDrawBehind { drawRect(light); drawRect(vignette) }
    }
}
