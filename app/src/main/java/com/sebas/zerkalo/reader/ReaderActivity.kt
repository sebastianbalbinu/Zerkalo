package com.sebas.zerkalo.reader

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
import com.sebas.zerkalo.ui.Chashitsu
import com.sebas.zerkalo.ui.Nogal
import com.sebas.zerkalo.ui.Palette
import com.sebas.zerkalo.ui.SumiPalette
import com.sebas.zerkalo.ui.SepiaPalette
import com.sebas.zerkalo.ui.GrisPalette
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import com.sebas.zerkalo.ui.highlightColor
import kotlinx.coroutines.delay
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


/** Colores de letra para papel oscuro. El último lo elige la persona. */
val InkNames = listOf("Original", "Azul claro", "Blanco cálido", "Gris perla", "Ámbar suave", "Mío")
val InkColors = listOf(0, 0xFFC7D8EA.toInt(), 0xFFEEE6D8.toInt(), 0xFFCFCBC5.toInt(), 0xFFD9C3A0.toInt(), 0)

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
    val locator: String = "",
)

/** Estado que ve la capa de controles (Compose). */
class ReaderUi {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var controls by mutableStateOf(true)
    var chapter by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var chapterLeft by mutableStateOf("")
    var minutesToday by mutableIntStateOf(0)
    var bookmarked by mutableStateOf(false)
    var settings by mutableStateOf(ReaderSettings())
    var palette by mutableStateOf(Chashitsu)
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
    var marks by mutableStateOf<List<Mark>>(emptyList())
    var activeMark by mutableStateOf<Mark?>(null)
    var noteFor by mutableStateOf<Locator?>(null)
    var finishedNow by mutableStateOf(false)
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
    var chapterFade by mutableStateOf(false) // papel encima mientras el capítulo nuevo se acomoda
    var pairSwap by mutableStateOf(false)       // la pareja va arriba (o a la izquierda)
    var pairRatio by mutableIntStateOf(50)      // % de la pantalla para el libro principal
    var lang by mutableStateOf("")
    var dim by mutableFloatStateOf(0f)        // velo negro para oscurecer por debajo del mínimo
    var clock by mutableStateOf("")
    var rulerY by mutableFloatStateOf(0.42f)  // posición de la regla de lectura (0..1)
    var edgeHint by mutableStateOf<String?>(null) // aviso al llegar al borde de un capítulo (desplazamiento)
    var edgeReady by mutableStateOf(false)        // ya basta con soltar para cambiar de capítulo
    var auto by mutableStateOf(false)             // avance automático encendido
    var tocTab by mutableIntStateOf(0)
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
    private val containerId = View.generateViewId()
    private val pairId = View.generateViewId()
    private lateinit var root: FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // No se restauran fragmentos viejos: el lector siempre se arma de nuevo con el libro abierto.
        super.onCreate(null)
        book = Library.book(intent.getStringExtra("id")) ?: run { finish(); return }
        ui.settings = Prefs.reader.value
        ui.palette = paletteFor(ui.settings)
        ui.marks = Library.marks.value.filter { it.bookId == book.id }
        ui.minutesToday = Library.minutesToday()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.studyTap = Prefs.studyTap
        root = FrameLayout(this)
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
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReaderOverlay(ui, actions) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    private fun applyMargins() {
        val insets = lastInsets ?: return
        val r = ui.settings
        val i = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val dm = resources.displayMetrics
        val em = 16f * dm.density * resources.configuration.fontScale * (r.size / 100f)
        val top = i.top + (listOf(0.2f, 0.9f, 1.8f).getOrElse(r.margin) { 0.9f } * em).toInt()
        val bottom = (listOf(0.35f, 1.1f, 2.1f).getOrElse(r.margin) { 1.1f } * em).toInt()
        panes.setPadding(i.left, top, i.right, bottom)
        // Dónde está la cámara, para poner la hora a un lado y el avance al otro
        ui.band = i.top / dm.density
        val cut = insets.displayCutout?.boundingRects?.firstOrNull { it.top <= 4 }
        val w = root.width.takeIf { it > 0 } ?: dm.widthPixels
        ui.camX = cut?.let { (it.exactCenterX() / w).coerceIn(0.15f, 0.85f) } ?: 0.5f
        ui.camHalf = (cut?.width()?.div(2f) ?: 0f) / dm.density
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
    }

