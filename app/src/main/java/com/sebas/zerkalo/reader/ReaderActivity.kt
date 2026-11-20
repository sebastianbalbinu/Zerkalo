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
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import org.readium.r2.navigator.epub.css.FontWeight as CssWeight
import org.readium.r2.navigator.preferences.Color as RColor

/** Una entrada del índice, ya aplanada. */
data class TocEntry(val title: String, val link: Link, val depth: Int)

/** Tipografías del lector. La última es la del propio libro. */
val ReaderFonts = listOf("Literata", "Mincho", "Garamond", "Gótica", "Original")
private val fontFamilies = listOf("Literata", "Shippori Mincho", "EB Garamond", "Zen Kaku Gothic New", null)

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
    fun say(s: String) { toast = s to System.currentTimeMillis() }
}

class ReaderActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    private lateinit var book: Book
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private val ui = ReaderUi()
    private val containerId = View.generateViewId()
    private lateinit var root: FrameLayout

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
        root = FrameLayout(this)
        val container = FragmentContainerView(this).apply { id = containerId }
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReaderOverlay(ui, actions) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // El texto queda siempre dentro de la zona segura, aunque las barras se oculten (sin saltos).
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val i = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        applyPaper()
        lifecycleScope.launch { open() }
    }

    private suspend fun open() {
        val core = (application as ZerkaloApp).readium
        val pub = core.open(File(Library.booksDir, book.file)).getOrElse {
            ui.loading = false
            ui.error = it.message ?: "No se pudo abrir el libro"
            return
        }
        publication = pub
        ui.toc = flatten(pub.tableOfContents, 0)
        val initial = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val factory = EpubNavigatorFactory(pub, EpubNavigatorFactory.Configuration(defaults = EpubDefaults(pageMargins = 1.2)))
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initial,
            initialPreferences = preferences(ui.settings, ui.palette),
            listener = this,
            configuration = EpubNavigatorFragment.Configuration {
                servedAssets = listOf("fonts/.*")
                selectionActionModeCallback = selectionCallback
                declareFont("Literata", "fonts/literata_400_normal.ttf", "fonts/literata_400_italic.ttf", "fonts/literata_500_normal.ttf")
                declareFont("Shippori Mincho", "fonts/shippori_mincho_400_normal.ttf", null, "fonts/shippori_mincho_600_normal.ttf")
                declareFont("EB Garamond", "fonts/eb_garamond_400_normal.ttf", "fonts/eb_garamond_400_italic.ttf", null)
                declareFont("Zen Kaku Gothic New", "fonts/zen_kaku_gothic_new_400_normal.ttf", null, "fonts/zen_kaku_gothic_new_700_normal.ttf")
            },
        )
        supportFragmentManager.commitNow {
            add(containerId, EpubNavigatorFragment::class.java, Bundle(), "lector")
        }
        val nav = supportFragmentManager.findFragmentByTag("lector") as EpubNavigatorFragment
        navigator = nav
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                val w = nav.publicationView.width.toFloat().coerceAtLeast(1f)
                val x = event.point.x / w
                return if (x in 0.3f..0.7f) { setControls(!ui.controls); true } else { if (ui.controls) setControls(false); false }
            }
        })
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
        lifecycleScope.launch { delay(1600); setControls(false) }
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

    private fun paletteFor(r: ReaderSettings): Palette = when (r.paper) {
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
        window.decorView.setBackgroundColor(p.bg.toArgb())
        val c = WindowInsetsControllerCompat(window, root)
        c.isAppearanceLightStatusBars = !p.isDark
        c.isAppearanceLightNavigationBars = !p.isDark
    }

    private fun preferences(r: ReaderSettings, p: Palette): EpubPreferences {
        val fam = fontFamilies.getOrNull(r.font)
        return EpubPreferences(
            backgroundColor = RColor(p.bg.toArgb()),
            textColor = RColor(p.text.toArgb()),
            fontFamily = fam?.let { FontFamily(it) },
            fontSize = r.size / 100.0,
            lineHeight = if (fam == null) null else r.lineHeight.toDouble(),
            pageMargins = listOf(0.75, 1.15, 1.6).getOrElse(r.margin) { 1.15 },
            textAlign = if (fam == null) null else if (r.justify) TextAlign.JUSTIFY else TextAlign.START,
            hyphens = if (fam == null) null else r.justify,
            publisherStyles = fam == null,
            scroll = false,
        )
    }

    fun updateSettings(r: ReaderSettings) {
        Prefs.setReader(r)
        ui.settings = r
        ui.palette = paletteFor(r)
        applyPaper()
        navigator?.submitPreferences(preferences(r, ui.palette))
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
            menu.add(Menu.NONE, 103, 2, "Copiar")
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                101 -> highlight(0)
                102 -> highlight(1, note = "")
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
)
