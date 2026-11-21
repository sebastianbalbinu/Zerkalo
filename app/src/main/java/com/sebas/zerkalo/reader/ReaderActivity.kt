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
import com.sebas.zerkalo.ui.highlightColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
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
    var lang by mutableStateOf("")
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
    private lateinit var divider: View

    // La misma obra en otro idioma
    private var pairBook: Book? = null
    private var pairPub: Publication? = null
    private var pairNav: EpubNavigatorFragment? = null
    private var pairPositions: List<List<Locator>> = emptyList()
    private var pairLang = ""
    private var lookupJob: Job? = null
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
        divider = View(this).apply { visibility = View.GONE }
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
        ViewCompat.setOnApplyWindowInsetsListener(panes) { v, insets ->
            val i = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        applyPaper()
        applyBrightness(ui.settings.brightness)
        lifecycleScope.launch { open() }
    }

    /** Vertical: un libro encima del otro. Horizontal: uno al lado del otro. */
    private fun layoutPanes(c: Configuration) {
        val landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        panes.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val w = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        val h = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0
        val line = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        panes.getChildAt(0).layoutParams = LinearLayout.LayoutParams(w, h, 1f)
        divider.layoutParams = if (landscape) LinearLayout.LayoutParams(line, ViewGroup.LayoutParams.MATCH_PARENT) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, line)
        pairContainer.layoutParams = LinearLayout.LayoutParams(w, h, 1f)
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
        nav.addInputListener(DirectionalNavigationAdapter(nav, animatedTransition = true))
        nav.addDecorationListener("subrayados", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                ui.activeMark = ui.marks.firstOrNull { it.id == event.decoration.id }
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                nav.currentLocator.collect { onLocator(it) }
            }
        }
        lifecycleScope.launch {
            positions = runCatching { pub.positionsByReadingOrder() }.getOrDefault(emptyList())
            nav.currentLocator.value.let { onLocator(it, turn = false) }
        }
        lifecycleScope.launch { applyHighlights() }
        lifecycleScope.launch {
            while (true) {
                delay(30_000)
                ui.minutesToday = Library.minutesToday() + ((activeMs + pendingMs()) / 60_000).toInt()
            }
        }
        ui.loading = false
        Log.i("Zerkalo", "ZK_READER_OK ${book.title}")
        refreshPairInfo()
        if (ui.hasPair && Prefs.pairOn(book.id)) showPair(true)
        lifecycleScope.launch { delay(1600); setControls(false) }
    }

    private fun navConfig() = EpubNavigatorFragment.Configuration {
        servedAssets = listOf("fonts/.*")
        selectionActionModeCallback = selectionCallback
        val files = FontFiles.available(this@ReaderActivity).toSet()
        ReaderFonts.forEachIndexed { i, f ->
            if (f.css != null && f.regular != null && i in files) declareFont(f.css, "fonts/${f.regular}", f.italic?.let { "fonts/$it" }, f.bold?.let { "fonts/$it" })
        }
    }

    /** Toques: en el centro muestran u ocultan los controles; en modo estudio, tocar una palabra la traduce. */
    private fun tapListener(nav: EpubNavigatorFragment, pair: Boolean) = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val w = nav.publicationView.width.toFloat().coerceAtLeast(1f)
            val x = event.point.x / w
            if (ui.studyTap && x in 0.16f..0.84f) {
                lifecycleScope.launch {
                    if (!lookUpAt(nav, event.point, if (pair) pairLang else ui.lang)) {
                        if (ui.lookup != null) { lookupJob?.cancel(); ui.lookup = null } else setControls(!ui.controls)
                    }
                }
                return true
            }
            return if (x in 0.3f..0.7f) { setControls(!ui.controls); true } else { if (ui.controls) setControls(false); false }
        }
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
        if (turn) syncPair(loc)
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
        val base = basePalette(r)
        if (!base.isDark || r.ink == 0) return base
        val argb = if (r.ink == 5) r.customInk else InkColors.getOrElse(r.ink) { 0 }
        if (argb == 0) return base
        val ink = androidx.compose.ui.graphics.Color(argb)
        return base.copy(text = ink, text2 = ink.copy(alpha = 0.72f))
    }

    private fun basePalette(r: ReaderSettings): Palette = when (r.paper) {
        0 -> Chashitsu
        1 -> Nogal
        2 -> SumiPalette
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
            pageMargins = listOf(0.75, 1.15, 1.6).getOrElse(r.margin) { 1.15 },
            textAlign = if (r.justify) TextAlign.JUSTIFY else TextAlign.START,
            hyphens = r.justify,
            publisherStyles = false,
            scroll = r.scroll,
        )
    }

    /** Brillo propio del lector (no cambia el del sistema). */
    private fun applyBrightness(b: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (b < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b.coerceIn(0.02f, 1f)
        }
    }

    fun updateSettings(r: ReaderSettings) {
        Prefs.setReader(r)
        val old = ui.settings
        ui.settings = r
        ui.palette = paletteFor(r)
        if (old.brightness != r.brightness) applyBrightness(r.brightness)
        // El brillo y el filtro cálido no necesitan rehacer las páginas.
        if (old.copy(brightness = r.brightness, warmth = r.warmth) == r) return
        applyPaper()
        navigator?.submitPreferences(preferences(r, ui.palette))
        pairNav?.submitPreferences(preferences(r, ui.palette))
        lifecycleScope.launch { applyHighlights() }
    }

    // ---------------- subrayados y shiori ----------------
    private suspend fun applyHighlights() {
        val nav = navigator ?: return
        val decos = ui.marks.filter { it.type == "hl" }.mapNotNull { m ->
            val loc = runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull() ?: return@mapNotNull null
            val tint = highlightColor(m.color).copy(alpha = if (ui.palette.isDark) 0.45f else 0.55f).toArgb()
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
                101 -> highlight(0)
                102 -> highlight(1, note = "")
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
        recolor = { m, c -> Library.putMark(m.copy(color = c)); ui.activeMark = null; refreshMarks() },
        seek = { f -> seek(f) },
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
            val start = navigator?.currentLocator?.value?.let { mapLocator(it, publication ?: pub, pub) }
                ?: other.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            val ff = EpubNavigatorFactory(pub, EpubNavigatorFactory.Configuration(defaults = EpubDefaults(pageMargins = 1.2)))
                .createFragmentFactory(initialLocator = start, initialPreferences = preferences(ui.settings, ui.palette), listener = this@ReaderActivity, configuration = navConfig())
            val frag = ff.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
            pairContainer.visibility = View.VISIBLE; divider.visibility = View.VISIBLE
            supportFragmentManager.commitNow { replace(pairId, frag, "pareja") }
            pairNav = frag
            frag.addInputListener(tapListener(frag, pair = true))
            frag.addInputListener(DirectionalNavigationAdapter(frag, animatedTransition = true))
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
        pn.go(target, animated = false)
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
)

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