    private fun togglePairLink() {
        ui.pairLinked = !ui.pairLinked; Prefs.pairLinked = ui.pairLinked
        ui.say(if (ui.pairLinked) "Casadas: pasan página juntas" else "Separadas: cada una a su ritmo")
        if (ui.pairLinked) navigator?.currentLocator?.value?.let { syncPair(it, force = true) }
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
        val initial = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
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
        ui.loading = false
        Log.i("Zerkalo", "ZK_READER_OK ${book.title}")
        refreshPairInfo()
        if (ui.hasPair && Prefs.pairOn(book.id)) showPair(true)
        lifecycleScope.launch { delay(1600); setControls(false) }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private fun navConfig() = EpubNavigatorFragment.Configuration {
        // Largo de línea: unos 66 caracteres, lo ideal según los estudios (antes ~80). Afecta tabletas y el modo horizontal.
        readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(maxLineLength = org.readium.r2.navigator.epub.css.Length.Rem(34.0))
        servedAssets = listOf("fonts/.*")
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
        }
        val files = FontFiles.available(this@ReaderActivity).toSet()
        ReaderFonts.forEachIndexed { i, f ->
            if (f.css != null && f.regular != null && i in files) {
                val ctx = this@ReaderActivity
                val italic = f.italic?.takeIf { FontFiles.has(ctx, it) }
                val bold = f.bold?.takeIf { FontFiles.has(ctx, it) } ?: f.boldAlt?.takeIf { FontFiles.has(ctx, it) }
                declareFont(f.css, "fonts/${f.regular}", italic?.let { "fonts/$it" }, bold?.let { "fonts/$it" })
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
                ui.pairOn -> 0
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
                    lifecycleScope.launch { dragEdge[nav] = runCatching { nav.evaluateJavascript(EDGE_STATE) }.getOrNull().orEmpty() }
                    if (ui.auto) stopAuto()
                }
                DragEvent.Type.Move -> {
                    val edge = dragEdge[nav].orEmpty()
                    val vertical = abs(dy) > abs(dx) * 1.4f
                    val toNext = vertical && dy < 0 && (edge.contains("bottom") || edge.contains("both"))
                    val toPrev = vertical && dy > 0 && (edge.contains("top") || edge.contains("both"))
                    ui.edgeReady = (toNext || toPrev) && abs(dy) > th
                    ui.edgeHint = when {
                        toNext && ui.edgeReady -> "Suelta para ir a: " + neighborTitle(nav, 1)
                        toPrev && ui.edgeReady -> "Suelta para volver a: " + neighborTitle(nav, -1)
                        toNext && abs(dy) > th * 0.25f -> "Sigue deslizando para el siguiente capítulo"
                        toPrev && abs(dy) > th * 0.25f -> "Sigue deslizando para el capítulo anterior"
                        else -> null
                    }
                }
                DragEvent.Type.End -> {
                    val edge = dragEdge[nav].orEmpty()
                    val vertical = abs(dy) > abs(dx) * 1.4f
                    val horizontal = abs(dx) > th * 1.3f && abs(dx) > abs(dy) * 2.2f
                    ui.edgeHint = null; ui.edgeReady = false
                    when {
                        vertical && dy < -th && (edge.contains("bottom") || edge.contains("both")) -> chapterJump(nav, 1)
                        vertical && dy > th && (edge.contains("top") || edge.contains("both")) -> chapterJump(nav, -1)
                        horizontal -> chapterJump(nav, if (dx < 0) 1 else -1)
                    }
                }
            }
            return false
        }
    }

    private fun pubOf(nav: EpubNavigatorFragment): Publication? = if (nav === pairNav) pairPub else publication

    private fun resourceIndex(pub: Publication, loc: Locator): Int {
        val href = loc.href.toString().substringBefore('#')
        return pub.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == href }
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
        val pub = pubOf(nav) ?: return
        val idx = resourceIndex(pub, nav.currentLocator.value)
        val link = pub.readingOrder.getOrNull(idx + dir) ?: run { ui.say(if (dir > 0) "Es el final del libro" else "Es el inicio del libro"); return }
        val loc = pub.locatorFromLink(link)?.copyWithLocations(progression = if (dir > 0) 0.0 else 1.0) ?: return
        if (ui.settings.haptic) root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        nav.go(loc, animated = false)
    }

    // ---------------- leer y escuchar ----------------
    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9ivxlc ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** El capítulo del audiolibro que corresponde a donde vas leyendo: por título si coincide; si no, por proporción. */
    private fun trackFor(ab: com.sebas.zerkalo.data.AudioBook): Int {
        if (ab.tracks.isEmpty()) return 0
        val ch = norm(ui.chapter)
        if (ch.length > 3) {
            val i = ab.tracks.indexOfFirst { t -> val n = norm(t.title); n.length > 3 && (n.contains(ch) || ch.contains(n)) }
            if (i >= 0) return i
        }
        val pub = publication ?: return 0
        val idx = navigator?.currentLocator?.value?.let { resourceIndex(pub, it) }?.coerceAtLeast(0) ?: 0
        return ((idx.toFloat() / pub.readingOrder.size.coerceAtLeast(1)) * ab.tracks.size).toInt().coerceIn(0, ab.tracks.size - 1)
    }

    /** Lleva el texto al capítulo que está sonando. */
    private fun goToChapterNamed(title: String) {
        val n = norm(title)
        val hit = ui.toc.firstOrNull { t -> val c = norm(t.title); c.length > 3 && (n.contains(c) || c.contains(n)) }
        if (hit != null) { navigator?.go(hit.link, animated = false); setControls(false) } else ui.say("No encontré ese capítulo en el texto")
    }

    /**
     * Dentro del capítulo: si el narrador ya va más allá de la página que se ve, se pasa una página.
     * Solo hacia adelante, y nunca si la persona tocó la pantalla hace poco (quizá va adelantada).
     */
    private fun pageWithNarrator(ab: com.sebas.zerkalo.data.AudioBook, st: com.sebas.zerkalo.audio.Listen.State) {
        if (!com.sebas.zerkalo.data.Prefs.audioFollow || ui.sheet != null || ui.auto || ui.lookup != null || st.duration <= 0) return
        val t = System.currentTimeMillis()
        if (t - lastPageCheck < 3_000 || t - lastTouch < 10_000) return
        lastPageCheck = t
        val nav = navigator ?: return
        val pub = publication ?: return
        val loc = nav.currentLocator.value
        val track = ab.tracks.getOrNull(st.index) ?: return
        // ¿El capítulo que se ve es el que suena? Por título; si no coincide, por proporción.
        val n = norm(track.title)
        val hit = ui.toc.firstOrNull { e -> val c = norm(e.title); c.length > 3 && n.length > 3 && (n.contains(c) || c.contains(n)) }
        val want = hit?.let { h -> pub.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == h.link.url().toString().substringBefore('#') } }
            ?.takeIf { it >= 0 } ?: ((st.index.toFloat() / ab.tracks.size.coerceAtLeast(1)) * pub.readingOrder.size).toInt()
        if (resourceIndex(pub, loc) != want) return
        val frac = (st.position.toDouble() / st.duration).coerceIn(0.0, 1.0)
        val p = loc.locations.progression ?: 0.0
        lifecycleScope.launch {
            val page = runCatching { nav.evaluateJavascript(PAGE_SHARE) }.getOrNull()?.trim('"')?.toDoubleOrNull()?.takeIf { it in 0.0001..1.0 } ?: return@launch
            if (frac > p + page * 1.05 && frac < p + page * 4) turn(nav, 1)
        }
    }

    /**
     * La página sigue al narrador: cuando el audiolibro unido a este libro pasa de capítulo,
     * el texto salta a ese capítulo (por título; si no coincide, por proporción).
     */
    private fun followAudio() {
        var last: Pair<String, Int>? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.sebas.zerkalo.audio.Listen.state.collect { st ->
                    val ab = st.book?.takeIf { it.linkedBook == book.id && !st.sample } ?: run { last = null; return@collect }
                    val now = ab.id to st.index
                    val prev = last
                    last = now
                    if (prev == now && st.playing) { pageWithNarrator(ab, st); return@collect }
                    if (prev == null || prev == now || !st.playing) return@collect
                    if (!com.sebas.zerkalo.data.Prefs.audioFollow || ui.sheet != null || ui.auto) return@collect
                    val track = ab.tracks.getOrNull(st.index) ?: return@collect
                    val n = norm(track.title)
                    val hit = ui.toc.firstOrNull { t -> val c = norm(t.title); c.length > 3 && n.length > 3 && (n.contains(c) || c.contains(n)) }
                    if (hit != null) { navigator?.go(hit.link, animated = false); return@collect }
                    val pub = publication ?: return@collect
                    val size = pub.readingOrder.size
                    if (size == 0 || ab.tracks.isEmpty()) return@collect
                    val r = ((st.index.toFloat() / ab.tracks.size) * size).toInt().coerceIn(0, size - 1)
                    val cur = navigator?.currentLocator?.value?.let { resourceIndex(pub, it) } ?: -1
                    if (r != cur) pub.locatorFromLink(pub.readingOrder[r])?.let { navigator?.go(it, animated = false) }
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

    fun stopAuto() {
        autoJob?.cancel(); autoJob = null
        ui.auto = false
        lifecycleScope.launch { runCatching { navigator?.evaluateJavascript(AUTO_STOP) } }
    }

    /** Pasa página. En desplazamiento continuo avanza una pantalla dejando dos líneas de referencia. */
    private fun turn(nav: EpubNavigatorFragment, dir: Int) {
        if (ui.controls) setControls(false)
        if (ui.settings.haptic) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (!ui.settings.scroll) {
            if (dir > 0) nav.goForward(animated = ui.settings.animate) else nav.goBackward(animated = ui.settings.animate)
            return
        }
        lifecycleScope.launch {
            val js = SCROLL_BY.replace("__D__", dir.toString()).replace("__S__", if (ui.settings.animate) "smooth" else "auto")
            val r = runCatching { nav.evaluateJavascript(js) }.getOrNull().orEmpty()
            // Al final (o al inicio) del capítulo, se pasa al siguiente (o al anterior).
            if (r.contains("edge")) chapterJump(nav, dir)
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
        ui.bookmarked = pos != null && ui.marks.any { it.type == "shiori" && posOf(it) == pos }
        ui.chapterLeft = chapterLeft(pos)
        if (turn && ui.pairLinked && System.currentTimeMillis() > ignoreMainUntil) syncPair(loc)
        val json = loc.toJSON().toString()
        Library.update(book.id) { it.copy(progress = total.toDouble(), locator = json, lastOpened = now) }
        val last = positions.lastOrNull()?.lastOrNull()?.locations?.position
        if (turn && pos != null && last != null && pos >= last && !book.finished && Library.book(book.id)?.finished != true) {
            Library.update(book.id) { it.copy(finishedAt = now, progress = 1.0) }
            ui.finishedNow = true
        }
    }

    private fun chapterFor(loc: Locator): String? {
        val href = loc.href.toString().substringBefore('#')
        return ui.toc.lastOrNull { it.link.href.toString().substringBefore('#').let { h -> h.isNotEmpty() && (href.endsWith(h) || h.endsWith(href)) } }?.title
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
        if (pal.isDark && r.ink != 0) {
            val argb = if (r.ink == 5) r.customInk else InkColors.getOrElse(r.ink) { 0 }
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
        val fam = ReaderFonts.getOrNull(r.font)?.css
        // Los estilos del libro se desactivan siempre: si no, el libro manda sobre el justificado
        // y el interlineado (con la letra "Original" se conserva su tipografía).
        return EpubPreferences(
            backgroundColor = RColor(p.bg.toArgb()),
            textColor = RColor(p.text.toArgb()),
            fontFamily = fam?.let { FontFamily(it) },
            fontSize = r.size / 100.0,
            lineHeight = r.lineHeight.toDouble(),
            pageMargins = pageMargins(r),
            textAlign = if (r.justify && !r.dyslexia) TextAlign.JUSTIFY else TextAlign.START,
            hyphens = r.justify && !r.dyslexia,
            // Original: manda el diseño del libro (Zerkalo solo corrige lo que se rompe y respeta tu justificado)
            publisherStyles = r.layout == 0 || r.layout == 3,
            scroll = r.scroll,
            paragraphSpacing = listOf(null, 0.6, 1.2).getOrNull(r.paragraph),
            letterSpacing = listOf(null, 0.06, 0.14).getOrNull(r.spacing),
            wordSpacing = listOf(null, 0.15, 0.3).getOrNull(r.spacing),
        )
    }

    /**
     * Márgenes de los lados. En modo Zerkalo se achican cuando la letra crece, para que la línea
     * siga teniendo un largo cómodo (y los versos quepan) en vez de quedar con 4 palabras.
     */
    private fun pageMargins(r: ReaderSettings): Double {
        val base = listOf(0.75, 1.15, 1.6).getOrElse(r.margin) { 1.15 }
        if (r.size <= 100) return base
        return (base * Math.pow(100.0 / r.size, 0.9)).coerceAtLeast(0.45)
    }

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
        lifecycleScope.launch {
            // Capítulo nuevo: el papel lo cubre un instante mientras se acomoda, así el texto no "salta" a la vista
            if (fresh) ui.chapterFade = true
            try {
                for ((i, wait) in listOf(0L, 240L, 700L).withIndex()) {
                    delay(wait)
                    val r = runCatching { nav.evaluateJavascript(Typeset.script(ui.settings, lang)) }.getOrNull()
                    if (i == 0) Log.i("Zerkalo", "ZK_TYPESET $href $r")
                    if (i == 1) {
                        // Al entrar por el final (o a un punto guardado), volver a ese punto tras reacomodar
                        val p = entry.locations.progression ?: 0.0
                        val now = nav.currentLocator.value
                        if (fresh && !ui.settings.scroll && p > 0.001 && now.href.toString() == href &&
                            abs((now.locations.progression ?: 0.0) - p) > 0.0005
                        ) nav.go(entry, animated = false)
                        ui.chapterFade = false
                    }
                }
            } finally {
                ui.chapterFade = false
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
        Prefs.setReader(r)
        val old = ui.settings
        ui.settings = r
        ui.palette = paletteFor(r)
        if (old.brightness != r.brightness) applyBrightness(r.brightness)
        if (old.veil != r.veil) applyVeil()
        if (old.margin != r.margin || old.size != r.size) applyMargins()
        if (ui.auto && (old.autoSpeed != r.autoSpeed || old.autoPage != r.autoPage || old.scroll != r.scroll)) startAuto()
        // Estos ajustes no necesitan rehacer las páginas.
        if (old.copy(
                brightness = r.brightness, veil = r.veil, ruler = r.ruler, haptic = r.haptic, volumeKeys = r.volumeKeys, breaks = r.breaks, animate = r.animate,
                fullscreen = r.fullscreen, vMargin = r.vMargin, autoSpeed = r.autoSpeed, autoPage = r.autoPage, info = r.info,
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
    }

    private fun refreshMarks() {
        ui.marks = Library.marks.value.filter { it.bookId == book.id }
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
            menu.add(Menu.NONE, 101, 0, "Subrayar")
            menu.add(Menu.NONE, 102, 1, "Nota")
            menu.add(Menu.NONE, 104, 2, "Traducir")
            menu.add(Menu.NONE, 103, 3, "Copiar")
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
        toggleShiori = { toggleShiori() },
        go = { navigator?.go(it, animated = false); setControls(false) },
        goLink = { navigator?.go(it, animated = false); setControls(false) },
        settings = { updateSettings(it) },
        removeMark = { Library.removeMark(it.id); ui.activeMark = null; refreshMarks() },
        saveNote = { loc, note -> saveHighlight(loc, 1, note); ui.noteFor = null },
        recolor = { m, c -> Prefs.lastInk = c; Library.putMark(m.copy(color = c)); ui.activeMark = null; refreshMarks() },
        seek = { f -> seek(f) },
        brightnessLive = { v -> applyBrightness(v) },
        toggleAuto = { if (ui.auto) stopAuto() else startAuto() },
        findAudio = { com.sebas.zerkalo.data.AudioStore.findFor(book) },
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
        setInks = { Prefs.setInks(it) },
        highlightWith = { c -> Prefs.lastInk = c },
        openInks = { ui.activeMark = null; ui.sheet = "inks" },
        turn = { d -> navigator?.let { turn(it, d) } },
        toggleStudy = {
            ui.studyTap = !ui.studyTap; Prefs.studyTap = ui.studyTap
            ui.say(if (ui.studyTap) "Toca una palabra para traducirla" else "Modo estudio apagado")
            if (ui.studyTap) setControls(false)
        },
        closeLookup = { lookupJob?.cancel(); ui.lookup = null },
        retarget = { to -> Prefs.translateTo = to; ui.lookup?.let { startLookup(it.text, it.sentence, it.from, it.locator) } },
        translateSentence = { translateSentence() },
        saveWord = { saveWord() },
        speak = { t, lang -> speak(t, lang) },
        pairCandidates = { Library.books.value.filter { it.id != book.id }.sortedWith(compareByDescending<Book> { it.author.isNotBlank() && it.author == book.author }.thenByDescending { it.lang.isNotBlank() && it.lang != book.lang }.thenByDescending { it.lastOpened }) },
        setPair = { other -> setPair(other) },
        togglePair = { showPair(!ui.pairOn) },
        syncPair = { navigator?.currentLocator?.value?.let { syncPair(it, force = true) } },
        togglePairLink = { togglePairLink() },
        swapPair = { swapPair() },
        setPairRatio = { setPairRatio(it) },
        veilLive = { v -> ui.dim = 1f - (1f - (if (ui.settings.brightness in 0f..0.3f) (0.3f - ui.settings.brightness) / 0.3f * 0.78f else 0f)) * (1f - v) },
    )

    private fun toggleShiori() {
        val loc = navigator?.currentLocator?.value ?: return
        val pos = loc.locations.position
        val existing = ui.marks.firstOrNull { it.type == "shiori" && pos != null && posOf(it) == pos }
        if (existing != null) {
            Library.removeMark(existing.id); ui.say("Shiori retirado")
        } else {
            Library.putMark(
                Mark(
                    id = "s" + System.currentTimeMillis().toString(36), bookId = book.id, type = "shiori",
                    locator = loc.toJSON().toString(), chapter = ui.chapter, progress = ui.progress.toDouble(),
                    text = loc.text.highlight?.take(120).orEmpty(),
                ),
            )
            ui.say("Shiori puesto en esta página")
        }
        refreshMarks()
    }

    /** Saltar a un punto del libro (0..1) con la barra de progreso. */
    private fun seek(f: Float) {
        val all = positions.flatten()
        if (all.isEmpty()) return
        val target = all[((all.size - 1) * f.coerceIn(0f, 1f)).toInt()]
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
            val start = navigator?.currentLocator?.value?.let { mapLocator(it, publication ?: pub, pub) }
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
            navigator?.currentLocator?.value?.let { syncPair(it, force = true) }
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
    }

    override fun onPause() {
        super.onPause()
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
    val toggleShiori: () -> Unit,
    val go: (Locator) -> Unit,
    val goLink: (Link) -> Unit,
    val settings: (ReaderSettings) -> Unit,
    val removeMark: (Mark) -> Unit,
    val saveNote: (Locator, String) -> Unit,
    val recolor: (Mark, Int) -> Unit,
    val seek: (Float) -> Unit,
    val brightnessLive: (Float) -> Unit,
    val toggleAuto: () -> Unit,
    val findAudio: suspend () -> List<com.sebas.zerkalo.data.AudioBook>,
    val linkedAudio: () -> com.sebas.zerkalo.data.AudioBook?,
    val listen: (com.sebas.zerkalo.data.AudioBook, Boolean) -> Unit, // true = desde el capítulo donde vas leyendo
    val goToTrack: (String) -> Unit,
    val setInks: (List<com.sebas.zerkalo.data.InkColor>) -> Unit,
    val highlightWith: (Int) -> Unit,
    val openInks: () -> Unit,
    val turn: (Int) -> Unit,
    val toggleStudy: () -> Unit,
    val closeLookup: () -> Unit,
    val retarget: (String) -> Unit,
    val translateSentence: () -> Unit,
    val saveWord: () -> Unit,
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
  var d = __D__, se = document.scrollingElement || document.documentElement, h = window.innerHeight, w = window.innerWidth;
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
  if (d > 0) {
    var y = h - 2, L = null;
    for (var k = 0; k < 8 && !L; k++) { L = lineAt(y); y -= lh / 3; }
    delta = L ? L.top - pad : h - 2 * lh;
    if (delta < h * 0.45) delta = h - 2 * lh;
  } else {
    var y2 = 2, F = null;
    for (var j = 0; j < 8 && !F; j++) { F = lineAt(y2); y2 += lh / 3; }
    delta = F ? -(h - F.bottom - pad) : -(h - 2 * lh);
    if (-delta < h * 0.45) delta = -(h - 2 * lh);
  }
  window.scrollBy({ top: Math.round(delta), behavior: '__S__' });
  // Al terminar de moverse, si la primera línea quedó mordida arriba, se acomoda para verla entera.
  var fix = function(){
    window.removeEventListener('scrollend', fix);
    var y = 1, F = null;
    for (var j = 0; j < 4 && !F; j++) { F = lineAt(y); y += lh / 3; }
    if (F && F.top < -1 && F.bottom > 1) window.scrollBy({ top: Math.round(F.top - pad), behavior: 'auto' });
  };
  if ('onscrollend' in window && '__S__' === 'smooth') window.addEventListener('scrollend', fix); else setTimeout(fix, 60);
  return 'ok';
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
