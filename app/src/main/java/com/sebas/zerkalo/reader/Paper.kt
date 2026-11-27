package com.sebas.zerkalo.reader

import android.content.Context

/**
 * Texturas de papel (en assets/papel/). Cada papel del lector puede tener la suya;
 * si falta el archivo, el papel se ve liso como siempre.
 */
object Paper {
    /** Papel del lector (número) → nombre del archivo de su textura. */
    private val FILES = mapOf(0 to "crema", 1 to "nogal", 2 to "negro", 3 to "sepia", 4 to "gris", 5 to "washi", 6 to "kraft")
    val NAMES = listOf("Crema" to 0, "Sepia" to 3, "Washi" to 5, "Kraft" to 6, "Gris" to 4, "Nogal" to 1, "Negro" to 2)

    private var have: Set<String>? = null

    private fun files(ctx: Context): Set<String> = have ?: runCatching { ctx.assets.list("papel")?.toSet() }.getOrNull().orEmpty().also { have = it }

    /** El archivo de la textura del papel [paper] (por ejemplo "washi.jpg"), o null si no tiene. */
    fun file(ctx: Context, paper: Int): String? {
        val base = FILES[paper] ?: return null
        val all = files(ctx)
        return listOf("$base.jpg", "$base.webp", "$base.png").firstOrNull { it in all }
    }

    fun hasAny(ctx: Context) = files(ctx).isNotEmpty()
}
