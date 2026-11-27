package com.sebas.zerkalo.reader

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.sebas.zerkalo.data.ReaderSettings

/**
 * "Ajustada a tu pantalla": mide cuántas letras caben en cada línea con tu letra, tu tamaño y tu pantalla,
 * y acomoda márgenes e interlineado para que la línea quede cómoda (unas 50 a 70 letras, ideal 60).
 *
 * Lo que dicen los estudios de lectura: líneas de 50–75 letras son las más cómodas; con menos, el ojo
 * salta demasiado; con más, cuesta encontrar la línea siguiente. Las líneas largas piden más interlineado.
 */
object Fit {
    const val MIN_CPL = 45           // menos que esto: se achican los márgenes (en celular 60 no caben sin pegar el texto al borde)
    const val IDEAL = 60
    const val MAX_REM = 34.0         // ancho máximo del texto (en "rem", crece con la letra): ~66 letras
    const val MIN_MM = 2.5f          // nunca menos margen que esto (salvo que tú lo pongas)
    const val AUTO_JUSTIFY = 50      // "Automático" justifica solo desde 50 letras por línea (tablet); en celular queda a la izquierda, como JW Library
    private const val SAMPLE = "de la conquista del infinito y lo desconocido"

    private val faces = HashMap<Int, Typeface>()

    private fun typeface(ctx: Context, index: Int): Typeface = faces.getOrPut(index) {
        runCatching {
            GoogleFonts.get(ctx, index)?.let { g -> GoogleFonts.file(ctx, g, "regular")?.let { Typeface.createFromFile(it) } }
                ?: ReaderFonts.getOrNull(index)?.let { f -> FontFiles.regular(ctx, f)?.let { Typeface.createFromAsset(ctx.assets, "fonts/$it") } }
        }.getOrNull() ?: Typeface.SERIF
    }

    /** Ancho de una letra promedio, en píxeles reales, con la letra y el tamaño elegidos. */
    fun charPx(ctx: Context, r: ReaderSettings, density: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typeface(ctx, r.font)
        p.textSize = 16f * r.size / 100f * density
        val extra = listOf(0f, 0.06f, 0.14f).getOrElse(Letra.spacing(r)) { 0f } * p.textSize
        return p.measureText(SAMPLE) / SAMPLE.length + extra
    }

    /** Ancho máximo del texto en píxeles (como hace el motor de páginas en tabletas y pantallas anchas). */
    fun capPx(r: ReaderSettings, density: Float) = (maxRem(r) * 16 * r.size / 100 * density).toFloat()

    /** Ancho máximo del texto en "rem": automático (~66 letras) o las letras por línea que elegiste. */
    fun maxRem(r: ReaderSettings): Double = if (r.cpl in 20..90) MAX_REM * r.cpl / 66.0 else MAX_REM

    /** Letras por línea con un ancho de texto dado. */
    fun cpl(ctx: Context, r: ReaderSettings, textPx: Float, density: Float): Int =
        (minOf(textPx, capPx(r, density)) / charPx(ctx, r, density)).toInt().coerceAtLeast(1)

    /**
     * Cuánto achicar los márgenes de los lados (1 = como los pusiste). En "Ajustada" se calcula para llegar
     * a [MIN_CPL]; en los otros modos solo se achican con letra grande, como antes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sideFactor(ctx: Context, r: ReaderSettings, screenPx: Float, insetsPx: Float, mmPx: Float, density: Float): Float = 1f // tus márgenes se respetan siempre

    /** Margen de cada lado (mm) para llegar a [MIN_CPL] letras; null si ya se llega o no se puede bajar de [MIN_MM]. */
    fun suggestMm(ctx: Context, r: ReaderSettings, screenPx: Float, insetsPx: Float, mmPx: Float, density: Float): Float? {
        val cw = charPx(ctx, r, density)
        val now = minOf(screenPx - insetsPx - (r.mmLeft + r.mmRight) * mmPx, capPx(r, density)) / cw
        if (now >= MIN_CPL - 3) return null
        val side = ((screenPx - insetsPx - MIN_CPL * cw) / 2f / mmPx)
        val mm = (kotlin.math.floor(side * 2f) / 2f).coerceAtLeast(MIN_MM)
        return mm.takeIf { it < minOf(r.mmLeft, r.mmRight) - 0.4f }
    }

    @Suppress("unused")
    private fun oldSideFactor(ctx: Context, r: ReaderSettings, screenPx: Float, insetsPx: Float, mmPx: Float, density: Float): Float {
        if (r.layout != 1) return Letra.sideK(r.size)
        val userPx = (r.mmLeft + r.mmRight) * mmPx
        if (userPx <= 0f) return 1f
        val cw = charPx(ctx, r, density)
        val text = screenPx - insetsPx - userPx
        if (minOf(text, capPx(r, density)) / cw >= MIN_CPL) return 1f
        val room = screenPx - insetsPx - MIN_CPL * cw
        val minPx = minOf(userPx, 2 * MIN_MM * mmPx)
        return (room.coerceAtLeast(minPx) / userPx).coerceIn(minPx / userPx, 1f)
    }

    /** Interlineado: en "Ajustada" sigue el largo de la línea (cortas: más junto; largas: más aire). */
    fun lineHeight(r: ReaderSettings, cpl: Int): Float {
        // Líneas cortas (celular) conservan tu interlineado: es lo que las hace cómodas. Solo las largas ganan aire.
        if (r.layout != 1 || r.dyslexia || cpl <= IDEAL) return Letra.lineHeight(r)
        return Letra.lineHeight(r) + ((cpl - IDEAL) * 0.004f).coerceAtMost(0.08f)
    }

    /** Alineación efectiva: el modo dislexia siempre a la izquierda. */
    fun align(r: ReaderSettings) = if (r.dyslexia) 0 else r.align

    fun label(cpl: Int): String = when {
        cpl < 35 -> "corta"
        cpl < 50 -> "algo corta"
        cpl <= 75 -> "cómoda"
        else -> "larga"
    }
}
