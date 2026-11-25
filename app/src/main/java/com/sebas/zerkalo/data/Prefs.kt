package com.sebas.zerkalo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Papel de la app: Sistema, Crema (Chashitsu), Nogal o Sumi (OLED). */
enum class PaperPref { SISTEMA, CREMA, NOGAL, SUMI }

data class ReaderSettings(
    val font: Int = 0,          // índice en ReaderFonts
    val size: Int = 100,        // porcentaje
    val lineHeight: Float = 1.6f,
    val margin: Int = 1,        // 0 estrechos, 1 medios, 2 amplios
    val justify: Boolean = true,
    val paper: Int = -1,        // -1 = el mismo de la app; -2 = según la hora; 0 crema, 1 nogal, 2 negro, 3 sepia, 4 gris
    val scroll: Boolean = false, // desplazamiento continuo en vez de páginas
    val ink: Int = 0,           // color de letra en papel oscuro: 0 original, 1 azul claro, 2 blanco cálido, 3 gris perla, 4 ámbar suave, 5 propio
    val customInk: Int = 0xFFC7D8EA.toInt(),
    val brightness: Float = -1f, // -1 = el del sistema; 0..0.3 oscurece por debajo del mínimo; 0.3..1 brillo de pantalla
    val warmth: Float = 0f,     // filtro cálido, 0..1
    val paragraph: Int = 0,     // espacio entre párrafos: 0 como el libro, 1 separados, 2 aireados
    val spacing: Int = 0,       // espacio entre letras y palabras: 0 normal, 1 amplio, 2 muy amplio
    val animate: Boolean = true, // animación al pasar página
    val haptic: Boolean = false, // vibración suave al pasar página
    val volumeKeys: Boolean = true, // pasar página con los botones de volumen
    val ruler: Boolean = false, // regla de lectura
    val breaks: Boolean = false, // recordar pausas para los ojos
    val dyslexia: Boolean = false, // modo dislexia: letra clara, más espacio, sin justificar
    val fullscreen: Boolean = false, // usar toda la pantalla, hasta la cámara
    val vMargin: Int = 1,        // margen arriba y abajo: 0 compacto, 1 normal, 2 amplio
    val autoSpeed: Float = 30f,  // avance automático en desplazamiento: píxeles por segundo
    val autoPage: Float = 40f,   // avance automático en páginas: segundos por página
    val layout: Int = 1,         // diagramación: 0 original del libro, 1 Zerkalo (cuidada), 2 mía (mis ajustes en todo), 3 ninguna (el libro tal cual)
    val markStyle: Int = 0,      // subrayado: 0 marcador, 1 lápiz, 2 ondulado
    val veil: Float = 0f,        // velo que atenúa la página sin tocar el brillo, 0..0.85
    val info: Boolean = true,    // hora y avance junto a la cámara
    // Márgenes en milímetros, como en Moon+ Reader (arriba se suma la franja de la cámara)
    val mmLeft: Float = 4f,
    val mmRight: Float = 4f,
    val mmTop: Float = 1.5f,
    val mmBottom: Float = 2f,
    val respectCamera: Boolean = true, // el margen de arriba empieza debajo de la cámara (si no, desde el borde)
    val customInkLight: Int = 0xFF3A3350.toInt(), // tinta propia para papeles claros (Crema, Sepia)
    val continuous: Boolean = false,   // desplazamiento continuo: todo el libro seguido, como en Books
)

data class OpdsCatalog(val name: String, val url: String)

/** Un color de subrayado con nombre (por ejemplo "Cita" o "Duda"). */
data class InkColor(val name: String, val argb: Int) {
    companion object {
        val DEFAULTS = listOf(
            InkColor("Importante", 0xFFE2BE52.toInt()), InkColor("Idea", 0xFF8CA05A.toInt()),
            InkColor("Cita", 0xFFD9694F.toInt()), InkColor("Duda", 0xFF6F8DBF.toInt()),
        )
    }
}

/**
 * "Cómo se ve el texto": letra, tamaño, interlineado, márgenes y espacios. Es lo que cambia dónde cae cada línea.
 * Se usa para los ajustes propios de un libro y para recordar con qué letra se hizo cada trazo de la pluma.
 */
fun ReaderSettings.look(): String = listOf(
    font, size, lineHeight, margin, vMargin, spacing, paragraph, justify, layout, dyslexia, fullscreen, mmLeft, mmRight, mmTop, mmBottom,
).joinToString("~")

/** Los mismos ajustes, pero con la letra y los márgenes de [look]. */
fun ReaderSettings.withLook(look: String): ReaderSettings {
    val v = look.split("~")
    if (v.size < 15) return this
    return runCatching {
        copy(
            font = v[0].toInt(), size = v[1].toInt(), lineHeight = v[2].toFloat(), margin = v[3].toInt(), vMargin = v[4].toInt(),
            spacing = v[5].toInt(), paragraph = v[6].toInt(), justify = v[7].toBoolean(), layout = v[8].toInt(), dyslexia = v[9].toBoolean(),
            fullscreen = v[10].toBoolean(), mmLeft = v[11].toFloat(), mmRight = v[12].toFloat(), mmTop = v[13].toFloat(), mmBottom = v[14].toFloat(),
        )
    }.getOrDefault(this)
}

