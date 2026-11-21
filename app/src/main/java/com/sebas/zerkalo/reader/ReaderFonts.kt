package com.sebas.zerkalo.reader

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle

/**
 * Una tipografía del lector. [css] es el nombre que ve el libro; null = la del propio libro.
 * El índice en [ReaderFonts] es lo que se guarda en las preferencias, así que solo se agregan al final.
 */
data class ReaderFont(
    val name: String,
    val css: String?,
    val serif: Boolean,
    val regular: String? = null,
    val italic: String? = null,
    val bold: String? = null,
    val note: String = "",
    val boldAlt: String? = null, // negrita de respaldo si [bold] no vino en esta compilación
)

val ReaderFonts: List<ReaderFont> = listOf(
    // Negrita real: Literata y Garamond la toman de su versión completa (variable); si no está, la de antes.
    ReaderFont("Literata", "Literata", true, "literata_400_normal.ttf", "literata_400_italic.ttf", "literata_v.ttf", "Hecha para leer libros en pantalla", boldAlt = "literata_500_normal.ttf"),
    // Mincho y Gótica no tienen cursiva: se usa una cursiva hermana de verdad en vez de inclinar la letra.
    ReaderFont("Mincho", "Shippori Mincho", true, "shippori_mincho_400_normal.ttf", "eb_garamond_400_italic.ttf", "shippori_mincho_600_normal.ttf", "Elegante, de inspiración japonesa"),
    ReaderFont("Garamond", "EB Garamond", true, "eb_garamond_400_normal.ttf", "eb_garamond_400_italic.ttf", "garamond_v.ttf", "Clásica de libro impreso"),
    ReaderFont("Gótica", "Zen Kaku Gothic New", false, "zen_kaku_gothic_new_400_normal.ttf", "notosans_i.ttf", "zen_kaku_gothic_new_700_normal.ttf", "Sencilla y limpia"),
    ReaderFont("Original", null, true, note = "La que trae el libro"),
    // Sin serifa, elegidas por legibilidad comprobada en pantalla
    ReaderFont("Atkinson", "Atkinson Hyperlegible Next", false, "atkinson.ttf", "atkinson_i.ttf", "atkinson.ttf", "Del Braille Institute: letras imposibles de confundir"),
    ReaderFont("Lexend", "Lexend", false, "lexend.ttf", null, "lexend.ttf", "Diseñada y probada para leer con más fluidez"),
    ReaderFont("Inter", "Inter", false, "inter.ttf", "inter_i.ttf", "inter.ttf", "Muy nítida en pantallas pequeñas"),
    ReaderFont("Source Sans", "Source Sans 3", false, "sourcesans.ttf", "sourcesans_i.ttf", "sourcesans.ttf", "Clara y tranquila para textos largos"),
    ReaderFont("Noto Sans", "Noto Sans", false, "notosans.ttf", "notosans_i.ttf", "notosans.ttf", "La que mejor cubre idiomas: latín, griego y cirílico"),
    ReaderFont("Andika", "Andika", false, "andika.ttf", "andika_i.ttf", "andika_b.ttf", "Hecha por SIL para quien aprende a leer un idioma"),
    ReaderFont("Plex Sans", "IBM Plex Sans", false, "plexsans.ttf", "plexsans_i.ttf", "plexsans.ttf", "Técnica y ordenada, buena para ciencias"),
    ReaderFont("Figtree", "Figtree", false, "figtree.ttf", "figtree_i.ttf", "figtree.ttf", "Moderna y amable"),
    ReaderFont("Nunito", "Nunito", false, "nunito.ttf", "nunito_i.ttf", "nunito.ttf", "Redondeada y suave a la vista"),
    // Con serifa, pensadas para pantalla
    ReaderFont("Source Serif", "Source Serif 4", true, "sourceserif.ttf", "sourceserif_i.ttf", "sourceserif.ttf", "Se ajusta sola al tamaño de la letra"),
    ReaderFont("Charis", "Charis SIL", true, "charis.ttf", "charis_i.ttf", "charis_b.ttf", "Muy legible y con todos los acentos del mundo"),
    ReaderFont("Merriweather", "Merriweather", true, "merriweather.ttf", "merriweather_i.ttf", "merriweather.ttf", "Diseñada para leer en pantalla"),
    ReaderFont("Lora", "Lora", true, "lora.ttf", "lora_i.ttf", "lora.ttf", "Cálida, de novela"),
    ReaderFont("Crimson", "Crimson Pro", true, "crimson.ttf", "crimson_i.ttf", "crimson.ttf", "De libro antiguo, muy fina"),
    ReaderFont("Newsreader", "Newsreader", true, "newsreader.ttf", "newsreader_i.ttf", "newsreader.ttf", "Para leer largo, como un buen periódico"),
    ReaderFont("Bitter", "Bitter", true, "bitter.ttf", "bitter_i.ttf", "bitter.ttf", "Serifa firme, cómoda con poca luz"),
)

object FontFiles {
    private var present: Set<String>? = null
    private val previews = HashMap<Int, FontFamily>()

    private var smallCaps: Set<String>? = null

    fun has(ctx: Context, file: String?): Boolean {
        val files = present ?: (ctx.assets.list("fonts")?.toSet() ?: emptySet()).also { present = it }
        return file != null && file in files
    }

    /** Si la letra trae versalitas propias (las anota la compilación en fonts/versalitas.txt). */
    fun hasSmallCaps(ctx: Context, i: Int): Boolean {
        val set = smallCaps ?: runCatching { ctx.assets.open("fonts/versalitas.txt").bufferedReader().readLines().map { it.trim() }.toSet() }
            .getOrDefault(emptySet()).also { smallCaps = it }
        return ReaderFonts.getOrNull(i)?.regular in set
    }

    /** Solo se ofrecen las fuentes que de verdad vienen dentro de la app. */
    fun available(ctx: Context): List<Int> {
        val files = present ?: (ctx.assets.list("fonts")?.toSet() ?: emptySet()).also { present = it }
        return ReaderFonts.indices.filter { i -> ReaderFonts[i].regular.let { it == null || it in files } }
    }

    fun preview(ctx: Context, i: Int): FontFamily = previews.getOrPut(i) {
        val f = ReaderFonts[i]
        val reg = f.regular ?: return@getOrPut FontFamily.Serif
        runCatching {
            val list = mutableListOf(Font("fonts/$reg", ctx.assets))
            f.italic?.let { list += Font("fonts/$it", ctx.assets, style = FontStyle.Italic) }
            FontFamily(list)
        }.getOrDefault(FontFamily.Default)
    }
}
