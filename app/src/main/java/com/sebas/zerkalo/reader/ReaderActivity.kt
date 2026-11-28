package com.sebas.zerkalo.reader

import android.graphics.Bitmap
import kotlinx.coroutines.flow.first
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import android.graphics.PointF
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sebas.zerkalo.ZerkaloApp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Mark
import com.sebas.zerkalo.data.PaperPref
import com.sebas.zerkalo.data.Prefs
import com.sebas.zerkalo.data.ReaderSettings
import com.sebas.zerkalo.data.look
import com.sebas.zerkalo.data.withLook
import com.sebas.zerkalo.ui.Chashitsu
import com.sebas.zerkalo.ui.Nogal
import com.sebas.zerkalo.ui.Palette
import com.sebas.zerkalo.ui.SumiPalette
import com.sebas.zerkalo.ui.SepiaPalette
import com.sebas.zerkalo.ui.GrisPalette
import com.sebas.zerkalo.ui.WashiPalette
import com.sebas.zerkalo.ui.KraftPalette
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import com.sebas.zerkalo.ui.highlightColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlinx.coroutines.Job
import java.util.Locale
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import org.readium.r2.navigator.epub.css.FontWeight as CssWeight
import org.readium.r2.navigator.preferences.Color as RColor

/** Una entrada del índice, ya aplanada. */
data class TocEntry(val title: String, val link: Link, val depth: Int)

/** Dirección de un archivo del libro, limpia para comparar (sin #, sin %C3%A1, sin "./"). */
fun hnorm(s: String): String = android.net.Uri.decode(s.substringBefore('#')).replace("/./", "/").removePrefix("./")


/** Colores de letra para papel oscuro. El último lo elige la persona. */
val InkNames = listOf("Original", "Azul claro", "Blanco cálido", "Gris perla", "Ámbar suave", "Mío")
val InkColors = listOf(0, 0xFFC7D8EA.toInt(), 0xFFEEE6D8.toInt(), 0xFFCFCBC5.toInt(), 0xFFD9C3A0.toInt(), 0)
/** Tintas para papel claro (Crema y Sepia): oscuras y suaves, que se leen bien sobre papel. */
val InkNamesLight = listOf("Original", "Café tinta", "Grafito", "Azul tinta", "Verde bosque", "Mía")
val InkColorsLight = listOf(0, 0xFF4A3426.toInt(), 0xFF33363A.toInt(), 0xFF22344F.toInt(), 0xFF2E4434.toInt(), 0)

/** Una palabra (o frase) que se está traduciendo. */
data class Lookup(
    val text: String,
    val sentence: String,
    val from: String,
    val to: String,
    val translation: String? = null,
    val sentenceTranslation: String? = null,
    val status: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val carded: Boolean = false, // ya se hizo tarjeta con esta palabra
    val locator: String = "",
)

/** Estado que ve la capa de controles (Compose). */
class ReaderUi {
    var inking by mutableStateOf(false)   // pluma de colores activa
    var ownLook by mutableStateOf(false)  // este libro tiene su propia letra y márgenes
    var inkOther by mutableStateOf<Pair<Int, String>?>(null) // trazos hechos con otra letra en este capítulo: (cuántos, ajustes)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var controls by mutableStateOf(true)
    var chapter by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var chapterLeft by mutableStateOf("")
    var minutesToday by mutableIntStateOf(0)
    var bookmarked by mutableStateOf(false)    // la página que se ve es la del shiori
    var hasShiori by mutableStateOf(false)     // el libro tiene shiori (en otra página)
    var backTo by mutableStateOf<String?>(null) // al ir al shiori: dónde ibas, para volver
    var shioriColor by mutableIntStateOf(com.sebas.zerkalo.data.Prefs.shioriColor)
    // Leer en voz alta (voz del teléfono)
    var footnote by mutableStateOf<Pair<String, org.readium.r2.shared.publication.Link>?>(null)
    var aloud by mutableStateOf(false)       // hay lectura en curso (aunque esté en pausa)
    var aloudPlaying by mutableStateOf(false)
    var aloudRate by mutableFloatStateOf(com.sebas.zerkalo.data.Prefs.aloudRate)
    var aloudSleep by mutableIntStateOf(0)    // minutos del temporizador (0 = sin)
    var settings by mutableStateOf(ReaderSettings())
    var palette by mutableStateOf(Chashitsu)
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
    var marks by mutableStateOf<List<Mark>>(emptyList())
    var activeMark by mutableStateOf<Mark?>(null)
    var noteFor by mutableStateOf<Locator?>(null)
    var finishedNow by mutableStateOf(false)
    var finishedStamp by mutableStateOf<com.sebas.zerkalo.data.Stamps.BookStamp?>(null) // el sello del libro recién terminado
    var cardDraft by mutableStateOf<CardDraft?>(null) // tarjeta que se está haciendo
    var sheet by mutableStateOf<String?>(null) // "toc" | "type"
    var toast by mutableStateOf<Pair<String, Long>?>(null)
    var studyTap by mutableStateOf(false)
    var lookup by mutableStateOf<Lookup?>(null)
    var hasPair by mutableStateOf(false)
    var pairOn by mutableStateOf(false)
    var pairTitle by mutableStateOf("")
    var pairLinked by mutableStateOf(true)      // las dos ediciones pasan página juntas
    var pairLangLabel by mutableStateOf("")     // idioma de la pareja, en dos letras
    var landscape by mutableStateOf(false)
    var band by mutableFloatStateOf(24f)    // alto de la franja de la cámara, en dp
    var camX by mutableFloatStateOf(0.5f)   // centro de la cámara (0 izquierda, 1 derecha)
    var camHalf by mutableFloatStateOf(0f)  // medio ancho de la cámara, en dp
    var typeTab by mutableIntStateOf(0)     // pestaña abierta en las opciones de lectura
    var cpl by mutableIntStateOf(0)           // letras por línea (aprox.) con los ajustes de ahora
    var fitMm by mutableFloatStateOf(0f)      // margen sugerido (mm por lado) para líneas más cómodas; 0 = nada que sugerir
    var typeMore by mutableStateOf(false)     // Aa: "Más ajustes" abierto (si no, lo básico)
    var fadeTop by mutableFloatStateOf(0f)    // desplazamiento: alto (dp) de la zona difuminada de arriba
    var fadeBottom by mutableFloatStateOf(0f) // y de abajo
    var pairSwap by mutableStateOf(false)       // la pareja va arriba (o a la izquierda)
    var pairRatio by mutableIntStateOf(50)      // % de la pantalla para el libro principal
    var lang by mutableStateOf("")
    var dim by mutableFloatStateOf(0f)        // velo negro para oscurecer por debajo del mínimo
    var clock by mutableStateOf("")
    var rulerY by mutableFloatStateOf(0.42f)  // posición de la regla de lectura (0..1)
    var edgeHint by mutableStateOf<String?>(null) // aviso al llegar al borde de un capítulo (desplazamiento)
    var edgeReady by mutableStateOf(false)        // ya basta con soltar para cambiar de capítulo
    var auto by mutableStateOf(false)             // avance automático encendido
    var guide by mutableStateOf(false)            // guía de lectura rápida avanzando
    var setTab by mutableIntStateOf(0)            // hoja de ajustes: 0 Luz, 1 Letra, 2 Página, 3 Ayudas
    var profile by mutableStateOf("")             // perfil en uso (id)
    var tocTab by mutableIntStateOf(0)
    var tocMade by mutableStateOf(false) // el índice lo armó Zerkalo (el libro no traía)
    // La lupa: buscar en todo el libro
    var findQuery by mutableStateOf("")
    var findHits by mutableStateOf<List<BookText.Hit>?>(null)
    var findBusy by mutableStateOf(false)
    var findTitles by mutableStateOf<Map<Int, String>>(emptyMap())
    val tocState = androidx.compose.foundation.lazy.LazyListState()
    val marksState = androidx.compose.foundation.lazy.LazyListState()
    var marksFilter by mutableStateOf<Int?>(null)
    fun say(s: String) { toast = s to System.currentTimeMillis() }
}

class ReaderActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    private lateinit var book: Book
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private val ui = ReaderUi()
    private val note = com.sebas.zerkalo.ui.NoteState.start()
    private val inkUi = com.sebas.zerkalo.ui.InkUi()
    private var sticky by mutableStateOf<Mark?>(null)   // nota adhesiva abierta
    private var inkView: com.sebas.zerkalo.ui.InkView? = null

    /**
     * La pantalla exacta donde va la tinta en un EPUB: capítulo, punto del capítulo y todo lo que cambia
     * cómo se acomoda el texto (letra, tamaño, márgenes, pantalla). Si algo de eso cambia, los trazos
     * se guardan igual y vuelven a verse al regresar a esos mismos ajustes. Solo en modo páginas.
     */
    private fun inkKey(): String? {
        if (cont != null || ui.settings.scroll) return null
        val loc = navigator?.currentLocator?.value ?: return null
        val prog = loc.locations.progression ?: return null
        val s = ui.settings
        val look = s.look() + "~" + ui.pairOn
        return "e|${loc.href}|${String.format(java.util.Locale.US, "%.4f", prog)}|$look|${root.width}x${root.height}"
    }

    private var snapJob: Job? = null

    /**
     * Guarda una foto de la pantalla rayada (la página y sus trazos), para poder exportar las páginas rayadas
     * de un EPUB: los trazos no se pueden meter dentro del EPUB porque su texto se reacomoda.
     */
    private fun snapInk(key: String) {
        snapJob?.cancel()
        snapJob = lifecycleScope.launch {
            delay(700)
            val iv = inkView ?: return@launch
            val dir = com.sebas.zerkalo.data.Export.inkShotsDir(this@ReaderActivity, book.id)
            val tag = Integer.toHexString(key.hashCode())
            dir.listFiles { f -> f.name.endsWith("-$tag.jpg") }?.forEach { it.delete() }
            if ((iv.counts()[key] ?: 0) == 0 || root.width == 0) return@launch
            runCatching {
                val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.drawColor(ui.palette.bg.toArgb())
                panes.draw(c)
                iv.draw(c)
                val prog = ((navigator?.currentLocator?.value?.locations?.totalProgression ?: 0.0) * 1_000_000).toInt()
                val f = java.io.File(dir, String.format(java.util.Locale.US, "%07d-%s.jpg", prog, tag))
                withContext(Dispatchers.IO) { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) } }
                bmp.recycle()
            }
        }
    }

    private fun exportEpub() {
        if (book.isPdf) return
        ui.say("Preparando el EPUB…")
        lifecycleScope.launch {
            runCatching { com.sebas.zerkalo.data.Export.epubWithNotes(this@ReaderActivity, book, Library.marks.value) }
                .onSuccess { com.sebas.zerkalo.data.Export.share(this@ReaderActivity, it, "application/epub+zip") }
                .onFailure { ui.say("No se pudo exportar: ${it.message}") }
        }
    }

    private fun exportInkPages() {
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { com.sebas.zerkalo.data.Export.inkPagesPdf(this@ReaderActivity, book) }.getOrNull() }
            if (f == null) ui.say("Todavía no has rayado ninguna página con la pluma")
            else com.sebas.zerkalo.data.Export.share(this@ReaderActivity, f, "application/pdf")
        }
    }

    /** "Solo para este libro": al encenderlo se guarda la letra actual para este libro; al apagarlo vuelve la de todos. */
    private fun setOwnLook(on: Boolean) {
        if (on) { ui.ownLook = true; Prefs.setBookLook(book.id, ui.settings.look()); ui.say("Estos ajustes quedan solo para este libro") }
        else {
            Prefs.setBookLook(book.id, null); ui.ownLook = false
            updateSettings(ui.settings.withLook(Prefs.reader.value.look()))
            ui.say("Este libro vuelve a usar los ajustes de todos")
        }
    }

    /**
     * ¿Hay trazos en este capítulo hechos con otra letra? Entonces se ofrece volver a esa letra para verlos,
     * así nunca se pierden aunque se cambie el tamaño o la letra.
     */
    private fun checkInkProfiles() {
        val iv = inkView ?: return
        val k = inkKey() ?: run { ui.inkOther = null; return }
        val parts = k.split("|")
        val href = parts.getOrNull(1) ?: return
        val prog = parts.getOrNull(2)?.toDoubleOrNull() ?: return
        val mine = parts.getOrNull(3) ?: return
        val counts = iv.counts()
        // Solo cerca de esta página (con otra letra, la página empieza en otro punto del capítulo)
        val near = counts.filter { (key, _) ->
            val p = key.split("|")
            p.getOrNull(0) == "e" && p.getOrNull(1) == href && p.getOrNull(3) != mine && (p.getOrNull(3)?.contains("~") == true) &&
                abs((p.getOrNull(2)?.toDoubleOrNull() ?: -1.0) - prog) < 0.04
        }
        if (near.isEmpty() || counts.containsKey(k)) { ui.inkOther = null; return }
        val best = near.entries.groupBy { it.key.split("|")[3] }.mapValues { e -> e.value.sumOf { it.value } }.maxByOrNull { it.value } ?: return
        val look = best.key.substringBeforeLast("~")
        // La ✕ lo cierra para siempre (para ese capítulo y esa letra)
        if (Prefs.inkHintHidden(book.id, "$href|$look")) { ui.inkOther = null; return }
        ui.inkOther = best.value to look
    }

    private fun hideInkHint() {
        val o = ui.inkOther ?: return
        val href = inkKey()?.split("|")?.getOrNull(1) ?: return
        Prefs.hideInkHint(book.id, "$href|${o.second}")
        ui.inkOther = null
    }

    private fun toggleInk() {
        if (!inkUi.on && inkKey() == null) { ui.say("La pluma funciona en Páginas (en Continuo llega pronto)"); return }
        inkUi.on = !inkUi.on
        ui.inking = inkUi.on
        if (inkUi.on) { setControls(false); note.minimize() }
        inkView?.invalidate()
    }
    private val containerId = View.generateViewId()
    private val pairId = View.generateViewId()
    private lateinit var root: FrameLayout
    private var curl: PaperFlip? = null
    private var curlLive = false                       // la hoja ya sigue al dedo
    private var curlPending: Pair<Float, Float>? = null // último movimiento del dedo mientras se prepara
    private var curlEnd: List<Float>? = null // el dedo se soltó antes de estar lista: dx, y, pasa(1/0), velocidad
    private var snapDown: Bitmap? = null               // foto tomada al tocar (para arrancar al instante)
    private var snapAt = 0L
    private var snapLoc: String? = null
    private lateinit var panes: LinearLayout
    private lateinit var pairContainer: FragmentContainerView
    private lateinit var divider: ComposeView
    private lateinit var mainContainer: View
    private var pairJob: Job? = null
    private var ignorePairUntil = 0L   // la pareja se está moviendo porque la movió el lector
    private var ignoreMainUntil = 0L   // el libro principal se está moviendo porque lo movió la pareja
    private var alignJob: Job? = null  // ajuste fino de la pareja, párrafo por párrafo
    private var lastTouch = 0L         // último toque o arrastre de la persona
    private var lastPageCheck = 0L     // última vez que se revisó si el narrador ya pasó de página
    private var cont: ContinuousView? = null  // modo continuo: todo el libro seguido (motor propio)
    private var contLoc: Locator? = null      // dónde va la lectura en el modo continuo
    private var scrollPadTop = 0f      // en desplazamiento: espacio dentro de la página arriba (px de CSS)
    private var scrollPadBottom = 0f
    private var scrollPadLeft = 0f     // en desplazamiento: márgenes de los lados dentro de la página (px de CSS)
    private var scrollPadRight = 0f

    // La misma obra en otro idioma
    private var pairBook: Book? = null
    private var pairPub: Publication? = null
    private var pairNav: EpubNavigatorFragment? = null
    private var pairPositions: List<List<Locator>> = emptyList()
    private var pairLang = ""
    private var lookupJob: Job? = null
    private var lastInsets: WindowInsetsCompat? = null
    private var autoJob: Job? = null
    private val dragEdge = HashMap<EpubNavigatorFragment, String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var positions: List<List<Locator>> = emptyList()
    private var lastPosition = 0
    private var lastTurn = 0L
    private var activeMs = 0L
    private var lastEvent = 0L

    /** Pide la frecuencia más alta de la pantalla (90/120 Hz si la tiene): bajar y pasar página se ven más fluidos. */
    private fun highRefresh() = runCatching {
        @Suppress("DEPRECATION")
        val d = if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        d ?: return@runCatching
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return@runCatching
        if (best.refreshRate > cur.refreshRate + 1f || window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            Log.i("Zerkalo", "ZK_HZ ${cur.refreshRate.toInt()} -> ${best.refreshRate.toInt()} Hz")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No se restauran fragmentos viejos: el lector siempre se arma de nuevo con el libro abierto.
        super.onCreate(null)
        book = Library.book(intent.getStringExtra("id")) ?: run { finish(); return }
        ui.settings = Prefs.reader.value
        // Este libro puede tener su propia letra y márgenes
        Prefs.bookLook(book.id)?.let { ui.settings = ui.settings.withLook(it); ui.ownLook = true }
        // La prueba automática abre el libro en modo continuo sin cambiar los ajustes guardados
        if (intent.getBooleanExtra("continuo", false)) ui.settings = ui.settings.copy(scroll = true, continuous = true)
        if (intent.getBooleanExtra("papel", false)) ui.settings = ui.settings.copy(scroll = false, continuous = false, animate = true, pageAnim = 2)
        intent.getStringExtra("hoja")?.let { h -> lifecycleScope.launch { delay(6000); ui.sheet = h } }
        ui.profile = Prefs.profile
        ui.palette = paletteFor(ui.settings)
        highRefresh()
        // El margen de los lados (dentro de la página) desde el principio, para que el libro no salte al abrir
        resources.displayMetrics.let { dm ->
            val xdpi = dm.xdpi.takeIf { it in 100f..900f } ?: dm.densityDpi.toFloat()
            gutterCss = (minOf(ui.settings.mmLeft, ui.settings.mmRight) * xdpi / 25.4f).toInt() / dm.density.toDouble()
        }
        ui.marks = Library.marks.value.filter { it.bookId == book.id }
        ui.minutesToday = Library.minutesToday()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.studyTap = Prefs.studyTap
        root = CurlRoot(this).apply {
            enabled = { curlAllowed() }
            onDown = { if (snapLoc != hereKey()) root.post { curlPreShot() } }
            onStart = { d, y -> curlBegin(d, y) }
            onMove = { dx, y -> if (curlLive) curlDrag(dx, y) else curlPending = dx to y }
            onEnd = { dx, y, ok, vx -> if (curlLive) curlRelease(dx, y, ok, vx) else curlEnd = listOf(dx, y, if (ok) 1f else 0f, vx) }
        }
        // Dos paneles: el libro y, si se activa, su versión en otro idioma (al lado o debajo).
        panes = LinearLayout(this)
        val container = FragmentContainerView(this).apply { id = containerId }
        pairContainer = FragmentContainerView(this).apply { id = pairId; visibility = View.GONE }
        ui.pairLinked = Prefs.pairLinked; ui.pairSwap = Prefs.pairSwap; ui.pairRatio = Prefs.pairRatio
        divider = ComposeView(this).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.sebas.zerkalo.ui.LocalPalette provides ui.palette) {
                    val main = ui.lang.ifBlank { "?" }.take(2).uppercase()
                    val other = ui.pairLangLabel
                    com.sebas.zerkalo.ui.WoodDivider(
                        vertical = ui.landscape,
                        first = if (ui.pairSwap) other else main, second = if (ui.pairSwap) main else other,
                        linked = ui.pairLinked, onLink = { togglePairLink() }, onTap = { setControls(!ui.controls) },
                    )
                }
            }
        }
        mainContainer = container
        panes.addView(container)
        panes.addView(divider)
        panes.addView(pairContainer)
        layoutPanes(resources.configuration)
        root.addView(panes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Pluma de colores: capa encima de la página (en modo páginas, cada pantalla guarda sus trazos)
        inkView = com.sebas.zerkalo.ui.InkView(this, book.id, panes, inkUi, pages = {
            val k = inkKey()
            if (k == null || root.width == 0) emptyList() else listOf(k to android.graphics.RectF(0f, 0f, root.width.toFloat(), root.height.toFloat()))
        }, onCommit = { key -> snapInk(key) })
        root.addView(inkView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // La hoja de papel que se dobla al pasar página (encima del libro, debajo de los menús)
        // Con papel, las esquinas de abajo son para tomar la hoja (no para el gesto de volver de Android)
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val d = resources.displayMetrics.density
                val w = v.width; val h = v.height
                val top = (h - 260 * d).toInt().coerceAtLeast(0); val bottom = (h - 60 * d).toInt().coerceAtLeast(top)
                val edge = (32 * d).toInt()
                v.systemGestureExclusionRects = if (ui.settings.pageAnim == 2 && ui.settings.animate && !ui.settings.scroll)
                    listOf(android.graphics.Rect(0, top, edge, bottom), android.graphics.Rect(w - edge, top, w, bottom)) else emptyList()
            }
        }
        curl = runCatching { PaperFlip(this) }.getOrNull()?.also { pf ->
            pf.onFinished = { d, done -> curlDone(d, done) }
            root.addView(pf.layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReaderOverlay(ui, actions) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // El apunte flotante: bolita 記 (o pestañita en el borde si se escondió), igual que en los PDF
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                com.sebas.zerkalo.ui.ZerkaloTheme {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().imePadding()) {
                        if (!inkUi.on) com.sebas.zerkalo.ui.FloatingNote(book.id, book.title, note, bottomSpace = 140.dp)
                        sticky?.let { m ->
                            com.sebas.zerkalo.ui.StickyEditor(
                                m, m.chapter.take(40),
                                onSave = { t -> Library.putMark(m.copy(note = t)); refreshMarks() },
                                onDelete = { Library.removeMark(m.id); refreshMarks(); sticky = null },
                                onClose = { sticky = null },
                            )
                        }
                        if (inkUi.on) com.sebas.zerkalo.ui.InkPill(
                            inkUi, onUndo = { inkView?.undoLast() }, onDone = { toggleInk() },
                            onPage = { d -> turn(navigator, d); inkView?.postInvalidateDelayed(350) },
                            modifier = androidx.compose.ui.Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars),
                        )
                    }
                }
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // El texto queda siempre dentro de la zona segura, aunque las barras se oculten (sin saltos).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        ViewCompat.setOnApplyWindowInsetsListener(panes) { _, insets ->
            lastInsets = insets
            applyMargins()
            insets
        }
        applyPaper()
        applyBrightness(ui.settings.brightness)
        // El perfil de la hora se pone ANTES de abrir el libro: así nada se reacomoda mientras lees
        if (Prefs.profileAuto && !intent.getBooleanExtra("papel", false)) {
            val want = com.sebas.zerkalo.data.Profiles.forHour(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            com.sebas.zerkalo.data.Profiles.get(want)?.let { pr ->
                if (ui.profile != want) {
                    val r = com.sebas.zerkalo.data.Profiles.apply(ui.settings, pr.values)
                    if (r.continuous == ui.settings.continuous) { ui.settings = r; Prefs.setReader(r); Prefs.profile = want; ui.profile = want }
                }
            }
            autoProfileAtOpen = want
        }
        lifecycleScope.launch { open() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
    }

    /** Atrás cierra en orden lo que esté abierto; solo al final sale del libro. */
    private fun back() {
        when {
            ui.lookup != null -> { lookupJob?.cancel(); ui.lookup = null }
            ui.activeMark != null -> ui.activeMark = null
            ui.noteFor != null -> ui.noteFor = null
            ui.sheet != null -> ui.sheet = null
            ui.studyTap -> { ui.studyTap = false; Prefs.studyTap = false; ui.say("Modo traducir apagado") }
            ui.auto -> stopAuto()
            else -> finish()
        }
    }

    /**
     * Márgenes del texto, como en Moon+ Reader: arriba queda la franja de la cámara (ahí van la hora y el
     * avance) y el texto empieza justo debajo; abajo llega hasta el borde de la pantalla.
     * El espacio de arriba y abajo crece con el tamaño de la letra (estrechos, normales o amplios).
     */
    /**
     * Medida de la línea: (cuánto achicar los márgenes de los lados, letras por línea) con la letra,
     * el tamaño y la pantalla de ahora. También actualiza el indicador del menú.
     */
    private fun fit(r: ReaderSettings = ui.settings): Pair<Float, Int> {
        val dm = resources.displayMetrics
        val xdpi = dm.xdpi.takeIf { it in 100f..900f } ?: dm.densityDpi.toFloat()
        val mmPx = xdpi / 25.4f
        val w = (runCatching { root.width }.getOrDefault(0).takeIf { it > 0 } ?: dm.widthPixels).toFloat()
        val i = lastInsets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ins = ((i?.left ?: 0) + (i?.right ?: 0)).toFloat()
        val side = runCatching { Fit.sideFactor(this, r, w, ins, mmPx, dm.density) }.getOrDefault(1f)
        var text = w - ins - (r.mmLeft + r.mmRight) * side * mmPx
        // Páginas en pantalla ancha y acostada: el motor pone dos columnas, cada una con la mitad
        if (!r.continuous && !r.scroll && w > dm.heightPixels && w / dm.density >= 600f) text = (text - 32f * dm.density) / 2f
        val cpl = runCatching { Fit.cpl(this, r, text, dm.density) }.getOrDefault(0)
        if (r == ui.settings) {
            ui.cpl = cpl
            ui.fitMm = runCatching { Fit.suggestMm(this, r, w, ins, mmPx, dm.density) }.getOrNull() ?: 0f
        }
        return side to cpl
    }

    private fun applyMargins() {
        val insets = lastInsets ?: return
        val r = ui.settings
        val i = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val dm = resources.displayMetrics
        // Milímetros reales de la pantalla (si el teléfono informa mal sus puntos por pulgada, se usa la densidad)
        val xdpi = dm.xdpi.takeIf { it in 100f..900f } ?: dm.densityDpi.toFloat()
        val ydpi = dm.ydpi.takeIf { it in 100f..900f } ?: dm.densityDpi.toFloat()
        // Ajustada: los lados se achican lo justo para que la línea tenga unas 52+ letras (otros modos: solo con letra grande)
        val (side, cplNow) = fit(r)
        Log.i("Zerkalo", "ZK_FIT ${cplNow} letras/línea · márgenes x${"%.2f".format(side)} · ${Letra.pt(r.size)} pt · modo ${r.layout} · alineación ${r.align}")
        fun mmX(v: Float) = (v * side * xdpi / 25.4f).toInt()
        fun mmY(v: Float) = (v * ydpi / 25.4f).toInt()
        val cam = if (r.respectCamera) i.top else 0
        if (r.scroll || r.continuous) {
            // Desplazamiento: el texto usa toda la pantalla; arriba y abajo, la zona del margen se difumina
            // Los márgenes de los lados van dentro de la página, para que el desenfoque cubra todo el ancho
            panes.setPadding(0, 0, 0, 0)
            scrollPadLeft = (i.left + mmX(r.mmLeft)) / dm.density
            scrollPadRight = (i.right + mmX(r.mmRight)) / dm.density
            scrollPadTop = (cam + mmY(r.mmTop)) / dm.density
            scrollPadBottom = mmY(r.mmBottom) / dm.density
            ui.fadeTop = scrollPadTop; ui.fadeBottom = scrollPadBottom
        } else {
            // Los lados van DENTRO de la página (margen de Readium): así al pasar página la hoja se desliza
            // de borde a borde, en vez de cortarse a unos milímetros del borde. Lo que sobre de un lado
            // (si izquierda y derecha son distintos) queda como marco.
            val gl = mmX(r.mmLeft); val gr = mmX(r.mmRight); val g = minOf(gl, gr)
            panes.setPadding(i.left + gl - g, cam + mmY(r.mmTop), i.right + gr - g, mmY(r.mmBottom))
            val css = g / dm.density.toDouble()
            if (kotlin.math.abs(css - gutterCss) > 0.5) {
                gutterCss = css
                navigator?.submitPreferences(preferences(r, ui.palette))
                pairNav?.submitPreferences(preferences(r, ui.palette))
            }
            scrollPadTop = 0f; scrollPadBottom = 0f; scrollPadLeft = 0f; scrollPadRight = 0f
            ui.fadeTop = 0f; ui.fadeBottom = 0f
        }
        // Dónde está la cámara, para poner la hora a un lado y el avance al otro
        ui.band = i.top / dm.density
        val cut = insets.displayCutout?.boundingRects?.firstOrNull { it.top <= 4 }
        val w = root.width.takeIf { it > 0 } ?: dm.widthPixels
        ui.camX = cut?.let { (it.exactCenterX() / w).coerceIn(0.15f, 0.85f) } ?: 0.5f
        ui.camHalf = (cut?.width()?.div(2f) ?: 0f) / dm.density
        if (cont != null) contRefresh()
    }

    /**
     * Vertical: un libro encima del otro. Horizontal: uno al lado del otro.
     * El orden se puede intercambiar y el tamaño de cada parte elegir (mitad y mitad, 60/40…).
     */
    private fun layoutPanes(c: Configuration) {
        val landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        ui.landscape = landscape
        panes.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val w = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        val h = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0
        val wood = (26 * resources.displayMetrics.density).toInt()
        val main = mainContainer
        val order = if (ui.pairSwap) listOf(pairContainer, divider, main) else listOf(main, divider, pairContainer)
        if ((0 until panes.childCount).map { panes.getChildAt(it) } != order) {
            order.forEach { v -> (v.parent as? ViewGroup)?.removeView(v) }
            order.forEach { panes.addView(it) }
        }
        val r = ui.pairRatio.coerceIn(30, 70) / 100f
        main.layoutParams = LinearLayout.LayoutParams(w, h, r)
        divider.layoutParams = if (landscape) LinearLayout.LayoutParams(wood, ViewGroup.LayoutParams.MATCH_PARENT) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wood)
        pairContainer.layoutParams = LinearLayout.LayoutParams(w, h, 1f - r)
        cont?.layoutParams = LinearLayout.LayoutParams(w, h, 1f)
    }

    private fun togglePairLink() {
        ui.pairLinked = !ui.pairLinked; Prefs.pairLinked = ui.pairLinked
        ui.say(if (ui.pairLinked) "Casadas: pasan página juntas" else "Separadas: cada una a su ritmo")
        if (ui.pairLinked) (navigator?.currentLocator?.value ?: contLoc)?.let { syncPair(it, force = true) }
    }

    private fun swapPair() {
        ui.pairSwap = !ui.pairSwap; Prefs.pairSwap = ui.pairSwap
        layoutPanes(resources.configuration)
    }

    private fun setPairRatio(v: Int) {
        ui.pairRatio = v; Prefs.pairRatio = v
        layoutPanes(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::panes.isInitialized) layoutPanes(newConfig)
    }

    private suspend fun open() {
        val core = (application as ZerkaloApp).readium
        val pub = core.open(File(Library.booksDir, book.file)).getOrElse {
            ui.loading = false
            ui.error = it.message ?: "No se pudo abrir el libro"
            return
        }
        publication = pub
        ui.lang = book.lang.ifBlank { pub.metadata.languages.firstOrNull().orEmpty() }.ifBlank { "en" }
        ui.toc = flatten(pub.tableOfContents, 0)
        bookText = BookText(pub)
        if (ui.toc.isEmpty() && pub.readingOrder.isNotEmpty()) {
            // El libro no trae índice: se arma uno con sus partes, y luego con el primer título de cada una
            ui.tocMade = true
            ui.toc = pub.readingOrder.mapIndexed { i, l -> TocEntry(l.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Parte ${i + 1}", l, 0) }
            lifecycleScope.launch {
                val bt = bookText ?: return@launch
                val named = pub.readingOrder.mapIndexed { i, l -> TocEntry(l.title?.trim()?.takeIf { it.isNotEmpty() } ?: bt.heading(i) ?: "Parte ${i + 1}", l, 0) }
                ui.toc = named
            }
        }
        // Si el libro tiene shiori, se abre directo en su página (como abrir un libro por la cinta)
        val shiori = Library.marks.value.filter { it.bookId == book.id && it.type == "shiori" }.maxByOrNull { it.id }
        val initial = (shiori?.locator ?: book.locator)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?: book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        // "Desplazamiento" ya no existe: todo lo que baja con el dedo se lee en Continuo
        if (ui.settings.continuous || ui.settings.scroll) { openContinuous(pub, initial); return }
        val factory = EpubNavigatorFactory(pub, EpubNavigatorFactory.Configuration(defaults = EpubDefaults(pageMargins = 1.2)))
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initial,
            initialPreferences = preferences(ui.settings, ui.palette),
            listener = this,
            configuration = navConfig(),
        )
        supportFragmentManager.commitNow {
            add(containerId, EpubNavigatorFragment::class.java, Bundle(), "lector")
        }
        val nav = supportFragmentManager.findFragmentByTag("lector") as EpubNavigatorFragment
        navigator = nav
        nav.addInputListener(tapListener(nav, pair = false))
        lockSideways(nav)
        nav.addDecorationListener("notas", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                sticky = ui.marks.firstOrNull { it.id == event.decoration.id }
                return true
            }
        })
        nav.addDecorationListener("subrayados", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                ui.activeMark = ui.marks.firstOrNull { it.id == event.decoration.id }
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                nav.currentLocator.collect { onLocator(it); typeset(nav) }
            }
        }
        lifecycleScope.launch {
            positions = runCatching { pub.positionsByReadingOrder() }.getOrDefault(emptyList())
            nav.currentLocator.value.let { onLocator(it, turn = false) }
        }
        lifecycleScope.launch { applyHighlights() }
        afterOpen()
    }

    /** Lo que se hace al abrir, sea cual sea el modo: reloj, pausas, audio, aviso de listo. */
    private fun afterOpen() {
        lifecycleScope.launch {
            var sinceBreak = 0L
            while (true) {
                ui.clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                delay(30_000)
                ui.minutesToday = Library.minutesToday() + ((activeMs + pendingMs()) / 60_000).toInt()
                // Pausa para los ojos: cada 20 minutos de lectura activa
                if (lastEvent > 0 && System.currentTimeMillis() - lastEvent < 180_000) sinceBreak += 30_000
                if (ui.settings.breaks && sinceBreak >= 20 * 60_000L) {
                    sinceBreak = 0
                    ui.say("Llevas 20 minutos: mira algo lejano unos segundos y parpadea")
                }
            }
        }
        followAudio()
        watchProfileHours()
        ui.loading = false
        Log.i("Zerkalo", "ZK_READER_OK ${book.title}")
        refreshPairInfo()
        if (ui.hasPair && Prefs.pairOn(book.id)) showPair(true)
        lifecycleScope.launch { delay(1600); setControls(false) }
    }

    // ---------------- modo continuo ----------------
    /** Abre el libro en el modo continuo: todo seguido, con su propio motor (ver Continuous.kt). */
    private fun openContinuous(pub: Publication, initial: Locator?) {
        lifecycleScope.launch { positions = runCatching { pub.positionsByReadingOrder() }.getOrDefault(emptyList()) }
        val v = ContinuousView(this, pub, object : ContinuousView.Callbacks {
            override fun onPosition(index: Int, progression: Double) {
                val loc = locatorAt(index, progression) ?: return
                val first = contLoc == null
                contLoc = loc
                onLocator(loc, turn = !first)
            }
            override fun onTap(fx: Float, fy: Float, x: Float, y: Float) {
                lastTouch = System.currentTimeMillis()
                when {
                    ui.lookup != null -> ui.lookup = null
                    // Modo traducir: tocar una palabra la traduce (los bordes siguen pasando página)
                    ui.studyTap && fy in 0.1f..0.9f && fx in 0.16f..0.84f -> lifecycleScope.launch { if (!contLookUp(x, y)) setControls(!ui.controls) }
                    fx < 0.28f -> contPage(-1)
                    fx > 0.72f -> contPage(1)
                    else -> setControls(!ui.controls)
                }
            }
            override fun onMark(id: String) {
                val m = ui.marks.firstOrNull { it.id == id } ?: return
                if (m.type == "sticky") sticky = m else ui.activeMark = m
            }
            override fun onSelection(id: Int, json: String?) { contSelection(id, json) }
            override fun onLoaded(index: Int) { contTypeset() }
            override fun onReady() { Log.i("Zerkalo", "ZK_CONT_OK ${book.title}") }
            override fun onMissing(url: String) { ui.say("Ese capítulo no está dentro del libro") }
        })
        // El mismo menú de texto seleccionado que en Páginas
        v.menu = listOf(101 to "Subrayar", 107 to "Nota adhesiva", 106 to "Al apunte", 103 to "Copiar", 104 to "Traducir", 105 to "Tarjeta")
        cont = v
        mainContainer.visibility = View.GONE
        panes.addView(v, 0)
        layoutPanes(resources.configuration)
        val idx = initial?.let { resourceIndex(pub, it) }?.takeIf { it >= 0 } ?: 0
        v.start(idx, initial?.locations?.progression ?: 0.0, contCss(), contMarks())
        afterOpen()
    }

    /** El punto del libro: capítulo [index] a [progression]; con la posición de página si ya se conocen. */
    private fun locatorAt(index: Int, progression: Double): Locator? {
        val pub = publication ?: return null
        val list = positions.getOrNull(index)
        val base = list?.getOrNull(((list.size - 1) * progression).toInt().coerceIn(0, (list.size - 1).coerceAtLeast(0)))
            ?: pub.readingOrder.getOrNull(index)?.let { pub.locatorFromLink(it) } ?: return null
        return base.copyWithLocations(progression = progression)
    }

    /** Una opción del menú de texto seleccionado en el modo continuo. */
    private fun contSelection(id: Int, json: String?) {
        val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val t = o.optString("t").trim()
        if (t.isEmpty()) return
        val base = locatorAt(o.optInt("i"), o.optDouble("p", 0.0)) ?: return
        // El mismo tipo de marca que en Páginas: así un subrayado hecho aquí se ve también allá
        val loc = base.copy(text = Locator.Text(before = o.optString("b"), highlight = t, after = o.optString("a")))
        cont?.clearSelection()
        when (id) {
            101 -> saveHighlight(loc, Prefs.lastInk, "")
            107 -> {
                val m = Mark(
                    id = "n" + System.currentTimeMillis().toString(36), bookId = book.id, type = "sticky",
                    locator = loc.toJSON().toString(), text = t, chapter = ui.chapter,
                    progress = loc.locations.totalProgression ?: ui.progress.toDouble(),
                )
                Library.putMark(m); refreshMarks(); sticky = m
            }
            106 -> { com.sebas.zerkalo.data.Apuntes.addQuote(book.id, t, ui.chapter.take(40)); note.open() }
            103 -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Zerkalo", t))
            104 -> startLookup(t, o.optString("b").takeLast(160) + t + o.optString("a").take(160), ui.lang, loc.toJSON().toString())
            105 -> ui.cardDraft = CardDraft(t, loc.toJSON().toString(), null)
        }
    }

    /** Traducir tocando en el modo continuo: la palabra que está bajo el dedo ([x], [y] en píxeles). */
    private suspend fun contLookUp(x: Float, y: Float): Boolean {
        val v = cont ?: return false
        val js = WORD_AT_POINT.replace("__X__", x.toString()).replace("__Y__", y.toString())
        val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { k -> v.evaluateJavascript(js) { r -> if (k.isActive) k.resumeWith(Result.success(r)) } }
        if (raw.isNullOrBlank() || raw == "null") return false
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val word = o.optString("w").trim()
        if (word.isEmpty()) return false
        startLookup(word, o.optString("s"), ui.lang, contLoc?.toJSON()?.toString().orEmpty())
        return true
    }

    private fun contPage(dir: Int) {
        if (ui.controls) setControls(false)
        if (ui.settings.haptic) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        cont?.page(dir)
    }

    private fun contGo(loc: Locator) {
        val pub = publication ?: return
        val i = resourceIndex(pub, loc).takeIf { it >= 0 } ?: return
        cont?.go(i, loc.locations.progression ?: 0.0)
        setControls(false)
    }

    /** Vuelve a diagramar todo lo pegado (al agregar un capítulo o cambiar un ajuste). */
    private fun contTypeset() {
        val v = cont ?: return
        val pad = PagePad(scrollPadTop, scrollPadBottom, ui.palette.bg.toArgb(), scrollPadLeft, scrollPadRight, texUrl(true))
        v.evaluateJavascript(Typeset.script(ui.settings.copy(scroll = true), ui.lang, pad, System.nanoTime()), null)
    }

    /** Subrayados guardados, para mostrarlos en el modo continuo. */
    private fun contMarks(): org.json.JSONArray {
        val v = cont
        val a = org.json.JSONArray()
        for (m in ui.marks.filter { it.type == "hl" || it.type == "sticky" }) {
            val loc = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: continue
            val pub = publication ?: continue
            val i = resourceIndex(pub, loc).takeIf { it >= 0 } ?: continue
            val c = highlightColor(m.color)
            a.put(
                JSONObject().put("h", v?.urlOf(i) ?: (ContinuousView.BASE + pub.readingOrder[i].url().toString().substringBefore('#')))
                    .put("t", loc.text.highlight ?: m.text).put("b", loc.text.before.orEmpty())
                    .put("c", if (m.type == "sticky") "transparent" else "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},0.38)")
                    .put("id", m.id).put("k", if (m.type == "sticky") "s" else "h"),
            )
        }
        return a
    }

    /** Letra, tamaño, colores y espacios del modo continuo (lo que en los otros modos hace Readium). */
    /** El papel que se ve ahora (con "según la hora" y "como la app" ya resueltos). */
    private fun paperNow(): Int = when (ui.palette.name) {
        "Crema" -> 0; "Nogal" -> 1; "Sumi" -> 2; "Sepia" -> 3; "Gris" -> 4; "Washi" -> 5; "Kraft" -> 6; else -> -1
    }

    /** Dirección de la textura del papel para la página ("" = papel liso). */
    private fun texUrl(continuous: Boolean): String {
        if (!ui.settings.texture) return ""
        val f = Paper.file(this, paperNow()) ?: return ""
        return if (continuous) "/__tex/$f" else "https://readium/assets/papel/$f"
    }

    private fun contCss(): String {
        val r = ui.settings
        val p = ui.palette
        fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())
        val f = if (GoogleFonts.isGoogle(r.font)) null else ReaderFonts.getOrNull(r.font)
        val faces = StringBuilder()
        var fam: String? = null
        val gf = GoogleFonts.get(this, r.font)
        if (gf != null) gf.let { g ->
            fam = g.family
            listOf(Triple("regular", "normal", 400), Triple("italic", "italic", 400), Triple("bold", "normal", 700)).forEach { (kind, style, weight) ->
                if (kind != "regular" && g.files[kind] == null) return@forEach
                GoogleFonts.file(this, g, kind)?.let { faces.append("@font-face{font-family:'${g.family}';src:url('/${GoogleFonts.webPath(g, it)}');font-style:$style;font-weight:$weight}") }
            }
        } else if (GoogleFonts.isGoogle(r.font)) {
            // Letra de Google que ya no está: se usa Literata
            ReaderFonts[0].let { lf -> FontFiles.regular(this, lf)?.let { reg -> fam = lf.css; faces.append("@font-face{font-family:'${lf.css}';src:url('/__font/$reg');font-style:normal;font-weight:400}") } }
        }
        if (f?.css != null) {
            val reg = FontFiles.regular(this, f)
            if (reg != null) {
                fam = f.css
                fun face(file: String?, style: String, weight: Int) {
                    if (file != null && FontFiles.has(this, file)) faces.append("@font-face{font-family:'${f.css}';src:url('/__font/$file');font-style:$style;font-weight:$weight}")
                }
                face(reg, "normal", 400)
                face(f.italic, "italic", 400)
                face(f.bold?.takeIf { FontFiles.has(this, it) } ?: f.boldAlt, "normal", 700)
            }
        }
        val override = r.layout != 0 && r.layout != 3
        val justify = Fit.align(r) != 0
        val sb = StringBuilder(faces)
        sb.append(":root{--zk-pt:${scrollPadTop}px;--zk-pb:${scrollPadBottom + 18f}px}")
        sb.append("html{font-size:${r.size}% !important;background:${hex(p.bg)} !important;-webkit-text-size-adjust:100% !important;overflow-anchor:auto;overflow-x:hidden !important;touch-action:pan-y}")
        sb.append("body{margin:0 !important;padding:0 ${scrollPadRight}px 0 ${scrollPadLeft}px !important;box-sizing:border-box !important;background:${hex(p.bg)} !important;color:${hex(p.text)} !important;line-height:${Fit.lineHeight(r, fit(r).second)} !important;overflow-wrap:break-word")
        if (fam != null) sb.append(";font-family:'$fam',serif !important")
        sb.append("}")
        sb.append("section.zk-ch *{color:inherit !important;background-color:transparent !important}")
        sb.append("section.zk-ch img,section.zk-ch svg,section.zk-ch video{max-width:100% !important;height:auto !important;max-height:92vh !important;object-fit:contain}")
        // Hojas de estilo de portadas que fijan el alto o esconden el resto: en el rollo continuo, todo baja
        sb.append("html{overflow-y:auto !important;height:auto !important}body{overflow:visible !important;height:auto !important;min-height:0 !important;position:static !important}")
        sb.append("section.zk-ch{position:relative}section.zk-skip{height:0}")
        sb.append("mark.zk-find{background:rgba(226,190,82,.55) !important;color:inherit !important;border-radius:3px;box-shadow:0 0 0 3px rgba(226,190,82,.55);transition:background 1s}")
        sb.append("section.zk-ch a{text-decoration:none !important;border-bottom:1px solid currentColor}")
        sb.append("section.zk-ch + section.zk-ch{margin-top:2.6em !important}")
        sb.append(".zk-body{margin:0 !important;padding:0 !important}")
        // Como en el modo Páginas: el texto nunca más ancho que ~66 letras (en tabletas y pantallas anchas)
        sb.append("section.zk-ch{max-width:${Fit.maxRem(r)}rem !important;margin-left:auto !important;margin-right:auto !important}")
        texUrl(true).takeIf { it.isNotEmpty() }?.let { t ->
            sb.append("html{background-image:url('$t') !important;background-size:512px auto !important;background-repeat:repeat !important}body{background-color:transparent !important}")
        }
        if (override) {
            if (fam != null) sb.append("section.zk-ch *:not(code):not(pre):not(kbd){font-family:inherit !important}")
            sb.append("section.zk-ch p,section.zk-ch li,section.zk-ch div,section.zk-ch blockquote{line-height:inherit !important}")
            sb.append("section.zk-ch p{text-align:${if (justify) "justify" else "left"} !important;hyphens:${if (justify) "auto" else "manual"} !important;-webkit-hyphens:${if (justify) "auto" else "manual"} !important}")
            listOf(null, "0.6em", "1.2em").getOrNull(r.paragraph)?.let { sb.append("section.zk-ch p{margin-top:$it !important;margin-bottom:$it !important}") }
        }
        listOf(null, 0.06, 0.14).getOrNull(Letra.spacing(r))?.let { sb.append("section.zk-ch{letter-spacing:${it}em !important;word-spacing:${it * 2.5}em !important}") }
        sb.append("::selection{background:rgba(196,67,42,0.28)}")
        // Papelito de las notas adhesivas, al final de su frase
        sb.append(".zk-stk{display:inline-block;width:.85em;height:.85em;margin-left:.2em;background:#EFD995 !important;border-radius:2px 2px 6px 2px;transform:rotate(-6deg) translateY(-.45em);box-shadow:1px 2px 4px rgba(0,0,0,.35);vertical-align:baseline;cursor:pointer}")
        return sb.toString()
    }

    /** Cuando cambia un ajuste en el modo continuo: colores, letra, márgenes y diagramación. */
    private fun contRefresh() {
        cont?.applyCss(contCss())
        lifecycleScope.launch { delay(60); contTypeset() }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private fun navConfig() = EpubNavigatorFragment.Configuration {
        // Largo de línea: unos 66 caracteres, lo ideal según los estudios (antes ~80). Afecta tabletas y el modo horizontal.
        readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(
            maxLineLength = org.readium.r2.navigator.epub.css.Length.Rem(34.0),
            // 1 px: así "pageMargins" son directamente los px de margen (tus milímetros convertidos)
            pageGutter = org.readium.r2.navigator.epub.css.Length.Px(1.0),
        )
        servedAssets = listOf("fonts/.*", "papel/.*")
        // Letra de Google elegida: va declarada en cada capítulo que Readium sirve (así nunca falta al reabrir)
        runCatching {
            GoogleFonts.get(this@ReaderActivity, ui.settings.font)?.let { g ->
                val faces = GFontInject.dataUrls(this@ReaderActivity, ui.settings.font)
                if (faces.isNotEmpty()) addFontFamilyDeclaration(FontFamily(g.family)) {
                    faces.forEach { (url, italic, bold) ->
                        org.readium.r2.shared.util.Url(url)?.let { u ->
                            addFontFace {
                                addSource(u)
                                setFontStyle(if (italic) org.readium.r2.navigator.epub.css.FontStyle.ITALIC else org.readium.r2.navigator.epub.css.FontStyle.NORMAL)
                                setFontWeight(if (bold) org.readium.r2.navigator.epub.css.FontWeight.BOLD else org.readium.r2.navigator.epub.css.FontWeight.NORMAL)
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w("Zerkalo", "ZK_GFONT_DECL ${it.message}") }
        selectionActionModeCallback = selectionCallback
        // Los cambios de capítulo en desplazamiento continuo los maneja Zerkalo (ver onDrag), no Readium:
        // Readium cambiaba de capítulo con cualquier gesto un poco de lado y siempre volvía al inicio.
        disablePageTurnsWhileScrolling = true
        shouldApplyInsetsPadding = false
        // Subrayados que parecen hechos a mano: tinta transparente que tiñe el papel, sin tapar la letra
        decorationTemplates = HtmlDecorationTemplates.defaultTemplates().also { t ->
            t[Decoration.Style.Highlight::class] = HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { deco -> markElement(deco) },
                stylesheet = MARK_CSS,
            )
            t[Decoration.Style.Underline::class] = HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOUNDS,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { "<div><div class=\"zk-sticky\"></div></div>" },
                stylesheet = STICKY_CSS,
            )
        }
        val files = FontFiles.available(this@ReaderActivity).toSet()
        ReaderFonts.forEachIndexed { i, f ->
            if (f.css != null && f.regular != null && i in files) {
                val ctx = this@ReaderActivity
                val italic = f.italic?.takeIf { FontFiles.has(ctx, it) }
                val bold = f.bold?.takeIf { FontFiles.has(ctx, it) } ?: f.boldAlt?.takeIf { FontFiles.has(ctx, it) }
                val reg = FontFiles.regular(ctx, f) ?: f.regular
                declareFont(f.css, "fonts/$reg", italic?.let { "fonts/$it" }, bold?.let { "fonts/$it" })
            }
        }
    }

    private fun markElement(deco: Decoration): String {
        val c = (deco.style as? Decoration.Style.Highlight)?.tint ?: 0xFFE2BE52.toInt()
        val dark = ui.palette.isDark
        val kind = ui.settings.markStyle
        val a = when (kind) { 1, 2 -> 0.9; else -> if (dark) 0.34 else 0.5 }
        val rgba = "rgba(${(c shr 16) and 255},${(c shr 8) and 255},${c and 255},$a)"
        val cls = when (kind) { 1 -> "zk-pen"; 2 -> "zk-wave"; else -> if (dark) "zk-mk zk-dark" else "zk-mk" }
        // La forma cambia un poco en cada línea, como un trazo de verdad
        val v = (deco.id.hashCode() and 3)
        return "<div class=\"$cls zk-v$v\" style=\"--c:$rgba\"></div>"
    }

    /**
     * Zonas de toque como Moon+ Reader:
     * izquierda o franja de arriba = anterior; derecha o franja de abajo = siguiente; centro = menú.
     * En modo estudio, tocar una palabra la traduce (los bordes siguen pasando página).
     */
    private fun tapListener(nav: EpubNavigatorFragment, pair: Boolean) = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            lastTouch = System.currentTimeMillis()
            val w = nav.publicationView.width.toFloat().coerceAtLeast(1f)
            val h = nav.publicationView.height.toFloat().coerceAtLeast(1f)
            val x = event.point.x / w
            val y = event.point.y / h
            // En modo traducir, tocar la franja de arriba o de abajo sigue abriendo el menú.
            if (ui.studyTap && (y < 0.1f || y > 0.9f)) { if (ui.lookup != null) { lookupJob?.cancel(); ui.lookup = null } else setControls(!ui.controls); return true }
            if (ui.studyTap && x in 0.16f..0.84f) {
                lifecycleScope.launch {
                    if (!lookUpAt(nav, event.point, if (pair) pairLang else ui.lang)) {
                        if (ui.lookup != null) { lookupJob?.cancel(); ui.lookup = null } else setControls(!ui.controls)
                    }
                }
                return true
            }
            if (ui.lookup != null) { lookupJob?.cancel(); ui.lookup = null; return true }
            val dir = when {
                ui.studyTap -> if (x < 0.5f) -1 else 1
                x < 0.33f -> -1
                x > 0.67f -> 1
                // Con dos idiomas cada mitad es baja: el centro abre el menú y las páginas se pasan por los lados
                ui.pairOn || ui.settings.scroll -> 0
                y < 0.22f -> -1
                y > 0.78f -> 1
                else -> 0
            }
            if (dir == 0) setControls(!ui.controls) else turn(nav, dir)
            return true
        }

        /**
         * Desplazamiento continuo: para pasar de capítulo hay que llegar al final (o al inicio) y seguir
         * deslizando a propósito. Aparece un aviso; al soltar pasado el umbral, se cambia de capítulo.
         * Un deslizamiento claramente horizontal también cambia de capítulo.
         */
        override fun onDrag(event: DragEvent): Boolean {
            if (!ui.settings.scroll) return false
            val th = 110 * resources.displayMetrics.density
            val dx = event.offset.x
            val dy = event.offset.y
            when (event.type) {
                DragEvent.Type.Start -> {
                    lastTouch = System.currentTimeMillis()
                    dragEdge[nav] = ""
                    lifecycleScope.launch { dragEdge[nav] = runCatching { nav.evaluateJavascript(EDGE_START) }.getOrNull().orEmpty() }
                    if (ui.auto) stopAuto()
                }
                DragEvent.Type.Move -> {
                    val edge = dragEdge[nav].orEmpty()
                    val vertical = abs(dy) > abs(dx) * 1.4f
                    val toNext = vertical && dy < 0 && (edge.contains("bottom") || edge.contains("both"))
                    val toPrev = vertical && dy > 0 && (edge.contains("top") || edge.contains("both"))
                    ui.edgeReady = (toNext || toPrev) && abs(dy) > th * 0.35f
                    ui.edgeHint = null
                }
                DragEvent.Type.End -> {
                    val edge = dragEdge[nav].orEmpty()
                    val vertical = abs(dy) > abs(dx) * 1.4f
                    val horizontal = abs(dx) > th * 1.3f && abs(dx) > abs(dy) * 2.2f
                    ui.edgeHint = null; ui.edgeReady = false
                    when {
                        horizontal -> chapterJump(nav, if (dx < 0) 1 else -1)
                        // ¿Ya estaba en el borde y el texto no se movió? Entonces sigue el capítulo (se pregunta al soltar,
                        // así no depende de una respuesta que llegue tarde)
                        vertical && abs(dy) > th * 0.35f -> lifecycleScope.launch {
                            val e = runCatching { nav.evaluateJavascript(EDGE_END) }.getOrNull().orEmpty()
                            if (dy < 0 && e.contains("bottom")) chapterJump(nav, 1)
                            else if (dy > 0 && e.contains("top")) chapterJump(nav, -1)
                        }
                    }
                }
            }
            return false
        }
    }

    /**
     * En desplazamiento la página nunca se corre hacia los lados. Un deslizamiento diagonal podía dejar la
     * página del motor (WebView) corrida y el texto cortado por un borde; aquí se devuelve siempre a su lugar.
     */
    private fun lockSideways(nav: EpubNavigatorFragment) {
        val root = nav.view ?: return
        fun fix(v: View) {
            if (v is android.webkit.WebView) {
                v.isHorizontalScrollBarEnabled = false
                if (v.scrollX != 0) v.scrollTo(0, v.scrollY)
                return
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) fix(v.getChildAt(i))
        }
        root.viewTreeObserver.addOnScrollChangedListener { if (ui.settings.scroll) fix(root) }
    }

    private fun pubOf(nav: EpubNavigatorFragment): Publication? = if (nav === pairNav) pairPub else publication

    private fun resourceIndex(pub: Publication, loc: Locator): Int {
        val href = hnorm(loc.href.toString())
        return pub.readingOrder.indexOfFirst { hnorm(it.url().toString()) == href }
    }

    // ---------------- la lupa ----------------
    private var bookText: BookText? = null
    private var findJob: kotlinx.coroutines.Job? = null

    private fun findInBook(q: String) {
        ui.findQuery = q
        findJob?.cancel()
        if (q.trim().length < 2) { ui.findHits = null; ui.findBusy = false; return }
        val bt = bookText ?: return
        ui.findBusy = true
        findJob = lifecycleScope.launch {
            delay(350) // se espera a que termine de escribir
            val hits = runCatching { bt.search(q) }.getOrDefault(emptyList())
            // El capítulo de cada resultado: la última entrada del índice que empieza en esa parte o antes
            val pub = publication
            if (pub != null) {
                val starts = ui.toc.map { e -> e to pub.readingOrder.indexOfFirst { hnorm(it.url().toString()) == hnorm(e.link.url().toString()) } }.filter { it.second >= 0 }.sortedBy { it.second }
                ui.findTitles = hits.map { it.chapter }.distinct().associateWith { c -> starts.lastOrNull { it.second <= c }?.first?.title ?: "Parte ${c + 1}" }
            }
            ui.findHits = hits
            ui.findBusy = false
        }
    }

    private fun goToHit(h: BookText.Hit) {
        val pub = publication ?: return
        ui.sheet = null; setControls(false)
        val c = cont
        if (c != null) { c.find(h.chapter, BookText.fold(h.match), h.nth); return }
        val nav = navigator ?: return
        val base = pub.locatorFromLink(pub.readingOrder[h.chapter]) ?: return
        val loc = base.copy(
            locations = base.locations.copy(progression = h.progression),
            text = Locator.Text(before = h.before.removePrefix("…").takeLast(40), highlight = h.match, after = h.after.removeSuffix("…").take(40)),
        )
        nav.go(loc, animated = false)
        lifecycleScope.launch {
            runCatching { nav.applyDecorations(listOf(Decoration(id = "lupa", locator = loc, style = Decoration.Style.Highlight(tint = 0xFFE2BE52.toInt()))), "lupa") }
            delay(3500)
            runCatching { nav.applyDecorations(emptyList(), "lupa") }
        }
    }

    private fun neighborTitle(nav: EpubNavigatorFragment, dir: Int): String {
        val pub = pubOf(nav) ?: return ""
        val link = pub.readingOrder.getOrNull(resourceIndex(pub, nav.currentLocator.value) + dir) ?: return if (dir > 0) "fin del libro" else "inicio"
        val href = link.url().toString().substringBefore('#')
        fun find(ls: List<org.readium.r2.shared.publication.Link>): String? {
            for (l in ls) {
                if (l.url().toString().substringBefore('#') == href) return l.title
                find(l.children)?.let { return it }
            }
            return null
        }
        return find(pub.tableOfContents) ?: link.title ?: if (dir > 0) "siguiente capítulo" else "capítulo anterior"
    }

    /**
     * Cambia de capítulo en desplazamiento continuo: hacia adelante empieza arriba del siguiente;
     * hacia atrás abre el anterior por el final, justo donde lo dejaste.
     */
    private fun chapterJump(nav: EpubNavigatorFragment, dir: Int) {
        userMoves++
        val pub = pubOf(nav) ?: return
        val idx = resourceIndex(pub, nav.currentLocator.value)
        val link = pub.readingOrder.getOrNull(idx + dir) ?: run { ui.say(if (dir > 0) "Es el final del libro" else "Es el inicio del libro"); return }
        val loc = pub.locatorFromLink(link)?.copyWithLocations(progression = if (dir > 0) 0.0 else 1.0) ?: return
        if (ui.settings.haptic) root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        nav.go(loc, animated = false)
    }

    // ---------------- leer y escuchar ----------------
    // El libro y el audiolibro se unen por capítulos: cuando el narrador empieza un capítulo,
    // el texto va al inicio de ese capítulo. Dentro del capítulo, cada quien lee a su ritmo.

    private var trackMap: Pair<String, IntArray>? = null // audiolibro|índice → capítulo del índice del libro

    /** Los capítulos del índice que mejor corresponden a los del audio (el nivel con una cantidad parecida). */
    private fun chapterEntries(tracks: Int): List<TocEntry> {
        val toc = ui.toc
        if (toc.isEmpty()) return emptyList()
        val depths = toc.map { it.depth }.distinct().sorted()
        val best = depths.minByOrNull { d -> kotlin.math.abs(toc.count { it.depth <= d } - tracks) } ?: 0
        return toc.filter { it.depth <= best }
    }

    /** Para cada capítulo del audio, cuál capítulo del libro es: por título (números en letras, romanos…) o por orden. */
    private fun mapTracks(ab: com.sebas.zerkalo.data.AudioBook): IntArray {
        val key = ab.id + "|" + ui.toc.size
        trackMap?.let { if (it.first == key) return it.second }
        val ch = chapterEntries(ab.tracks.size)
        val T = ab.tracks.size
        val C = ch.size
        val tocTok = ch.map { com.sebas.zerkalo.data.Fuzzy.tokens(it.title) }
        val out = IntArray(T) { i ->
            if (C == 0) return@IntArray -1
            val guess = if (T <= 1) 0 else ((i.toDouble() / (T - 1)) * (C - 1)).toInt()
            val trTok = com.sebas.zerkalo.data.Fuzzy.tokens(ab.tracks[i].title)
            val hits = ch.indices.filter { k -> tocTok[k].isNotEmpty() && com.sebas.zerkalo.data.Fuzzy.score(tocTok[k], trTok) >= 0.75 }
            hits.minByOrNull { kotlin.math.abs(it - guess) } ?: guess
        }
        trackMap = key to out
        return out
    }

    /** El capítulo del índice en el que vas leyendo. */
    private fun currentEntry(entries: List<TocEntry>): Int {
        val byTitle = entries.indexOfLast { it.title == ui.chapter }
        if (byTitle >= 0) return byTitle
        val loc = navigator?.currentLocator?.value ?: contLoc ?: return 0
        val href = hnorm(loc.href.toString())
        return entries.indexOfLast { hnorm(it.link.href.toString()).let { h -> h.isNotEmpty() && (href.endsWith(h) || h.endsWith(href)) } }.coerceAtLeast(0)
    }

    /** El capítulo del audiolibro que corresponde a donde vas leyendo. */
    private fun trackFor(ab: com.sebas.zerkalo.data.AudioBook): Int {
        if (ab.tracks.isEmpty()) return 0
        val map = mapTracks(ab)
        val cur = currentEntry(chapterEntries(ab.tracks.size))
        // El primero que cae en este capítulo del libro; si ninguno, el último de antes
        return (map.indices.firstOrNull { map[it] == cur } ?: map.indices.lastOrNull { map[it] in 0 until cur } ?: 0).coerceIn(0, ab.tracks.size - 1)
    }

    /** Lleva el texto al inicio del capítulo que corresponde a un capítulo del audio. */
    private fun goToTrackChapter(ab: com.sebas.zerkalo.data.AudioBook, index: Int, quiet: Boolean = false) {
        val entries = chapterEntries(ab.tracks.size)
        val k = mapTracks(ab).getOrNull(index) ?: -1
        val e = entries.getOrNull(k)
        if (e == null) { if (!quiet) ui.say("No encontré ese capítulo en el texto"); return }
        if (currentEntry(entries) == k && quiet) return
        val c = cont
        if (c != null) c.goHref(ContinuousView.BASE + e.link.url().toString()) else navigator?.go(e.link, animated = false)
        if (!quiet) ui.say(e.title)
    }

    /** Lleva el texto al capítulo que está sonando (botón "Ir al capítulo que suena"). */
    private fun goToChapterNamed(@Suppress("UNUSED_PARAMETER") title: String) {
        val st = com.sebas.zerkalo.audio.Listen.state.value
        val ab = st.book ?: return
        goToTrackChapter(ab, st.index)
        ui.sheet = null; setControls(false)
    }

    /** Que el audio empiece el capítulo que estás leyendo, desde su inicio. */
    private fun audioFromText() {
        val st = com.sebas.zerkalo.audio.Listen.state.value
        val ab = (st.book?.takeIf { it.linkedBook == book.id } ?: com.sebas.zerkalo.data.AudioStore.forBook(book.id))
            ?: run { ui.say("Este libro no tiene audiolibro unido"); return }
        if (ab.tracks.isEmpty()) { actions.listen(ab, true); return }
        val i = trackFor(ab)
        if (st.book?.id == ab.id && !st.sample) {
            com.sebas.zerkalo.audio.Listen.seekTrack(i, 0)
            if (!st.playing) com.sebas.zerkalo.audio.Listen.toggle()
        } else {
            com.sebas.zerkalo.audio.Listen.play(this, ab.copy(linkedBook = book.id), i, 0)
            com.sebas.zerkalo.data.AudioStore.link(ab.id, book.id)
        }
        ui.sheet = null; setControls(false)
        ui.say("El narrador empieza este capítulo")
    }

    /**
     * La página sigue al narrador por capítulos: al abrir el libro con su audio sonando, y cada vez que el audio
     * cambia de capítulo (solo, o porque lo pasaste en el reproductor), el texto va al inicio de ese capítulo.
     */
    private fun followAudio() {
        var last: Pair<String, Int>? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.sebas.zerkalo.audio.Listen.state.collect { st ->
                    val ab = st.book?.takeIf { it.linkedBook == book.id && !st.sample && it.tracks.isNotEmpty() } ?: run { last = null; return@collect }
                    val now = ab.id to st.index
                    val prev = last
                    last = now
                    if (prev == now || !com.sebas.zerkalo.data.Prefs.audioFollow || ui.auto) return@collect
                    launch {
                        // Al abrir: espera a que el libro esté listo
                        var n = 0
                        while (n++ < 40 && (publication == null || (navigator == null && cont == null) || ui.loading || ui.toc.isEmpty())) delay(250)
                        if (prev == null) delay(600)
                        goToTrackChapter(ab, st.index, quiet = prev == null)
                    }
                }
            }
        }
    }

    // ---------------- avance automático ----------------
    /** Desplazamiento: el texto sube solo a la velocidad elegida. Páginas: pasa de página cada tantos segundos. */
    fun startAuto() {
        val nav = navigator ?: return
        autoJob?.cancel()
        ui.auto = true
        setControls(false)
        autoJob = lifecycleScope.launch {
            if (!ui.settings.scroll) {
                while (true) {
                    delay((ui.settings.autoPage * 1000).toLong())
                    if (ui.sheet != null || ui.controls || ui.lookup != null) continue
                    if (!nav.goForward(animated = ui.settings.animate)) { stopAuto(); ui.say("Llegaste al final"); break }
                }
            } else {
                runCatching { nav.evaluateJavascript(AUTO_SCROLL.replace("__S__", ui.settings.autoSpeed.toString())) }
                while (true) {
                    delay(700)
                    val paused = ui.sheet != null || ui.controls || ui.lookup != null
                    val st = runCatching { nav.evaluateJavascript(if (paused) AUTO_PAUSE else AUTO_STATE) }.getOrNull().orEmpty()
                    if (st.contains("off")) runCatching { nav.evaluateJavascript(AUTO_SCROLL.replace("__S__", ui.settings.autoSpeed.toString())) }
                    if (st.contains("end")) {
                        // Fin del capítulo: pausa breve y sigue con el siguiente.
                        delay(1200)
                        val pub = publication ?: break
                        if (resourceIndex(pub, nav.currentLocator.value) >= pub.readingOrder.size - 1) { stopAuto(); ui.say("Llegaste al final"); break }
                        chapterJump(nav, 1)
                        delay(1500)
                    }
                }
            }
        }
    }

    // ---------------- perfiles ----------------
    private fun applyProfile(id: String, quiet: Boolean = false) {
        val pr = com.sebas.zerkalo.data.Profiles.get(id) ?: return
        Prefs.profile = id
        ui.profile = id
        updateSettings(com.sebas.zerkalo.data.Profiles.apply(ui.settings, pr.values))
        if (!quiet) ui.say(pr.name)
    }

    /** Siguiente perfil (dos toques en la hora, o el botón de luz presionado). */
    private fun nextProfile() {
        val all = com.sebas.zerkalo.data.Profiles.list.value
        if (all.isEmpty()) return
        val i = all.indexOfFirst { it.id == ui.profile }
        applyProfile(all[(i + 1).mod(all.size)].id)
    }

    /** Cambio automático por hora: Día, Noche y Oscuro total. Solo cambia al cruzar una hora, así lo que elijas a mano se respeta. */
    private var autoProfileAtOpen = ""
    private var userMoves = 0 // cuántas veces pasaste página tú (para no devolverte nunca a otra)

    private fun watchProfileHours() {
        lifecycleScope.launch {
            var last = autoProfileAtOpen
            while (true) {
                if (Prefs.profileAuto) {
                    val want = com.sebas.zerkalo.data.Profiles.forHour(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                    if (want != last) { if (last.isNotEmpty() || ui.profile != want) applyProfile(want, quiet = last.isEmpty()); last = want }
                } else last = ""
                delay(60_000)
            }
        }
    }

    // ---------------- guía de lectura rápida ----------------
    private var guideJob: kotlinx.coroutines.Job? = null

    /** Una franja que baja renglón por renglón a tus palabras por minuto; al final de la página, pasa sola. */
    private fun startGuide() {
        stopAuto()
        guideJob?.cancel()
        ui.guide = true
        setControls(false)
        val dm = resources.displayMetrics
        guideJob = lifecycleScope.launch {
            val top = ((scrollPadTop.takeIf { it > 0 } ?: 40f) * dm.density + 20 * dm.density) / dm.heightPixels
            ui.rulerY = top.coerceIn(0.06f, 0.3f)
            while (true) {
                val r = ui.settings
                val linePx = 16f * r.size / 100f * dm.density * r.lineHeight
                val words = ((if (ui.cpl > 0) ui.cpl else 60) / 6.0).coerceAtLeast(2.0)
                delay((60_000.0 / r.wpm.coerceIn(80, 900) * words).toLong().coerceAtLeast(120))
                if (ui.sheet != null || ui.controls || ui.lookup != null) continue
                val c = cont
                if (c != null) {
                    // Continuo: la franja queda quieta y el texto sube un renglón
                    ui.rulerY = 0.4f
                    c.evaluateJavascript("window.scrollBy(0, ${linePx / dm.density});", null)
                    continue
                }
                val next = ui.rulerY + linePx / dm.heightPixels
                if (next > 0.9f) {
                    val nav = navigator ?: break
                    if (!nav.goForward(animated = r.animate)) { stopGuide(); ui.say("Llegaste al final"); break }
                    delay(350)
                    ui.rulerY = top.coerceIn(0.06f, 0.3f)
                } else ui.rulerY = next
            }
        }
    }

    private fun stopGuide() {
        guideJob?.cancel(); guideJob = null
        ui.guide = false
        ui.rulerY = 0.42f
    }

    fun stopAuto() {
        autoJob?.cancel(); autoJob = null
        ui.auto = false
        lifecycleScope.launch { runCatching { navigator?.evaluateJavascript(AUTO_STOP) } }
    }

    /** Pasa página. En desplazamiento continuo avanza una pantalla dejando dos líneas de referencia. */
    private fun turn(nav: EpubNavigatorFragment?, dir: Int) {
        userMoves++
        if (ui.aloudPlaying) lifecycleScope.launch { delay(520); raFromView() }
        if (nav == null) { contPage(dir); return }
        if (ui.controls) setControls(false)
        if (ui.settings.haptic) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (!ui.settings.scroll) {
            if (curlAllowed()) { curlTap(nav, dir); return }
            if (dir > 0) nav.goForward(animated = ui.settings.animate) else nav.goBackward(animated = ui.settings.animate)
            return
        }
        lifecycleScope.launch {
            // Lo que se lee con claridad está entre el desenfoque de arriba y el de abajo
            val pt = scrollPadTop
            val pb = scrollPadBottom + 18f
            val js = SCROLL_BY.replace("__D__", dir.toString()).replace("__S__", if (ui.settings.animate) "smooth" else "auto")
                .replace("__PT__", pt.toString()).replace("__PB__", pb.toString())
            val r = runCatching { nav.evaluateJavascript(js) }.getOrNull().orEmpty()
            // Al final (o al inicio) del capítulo, se pasa al siguiente (o al anterior).
            if (r.contains("edge")) chapterJump(nav, dir)
        }
    }

    // ---------------- hoja de papel ----------------
    private fun curlAllowed(): Boolean {
        val s = ui.settings
        return navigator != null && cont == null && !s.scroll && s.animate && s.pageAnim == 2 &&
            !ui.pairOn && !inkUi.on && ui.sheet == null && !ui.controls && ui.lookup == null && curl != null && curl?.busy != true
    }

    private fun curlColor(): Int = android.graphics.Color.argb(
        255, (ui.palette.bg.red * 255).toInt(), (ui.palette.bg.green * 255).toInt(), (ui.palette.bg.blue * 255).toInt(),
    )

    private fun hereKey(): String? = navigator?.currentLocator?.value?.let {
        it.href.toString() + "@" + it.locations.progression + "@" + it.locations.position + "@" + ui.settings.hashCode() + "@" + ui.palette.name + "@" + ui.marks.size
    }

    /** Cuando la página queda quieta, se guarda su foto: así la hoja se dobla al instante, sin trabar el toque. */
    private val curlCacheRun = Runnable { curlPreShot() }
    private fun curlCacheSoon() {
        if (!::root.isInitialized || curl == null || ui.settings.pageAnim != 2 || ui.settings.scroll) return
        root.removeCallbacks(curlCacheRun)
        root.postDelayed(curlCacheRun, 900)
    }

    /** Al tocar la pantalla se toma ya la foto de la página: si el dedo se desliza, la hoja arranca al instante. */
    private fun curlPreShot() {
        val pf = curl ?: return
        if (pf.busy) return
        val key = hereKey()
        if (snapDown != null && snapLoc == key) return
        // Se dibuja solo la página (sin los botones que flotan encima, como la bolita 記)
        val bmp = Snap.draw(panes, Snap.scale(this, panes.height), curlColor())
        if (bmp != null) { snapDown?.recycle(); snapDown = bmp; snapAt = System.currentTimeMillis(); snapLoc = key; return }
        Snap.take(window, panes, Snap.scale(this, panes.height)) { b ->
            if (b == null) return@take
            if (pf.busy) { b.recycle(); return@take }
            snapDown?.recycle()
            snapDown = b; snapAt = System.currentTimeMillis(); snapLoc = key
        }
    }

    /** La foto de la página de ahora: la que se tomó al tocar (si sigue siendo esta página) o una nueva. */
    private fun curlShot(got: (Bitmap?) -> Unit) {
        val fresh = snapDown?.takeIf { !it.isRecycled && snapLoc == hereKey() }
        if (fresh != null) { snapDown = null; got(fresh); return }
        val drawn = Snap.draw(panes, Snap.scale(this, panes.height), curlColor())
        if (drawn != null) { got(drawn); return }
        Snap.take(window, panes, Snap.scale(this, panes.height), got)
    }

    /** Espera a que el libro se haya dibujado en su nueva página (después de pasarla por debajo). */
    private suspend fun waitRedraw(nav: EpubNavigatorFragment, before: Locator?) {
        kotlinx.coroutines.withTimeoutOrNull(800) { nav.currentLocator.first { it != before } }
        delay(70)
    }

    /**
     * Prepara la hoja encima y pasa el libro por debajo. [waitUnder]: esperar a que la página nueva esté
     * dibujada antes de soltar la hoja (para los toques; con el dedo se suelta enseguida).
     */
    private fun curlStart(nav: EpubNavigatorFragment, dir: Int, waitUnder: Boolean, ready: (Int) -> Unit) {
        val pf = curl ?: return ready(0)
        curlShot { bmp ->
            if (bmp == null || isFinishing) { ready(0); return@curlShot }
            pf.paper = curlColor(); pf.dark = ui.palette.isDark
            pf.cover(dir, bmp) {
                val before = nav.currentLocator.value
                val moved = runCatching { if (dir > 0) nav.goForward(animated = false) else nav.goBackward(animated = false) }.getOrDefault(false)
                if (!moved) { pf.clear(); ready(-1); return@cover }
                if (dir > 0) {
                    if (!waitUnder) { ready(1); return@cover }
                    lifecycleScope.launch { waitRedraw(nav, before); ready(1) }
                } else lifecycleScope.launch {
                    // Atrás: se dibuja la página anterior (que ya está debajo) y es ella la que entra
                    waitRedraw(nav, before)
                    val prev = Snap.draw(panes, Snap.scale(this@ReaderActivity, panes.height), curlColor())
                    if (prev == null) { pf.clear(); ready(-1); return@launch } // sin foto: queda pasada, sin animación
                    pf.setPrevious(prev) { ready(1) }
                }
            }
        }
    }

    private fun curlTap(nav: EpubNavigatorFragment, dir: Int) {
        if (curl?.busy == true) return
        curlStart(nav, dir, waitUnder = false) { r ->
            Log.i("Zerkalo", "ZK_CURL toque dir=$dir r=$r")
            when (r) {
                1 -> curl?.flip()
                0 -> runCatching { if (dir > 0) nav.goForward(animated = false) else nav.goBackward(animated = false) } // sin foto: pasa sin animación
            }
        }
    }

    private fun curlBegin(dir: Int, y: Float): Boolean {
        val nav = navigator ?: return false
        userMoves++
        if (curl?.busy == true) return false
        curlLive = false; curlPending = null; curlEnd = null
        curlStart(nav, dir, waitUnder = false) { r ->
            Log.i("Zerkalo", "ZK_CURL dedo dir=$dir r=$r")
            if (r != 1) { curlPending = null; curlEnd = null; return@curlStart }
            curlLive = true
            curl?.press(y)
            curlPending?.let { (dx, py) -> curlDrag(dx, py) }
            curlEnd?.let { e -> curlRelease(e[0], e[1], e[2] > 0.5f, e[3]) }
            curlPending = null; curlEnd = null
        }
        return true
    }

    /** El dedo lleva la hoja: adelante la esquina derecha va hacia la izquierda; atrás, al revés. */
    private fun curlX(dx: Float): Float {
        val w = root.width.toFloat()
        val d = curl?.dir ?: 1
        return if (d > 0) (w - 2f - dx).coerceIn(0f, w) else (2f + dx).coerceIn(0f, w)
    }

    private fun curlDrag(dx: Float, y: Float) { curl?.drag(curlX(dx), y) }

    private fun curlRelease(dx: Float, y: Float, complete: Boolean, vx: Float) {
        curlLive = false
        curl?.release(curlX(dx), y, complete, vx)
    }

    /** Terminó el doblez: si pasó, listo; si volvió, el libro también vuelve a la página de antes. */
    private fun curlDone(d: Int, completed: Boolean) {
        Log.i("Zerkalo", "ZK_CURL fin dir=$d pasó=$completed")
        val pf = curl ?: return
        val nav = navigator
        if (completed || nav == null) {
            if (ui.settings.haptic && completed) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            pf.clear()
            if (ui.aloudPlaying) lifecycleScope.launch { raFromView() }
            return
        }
        lifecycleScope.launch {
            val before = nav.currentLocator.value
            runCatching { if (d > 0) nav.goBackward(animated = false) else nav.goForward(animated = false) }
            waitRedraw(nav, before)
            pf.clear()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val nav = navigator
        if (nav != null && ui.sheet == null) {
            // Con el menú abierto, los botones de volumen vuelven a ser solo volumen (para bajar la música)
            val volTurns = ui.settings.volumeKeys && !ui.controls && ui.lookup == null
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (volTurns) 1 else 0
                KeyEvent.KEYCODE_VOLUME_UP -> if (volTurns) -1 else 0
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> 1
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> -1
                else -> 0
            }
            if (dir != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) turn(nav, dir)
                return true
            }
        } else if (cont != null && ui.sheet == null && ui.settings.volumeKeys && !ui.controls) {
            val dir = when (event.keyCode) { KeyEvent.KEYCODE_VOLUME_DOWN -> 1; KeyEvent.KEYCODE_VOLUME_UP -> -1; else -> 0 }
            if (dir != 0) { if (event.action == KeyEvent.ACTION_DOWN) contPage(dir); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun EpubNavigatorFragment.Configuration.declareFont(name: String, regular: String, italic: String?, bold: String?) {
        addFontFamilyDeclaration(FontFamily(name)) {
            addFontFace { addSource(regular); setFontStyle(FontStyle.NORMAL); setFontWeight(CssWeight.NORMAL) }
            italic?.let { addFontFace { addSource(it); setFontStyle(FontStyle.ITALIC); setFontWeight(CssWeight.NORMAL) } }
            bold?.let { addFontFace { addSource(it); setFontStyle(FontStyle.NORMAL); setFontWeight(CssWeight.BOLD) } }
        }
    }

    private fun flatten(links: List<Link>, depth: Int): List<TocEntry> = links.flatMap { l ->
        listOf(TocEntry(l.title?.trim().orEmpty().ifEmpty { "Sin título" }, l, depth)) + flatten(l.children, depth + 1)
    }

    // ---------------- avance de lectura ----------------
    private fun pendingMs() = if (lastEvent == 0L) 0L else minOf(System.currentTimeMillis() - lastEvent, 180_000L)

    private fun onLocator(loc: Locator, turn: Boolean = true) {
        inkView?.invalidate()
        checkInkProfiles()
        val now = System.currentTimeMillis()
        if (turn) {
            activeMs += pendingMs(); lastEvent = now
            val pos = loc.locations.position ?: 0
            val dt = (now - lastTurn) / 1000f
            val dp = pos - lastPosition
            if (lastTurn > 0 && dp in 1..3 && dt in 4f..240f) {
                Prefs.secPerPosition = Prefs.secPerPosition * 0.85f + (dt / dp) * 0.15f
            }
            lastTurn = now; lastPosition = pos
        }
        val total = loc.locations.totalProgression?.toFloat() ?: ui.progress
        ui.progress = total
        ui.chapter = loc.title?.takeIf { it.isNotBlank() } ?: chapterFor(loc) ?: book.title
        ui.minutesToday = Library.minutesToday() + ((activeMs + pendingMs()) / 60_000).toInt()
        val pos = loc.locations.position
        updateShiori(loc)
        curlCacheSoon()
        ui.chapterLeft = chapterLeft(pos)
        if (turn && ui.pairLinked && System.currentTimeMillis() > ignoreMainUntil) syncPair(loc)
        val json = loc.toJSON().toString()
        Library.update(book.id) { it.copy(progress = total.toDouble(), locator = json, lastOpened = now) }
        val last = positions.lastOrNull()?.lastOrNull()?.locations?.position
        if (turn && pos != null && last != null && pos >= last && !book.finished && Library.book(book.id)?.finished != true) {
            Library.update(book.id) { it.copy(finishedAt = now, progress = 1.0) }
            ui.finishedNow = true
            // Su sello: se decide ahora (con la portada que tiene) si sale de cera
            lifecycleScope.launch {
                com.sebas.zerkalo.data.Stamps.settle(Library.books.value)
                ui.finishedStamp = com.sebas.zerkalo.data.Stamps.bookStamps(Library.books.value).lastOrNull { it.book.id == book.id }
            }
        }
    }

    private fun chapterFor(loc: Locator): String? {
        val href = hnorm(loc.href.toString())
        return ui.toc.lastOrNull { hnorm(it.link.href.toString()).let { h -> h.isNotEmpty() && (href.endsWith(h) || h.endsWith(href)) } }?.title
    }

    private fun chapterLeft(pos: Int?): String {
        if (pos == null || positions.isEmpty()) return ""
        val res = positions.firstOrNull { list -> list.any { it.locations.position == pos } } ?: return ""
        val end = res.lastOrNull()?.locations?.position ?: return ""
        val left = end - pos
        if (left <= 0) return "Última página del capítulo"
        val mins = ceil(left * Prefs.secPerPosition / 60f).toInt()
        return if (mins <= 1) "Menos de 1 min para cerrar el capítulo" else "$mins min para cerrar el capítulo"
    }

    private fun posOf(m: Mark): Int? = runCatching { Locator.fromJSON(JSONObject(m.locator))?.locations?.position }.getOrNull()

    // ---------------- controles ----------------
    fun setControls(show: Boolean) {
        ui.controls = show
        val c = WindowInsetsControllerCompat(window, root)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun paletteFor(r: ReaderSettings): Palette {
        var pal = basePalette(r)
        if (r.ink != 0) {
            // Color de la letra en todos los papeles: tintas claras en los oscuros y tintas oscuras en los claros
            val argb = when {
                r.ink == 5 -> if (pal.isDark) r.customInk else r.customInkLight
                pal.isDark -> InkColors.getOrElse(r.ink) { 0 }
                else -> InkColorsLight.getOrElse(r.ink) { 0 }
            }
            if (argb != 0) {
                val ink = androidx.compose.ui.graphics.Color(argb)
                pal = pal.copy(text = ink, text2 = ink.copy(alpha = 0.72f))
            }
        }
        // Filtro cálido de verdad: se tiñen el papel y la letra (no un velo encima), así el texto sigue nítido.
        if (r.warmth > 0f) {
            val w = r.warmth
            fun warm(c: androidx.compose.ui.graphics.Color) = androidx.compose.ui.graphics.Color(
                red = c.red, green = c.green * (1f - 0.10f * w), blue = c.blue * (1f - 0.42f * w), alpha = c.alpha,
            )
            pal = pal.copy(bg = warm(pal.bg), surface = warm(pal.surface), card = warm(pal.card), text = warm(pal.text), text2 = warm(pal.text2))
        }
        return pal
    }

    private fun basePalette(r: ReaderSettings): Palette = when (r.paper) {
        0 -> Chashitsu
        1 -> Nogal
        2 -> SumiPalette
        3 -> SepiaPalette
        4 -> GrisPalette
        5 -> WashiPalette
        6 -> KraftPalette
        // Según la hora: papel claro de día (se lee mejor), oscuro de noche (menos luz en los ojos).
        -2 -> java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { if (it in 7..18) Chashitsu else Nogal }
        else -> when (Prefs.paper.value) {
            PaperPref.CREMA -> Chashitsu
            PaperPref.NOGAL -> Nogal
            PaperPref.SUMI -> SumiPalette
            PaperPref.SISTEMA -> if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) Nogal else Chashitsu
        }
    }

    private fun applyPaper() {
        val p = ui.palette
        root.setBackgroundColor(p.bg.toArgb())
        divider.setBackgroundColor(p.line.toArgb())
        window.decorView.setBackgroundColor(p.bg.toArgb())
        val c = WindowInsetsControllerCompat(window, root)
        c.isAppearanceLightStatusBars = !p.isDark
        c.isAppearanceLightNavigationBars = !p.isDark
    }

    private fun preferences(r: ReaderSettings, p: Palette): EpubPreferences {
        val fam = fontCss(this, r.font)
        // Los estilos del libro se desactivan siempre: si no, el libro manda sobre el justificado
        // y el interlineado (con la letra "Original" se conserva su tipografía).
        return EpubPreferences(
            backgroundColor = RColor(p.bg.toArgb()),
            textColor = RColor(p.text.toArgb()),
            fontFamily = fam?.let { FontFamily(it) },
            fontSize = r.size / 100.0,
            lineHeight = Fit.lineHeight(r, fit(r).second).toDouble(),
            pageMargins = pageMargins(r),
            textAlign = if (Fit.align(r) != 0) TextAlign.JUSTIFY else TextAlign.START,
            hyphens = Fit.align(r) != 0,
            // Original: manda el diseño del libro (Zerkalo solo corrige lo que se rompe y respeta tu justificado)
            publisherStyles = r.layout == 0 || r.layout == 3,
            scroll = r.scroll,
            paragraphSpacing = listOf(null, 0.6, 1.2).getOrNull(r.paragraph),
            letterSpacing = listOf(null, 0.06, 0.14).getOrNull(Letra.spacing(r)),
            wordSpacing = listOf(null, 0.15, 0.3).getOrNull(Letra.spacing(r)),
        )
    }

    /**
     * Márgenes de los lados. En modo Zerkalo se achican cuando la letra crece, para que la línea
     * siga teniendo un largo cómodo (y los versos quepan) en vez de quedar con 4 palabras.
     */
    private fun pageMargins(r: ReaderSettings): Double =
        // Páginas: tus milímetros de los lados, dentro de la hoja (el "gutter" de Readium vale 1 px). Continuo: los pone Zerkalo.
        if (r.scroll || r.continuous) 0.0 else gutterCss

    /** Margen de los lados dentro de la página, en px de CSS (lo calcula applyMargins con tus milímetros). */
    private var gutterCss = 0.0

    // ---------------- diagramación ----------------
    private val typesetHref = HashMap<EpubNavigatorFragment, String>()

    /** Aplica la diagramación de Zerkalo al capítulo que se ve (una vez por capítulo y al cambiar ajustes). */
    private fun typeset(nav: EpubNavigatorFragment, force: Boolean = false) {
        val href = nav.currentLocator.value.href.toString()
        if (!force && typesetHref[nav] == href) return
        typesetHref[nav] = href
        val lang = if (nav === pairNav) pairLang else ui.lang
        val entry = nav.currentLocator.value
        val fresh = !force && nav === navigator
        val movesAtEntry = userMoves
        lifecycleScope.launch {
            // Como en Books: el capítulo se acomoda apenas aparece, sin parpadeos ni papel encima.
            // Las pasadas siguientes solo actúan si algo cambió (letras o imágenes que terminaron de cargar).
            val pad = PagePad(scrollPadTop, scrollPadBottom, ui.palette.bg.toArgb(), scrollPadLeft, scrollPadRight, texUrl(false))
            for ((i, wait) in listOf(0L, 240L, 700L).withIndex()) {
                if (wait > 0) delay(wait)
                // Letra de Google: va dentro de la página. Se revisa en cada pasada, porque al abrir el libro
                // la página puede no estar lista todavía en la primera.
                GoogleFonts.get(this@ReaderActivity, ui.settings.font)?.let { g ->
                    val has = runCatching { nav.evaluateJavascript(GFontInject.check(g.family)) }.getOrNull()
                    if (has == "false") {
                        val js = withContext(Dispatchers.IO) { runCatching { GFontInject.script(this@ReaderActivity, ui.settings.font) }.getOrNull() }
                        if (js != null) runCatching { nav.evaluateJavascript(js) }.onSuccess { Log.i("Zerkalo", "ZK_GFONT_PAGE ${g.family} $href $it") }
                    }
                }
                val r = runCatching { nav.evaluateJavascript(Typeset.script(ui.settings, lang, pad, if (force && i == 0) System.nanoTime() else 0)) }.getOrNull()
                if (i == 0) Log.i("Zerkalo", "ZK_TYPESET $href $r")
                if (i == 1) {
                    // Al entrar por el final (o a un punto guardado), volver a ese punto tras reacomodar
                    val p = entry.locations.progression ?: 0.0
                    val now = nav.currentLocator.value
                    if (fresh && userMoves == movesAtEntry && curl?.busy != true && !ui.settings.scroll && p > 0.001 && now.href.toString() == href &&
                        abs((now.locations.progression ?: 0.0) - p) > 0.0005
                    ) nav.go(entry, animated = false)
                }
            }
        }
    }

    /** Brillo propio del lector (no cambia el del sistema). */
    private fun applyBrightness(b: Float) {
        // 0..0.3: pantalla al mínimo y un velo negro que oscurece todavía más (como Moon+ Reader).
        // 0.3..1: brillo normal de la pantalla.
        val screen = when {
            b < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            b < 0.3f -> 0.01f
            else -> 0.02f + (b - 0.3f) / 0.7f * 0.98f
        }
        ui.dim = if (b in 0f..0.3f) (0.3f - b) / 0.3f * 0.78f else 0f
        applyVeil()
        window.attributes = window.attributes.apply { screenBrightness = screen }
    }

    /** El velo se suma al oscurecimiento del brillo: 1 − (1 − brillo)(1 − velo). */
    private fun applyVeil() {
        val base = if (ui.settings.brightness in 0f..0.3f) (0.3f - ui.settings.brightness) / 0.3f * 0.78f else 0f
        ui.dim = 1f - (1f - base) * (1f - ui.settings.veil.coerceIn(0f, 0.85f))
    }

    fun updateSettings(r: ReaderSettings) {
        // Con ajustes propios, la letra y los márgenes se guardan solo para este libro; lo demás vale para todos.
        if (ui.ownLook) {
            Prefs.setBookLook(book.id, r.look())
            Prefs.setReader(r.withLook(Prefs.reader.value.look()))
        } else Prefs.setReader(r)
        val old = ui.settings
        // Entrar o salir del modo continuo cambia de motor: se vuelve a abrir el lector en el mismo punto
        if (old.continuous != r.continuous) { ui.settings = r; recreate(); return }
        ui.settings = r
        ui.palette = paletteFor(r)
        if (cont != null) {
            if (old.brightness != r.brightness) applyBrightness(r.brightness)
            if (old.veil != r.veil) applyVeil()
            applyPaper(); applyMargins(); contRefresh()
            return
        }
        if (old.brightness != r.brightness) applyBrightness(r.brightness)
        if (old.veil != r.veil) applyVeil()
        if (fit(old).first != fit(r).first) applyMargins() else fit(r)
        if (old.mmLeft != r.mmLeft || old.mmRight != r.mmRight || old.mmTop != r.mmTop || old.mmBottom != r.mmBottom || old.scroll != r.scroll || old.respectCamera != r.respectCamera) {
            applyMargins()
            if (old.copy(mmLeft = r.mmLeft, mmRight = r.mmRight, mmTop = r.mmTop, mmBottom = r.mmBottom, respectCamera = r.respectCamera) == r) {
                // Solo cambiaron los márgenes: basta con volver a diagramar (para el espacio y el desenfoque del desplazamiento)
                lifecycleScope.launch { delay(120); navigator?.let { typeset(it, force = true) }; pairNav?.let { typeset(it, force = true) } }
                return
            }
        }
        if (ui.auto && (old.autoSpeed != r.autoSpeed || old.autoPage != r.autoPage || old.scroll != r.scroll)) startAuto()
        // Estos ajustes no necesitan rehacer las páginas.
        if (old.copy(
                brightness = r.brightness, veil = r.veil, ruler = r.ruler, haptic = r.haptic, volumeKeys = r.volumeKeys, breaks = r.breaks, animate = r.animate,
                fullscreen = r.fullscreen, vMargin = r.vMargin, autoSpeed = r.autoSpeed, autoPage = r.autoPage, info = r.info,
                noteButton = r.noteButton, wpm = r.wpm,
                mmLeft = r.mmLeft, mmRight = r.mmRight, mmTop = r.mmTop, mmBottom = r.mmBottom, respectCamera = r.respectCamera,
            ) == r
        ) return
        applyPaper()
        navigator?.submitPreferences(preferences(r, ui.palette))
        pairNav?.submitPreferences(preferences(r, ui.palette))
        lifecycleScope.launch {
            // Readium rehace las páginas; después se vuelve a diagramar con los ajustes nuevos
            delay(250)
            navigator?.let { typeset(it, force = true) }
            pairNav?.let { typeset(it, force = true) }
            if (old.markStyle != r.markStyle || old.paper != r.paper) runCatching { navigator?.applyDecorations(emptyList(), "subrayados") }
            applyHighlights()
        }
    }

    // ---------------- subrayados y shiori ----------------
    private suspend fun applyHighlights() {
        val nav = navigator ?: return
        val decos = ui.marks.filter { it.type == "hl" }.mapNotNull { m ->
            val loc = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: return@mapNotNull null
            val tint = highlightColor(m.color).toArgb()
            Decoration(id = m.id, locator = loc, style = Decoration.Style.Highlight(tint = tint))
        }
        runCatching { nav.applyDecorations(decos, "subrayados") }
        // Notas adhesivas: un papelito en la esquina de la frase (estilo "Underline" con su propia plantilla)
        val notes = ui.marks.filter { it.type == "sticky" }.mapNotNull { m ->
            val loc = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: return@mapNotNull null
            Decoration(id = m.id, locator = loc, style = Decoration.Style.Underline(tint = 0xFFEFD995.toInt()))
        }
        runCatching { nav.applyDecorations(notes, "notas") }
    }

    private fun refreshMarks() {
        ui.marks = Library.marks.value.filter { it.bookId == book.id }
        // Un libro tiene una sola cinta: si quedaron varios shioris de antes, se deja el último
        val ss = ui.marks.filter { it.type == "shiori" }
        if (ss.size > 1) {
            val keep = ss.maxByOrNull { it.id }
            ss.filter { it !== keep }.forEach { Library.removeMark(it.id) }
            ui.marks = Library.marks.value.filter { it.bookId == book.id }
        }
        cont?.setMarks(contMarks())
        contLoc?.let { onLocator(it, turn = false) }
        navigator?.currentLocator?.value?.let { onLocator(it, turn = false) }
        lifecycleScope.launch { applyHighlights() }
    }

    fun highlight(color: Int, note: String? = null) {
        val nav = navigator ?: return
        lifecycleScope.launch {
            val sel = nav.currentSelection() ?: return@launch
            nav.clearSelection()
            if (note == null) saveHighlight(sel.locator, color, "") else ui.noteFor = sel.locator
        }
    }

    fun saveHighlight(loc: Locator, color: Int, note: String) {
        val m = Mark(
            id = "h" + System.currentTimeMillis().toString(36), bookId = book.id, type = "hl",
            locator = loc.toJSON().toString(), text = loc.text.highlight?.trim().orEmpty(), note = note, color = color,
            chapter = ui.chapter, progress = loc.locations.totalProgression ?: ui.progress.toDouble(),
        )
        Library.putMark(m)
        refreshMarks()
        ui.say(if (note.isBlank()) "Subrayado" else "Nota guardada")
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Lo más usado primero; lo demás Android lo guarda solo en "⋮" si no cabe
            menu.add(Menu.NONE, 101, 0, "Subrayar")
            menu.add(Menu.NONE, 107, 1, "Nota adhesiva")
            menu.add(Menu.NONE, 106, 2, "Al apunte")
            menu.add(Menu.NONE, 103, 3, "Copiar")
            menu.add(Menu.NONE, 104, 4, "Traducir")
            menu.add(Menu.NONE, 105, 5, "Tarjeta")
            menu.add(Menu.NONE, 102, 6, "Subrayar con nota")
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                101 -> highlight(Prefs.lastInk)
                102 -> highlight(Prefs.lastInk, note = "")
                104 -> lifecycleScope.launch {
                    // La selección puede estar en cualquiera de los dos paneles.
                    val (nav, lang) = listOfNotNull(navigator?.let { it to ui.lang }, pairNav?.let { it to pairLang })
                        .firstOrNull { it.first.currentSelection() != null } ?: return@launch
                    val sel = nav.currentSelection() ?: return@launch
                    nav.clearSelection()
                    val t = sel.locator.text.highlight?.trim().orEmpty()
                    if (t.isNotEmpty()) startLookup(t, sel.locator.text.before.orEmpty().takeLast(160) + t + sel.locator.text.after.orEmpty().take(160), lang, sel.locator.toJSON().toString())
                }
                105 -> lifecycleScope.launch {
                    val nav = listOfNotNull(navigator, pairNav).firstOrNull { it.currentSelection() != null } ?: return@launch
                    val sel = nav.currentSelection() ?: return@launch
                    nav.clearSelection()
                    val t = sel.locator.text.highlight?.trim().orEmpty()
                    if (t.isNotEmpty()) ui.cardDraft = CardDraft(t, sel.locator.toJSON().toString(), null)
                }
                106 -> lifecycleScope.launch {
                    val nav = listOfNotNull(navigator, pairNav).firstOrNull { it.currentSelection() != null } ?: return@launch
                    val sel = nav.currentSelection() ?: return@launch
                    nav.clearSelection()
                    val t = sel.locator.text.highlight?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        com.sebas.zerkalo.data.Apuntes.addQuote(book.id, t, ui.chapter.take(40))
                        note.open()
                    }
                }
                107 -> lifecycleScope.launch {
                    val nav = navigator ?: return@launch
                    val sel = nav.currentSelection() ?: return@launch
                    nav.clearSelection()
                    val loc = sel.locator
                    // La nota adhesiva queda pegada a la frase: sobrevive aunque cambien la letra o el tamaño
                    val m = Mark(
                        id = "n" + System.currentTimeMillis().toString(36), bookId = book.id, type = "sticky",
                        locator = loc.toJSON().toString(), text = loc.text.highlight?.trim().orEmpty(),
                        chapter = ui.chapter, progress = loc.locations.totalProgression ?: ui.progress.toDouble(),
                    )
                    Library.putMark(m)
                    refreshMarks()
                    sticky = m
                }
                103 -> lifecycleScope.launch {
                    val t = navigator?.currentSelection()?.locator?.text?.highlight.orEmpty()
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Zerkalo", t))
                    navigator?.clearSelection()
                }
                else -> return false
            }
            mode.finish()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    private val actions = ReaderActions(
        close = { finish() },
        openNote = { setControls(false); note.toggle() },
        toggleInk = { toggleInk() },
        ink = { t -> inkUi.tool = t; if (!inkUi.on) toggleInk() },
        export = { ui.sheet = "export" },
        ownLook = { on -> setOwnLook(on) },
        useLook = { look -> updateSettings(ui.settings.withLook(look)); ui.inkOther = null },
        hideInkHint = { hideInkHint() },
        exportEpub = { exportEpub() },
        exportInk = { exportInkPages() },
        exportNote = { com.sebas.zerkalo.ui.shareTxt(this, book.title, com.sebas.zerkalo.data.Apuntes.flow(book.id).value) },
        toggleShiori = { toggleShiori() },
        goShiori = { goShiori() },
        aloud = { aloudStart() },
        noteGo = { l -> ui.sheet = null; followLink(l) },
        aloudToggle = { if (ui.aloudPlaying) aloudPause() else aloudResume() },
        aloudStop = { aloudStop() },
        aloudRate = { aloudRate() },
        aloudSleep = { aloudSleep() },
        backFromShiori = { goBackFromShiori() },
        go = { if (cont != null) contGo(it) else { navigator?.go(it, animated = false); setControls(false) } },
        goLink = { l ->
            val c = cont
            if (c != null) { c.goHref(ContinuousView.BASE + l.url().toString()); setControls(false) }
            else { navigator?.go(l, animated = false); setControls(false) }
        },
        settings = { updateSettings(it) },
        find = { findInBook(it) },
        goHit = { goToHit(it) },
        removeMark = { Library.removeMark(it.id); ui.activeMark = null; refreshMarks() },
        saveNote = { loc, note -> saveHighlight(loc, 1, note); ui.noteFor = null },
        recolor = { m, c -> Prefs.lastInk = c; Library.putMark(m.copy(color = c)); ui.activeMark = null; refreshMarks() },
        seek = { f -> seek(f) },
        brightnessLive = { v -> applyBrightness(v) },
        toggleAuto = { if (ui.auto) stopAuto() else startAuto() },
        toggleGuide = { if (ui.guide) stopGuide() else startGuide() },
        profile = { applyProfile(it) },
        nextProfile = { nextProfile() },
        saveProfile = { id ->
            com.sebas.zerkalo.data.Profiles.update(id, ui.settings)
            ui.profile = id; Prefs.profile = id
            ui.say("Guardado en «${com.sebas.zerkalo.data.Profiles.get(id)?.name}»")
        },
        newProfile = { name ->
            val pr = com.sebas.zerkalo.data.Profiles.create(name, ui.settings)
            ui.profile = pr.id; Prefs.profile = pr.id
            ui.say("Perfil «${pr.name}» guardado")
        },
        deleteProfile = { id -> com.sebas.zerkalo.data.Profiles.delete(id); if (ui.profile == id) ui.profile = "" },
        findAudio = { com.sebas.zerkalo.data.AudioStore.findFor(book) },
        searchAudio = { q ->
            val lang = book.lang.ifBlank { "es" }
            runCatching { com.sebas.zerkalo.data.LibriVox.search(lang, q, rows = 20) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { com.sebas.zerkalo.data.LibriVox.search("all", q, rows = 20) }.getOrDefault(emptyList()) }
        },
        linkedAudio = { com.sebas.zerkalo.data.AudioStore.forBook(book.id) },
        listen = { ab, here ->
            // Si ya lo venías escuchando, conserva por dónde ibas en cada capítulo.
            val base = com.sebas.zerkalo.data.AudioStore.get(ab.id)?.let { it.copy(tracks = it.tracks.ifEmpty { ab.tracks }) } ?: ab
            val linked = base.copy(linkedBook = book.id)
            val i = if (here) trackFor(linked) else linked.index
            com.sebas.zerkalo.audio.Listen.play(this, linked, i, linked.spot(i))
            com.sebas.zerkalo.data.AudioStore.link(linked.id, book.id)
            ui.sheet = null
        },
        goToTrack = { title -> goToChapterNamed(title) },
        audioFromHere = { audioFromText() },
        setInks = { Prefs.setInks(it) },
        highlightWith = { c -> Prefs.lastInk = c },
        openInks = { ui.activeMark = null; ui.sheet = "inks" },
        turn = { d -> turn(navigator, d) },
        toggleStudy = toggle@{
            ui.studyTap = !ui.studyTap; Prefs.studyTap = ui.studyTap
            ui.say(if (ui.studyTap) "Toca una palabra para traducirla" else "Modo estudio apagado")
            if (ui.studyTap) setControls(false)
        },
        closeLookup = { lookupJob?.cancel(); ui.lookup = null },
        retarget = { to -> Prefs.translateTo = to; ui.lookup?.let { startLookup(it.text, it.sentence, it.from, it.locator) } },
        translateSentence = { translateSentence() },
        saveWord = { saveWord() },
        wordCard = { wordCard() },
        saveCard = { d, f, b, k -> saveCard(d, f, b, k) },
        cardFromMark = { m -> ui.activeMark = null; ui.cardDraft = CardDraft(m.text, m.locator, m.id) },
        speak = { t, lang -> speak(t, lang) },
        pairCandidates = { Library.books.value.filter { it.id != book.id }.sortedWith(compareByDescending<Book> { it.author.isNotBlank() && it.author == book.author }.thenByDescending { it.lang.isNotBlank() && it.lang != book.lang }.thenByDescending { it.lastOpened }) },
        setPair = { other -> setPair(other) },
        togglePair = { showPair(!ui.pairOn) },
        syncPair = { (navigator?.currentLocator?.value ?: contLoc)?.let { syncPair(it, force = true) } },
        togglePairLink = { togglePairLink() },
        swapPair = { swapPair() },
        setPairRatio = { setPairRatio(it) },
        veilLive = { v -> ui.dim = 1f - (1f - (if (ui.settings.brightness in 0f..0.3f) (0.3f - ui.settings.brightness) / 0.3f * 0.78f else 0f)) * (1f - v) },
    )

    // ---------------- shiori: una sola cinta por libro, como un libro de verdad ----------------
    private val pagesIn = HashMap<String, Int>() // páginas que tiene cada capítulo (modo páginas)

    private fun shioriMark(): Mark? = ui.marks.filter { it.type == "shiori" }.maxByOrNull { it.id }

    /** ¿La página que se ve es la del shiori? Se compara capítulo y lugar dentro del capítulo, con la medida de una página. */
    private fun updateShiori(loc: Locator) {
        val m = shioriMark()
        if (m == null) { ui.hasShiori = false; ui.bookmarked = false; return }
        val at = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: run { ui.bookmarked = false; return }
        if (hnorm(at.href.toString()) != hnorm(loc.href.toString())) { ui.bookmarked = false; ui.hasShiori = true; return }
        val a = at.locations.progression ?: 0.0
        val b = loc.locations.progression ?: 0.0
        val href = hnorm(loc.href.toString())
        val pages = pagesIn[href]
        val span = when {
            cont != null || ui.settings.scroll -> 0.02 // continuo: el lugar del shiori está en pantalla
            pages != null && pages > 0 -> 1.0 / pages
            else -> 0.0
        }
        // Mientras no se sabe cuántas páginas tiene el capítulo, no se muestra nada (así no salta de punta a cinta)
        if (span > 0) { ui.bookmarked = abs(a - b) < span * 0.5; ui.hasShiori = true }
        // Se aprende cuántas páginas tiene este capítulo (para comparar bien la próxima vez)
        val nav = navigator
        if (pages == null && nav != null && !ui.settings.scroll) lifecycleScope.launch {
            var n: Int? = null
            for (t in 0 until 6) { // el capítulo puede estar terminando de cargar
                n = runCatching { nav.evaluateJavascript("Math.max(1, Math.round((document.scrollingElement || document.documentElement).scrollWidth / window.innerWidth))") }
                    .getOrNull()?.trim('"')?.toDoubleOrNull()?.toInt()
                if (n != null && n > 0) break
                delay(250)
            }
            if (n != null && n > 0) { pagesIn[href] = n; nav.currentLocator.value.let { if (hnorm(it.href.toString()) == href) updateShiori(it) } }
        }
    }

    /** Poner el shiori en esta página (si había uno en otra, se mueve aquí: es una sola cinta). */
    private fun placeShiori() {
        val loc = navigator?.currentLocator?.value ?: contLoc ?: return
        ui.marks.filter { it.type == "shiori" }.forEach { Library.removeMark(it.id) }
        Library.putMark(
            Mark(
                id = "s" + System.currentTimeMillis().toString(36), bookId = book.id, type = "shiori",
                locator = loc.toJSON().toString(), chapter = ui.chapter, progress = ui.progress.toDouble(),
                text = loc.text.highlight?.take(120).orEmpty(),
            ),
        )
        ui.backTo = null
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ui.say("Shiori puesto en esta página")
        refreshMarks()
    }

    private fun removeShiori() {
        ui.marks.filter { it.type == "shiori" }.forEach { Library.removeMark(it.id) }
        ui.backTo = null
        ui.say("Shiori retirado")
        refreshMarks()
    }

    /** Ir a la página del shiori (recordando dónde ibas, para poder volver). */
    private fun goShiori() {
        val m = shioriMark() ?: return
        val here = (navigator?.currentLocator?.value ?: contLoc)?.toJSON()?.toString()
        val at = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: return
        ui.backTo = here
        setControls(false)
        val c = cont
        if (c != null) contGo(at) else navigator?.go(at, animated = false)
        ui.say("Tu shiori")
    }

    private fun goBackFromShiori() {
        val j = ui.backTo ?: return
        ui.backTo = null
        val at = runCatching { Locator.fromJSON(JSONObject(j)) }.getOrNull() ?: return
        val c = cont
        if (c != null) contGo(at) else navigator?.go(at, animated = false)
    }

    private fun toggleShiori() {
        when {
            ui.bookmarked -> removeShiori()
            else -> placeShiori()
        }
    }

    /** Saltar a un punto del libro (0..1) con la barra de progreso. */
    private fun seek(f: Float) {
        val all = positions.flatten()
        if (all.isEmpty()) return
        val target = all[((all.size - 1) * f.coerceIn(0f, 1f)).toInt()]
        if (cont != null) { contGo(target); return }
        navigator?.go(target, animated = false)
    }

    // ---------------- traducir ----------------
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun lookUpAt(nav: EpubNavigatorFragment, point: PointF, lang: String): Boolean {
        val js = WORD_AT_POINT.replace("__X__", point.x.toString()).replace("__Y__", point.y.toString())
        val raw = runCatching { nav.evaluateJavascript(js) }.getOrNull()
        if (raw.isNullOrBlank() || raw == "null") return false
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val word = o.optString("w").trim()
        if (word.isEmpty()) return false
        startLookup(word, o.optString("s"), lang, nav.currentLocator.value.toJSON().toString())
        return true
    }

    private fun startLookup(text: String, sentence: String, from: String, locator: String) {
        val to = Traductor.targetFor(from)
        lookupJob?.cancel()
        ui.lookup = Lookup(text, sentence.replace(Regex("\\s+"), " ").trim(), from, to, status = "Traduciendo…", locator = locator)
        lookupJob = lifecycleScope.launch {
            runCatching {
                if (Traductor.needsDownload(from, to)) ui.lookup = ui.lookup?.copy(status = "Bajando ${Traductor.name(from.substringBefore('-'))} y ${Traductor.name(to)} (solo esta vez, unos 30 MB)…")
                Traductor.translate(text, from, to)
            }.onSuccess { t -> ui.lookup = ui.lookup?.copy(translation = t, status = null) }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) ui.lookup = ui.lookup?.copy(status = null, error = e.message ?: "No se pudo traducir. Revisa la conexión la primera vez.") }
        }
    }

    private fun translateSentence() {
        val l = ui.lookup ?: return
        if (l.sentence.isBlank() || l.sentenceTranslation != null) return
        ui.lookup = l.copy(sentenceTranslation = "…")
        lifecycleScope.launch {
            val t = runCatching { Traductor.translate(l.sentence, l.from, l.to) }.getOrElse { "No se pudo traducir la frase." }
            ui.lookup = ui.lookup?.copy(sentenceTranslation = t)
        }
    }

    /**
     * Guarda una tarjeta hecha al subrayar: el pasaje queda subrayado (si no lo estaba) y la tarjeta
     * apunta a ese subrayado. Solo se hace tarjeta cuando la persona lo pide.
     */
    private fun saveCard(d: CardDraft, front: String, back: String, kind: String) {
        val markId = d.markId ?: run {
            val loc = runCatching { Locator.fromJSON(JSONObject(d.locator)) }.getOrNull()
            val m = Mark(
                id = "h" + System.currentTimeMillis().toString(36), bookId = book.id, type = "hl", locator = d.locator,
                text = d.text, color = Prefs.lastInk, chapter = ui.chapter,
                progress = loc?.locations?.totalProgression ?: ui.progress.toDouble(),
            )
            Library.putMark(m); refreshMarks(); m.id
        }
        com.sebas.zerkalo.data.Cards.add(
            com.sebas.zerkalo.data.Card(
                id = "c" + System.currentTimeMillis().toString(36), bookId = book.id, markId = markId, kind = kind,
                front = front, back = back,
            ),
        )
        ui.cardDraft = null
        ui.say("Tarjeta guardada: la verás en Estudio")
    }

    /** Tarjeta de una palabra traducida: la palabra en su frase por delante, la traducción por detrás. */
    private fun wordCard() {
        val l = ui.lookup ?: return
        val tr = l.translation ?: return
        if (!l.saved) saveWord()
        val mark = Library.marks.value.lastOrNull { it.bookId == book.id && it.type == "word" && it.text == l.text }
        if (mark != null && com.sebas.zerkalo.data.Cards.hasFor(mark.id)) { ui.say("Esa palabra ya tiene tarjeta"); return }
        com.sebas.zerkalo.data.Cards.add(
            com.sebas.zerkalo.data.Card(
                id = "c" + System.currentTimeMillis().toString(36), bookId = book.id, markId = mark?.id, kind = "word",
                front = l.text, back = tr, context = l.sentence,
            ),
        )
        ui.lookup = ui.lookup?.copy(saved = true, carded = true)
        ui.say("Tarjeta guardada: la verás en Estudio")
    }

    private fun saveWord() {
        val l = ui.lookup ?: return
        if (l.saved || l.translation == null) return
        Library.putMark(
            Mark(
                id = "w" + System.currentTimeMillis().toString(36), bookId = book.id, type = "word", locator = l.locator,
                text = l.text, note = l.translation, chapter = ui.chapter, progress = ui.progress.toDouble(), context = l.sentence,
            ),
        )
        ui.lookup = l.copy(saved = true)
    }

    // ---------------- leer en voz alta ----------------
    private var raSent: List<String> = emptyList()
    private var raAt = 0
    private var raQueued = -1
    private var raGen = 0            // cambia cada vez que se reinicia (para ignorar avisos viejos de la voz)
    private var raSleepJob: Job? = null
    private var raBusy = false

    private val raPaged get() = cont == null && !ui.settings.scroll

    private suspend fun raEval(code: String): String? {
        val raw = (cont?.eval(code) ?: navigator?.let { n -> runCatching { n.evaluateJavascript(code) }.getOrNull() }) ?: return null
        return if (raw.startsWith("\"")) runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrNull() else raw
    }

    private fun raJs(code: String) = code.replace("__PAGED__", if (raPaged) "true" else "false")

    private fun raHl(): String {
        val a = ui.palette.accent
        return "rgba(${(a.red * 255).toInt()},${(a.green * 255).toInt()},${(a.blue * 255).toInt()},${if (ui.palette.isDark) 0.30 else 0.22})"
    }

    /** Abre la voz (si hace falta) y empieza a leer desde la página que se ve. */
    private fun aloudStart() {
        setControls(false); ui.sheet = null
        if (ui.pairOn) { ui.say("Para leer en voz alta, cierra el lado a lado"); return }
        val go = {
            ui.aloud = true
            lifecycleScope.launch { raFromView() }
        }
        if (tts != null && ttsReady) { raListen(); go(); return }
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) { raListen(); go() } else ui.say("Tu teléfono no tiene una voz instalada")
        }
    }

    private fun raListen() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String) { raEvent(id, false) }
            override fun onDone(id: String) { raEvent(id, true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String) { raEvent(id, true) }
        })
    }

    private fun raEvent(id: String, done: Boolean) {
        val parts = id.removePrefix("ra").split('_')
        val g = parts.getOrNull(0)?.toIntOrNull() ?: return
        val i = parts.getOrNull(1)?.toIntOrNull() ?: return
        runOnUiThread {
            if (g != raGen || !ui.aloudPlaying) return@runOnUiThread
            if (!done) { raAt = i; raQueue(); lifecycleScope.launch { raMark(i) } }
            else if (i >= raSent.lastIndex) lifecycleScope.launch { raNextChapter() }
        }
    }

    /** Siempre hay dos frases en la fila: así no hay silencios entre una y otra. */
    private fun raQueue() {
        val t = tts ?: return
        while (raQueued < raAt + 2 && raQueued < raSent.lastIndex) {
            raQueued++
            t.speak(raSent[raQueued], TextToSpeech.QUEUE_ADD, null, "ra${raGen}_$raQueued")
        }
    }

    private fun raPlayFrom(i: Int) {
        val t = tts ?: return
        if (raSent.isEmpty()) return
        raGen++
        raAt = i.coerceIn(0, raSent.lastIndex)
        t.setLanguage(Locale.forLanguageTag(ui.lang.ifBlank { Locale.getDefault().language }))
        t.setSpeechRate(ui.aloudRate)
        ui.aloudPlaying = true
        raQueued = raAt
        t.speak(raSent[raAt], TextToSpeech.QUEUE_FLUSH, null, "ra${raGen}_$raAt")
        raQueue()
    }

    private suspend fun raMark(i: Int) {
        val r = raEval(raJs(ReadAloud.MARK).replace("__I__", i.toString())) ?: return
        val nav = navigator
        if (raPaged && nav != null) when (r) {
            "next" -> { nav.goForward(animated = ui.settings.animate); delay(380); raEval(raJs(ReadAloud.MARK).replace("__I__", i.toString())) }
            "prev" -> { nav.goBackward(animated = ui.settings.animate); delay(380) }
        }
    }

    /** Lee desde la primera frase que se ve ahora. */
    private suspend fun raFromView() {
        if (raBusy) return
        raBusy = true
        try {
            tts?.stop(); raGen++
            val js = raJs(ReadAloud.INIT).replace("__HL__", raHl())
            var j = raEval(js)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (j == null) { delay(500); j = raEval(js)?.let { runCatching { JSONObject(it) }.getOrNull() } }
            val arr = j?.optJSONArray("s")
            raSent = if (arr == null) emptyList() else List(arr.length()) { arr.getString(it) }
            if (raSent.isEmpty()) {
                // Página sin texto (una imagen, una portada): se sigue con lo que viene
                if (!raAdvance()) aloudStop("No encontré texto para leer aquí")
                return
            }
            raPlayFrom(j!!.optInt("start", 0))
        } finally { raBusy = false }
    }

    /** Se acabó el capítulo: se pasa al siguiente y se sigue leyendo. */
    private suspend fun raNextChapter() {
        raEval(ReadAloud.CLEAR)
        if (!raAdvance()) { aloudStop("Fin del libro"); return }
    }

    private suspend fun raAdvance(): Boolean {
        val c = cont
        if (c != null) {
            c.page(1); delay(700)
        } else {
            val nav = navigator ?: return false
            val pub = publication ?: return false
            val here = resourceIndex(pub, nav.currentLocator.value)
            val next = pub.readingOrder.getOrNull(here + 1) ?: return false
            val loc = pub.locatorFromLink(next) ?: return false
            nav.go(loc, animated = false); delay(700)
        }
        raFromView()
        return true
    }

    private fun aloudPause() {
        ui.aloudPlaying = false; raGen++
        tts?.stop()
    }

    private fun aloudResume() {
        if (raSent.isEmpty()) lifecycleScope.launch { raFromView() } else raPlayFrom(raAt)
    }

    private fun aloudStop(msg: String? = null) {
        ui.aloud = false; ui.aloudPlaying = false; raGen++
        tts?.stop()
        raSleepJob?.cancel(); ui.aloudSleep = 0
        raSent = emptyList()
        lifecycleScope.launch { raEval(ReadAloud.CLEAR) }
        msg?.let { ui.say(it) }
    }

    private fun aloudRate() {
        val r = ReadAloud.RATES
        val next = r[(r.indexOfFirst { abs(it - ui.aloudRate) < 0.01f } + 1).mod(r.size)]
        ui.aloudRate = next; com.sebas.zerkalo.data.Prefs.aloudRate = next
        if (ui.aloudPlaying) raPlayFrom(raAt)
    }

    private fun aloudSleep() {
        val s = ReadAloud.SLEEP
        val next = s[(s.indexOf(ui.aloudSleep) + 1).mod(s.size)]
        ui.aloudSleep = next
        raSleepJob?.cancel()
        if (next == 0) { ui.say("Sin temporizador"); return }
        ui.say("Se apaga en $next minutos")
        raSleepJob = lifecycleScope.launch {
            delay(next * 60_000L)
            aloudPause(); ui.aloudSleep = 0
            ui.say("Lectura en pausa · que descanses")
        }
    }

    private fun speak(text: String, lang: String) {
        val say = {
            tts?.setLanguage(Locale.forLanguageTag(lang))
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zk")
        }
        if (tts != null && ttsReady) { say(); return }
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) say() else ui.say("Tu teléfono no tiene voz para este idioma")
        }
    }

    // ---------------- dos idiomas ----------------
    private fun refreshPairInfo() {
        pairBook = Library.book(Library.book(book.id)?.pair)
        ui.hasPair = pairBook != null
        ui.pairTitle = pairBook?.let { b -> b.title + (b.lang.takeIf { it.isNotBlank() }?.let { " · " + Traductor.name(it) } ?: "") }.orEmpty()
    }

    private fun setPair(other: Book?) {
        val old = Library.book(book.id)?.pair
        if (old != null && old != other?.id) Library.update(old) { if (it.pair == book.id) it.copy(pair = null) else it }
        Library.update(book.id) { it.copy(pair = other?.id) }
        other?.let { o -> Library.update(o.id) { it.copy(pair = book.id) } }
        if (ui.pairOn) showPair(false)
        refreshPairInfo()
        if (other != null) showPair(true)
    }

    private fun showPair(on: Boolean) {
        if (!on) {
            ui.pairOn = false; Prefs.setPairOn(book.id, false)
            pairContainer.visibility = View.GONE; divider.visibility = View.GONE
            pairJob?.cancel(); pairJob = null
            pairNav?.let { f -> supportFragmentManager.commitNow { remove(f) } }
            pairNav = null; runCatching { pairPub?.close() }; pairPub = null
            return
        }
        val other = pairBook ?: return
        lifecycleScope.launch {
            val core = (application as ZerkaloApp).readium
            val pub = core.open(File(Library.booksDir, other.file)).getOrElse { ui.say("No se pudo abrir «${other.title}»"); return@launch }
            pairPub = pub
            pairLang = other.lang.ifBlank { pub.metadata.languages.firstOrNull().orEmpty() }.ifBlank { "en" }
            ui.pairLangLabel = pairLang.take(2).uppercase()
            ignorePairUntil = System.currentTimeMillis() + 2_000
            val start = (navigator?.currentLocator?.value ?: contLoc)?.let { mapLocator(it, publication ?: pub, pub) }
                ?: other.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            val ff = EpubNavigatorFactory(pub, EpubNavigatorFactory.Configuration(defaults = EpubDefaults(pageMargins = 1.2)))
                .createFragmentFactory(initialLocator = start, initialPreferences = preferences(ui.settings, ui.palette), listener = this@ReaderActivity, configuration = navConfig())
            val frag = ff.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
            pairContainer.visibility = View.VISIBLE; divider.visibility = View.VISIBLE
            supportFragmentManager.commitNow { replace(pairId, frag, "pareja") }
            pairNav = frag
            frag.addInputListener(tapListener(frag, pair = true))
            pairJob?.cancel()
            pairJob = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { frag.currentLocator.collect { typeset(frag); syncMain(it) } } }
            ui.pairOn = true; Prefs.setPairOn(book.id, true)
            pairPositions = runCatching { pub.positionsByReadingOrder() }.getOrDefault(emptyList())
            (navigator?.currentLocator?.value ?: contLoc)?.let { syncPair(it, force = true) }
        }
    }

    /** Lleva la otra versión al mismo punto: mismo capítulo si ambas tienen la misma estructura; si no, la misma proporción del libro. */
    private fun syncPair(loc: Locator, force: Boolean = false) {
        val pn = pairNav ?: return
        val pp = pairPub ?: return
        val mp = publication ?: return
        val target = mapLocator(loc, mp, pp) ?: return
        val cur = pn.currentLocator.value
        if (!force && cur.href.toString() == target.href.toString() && abs((cur.locations.progression ?: 0.0) - (target.locations.progression ?: 0.0)) < 0.01) return
        ignorePairUntil = System.currentTimeMillis() + 900
        pn.go(target, animated = false)
        if (mp.readingOrder.size == pp.readingOrder.size) refineAlign(navigator ?: return, pn, toPair = true)
    }

    /**
     * Ajuste fino de las ediciones casadas: el párrafo que se ve arriba en una queda arriba en la otra
     * (si tienen distinta cantidad de párrafos, en la misma proporción). Así una traducción más larga
     * no queda unos párrafos adelante o atrás.
     */
    private fun refineAlign(from: EpubNavigatorFragment, to: EpubNavigatorFragment, toPair: Boolean) {
        alignJob?.cancel()
        alignJob = lifecycleScope.launch {
            delay(380)
            val at = runCatching { from.evaluateJavascript(PARA_AT) }.getOrNull().orEmpty().trim('"')
            val i = at.substringBefore('/').toIntOrNull() ?: return@launch
            val n = at.substringAfter('/').toIntOrNull() ?: return@launch
            val prog = runCatching { to.evaluateJavascript(PARA_PROG.replace("__I__", "$i").replace("__N__", "$n")) }
                .getOrNull()?.trim('"')?.toDoubleOrNull() ?: return@launch
            val cur = to.currentLocator.value
            if (abs((cur.locations.progression ?: 0.0) - prog) < 0.002) return@launch
            val until = System.currentTimeMillis() + 900
            if (toPair) ignorePairUntil = until else ignoreMainUntil = until
            to.go(cur.copyWithLocations(progression = prog), animated = false)
        }
    }

    /** Al pasar página en la pareja, el libro principal la sigue (si están casadas). */
    private fun syncMain(loc: Locator) {
        if (!ui.pairLinked || System.currentTimeMillis() < ignorePairUntil) return
        // Modo continuo: el libro principal va al mismo punto, sin mover los controles
        if (navigator == null && cont != null) {
            val pp = pairPub ?: return
            val mp = publication ?: return
            val target = mapLocator(loc, pp, mp) ?: return
            val i = resourceIndex(mp, target).takeIf { it >= 0 } ?: return
            val cur = contLoc
            if (cur != null && cur.href.toString() == target.href.toString() && abs((cur.locations.progression ?: 0.0) - (target.locations.progression ?: 0.0)) < 0.01) return
            ignoreMainUntil = System.currentTimeMillis() + 900
            cont?.go(i, target.locations.progression ?: 0.0)
            return
        }
        val nav = navigator ?: return
        val pp = pairPub ?: return
        val mp = publication ?: return
        val target = mapLocator(loc, pp, mp) ?: return
        val cur = nav.currentLocator.value
        if (cur.href.toString() == target.href.toString() && abs((cur.locations.progression ?: 0.0) - (target.locations.progression ?: 0.0)) < 0.01) return
        ignoreMainUntil = System.currentTimeMillis() + 900
        nav.go(target, animated = false)
        if (mp.readingOrder.size == pp.readingOrder.size) pairNav?.let { refineAlign(it, nav, toPair = false) }
    }

    private fun mapLocator(loc: Locator, from: Publication, to: Publication): Locator? {
        val href = loc.href.toString().substringBefore('#')
        val idx = from.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == href }
        val prog = loc.locations.progression ?: 0.0
        if (idx >= 0 && from.readingOrder.size == to.readingOrder.size) {
            return to.locatorFromLink(to.readingOrder[idx])?.copyWithLocations(progression = prog)
        }
        val total = loc.locations.totalProgression ?: return null
        val all = (if (to === pairPub) pairPositions else positions).flatten()
        if (all.isEmpty()) return to.readingOrder.getOrNull(((to.readingOrder.size - 1) * total).toInt())?.let { to.locatorFromLink(it) }
        return all[((all.size - 1) * total).toInt().coerceIn(0, all.size - 1)]
    }

    override fun onResume() {
        super.onResume()
        lastEvent = System.currentTimeMillis()
        curl?.onResume()
    }

    override fun onPause() {
        super.onPause()
        curl?.onPause()
        if (!::book.isInitialized) return
        activeMs += pendingMs()
        lastEvent = 0L
        Library.addSession(book.id, activeMs)
        activeMs = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { publication?.close() }
        runCatching { pairPub?.close() }
        runCatching { tts?.shutdown() }
    }

    // ---------------- notas al pie: se leen en una ventanita, sin perder la página ----------------
    override fun shouldFollowInternalLink(link: Link, context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?): Boolean {
        if (context is org.readium.r2.navigator.HyperlinkNavigator.FootnoteContext) {
            val t = BookText.plain(context.noteContent)
            if (t.isNotBlank()) { showNote(t, link); return false }
        }
        val frag = link.href.toString().substringAfter('#', "")
        if (frag.isEmpty()) return true
        lifecycleScope.launch {
            val t = runCatching { noteText(link, frag) }.getOrNull()
            if (t != null) showNote(t, link) else followLink(link)
        }
        return false
    }

    private fun followLink(link: Link) {
        val pub = publication ?: return
        val loc = pub.locatorFromLink(link) ?: return
        navigator?.go(loc, animated = false)
    }

    private fun showNote(text: String, link: Link) {
        ui.footnote = text.take(4000) to link
        ui.sheet = "footnote"
    }

    /** ¿El enlace lleva a una nota? Se mira el lugar al que apunta: si parece nota, se devuelve su texto. */
    private suspend fun noteText(link: Link, frag: String): String? = withContext(Dispatchers.IO) {
        val pub = publication ?: return@withContext null
        val base = link.href.toString().substringBefore('#')
        val target = (pub.readingOrder + pub.resources).firstOrNull { it.href.toString().substringBefore('#') == base } ?: return@withContext null
        val html = pub.get(target.url())?.read()?.getOrNull()?.toString(Charsets.UTF_8) ?: return@withContext null
        val doc = org.jsoup.Jsoup.parse(html)
        var el = doc.getElementById(frag) ?: doc.select("[name=$frag]").firstOrNull() ?: return@withContext null
        // Un ancla vacía: la nota es el párrafo (o el elemento de lista) donde está
        if (el.text().trim().length < 6) el = el.parents().firstOrNull { it.tagName() in setOf("p", "li", "aside", "div", "dd", "section") } ?: el
        val looks = Regex("foot|note|nota|endnote|rearnote|^fn|^n\\d", RegexOption.IGNORE_CASE)
        fun noteish(e: org.jsoup.nodes.Element): Boolean {
            val t = e.attr("epub:type") + " " + e.attr("role") + " " + e.className() + " " + e.id()
            return looks.containsMatchIn(t)
        }
        val here = navigator?.currentLocator?.value?.href?.toString()?.substringBefore('#')
        val isNote = noteish(el) || el.parents().take(4).any { noteish(it) } ||
            (here != null && here != base && Regex("note|nota|endnote|footnote", RegexOption.IGNORE_CASE).containsMatchIn(base))
        val text = el.text().trim()
        if (!isNote || text.isEmpty() || text.length > 2500) null else text
    }

    // ---------------- EpubNavigatorFragment.Listener ----------------
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url.toString()))) }
    }

    override fun onJumpToLocator(locator: Locator) {}

    @Suppress("unused")
    private fun near(a: Double?, b: Double?) = a != null && b != null && abs(a - b) < 0.002
}