object Prefs {
    private lateinit var sp: SharedPreferences
    val paper = MutableStateFlow(PaperPref.SISTEMA)
    val reader = MutableStateFlow(ReaderSettings())
    val catalogs = MutableStateFlow<List<OpdsCatalog>>(emptyList())

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("zerkalo", Context.MODE_PRIVATE)
        paper.value = runCatching { PaperPref.valueOf(sp.getString("paper", "SISTEMA")!!) }.getOrDefault(PaperPref.SISTEMA)
        reader.value = ReaderSettings(
            font = sp.getInt("rFont", 0), size = sp.getInt("rSize", 100), lineHeight = sp.getFloat("rLh", 1.6f),
            margin = sp.getInt("rMargin", 1), justify = sp.getBoolean("rJustify", true), paper = sp.getInt("rPaper", -1),
            scroll = sp.getBoolean("rScroll", false), ink = sp.getInt("rInk", 0), customInk = sp.getInt("rCustomInk", 0xFFC7D8EA.toInt()),
            brightness = sp.getFloat("rBright", -1f), warmth = sp.getFloat("rWarm", 0f),
            paragraph = sp.getInt("rPara", 0), spacing = sp.getInt("rSpacing", 0), animate = sp.getBoolean("rAnim", true),
            haptic = sp.getBoolean("rHaptic", false), volumeKeys = sp.getBoolean("rVol", true), ruler = sp.getBoolean("rRuler", false),
            breaks = sp.getBoolean("rBreaks", false), dyslexia = sp.getBoolean("rDys", false),
            fullscreen = sp.getBoolean("rFull", false), vMargin = sp.getInt("rVMargin", 1),
            autoSpeed = sp.getFloat("rAutoSpeed", 30f), autoPage = sp.getFloat("rAutoPage", 40f),
            layout = sp.getInt("rLayout", 1), markStyle = sp.getInt("rMarkStyle", 0),
            veil = sp.getFloat("rVeil", 0f), info = sp.getBoolean("rInfo", true),
            mmLeft = sp.getFloat("rMmL", 4f), mmRight = sp.getFloat("rMmR", 4f), mmTop = sp.getFloat("rMmT", 1.5f), mmBottom = sp.getFloat("rMmB", 2f),
            respectCamera = sp.getBoolean("rCam", true),
            customInkLight = sp.getInt("rInkL", 0xFF3A3350.toInt()), continuous = sp.getBoolean("rCont", false),
        ).let { r ->
            // "Desplazamiento" se unió a "Continuo": quien lo usaba pasa a Continuo
            if (r.scroll && !r.continuous) r.copy(continuous = true) else r
        }
        inks.value = runCatching {
            val a = JSONArray(sp.getString("inks", null) ?: "")
            (0 until a.length()).map { a.getJSONObject(it).let { o -> InkColor(o.optString("n"), o.optInt("c")) } }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: InkColor.DEFAULTS
        catalogs.value = runCatching {
            val a = JSONArray(sp.getString("opds", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> OpdsCatalog(o.getString("name"), o.getString("url")) } }
        }.getOrDefault(emptyList())
    }

    /** Avisos de "trazos con otra letra" que la persona cerró con la ✕ (no vuelven a salir). */
    fun inkHintHidden(bookId: String, what: String) = sp.getStringSet("inkHide_$bookId", emptySet())?.contains(what) == true
    fun hideInkHint(bookId: String, what: String) {
        val now = sp.getStringSet("inkHide_$bookId", emptySet()).orEmpty() + what
        sp.edit().putStringSet("inkHide_$bookId", now).apply()
    }

    /** Letra y márgenes propios de un libro (null = usa los de todos los libros). */
    fun bookLook(bookId: String): String? = sp.getString("look_$bookId", null)
    fun setBookLook(bookId: String, look: String?) {
        sp.edit().apply { if (look == null) remove("look_$bookId") else putString("look_$bookId", look) }.apply()
    }

    fun setPaper(p: PaperPref) { paper.value = p; sp.edit().putString("paper", p.name).apply() }

    fun setReader(r: ReaderSettings) {
        reader.value = r
        sp.edit().putInt("rFont", r.font).putInt("rSize", r.size).putFloat("rLh", r.lineHeight)
            .putInt("rMargin", r.margin).putBoolean("rJustify", r.justify).putInt("rPaper", r.paper)
            .putBoolean("rScroll", r.scroll).putInt("rInk", r.ink).putInt("rCustomInk", r.customInk)
            .putFloat("rBright", r.brightness).putFloat("rWarm", r.warmth)
            .putInt("rPara", r.paragraph).putInt("rSpacing", r.spacing).putBoolean("rAnim", r.animate)
            .putBoolean("rHaptic", r.haptic).putBoolean("rVol", r.volumeKeys).putBoolean("rRuler", r.ruler)
            .putBoolean("rBreaks", r.breaks).putBoolean("rDys", r.dyslexia)
            .putBoolean("rFull", r.fullscreen).putInt("rVMargin", r.vMargin)
            .putFloat("rAutoSpeed", r.autoSpeed).putFloat("rAutoPage", r.autoPage)
            .putInt("rLayout", r.layout).putInt("rMarkStyle", r.markStyle).putFloat("rVeil", r.veil).putBoolean("rInfo", r.info)
            .putFloat("rMmL", r.mmLeft).putFloat("rMmR", r.mmRight).putFloat("rMmT", r.mmTop).putFloat("rMmB", r.mmBottom).putBoolean("rCam", r.respectCamera)
            .putInt("rInkL", r.customInkLight).putBoolean("rCont", r.continuous).apply()
    }

