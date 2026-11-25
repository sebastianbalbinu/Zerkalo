package com.sebas.zerkalo.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.selection.ContextMenuComponent
import androidx.pdf.selection.SelectionMenuComponent
import androidx.pdf.selection.model.TextSelection
import androidx.pdf.view.PdfView
import com.sebas.zerkalo.data.Apuntes
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.ui.CircleButton
import com.sebas.zerkalo.ui.FloatingNote
import com.sebas.zerkalo.ui.LocalPalette
import com.sebas.zerkalo.ui.NoteMode
import com.sebas.zerkalo.ui.NoteState
import com.sebas.zerkalo.ui.Type
import com.sebas.zerkalo.ui.ZIcon
import com.sebas.zerkalo.ui.ZerkaloTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lector de PDF: el visor de Google para Android (androidx.pdf) con la ropa de Zerkalo.
 * Zoom con dos dedos, búsqueda, elegir texto para copiar, subrayar o mandar al apunte flotante,
 * modo noche, saltar a una página y abrir donde lo dejaste.
 */
class PdfReaderActivity : AppCompatActivity() {
    private lateinit var book: Book
    private lateinit var pdfView: PdfView
    private var doc: PdfDocument? = null
    private val note = NoteState.start()

    // Lo que muestra la capa de encima (Compose)
    private var page by mutableIntStateOf(0)
    private var pages by mutableIntStateOf(0)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var night by mutableStateOf(false)
    private var searching by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var hits by mutableStateOf<List<PdfRect>>(emptyList())
    private var hit by mutableIntStateOf(0)
    private var goTo by mutableStateOf(false)

    private val marks = ArrayList<PdfRect>()   // subrayados de este PDF
    // Luz: los mismos ajustes que el lector de EPUB (brillo, velo y filtro cálido)
    private var light by mutableStateOf(com.sebas.zerkalo.data.Prefs.reader.value)
    private var dim by mutableStateOf(0f)
    private var lightSheet by mutableStateOf(false)
    // Pluma de colores
    private val inkUi = com.sebas.zerkalo.ui.InkUi()
    private var inkView: com.sebas.zerkalo.ui.InkView? = null
    private val pageRects = java.util.TreeMap<Int, RectF>()  // dónde está cada página visible, en px
    private var rectsTick by mutableIntStateOf(0)           // cambia cuando las páginas se mueven (para las notas)
    // Notas adhesivas: locator "pdf:<página>:<x>:<y>" (x e y de 0 a 1 dentro de la página)
    private var stickies by mutableStateOf<List<com.sebas.zerkalo.data.Mark>>(emptyList())
    private var sticky by mutableStateOf<com.sebas.zerkalo.data.Mark?>(null)
    private var placing by mutableStateOf(false)
    private var controls by mutableStateOf(true)   // controles a la vista (un toque los muestra u oculta)
    private var tools by mutableStateOf(false)
    private var exporting by mutableStateOf(false)
    private var startedAt = 0L
    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val b = Library.book(intent.getStringExtra("id"))
        if (b == null) { finish(); return }
        book = b
        night = getSharedPreferences("zerkalo", MODE_PRIVATE).getBoolean("pdfNoche", false)