class ReaderActions(
    val close: () -> Unit,
    val openNote: () -> Unit = {},
    val toggleInk: () -> Unit = {},
    val ink: (com.sebas.zerkalo.ui.InkTool) -> Unit = {},
    val export: () -> Unit = {},
    val ownLook: (Boolean) -> Unit = {},
    val useLook: (String) -> Unit = {},
    val hideInkHint: () -> Unit = {},
    val exportEpub: () -> Unit = {},
    val exportInk: () -> Unit = {},
    val exportNote: () -> Unit = {},
    val toggleShiori: () -> Unit,
    val goShiori: () -> Unit,
    val aloud: () -> Unit,
    val noteGo: (org.readium.r2.shared.publication.Link) -> Unit,
    val aloudToggle: () -> Unit,
    val aloudStop: () -> Unit,
    val aloudRate: () -> Unit,
    val aloudSleep: () -> Unit,
    val backFromShiori: () -> Unit,
    val go: (Locator) -> Unit,
    val goLink: (Link) -> Unit,
    val find: (String) -> Unit,
    val goHit: (BookText.Hit) -> Unit,
    val settings: (ReaderSettings) -> Unit,
    val removeMark: (Mark) -> Unit,
    val saveNote: (Locator, String) -> Unit,
    val recolor: (Mark, Int) -> Unit,
    val seek: (Float) -> Unit,
    val brightnessLive: (Float) -> Unit,
    val toggleAuto: () -> Unit,
    val toggleGuide: () -> Unit,
    val profile: (String) -> Unit,
    val nextProfile: () -> Unit,
    val saveProfile: (String) -> Unit,
    val newProfile: (String) -> Unit,
    val deleteProfile: (String) -> Unit,
    val findAudio: suspend () -> List<com.sebas.zerkalo.data.AudioBook>,
    val searchAudio: suspend (String) -> List<com.sebas.zerkalo.data.AudioBook>,
    val linkedAudio: () -> com.sebas.zerkalo.data.AudioBook?,
    val listen: (com.sebas.zerkalo.data.AudioBook, Boolean) -> Unit, // true = desde el capítulo donde vas leyendo
    val goToTrack: (String) -> Unit,
    val audioFromHere: () -> Unit,
    val setInks: (List<com.sebas.zerkalo.data.InkColor>) -> Unit,
    val highlightWith: (Int) -> Unit,
    val openInks: () -> Unit,
    val turn: (Int) -> Unit,
    val toggleStudy: () -> Unit,
    val closeLookup: () -> Unit,
    val retarget: (String) -> Unit,
    val translateSentence: () -> Unit,
    val saveWord: () -> Unit,
    val wordCard: () -> Unit,
    val saveCard: (CardDraft, String, String, String) -> Unit,
    val cardFromMark: (Mark) -> Unit,
    val speak: (String, String) -> Unit,
    val pairCandidates: () -> List<Book>,
    val setPair: (Book?) -> Unit,
    val togglePair: () -> Unit,
    val syncPair: () -> Unit,
    val togglePairLink: () -> Unit,
    val swapPair: () -> Unit,
    val setPairRatio: (Int) -> Unit,
    val veilLive: (Float) -> Unit,
)