    fun setCatalogs(list: List<OpdsCatalog>) {
        catalogs.value = list
        sp.edit().putString("opds", JSONArray(list.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()).apply()
    }

    var tansuLang: String
        get() = sp.getString("tansuLang", null) ?: java.util.Locale.getDefault().language.takeIf { l -> Catalogs.LANGS.any { it.id == l } } ?: "es"
        set(v) { sp.edit().putString("tansuLang", v).apply() }

    var secPerPosition: Float
        get() = sp.getFloat("spp", 45f)
        set(v) { sp.edit().putFloat("spp", v).apply() }

    /** Idioma al que se traduce al tocar una palabra ("" = el del teléfono). */
    var translateTo: String
        get() = sp.getString("trTo", "") ?: ""
        set(v) { sp.edit().putString("trTo", v).apply() }

    /** Tocar una palabra la traduce. */
    /** Ya vio la explicación de las tarjetas (la primera vez que repasa). */
    var cardsIntroSeen: Boolean
        get() = sp.getBoolean("cardsIntro", false)
        set(v) { sp.edit().putBoolean("cardsIntro", v).apply() }

    /** Marcó "No volver a mostrar" en la primera hoja del libro de sellos. */
    var stampsIntroHidden: Boolean
        get() = sp.getBoolean("stampsIntroHidden", false)
        set(v) { sp.edit().putBoolean("stampsIntroHidden", v).apply() }

    var studyTap: Boolean
        get() = sp.getBoolean("studyTap", false)
        set(v) { sp.edit().putBoolean("studyTap", v).apply() }

    /** Con un audiolibro sonando, la página del lector sigue al capítulo que se escucha. */
    var audioFollow: Boolean
        get() = sp.getBoolean("audioFollow", true)
        set(v) { sp.edit().putBoolean("audioFollow", v).apply() }

    /** Lector bilingüe: las dos ediciones pasan página juntas. */
    var pairLinked: Boolean
        get() = sp.getBoolean("pairLinked", true)
        set(v) { sp.edit().putBoolean("pairLinked", v).apply() }

    /** Lector bilingüe: la pareja va primero (arriba o a la izquierda). */
    var pairSwap: Boolean
        get() = sp.getBoolean("pairSwap", false)
        set(v) { sp.edit().putBoolean("pairSwap", v).apply() }

    /** Lector bilingüe: porcentaje de la pantalla para el libro principal. */
    var pairRatio: Int
        get() = sp.getInt("pairRatio", 50)
        set(v) { sp.edit().putInt("pairRatio", v).apply() }

    /** Cómo se ordena la biblioteca: 0 estado, 1 géneros, 2 autores, 3 idiomas. */
    var libraryMode: Int
        get() = sp.getInt("libMode", 0)
        set(v) { sp.edit().putInt("libMode", v).apply() }

    /** Libro que espera una portada buscada en Google Imágenes (id y hora en que se pidió). */
    var pendingCover: Pair<String, Long>?
        get() = sp.getString("pendCover", null)?.let { id -> id to sp.getLong("pendCoverAt", 0) }
        set(v) { sp.edit().putString("pendCover", v?.first).putLong("pendCoverAt", v?.second ?: 0).apply() }

    fun pairOn(bookId: String) = sp.getBoolean("pairOn_$bookId", false)
    fun setPairOn(bookId: String, on: Boolean) { sp.edit().putBoolean("pairOn_$bookId", on).apply() }

    /** Colores de subrayado propios, con nombre. */
    val inks = MutableStateFlow<List<InkColor>>(emptyList())

    fun setInks(list: List<InkColor>) {
        inks.value = list
        sp.edit().putString("inks", JSONArray(list.map { JSONObject().put("n", it.name).put("c", it.argb) }).toString()).apply()
    }

    var lastInk: Int
        get() = sp.getInt("lastInk", InkColor.DEFAULTS[0].argb)
        set(v) { sp.edit().putInt("lastInk", v).apply() }

    var welcomed: Boolean
        get() = sp.getBoolean("welcomed", false)
        set(v) { sp.edit().putBoolean("welcomed", v).apply() }

    var reminderOn: Boolean
        get() = sp.getBoolean("remOn", false)
        set(v) { sp.edit().putBoolean("remOn", v).apply() }
    val reminderHour get() = sp.getInt("remH", 21)
    val reminderMinute get() = sp.getInt("remM", 30)
}