        val root = FrameLayout(this)
        val overlay = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setContent { ZerkaloTheme { Overlay() } }
        }
        if (Build.VERSION.SDK_INT < 28) {
            // El visor de PDF de Google necesita Android 9: en teléfonos más viejos solo se explica.
            loading = false
            error = "Para leer PDF dentro de Zerkalo hace falta Android 9 o más nuevo."
            root.setBackgroundColor(0xFF2E231D.toInt())
            root.addView(overlay)
            setContentView(root)
            return
        }
        pdfView = PdfView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            maxZoom = 8f
        }
        root.addView(pdfView)
        inkView = com.sebas.zerkalo.ui.InkView(
            this, book.id, pdfView, inkUi,
            pages = { pageRects.map { (pg, r) -> "p$pg" to r } },
            onTap = { if (!searching) controls = !controls },
        )
        root.addView(inkView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay)
        setContentView(root)
        applyNight()
        applyBrightness(light.brightness)
        loadMarks()
        loadStickies()
        setup()
        open()
        Library.update(book.id) { it.copy(lastOpened = System.currentTimeMillis()) }
    }

    private fun setup() {
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(firstVisiblePage: Int, visiblePagesCount: Int, pageLocations: SparseArray<RectF>, zoomLevel: Float) {
                // Copia de dónde está cada página (la pluma dibuja encima de ellas)
                pageRects.clear()
                for (i in 0 until pageLocations.size()) pageRects[pageLocations.keyAt(i)] = RectF(pageLocations.valueAt(i))
                inkView?.invalidate()
                rectsTick++
                if (!restored) return
                // La página que más se ve: la primera visible, salvo que ya casi se fue hacia arriba
                var p = firstVisiblePage
                val r = pageLocations.get(firstVisiblePage)
                if (r != null && visiblePagesCount > 1 && r.bottom < pdfView.height * 0.35f) p = firstVisiblePage + 1
                if (p != page) { page = p; saveProgress() }
            }
        })
        // Al mover la página, los controles se esconden: la pantalla queda para leer
        pdfView.addOnGestureStateChangedListener(object : PdfView.OnGestureStateChangedListener {
            override fun onGestureStateChanged(newState: Int) {
                if (newState == PdfView.GESTURE_STATE_INTERACTING && controls && !searching) controls = false
            }
        })
        pdfView.addOnFirstContentLoadListener {
            // Abre donde lo dejaste
            val saved = book.locator?.removePrefix("pdf:")?.toIntOrNull() ?: 0
            if (saved > 0) pdfView.scrollToPage(saved.coerceAtMost((pages - 1).coerceAtLeast(0)))
            page = saved
            restored = true
            Log.i("Zerkalo", "ZK_PDF_READY $pages páginas")
        }
        pdfView.addSelectionMenuItemPreparer(object : PdfView.SelectionMenuItemPreparer {
            override fun onPrepareSelectionMenuItems(components: MutableList<ContextMenuComponent>) {
                val sel = pdfView.currentSelection as? TextSelection ?: return
                val text = sel.text.toString()
                val bounds = sel.bounds
                // Al apunte va primero: es lo que más se usa mientras se estudia
                components.add(0, SelectionMenuComponent("zk-apunte", "Al apunte", null) {
                    val where = bounds.firstOrNull()?.let { "pág. ${it.pageNum + 1}" } ?: "pág. ${page + 1}"
                    Apuntes.addQuote(book.id, text, where)
                    note.open()
                    close(); pdfView.clearCurrentSelection()
                })
                components.add(1, SelectionMenuComponent("zk-nota", "Nota adhesiva", null) {
                    val b = bounds.firstOrNull()
                    close(); pdfView.clearCurrentSelection()
                    // El papelito queda arriba a la derecha de la frase elegida
                    b?.let { pdfView.pdfToViewPoint(PdfPoint(it.pageNum, it.right, it.top)) }?.let { pt -> placeSticky(pt.x, pt.y, text) }
                })
                components.add(2, SelectionMenuComponent("zk-subrayar", "Subrayar", null) {
                    marks.addAll(bounds); saveMarks(); paintHighlights()
                    close(); pdfView.clearCurrentSelection()
                })
                components.add(SelectionMenuComponent("zk-buscar", "Buscar", null) {
                    close(); pdfView.clearCurrentSelection()
                    query = text.trim().take(80); searching = true; search()
                })
            }
        })
    }

    private fun open() {
        val file = File(Library.booksDir, book.file)
        lifecycleScope.launch {
            try {
                val d = withContext(Dispatchers.IO) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    SandboxedPdfLoader(applicationContext).openDocument(Uri.fromFile(file), fd)
                }
                doc = d
                pages = d.pageCount
                pdfView.pdfDocument = d
                loading = false
                paintHighlights()
            } catch (e: SecurityException) {
                loading = false; error = "Este PDF tiene contraseña y Zerkalo todavía no sabe abrirlo."
            } catch (e: Throwable) {
                Log.e("Zerkalo", "No se pudo abrir el PDF", e)
                loading = false; error = "No se pudo abrir este PDF (${e.message ?: e.javaClass.simpleName})."
            }
        }
    }

    private fun saveProgress() {
        val n = pages.coerceAtLeast(1)
        val prog = ((page + 1).toDouble() / n).coerceIn(0.0, 1.0)
        Library.update(book.id) { it.copy(locator = "pdf:$page", progress = maxOf(prog, 0.0001), lastOpened = System.currentTimeMillis()) }
    }

    // ---------- Notas adhesivas ----------

    private fun loadStickies() { stickies = Library.marks.value.filter { it.bookId == book.id && it.type == "sticky" } }

    /** Pega una nota nueva donde se tocó la pantalla (en px) y la abre para escribir. */
    private fun placeSticky(x: Float, y: Float, quote: String = "") {
        placing = false
        val hit = pageRects.entries.firstOrNull { it.value.contains(x, y) } ?: run { say("Toca sobre una página"); return }
        val r = hit.value
        val nx = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        val ny = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        val m = com.sebas.zerkalo.data.Mark(
            id = "n" + System.currentTimeMillis().toString(36), bookId = book.id, type = "sticky",
            locator = "pdf:${hit.key}:$nx:$ny", chapter = "pág. ${hit.key + 1}", text = quote,
            progress = (hit.key + 1).toDouble() / pages.coerceAtLeast(1),
        )
        Library.putMark(m); loadStickies(); sticky = m
    }

    // ---------- Subrayados (se guardan aparte, en files/pdfmarcas) ----------

    private fun marksFile() = File(File(filesDir, "pdfmarcas").apply { mkdirs() }, "${book.id}.json")

    private fun loadMarks() {
        runCatching {
            val a = JSONArray(marksFile().readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                marks.add(PdfRect(o.getInt("p"), o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat()))
            }
        }
    }

    private fun saveMarks() {
        val a = JSONArray()
        marks.forEach { a.put(JSONObject().put("p", it.pageNum).put("l", it.left).put("t", it.top).put("r", it.right).put("b", it.bottom)) }
        lifecycleScope.launch(Dispatchers.IO) { runCatching { marksFile().writeText(a.toString()) } }
    }

    /** Subrayados en amarillo suave; lo encontrado al buscar en bermellón claro (el actual, más fuerte). */
    private fun paintHighlights() {
        if (!::pdfView.isInitialized) return
        val list = ArrayList<Highlight>()
        marks.forEach { list.add(Highlight(it, 0x66E9C46A)) }
        hits.forEachIndexed { i, r -> list.add(Highlight(r, if (i == hit) 0x88C4432A.toInt() else 0x40C4432A)) }
        pdfView.setHighlights(list)
    }

    // ---------- Búsqueda ----------

    private fun search() {
        val d = doc ?: return
        val q = query.trim()
        if (q.isEmpty()) { hits = emptyList(); paintHighlights(); return }
        lifecycleScope.launch {
            val found = runCatching { d.searchDocument(q, 0 until d.pageCount) }.getOrNull()
            val out = ArrayList<PdfRect>()
            if (found != null) for (i in 0 until found.size()) {
                val pg = found.keyAt(i)
                found.valueAt(i).forEach { m -> m.bounds.firstOrNull()?.let { out.add(PdfRect(pg, it)) } }
            }
            out.sortWith(compareBy<PdfRect>({ it.pageNum }, { it.top }))
            hits = out
            // Empieza por lo que está desde esta página en adelante
            hit = out.indexOfFirst { it.pageNum >= page }.let { if (it < 0) 0 else it }
            paintHighlights()
            showHit()
            if (out.isEmpty()) say("No aparece «$q» en este PDF")
        }
    }

    private fun showHit() {
        val r = hits.getOrNull(hit) ?: return
        pdfView.scrollToPosition(PdfPoint(r.pageNum, r.left, (r.top - 60f).coerceAtLeast(0f)))
        paintHighlights()
    }

    private fun closeSearch() {
        searching = false; query = ""; hits = emptyList(); paintHighlights()
    }

    // ---------- Modo noche: la página se invierte y queda en tonos cálidos ----------

    /**
     * Color de la página: "Página oscura" la invierte y la deja en tonos cálidos;
     * el filtro cálido baja el azul (y un poco el verde), igual que en el lector de EPUB.
     */
    private fun applyNight() {
        if (!::pdfView.isInitialized) return
        val m = ColorMatrix()
        var touched = false
        if (night) {
            m.set(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
            // Blanco → ámbar suave (como el modo Sumi), negro → casi negro
            m.postConcat(ColorMatrix(floatArrayOf(
                0.91f, 0f, 0f, 0f, 0f,
                0f, 0.80f, 0f, 0f, 0f,
                0f, 0f, 0.64f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
            touched = true
        }
        val w = light.warmth
        if (w > 0f) {
            m.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f - 0.10f * w, 0f, 0f, 0f,
                0f, 0f, 1f - 0.42f * w, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
            touched = true
        }
        if (touched) pdfView.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        else pdfView.setLayerType(View.LAYER_TYPE_NONE, null)
        // El espacio entre páginas: oscuro siempre (después de invertir, este gris claro se ve casi negro)
        pdfView.setBackgroundColor(if (night) 0xFFD6CEC4.toInt() else 0xFF3B2B24.toInt())
    }

    /** Brillo propio del lector, igual que en EPUB: bajo la marca, la pantalla al mínimo y un velo encima. */
    private fun applyBrightness(b: Float) {
        val screen = when {
            b < 0f -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            b < 0.3f -> 0.01f
            else -> 0.02f + (b - 0.3f) / 0.7f * 0.98f
        }
        val base = if (b in 0f..0.3f) (0.3f - b) / 0.3f * 0.78f else 0f
        dim = 1f - (1f - base) * (1f - light.veil.coerceIn(0f, 0.85f))
        window.attributes = window.attributes.apply { screenBrightness = screen }
    }

    private fun saveLight(r: com.sebas.zerkalo.data.ReaderSettings) {
        light = r
        com.sebas.zerkalo.data.Prefs.setReader(r)
        applyBrightness(r.brightness)
        applyNight()
    }

    private fun ink(t: com.sebas.zerkalo.ui.InkTool) {
        inkUi.tool = t
        if (t == com.sebas.zerkalo.ui.InkTool.MARKER && inkUi.color == com.sebas.zerkalo.ui.InkColors[0]) inkUi.color = com.sebas.zerkalo.ui.InkColors[1]
        if (!inkUi.on) toggleInk()
    }

    private fun exportPdf() {
        val iv = inkView ?: return
        say("Preparando el PDF…")
        lifecycleScope.launch {
            runCatching {
                com.sebas.zerkalo.data.Export.pdfWithNotes(
                    this@PdfReaderActivity, book, iv.all(),
                    marks.map { floatArrayOf(it.pageNum.toFloat(), it.left, it.top, it.right, it.bottom) },
                    stickies,
                )
            }.onSuccess { com.sebas.zerkalo.data.Export.share(this@PdfReaderActivity, it, "application/pdf") }
                .onFailure { Log.e("Zerkalo", "Exportar PDF", it); say("No se pudo exportar: ${it.message}") }
        }
    }

    private fun toggleInk() {
        if (!::pdfView.isInitialized || doc == null) return
        inkUi.on = !inkUi.on
        if (inkUi.on) { pdfView.clearCurrentSelection(); note.minimize(); controls = false } else controls = true
        inkView?.invalidate()
    }

    private fun toggleNight() {
        if (!::pdfView.isInitialized) return
        night = !night
        getSharedPreferences("zerkalo", MODE_PRIVATE).edit().putBoolean("pdfNoche", night).apply()
        applyNight()
    }

    private fun say(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        startedAt = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        if (::book.isInitialized && startedAt > 0) Library.addSession(book.id, System.currentTimeMillis() - startedAt)
        startedAt = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { doc?.close() }
    }

    // ---------- La capa de encima ----------

    @Composable
    private fun Overlay() {
        val p = LocalPalette.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Velo de la luz (atenúa la página sin cambiar el brillo de la pantalla)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Box(Modifier.fillMaxSize().imePadding()) {
            // Arriba, solo con los controles (o buscando): volver, título y buscar
            if (controls || searching) Row(
                Modifier.align(Alignment.TopCenter).padding(top = top + 8.dp, start = 12.dp, end = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp)).background(p.bg.copy(alpha = 0.92f)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!searching) {
                    CircleButton("back", { finish() }, size = 40.dp, desc = "Volver")
                    Text(
                        book.title, style = Type.title.copy(fontSize = 16.sp, lineHeight = 20.sp), color = p.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    CircleButton("search", { searching = true }, size = 40.dp, desc = "Buscar")
                } else SearchRow()
            }

            if (loading) Text("Abriendo…", style = Type.ui, color = Color(0xFFF0E6D4), modifier = Modifier.align(Alignment.Center))
            error?.let {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(it, style = Type.body, color = p.text)
                    Spacer(Modifier.height(14.dp))
                    Text("Volver", style = Type.button, color = p.accent, modifier = Modifier.clickable { finish() }.padding(8.dp))
                }
            }

            // Abajo: con los controles, la página y cuatro botones; sin ellos, solo una línea fina de avance
            if (pages > 0 && controls && !inkUi.on) Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(p.bg.copy(alpha = 0.88f)).clickable { goTo = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Text("pág. ${page + 1} de $pages", style = Type.uiSmall, color = p.text2) }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(30.dp)).background(p.bg.copy(alpha = 0.94f)).padding(7.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton("toc", { goTo = true }, size = 44.dp, desc = "Ir a la página")
                    CircleButton("aa", { lightSheet = true }, size = 44.dp, desc = "Aspecto")
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(p.surface.copy(alpha = if (p.isDark) 0.85f else 1f)).clickable { tools = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("✎", style = Type.title.copy(fontSize = 19.sp), color = p.text) }
                    com.sebas.zerkalo.ui.NoteButton(note, 44.dp)
                }
            }
            if (pages > 0 && !controls && !inkUi.on) Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 6.dp, start = 28.dp, end = 28.dp).fillMaxWidth().height(2.dp)
                    .background(p.text2.copy(alpha = 0.25f)),
            ) { Box(Modifier.fillMaxWidth(((page + 1f) / pages).coerceIn(0f, 1f)).height(2.dp).background(p.accent)) }

            // Notas adhesivas pegadas en las páginas que se ven
            @Suppress("UNUSED_VARIABLE") val tick = rectsTick
            val density = androidx.compose.ui.platform.LocalDensity.current
            val half = with(density) { 12.dp.toPx() }
            stickies.forEach { m ->
                val parts = m.locator.split(":")
                val pg = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val nx = parts.getOrNull(2)?.toFloatOrNull() ?: return@forEach
                val ny = parts.getOrNull(3)?.toFloatOrNull() ?: return@forEach
                val r = pageRects[pg] ?: return@forEach
                com.sebas.zerkalo.ui.StickyChip(
                    Modifier.offset { androidx.compose.ui.unit.IntOffset((r.left + nx * r.width() - half).toInt(), (r.top + ny * r.height() - half).toInt()) },
                ) { sticky = m }
            }
            if (placing) Box(
                Modifier.fillMaxSize().background(Color(0x22000000)).pointerInput(Unit) { detectTapGestures { o -> placeSticky(o.x, o.y) } },
            ) {
                Text(
                    "Toca donde quieres pegar la nota", style = Type.ui, color = Color.White,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xCC2E231D)).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (!inkUi.on) FloatingNote(book.id, book.title, note, bottomSpace = bottom + 90.dp)
            else com.sebas.zerkalo.ui.InkPill(
                inkUi, onUndo = { inkView?.undoLast() }, onDone = { toggleInk() },
                modifier = Modifier.padding(top = top + 70.dp, bottom = bottom + 10.dp),
            )
            if (lightSheet) LightSheet()
            if (tools) ToolsSheet()
            if (exporting) ExportSheet()
            sticky?.let { m ->
                com.sebas.zerkalo.ui.StickyEditor(
                    m, m.chapter,
                    onSave = { t -> Library.putMark(m.copy(note = t)); loadStickies() },
                    onDelete = { Library.removeMark(m.id); loadStickies(); sticky = null },
                    onClose = { sticky = null },
                )
            }

            if (goTo) GoToPage { n ->
                goTo = false
                if (n != null && pages > 0) { val t = (n - 1).coerceIn(0, pages - 1); pdfView.scrollToPage(t); page = t; saveProgress() }
            }
        }
    }

    @Composable
    private fun RowScope.SearchRow() {
        val p = LocalPalette.current
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        CircleButton("close", { closeSearch() }, size = 40.dp, desc = "Cerrar búsqueda")
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (query.isEmpty()) Text("Buscar en el PDF", style = Type.ui, color = p.text2)
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = Type.ui.copy(color = p.text), cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (hits.isNotEmpty()) Text("${hit + 1}/${hits.size}", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(end = 6.dp))
        CircleButton("chevron", { if (hits.isNotEmpty()) { hit = (hit + 1) % hits.size; showHit() } else search() }, size = 40.dp, desc = "Siguiente")
    }

    /** Herramientas: lo que no cabe en los cuatro botones. */
    @Composable
    private fun ToolsSheet() {
        val p = LocalPalette.current
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { tools = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface,
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                com.sebas.zerkalo.ui.Caption("Herramientas", accent = true)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    com.sebas.zerkalo.ui.ToolTile("✎", "Pluma", Modifier.weight(1f)) { tools = false; ink(com.sebas.zerkalo.ui.InkTool.PEN) }
                    com.sebas.zerkalo.ui.ToolTile("▌", "Resaltador", Modifier.weight(1f)) { tools = false; ink(com.sebas.zerkalo.ui.InkTool.MARKER) }
                    com.sebas.zerkalo.ui.ToolTile("▤", "Nota adhesiva", Modifier.weight(1f), tint = com.sebas.zerkalo.ui.StickyPaper) {
                        tools = false; controls = false; placing = true
                    }
                    com.sebas.zerkalo.ui.ToolTile("⌫", "Borrar", Modifier.weight(1f)) { tools = false; ink(com.sebas.zerkalo.ui.InkTool.ERASER) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    com.sebas.zerkalo.ui.ToolTile("⇪", "Exportar", Modifier.weight(1f)) { tools = false; exporting = true }
                    com.sebas.zerkalo.ui.ToolTile("⌕", "Buscar", Modifier.weight(1f)) { tools = false; searching = true }
                    com.sebas.zerkalo.ui.ToolTile("☼", "Luz", Modifier.weight(1f)) { tools = false; lightSheet = true }
                    com.sebas.zerkalo.ui.ToolTile("#", "Ir a página", Modifier.weight(1f)) { tools = false; goTo = true }
                }
            }
        }
    }

    @Composable
    private fun ExportSheet() {
        val p = LocalPalette.current
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { exporting = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface,
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                Text("Exportar", style = Type.title.copy(fontSize = 21.sp), color = p.text)
                Text("Se crea una copia nueva; tu PDF en Zerkalo no cambia.", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                ExportRow("PDF con mis notas", "Trazos, subrayados y notas adhesivas dentro del PDF; el texto se sigue pudiendo seleccionar") {
                    exporting = false; exportPdf()
                }
                ExportRow("Apunte (.txt)", "Las frases y lo que escribiste en el apunte") {
                    exporting = false; com.sebas.zerkalo.ui.shareTxt(this@PdfReaderActivity, book.title, Apuntes.flow(book.id).value)
                }
            }
        }
    }

    @Composable
    private fun ExportRow(title: String, sub: String, onClick: () -> Unit) {
        val p = LocalPalette.current
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.ui, color = p.text)
                Text(sub, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 2.dp))
            }
            ZIcon("share", p.text2, 18.dp)
        }
    }

    /** La misma hoja de Luz que en el lector de EPUB, más "Página oscura" (antes la luna). */
    @Composable
    private fun LightSheet() {
        val p = LocalPalette.current
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { lightSheet = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface,
        ) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                Text("Luz", style = Type.title.copy(fontSize = 21.sp), color = p.text)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Brillo automático", style = Type.ui, color = p.text)
                        Text("El del teléfono", style = Type.uiSmall, color = p.text2)
                    }
                    com.sebas.zerkalo.ui.ZSwitch(light.brightness < 0f) { saveLight(light.copy(brightness = if (it) -1f else 0.65f)) }
                }
                if (light.brightness >= 0f) {
                    var b by remember { mutableStateOf(light.brightness) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        ZIcon("moon", p.text2, 18.dp)
                        com.sebas.zerkalo.ui.ZSlider(
                            value = b, onChange = { b = it; applyBrightness(it) }, onFinished = { saveLight(light.copy(brightness = b)) },
                            marker = 0.3f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        ZIcon("sun", p.text2, 20.dp)
                    }
                }
                var veil by remember { mutableStateOf(light.veil) }
                LightLabel("Atenuar", if (veil <= 0f) "apagado" else "${(veil / 0.85f * 100).toInt()} %")
                com.sebas.zerkalo.ui.ZSlider(
                    value = veil, range = 0f..0.85f,
                    onChange = { veil = it; light = light.copy(veil = it); applyBrightness(light.brightness) },
                    onFinished = { saveLight(light.copy(veil = veil)) },
                )
                Text("Oscurece la página sin cambiar el brillo de la pantalla.", style = Type.uiSmall, color = p.text2)
                var w by remember { mutableStateOf(light.warmth) }
                LightLabel("Filtro cálido", if (w <= 0f) "apagado" else "${(w * 100).toInt()} %")
                com.sebas.zerkalo.ui.ZSlider(
                    value = w, onChange = { w = it; light = light.copy(warmth = it); applyNight() },
                    onFinished = { saveLight(light.copy(warmth = w)) },
                )
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Página oscura", style = Type.ui, color = p.text)
                        Text("La hoja blanca se vuelve oscura y la letra, ámbar", style = Type.uiSmall, color = p.text2)
                    }
                    com.sebas.zerkalo.ui.ZSwitch(night) { toggleNight() }
                }
            }
        }
    }

    @Composable
    private fun LightLabel(t: String, sub: String) {
        val p = LocalPalette.current
        Row(Modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(t, style = Type.ui, color = p.text)
            Text("  $sub", style = Type.uiSmall, color = p.text2)
        }
    }

    @Composable
    private fun GoToPage(done: (Int?) -> Unit) {
        val p = LocalPalette.current
        var v by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { done(null) }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(30.dp).clip(RoundedCornerShape(22.dp)).background(p.surface).clickable(enabled = false) {}.padding(22.dp),
            ) {
                Text("Ir a la página", style = Type.title.copy(fontSize = 19.sp), color = p.text)
                Text("De 1 a $pages", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.bg).padding(14.dp)) {
                    BasicTextField(
                        v, { s -> v = s.filter { it.isDigit() }.take(6) }, singleLine = true,
                        textStyle = Type.title.copy(fontSize = 22.sp, color = p.text), cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { done(v.toIntOrNull()) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancelar", style = Type.button, color = p.text2, modifier = Modifier.clickable { done(null) }.padding(10.dp))
                    Text("Ir", style = Type.button, color = p.accent, modifier = Modifier.clickable { done(v.toIntOrNull()) }.padding(10.dp))
                }
            }
        }
    }
}