/** Dónde está el texto: "top", "bottom", "both" (capítulo corto) o "none". */
private const val EDGE_STATE = """(function(){
  var se = document.scrollingElement || document.documentElement, h = window.innerHeight, max = se.scrollHeight - h;
  var t = se.scrollTop <= 6, b = se.scrollTop >= max - 6;
  return (t && b) ? 'both' : t ? 'top' : b ? 'bottom' : 'none';
})()"""

/** Avance automático suave (requestAnimationFrame): __S__ píxeles por segundo. */
private const val AUTO_SCROLL = """(function(s){
  if (window.__zk) { window.__zk.s = s; window.__zk.on = true; return 'ok'; }
  var z = window.__zk = { s: s, on: true, last: 0, acc: 0 };
  function f(t) {
    if (window.__zk !== z) return;
    if (z.on && z.last) { z.acc += z.s * (t - z.last) / 1000; if (z.acc >= 1) { var n = Math.floor(z.acc); window.scrollBy(0, n); z.acc -= n; } }
    z.last = t; requestAnimationFrame(f);
  }
  requestAnimationFrame(f); return 'ok';
})(__S__)"""

/** Estado del avance automático: "end" al final del capítulo, "off" si la página es nueva y hay que encenderlo. */
private const val AUTO_STATE = """(function(){
  var se = document.scrollingElement || document.documentElement, max = se.scrollHeight - window.innerHeight;
  if (!window.__zk) return 'off';
  window.__zk.on = true;
  return se.scrollTop >= max - 2 ? 'end' : 'on';
})()"""

private const val AUTO_PAUSE = "(function(){ if (window.__zk) window.__zk.on = false; return 'paused'; })()"
private const val AUTO_STOP = "(function(){ window.__zk = null; return 'off'; })()"

/**
 * Desplaza una pantalla sin dejar líneas cortadas: la última línea que se veía (entera o mordida)
 * pasa a ser la primera, así nunca se pierde el hilo. Un título al final baja completo.
 * Devuelve "edge" si ya está al final o al inicio del capítulo.
 */
private const val SCROLL_BY = """(function(){
  var d = __D__, se = document.scrollingElement || document.documentElement, w = window.innerWidth;
  var PT = __PT__, PB = __PB__, h = window.innerHeight - PT - PB;
  var lh = parseFloat(getComputedStyle(document.body).lineHeight) || 26;
  var max = se.scrollHeight - h;
  if (d > 0 && se.scrollTop >= max - 4) return 'edge';
  if (d < 0 && se.scrollTop <= 4) return 'edge';
  function lineAt(y) {
    var xs = [0.1, 0.25, 0.4, 0.55, 0.7, 0.85];
    for (var i = 0; i < xs.length; i++) {
      var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(w * xs[i], y) : null;
      if (!r) continue;
      var n = r.startContainer;
      if (!n || n.nodeType !== 3 || !n.textContent.trim()) continue;
      var o = r.startOffset, len = n.textContent.length;
      if (o < len) r.setEnd(n, o + 1); else if (o > 0) r.setStart(n, o - 1); else continue;
      var b = r.getBoundingClientRect();
      if (!b || b.height <= 0 || b.top > y + 3 || b.bottom < y - 3) continue;
      var hd = n.parentElement && n.parentElement.closest('h1,h2,h3,h4,h5,h6');
      if (hd) { var hb = hd.getBoundingClientRect(); return { top: hb.top, bottom: hb.bottom }; }
      return { top: b.top - (lh - b.height) / 2, bottom: b.bottom + (lh - b.height) / 2 };
    }
    return null;
  }
  var delta, pad = Math.min(6, lh * 0.2);
  // La zona clara va de PT a PT + h (arriba y abajo está el desenfoque)
  if (d > 0) {
    var y = PT + h - 2, L = null;
    for (var k = 0; k < 8 && !L; k++) { L = lineAt(y); y -= lh / 3; }
    delta = L ? L.top - PT - pad : h - 2 * lh;
    if (delta < h * 0.45) delta = h - 2 * lh;
  } else {
    var y2 = PT + 2, F = null;
    for (var j = 0; j < 8 && !F; j++) { F = lineAt(y2); y2 += lh / 3; }
    delta = F ? -(PT + h - F.bottom - pad) : -(h - 2 * lh);
    if (-delta < h * 0.45) delta = -(h - 2 * lh);
  }
  window.scrollBy({ top: Math.round(delta), behavior: '__S__' });
  // Al terminar de moverse, si la primera línea quedó mordida arriba, se acomoda para verla entera.
  var fix = function(){
    window.removeEventListener('scrollend', fix);
    var y = PT + 1, F = null;
    for (var j = 0; j < 4 && !F; j++) { F = lineAt(y); y += lh / 3; }
    if (F && F.top < PT - 1 && F.bottom > PT + 1) window.scrollBy({ top: Math.round(F.top - PT - pad), behavior: 'auto' });
  };
  if ('onscrollend' in window && '__S__' === 'smooth') window.addEventListener('scrollend', fix); else setTimeout(fix, 60);
  return 'ok';
})()"""

/** Una tarjeta a medio hacer: el texto subrayado, su ubicación y el subrayado si ya existía. */
data class CardDraft(val text: String, val locator: String, val markId: String?)

/** Al empezar a deslizar: se anota dónde estaba el texto. */
private const val EDGE_START = """(function(){ var se = document.scrollingElement || document.documentElement; window.__zkSt = se.scrollTop; return 'ok'; })()"""

/** Al soltar: "bottom" o "top" solo si el texto ya estaba en ese borde y no se movió con el gesto. */
private const val EDGE_END = """(function(){
  var se = document.scrollingElement || document.documentElement, h = window.innerHeight, max = se.scrollHeight - h;
  var still = (typeof window.__zkSt !== 'number') || Math.abs(se.scrollTop - window.__zkSt) < 6;
  if (!still) return 'moved';
  var t = se.scrollTop <= 6, b = se.scrollTop >= max - 6;
  return (t && b) ? 'top bottom' : t ? 'top' : b ? 'bottom' : 'none';
})()"""

/** Párrafos del capítulo que cuentan para alinear dos ediciones. */
private const val PARA_LIST = """var bs = [].filter.call(document.body.querySelectorAll('p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,dt,dd'), function(e){ return (e.textContent || '').trim().length > 0 && !e.querySelector('p,li,blockquote'); });"""

/** Cuál párrafo se ve arriba: "i/n". */
private const val PARA_AT = """(function(){ $PARA_LIST
  var w = window.innerWidth, h = window.innerHeight;
  for (var i = 0; i < bs.length; i++) { var r = bs[i].getBoundingClientRect(); if (r.right > 2 && r.left < w - 2 && r.bottom > 2 && r.top < h - 2) return i + '/' + bs.length; }
  return '0/' + bs.length;
})()"""

/** Dónde queda (de 0 a 1 en el capítulo) el párrafo __I__ de __N__ de la otra edición. */
private const val PARA_PROG = """(function(){ $PARA_LIST
  if (!bs.length) return '';
  var i = __I__, n = __N__, j = n === bs.length ? i : Math.round(i * bs.length / Math.max(1, n));
  var e = bs[Math.max(0, Math.min(bs.length - 1, j))], se = document.scrollingElement || document.documentElement, r = e.getBoundingClientRect();
  var horiz = se.scrollWidth > window.innerWidth + 4;
  var v = horiz ? (r.left + se.scrollLeft + 2) / se.scrollWidth : (r.top + se.scrollTop + 2) / se.scrollHeight;
  return String(Math.max(0, Math.min(0.999, v)));
})()"""

/** Qué parte del capítulo ocupa una pantalla (para saber si el narrador ya pasó de página). */
private const val PAGE_SHARE = """(function(){ var se = document.scrollingElement || document.documentElement;
  var horiz = se.scrollWidth > window.innerWidth + 4;
  return String(horiz ? window.innerWidth / se.scrollWidth : window.innerHeight / Math.max(1, se.scrollHeight));
})()"""

/** Formas de los subrayados: marcador, lápiz y ondulado. */
/** El papelito de la nota adhesiva: arriba a la derecha de la frase, un poco torcido, como pegado a mano. */
private const val STICKY_CSS = """
.zk-sticky { position: absolute; right: -10px; top: -12px; width: 18px; height: 18px; background: #EFD995;
  border-radius: 2px 2px 7px 2px; transform: rotate(-6deg); box-shadow: 1px 2px 4px rgba(0,0,0,.35); pointer-events: auto; }
.zk-sticky::before { content: ""; position: absolute; left: 4px; top: 6px; width: 9px; height: 1.5px; background: rgba(59,43,26,.35);
  box-shadow: 0 4px 0 rgba(59,43,26,.35); }
"""

private const val MARK_CSS = """
.zk-mk { background: linear-gradient(180deg, transparent 0 16%, var(--c) 16% 94%, transparent 94%); mix-blend-mode: multiply; }
.zk-mk.zk-dark { mix-blend-mode: screen; }
.zk-mk.zk-v0 { border-radius: 0.2em 0.55em 0.3em 0.6em / 0.5em 0.25em 0.6em 0.3em; transform: skewX(-3deg); }
.zk-mk.zk-v1 { border-radius: 0.45em 0.25em 0.55em 0.2em / 0.3em 0.55em 0.25em 0.5em; transform: skewX(2deg); }
.zk-mk.zk-v2 { border-radius: 0.3em 0.5em 0.2em 0.5em / 0.55em 0.3em 0.5em 0.25em; transform: skewX(-1.5deg); }
.zk-mk.zk-v3 { border-radius: 0.5em 0.3em 0.45em 0.25em / 0.25em 0.5em 0.3em 0.6em; transform: skewX(3deg); }
.zk-pen { border-bottom: 1.6px solid var(--c); opacity: 0.85; margin-top: 1px; }
.zk-pen.zk-v1, .zk-pen.zk-v3 { transform: rotate(-0.3deg); }
.zk-pen.zk-v2 { transform: rotate(0.25deg); }
.zk-wave { background: var(--c);
  -webkit-mask: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='5' viewBox='0 0 10 5'><path d='M0 3.5 Q2.5 0.5 5 3.5 T10 3.5' fill='none' stroke='black' stroke-width='1.3'/></svg>") repeat-x left bottom / 10px 5px;
  mask: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='5' viewBox='0 0 10 5'><path d='M0 3.5 Q2.5 0.5 5 3.5 T10 3.5' fill='none' stroke='black' stroke-width='1.3'/></svg>") repeat-x left bottom / 10px 5px; }
"""

/** Busca la palabra bajo el dedo y su frase. Las coordenadas llegan en píxeles físicos. */
private const val WORD_AT_POINT = """(function(){
  var d = window.devicePixelRatio || 1, x = __X__ / d, y = __Y__ / d;
  var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
  if (!r) return null;
  var n = r.startContainer; if (!n || n.nodeType !== 3) return null;
  var t = n.textContent, i = r.startOffset, re = /[\p{L}\p{M}\p{N}'’\-]/u;
  if (i >= t.length || !re.test(t[i])) { if (i > 0 && re.test(t[i-1])) i--; else return null; }
  var s = i, e = i;
  while (s > 0 && re.test(t[s-1])) s--;
  while (e < t.length && re.test(t[e])) e++;
  var wr = document.createRange(); wr.setStart(n, s); wr.setEnd(n, e);
  var b = wr.getBoundingClientRect();
  if (x < b.left - 6 || x > b.right + 6 || y < b.top - 6 || y > b.bottom + 6) return null;
  var w = t.slice(s, e).replace(/^['’\-]+|['’\-]+$/g, '');
  var block = n.parentElement.closest('p,li,dd,dt,blockquote,h1,h2,h3,h4,h5,h6,td,div') || n.parentElement;
  var full = block.textContent || '';
  var pre = document.createRange(); pre.setStart(block, 0); pre.setEnd(n, s);
  var off = pre.toString().length;
  var a = 0; for (var k = off - 1; k >= 0; k--) { if ('.!?…\n'.indexOf(full[k]) >= 0) { a = k + 1; break; } }
  var m = full.slice(off + w.length).search(/[.!?…\n]/);
  var z = m < 0 ? full.length : off + w.length + m + 1;
  return { w: w, s: full.slice(a, z).replace(/\s+/g, ' ').trim().slice(0, 500) };
})()"""
