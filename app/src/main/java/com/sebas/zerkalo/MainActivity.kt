package com.sebas.zerkalo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Zerkalo es una interfaz web (assets/www) dentro de un WebView a pantalla completa.
 * Todo se sirve desde https://appassets.androidplatform.net, así el lector y los libros
 * comparten origen y funcionan sin internet.
 */
private const val START_URL = "https://appassets.androidplatform.net/assets/www/index.html"

class MainActivity : ComponentActivity() {

    lateinit var web: WebView
    lateinit var bridge: Bridge
    private var pageReady = false
    @Volatile private var pageStarted = false
    private val pendingJs = mutableListOf<String>()
    private var insetsJs = ""
    private lateinit var overlay: FrameLayout
    private var overlayShownAt = 0L
    private var webReady = false
    private val errors = mutableListOf<String>()
    private val traceLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var served = 0
    @Volatile private var lastServed = ""
    @Volatile private var progress = 0
    fun trace(msg: String) { android.util.Log.i("Zerkalo", msg); if (traceLog.size < 40) traceLog.add(msg) }

    val booksDir: File by lazy { File(filesDir, "books").apply { mkdirs() } }

    private var pickCallback: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = pickCallback ?: return@registerForActivityResult
        pickCallback = null
        if (uri == null) bridge.resolve(cb, false, "cancel")
        else importUri(uri) { ok, payload -> bridge.resolve(cb, ok, payload) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/books/", WebViewAssetLoader.InternalStoragePathHandler(this, booksDir))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.setBackgroundColor(if (isNight()) Color.parseColor("#2E231D") else Color.parseColor("#F7F2E8"))
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(this)
        overlay.addView(LoadingView(this, isNight()), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.isClickable = true
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        overlayShownAt = System.currentTimeMillis()
        // Si en 12 s la biblioteca no avisó que está lista, se muestra qué falló (para poder arreglarlo).
        web.postDelayed({ if (!webReady) showDiagnostics() }, 12000)
        bridge = Bridge(this)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val line = "${m.messageLevel()}: ${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) addError(line) else trace(line)
                return true
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.addJavascriptInterface(bridge, "ZerkaloNative")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val r = loader.shouldInterceptRequest(request.url)
                if (r == null) { trace("sin manejar: ${request.url}"); return null }
                served++; lastServed = request.url.path ?: ""
                // Tipo de archivo explícito: los módulos JS no cargan si Android adivina mal.
                val path = request.url.path ?: ""
                val mime = when (path.substringAfterLast('.', "").lowercase()) {
                    "js", "mjs" -> "text/javascript"
                    "css" -> "text/css"
                    "html" -> "text/html"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "woff2" -> "font/woff2"
                    "json" -> "application/json"
                    "epub" -> "application/epub+zip"
                    else -> null
                }
                if (mime != null) r.mimeType = mime
                if (mime != null && (mime.startsWith("text/") || mime == "application/json")) r.encoding = "utf-8"
                return r
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
                if (request.url.host == "appassets.androidplatform.net") addError("No cargó ${request.url.path}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.url.host == "appassets.androidplatform.net") addError("HTTP ${errorResponse.statusCode} en ${request.url.path}")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                // Enlaces externos (créditos, donaciones): al navegador.
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { pageStarted = true; trace("empezó a cargar") }

            override fun onPageFinished(view: WebView, url: String) {
                trace("página cargada")
                pageReady = true
                if (insetsJs.isNotEmpty()) view.evaluateJavascript(insetsJs, null)
                pendingJs.forEach { view.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            insetsJs = "document.documentElement.style.setProperty('--sat','${bars.top / d}px');" +
                "document.documentElement.style.setProperty('--sab','${bars.bottom / d}px');" +
                "document.documentElement.style.setProperty('--kb','${ime.bottom / d}px');"
            if (pageReady) web.evaluateJavascript(insetsJs, null)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back ? window.__back() : false") { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        web.loadUrl(START_URL)
        // Plan B: si en 5 s la navegación no arrancó, se carga el mismo HTML directamente.
        web.postDelayed({
            if (!pageStarted) {
                trace("plan B: carga directa")
                try {
                    val html = assets.open("www/index.html").bufferedReader().use { it.readText() }
                    web.stopLoading()
                    web.loadDataWithBaseURL(START_URL, html, "text/html", "utf-8", null)
                } catch (e: Exception) { addError("plan B: ${e.message}") }
            }
        }, 5000)

        handleIntent(intent)
        Updates.schedule(this)
        lifecycleScope.launch { Updates.check(this@MainActivity) }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Updates.foreground.value = true
        js("window.__onResume && window.__onResume()")
    }

    override fun onStop() {
        super.onStop()
        Updates.foreground.value = false
        js("window.__onPause && window.__onPause()")
        // Si hay versión nueva descargada y Android deja instalarla sin preguntar, se instala ahora.
        if (Updates.ready.value != null && Updates.autoEnabled(this) && Updates.silentPossible(this)) {
            lifecycleScope.launch { Updates.install(applicationContext) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        js("window.__onSystemTheme && window.__onSystemTheme(${isNight()})")
    }

    fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun addError(msg: String) = runOnUiThread {
        android.util.Log.e("Zerkalo", msg)
        if (errors.size < 12) errors.add(msg)
    }

    /** La interfaz ya dibujó la biblioteca: se desvanece la pantalla de carga. */
    fun onWebReady() = runOnUiThread {
        if (webReady) return@runOnUiThread
        webReady = true
        android.util.Log.i("Zerkalo", "ZK_READY")
        val wait = (1700L - (System.currentTimeMillis() - overlayShownAt)).coerceAtLeast(0L)
        overlay.postDelayed({
            overlay.animate().alpha(0f).setDuration(480).withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        }, wait)
    }

    private fun showDiagnostics() {
        val dark = isNight()
        val tv = TextView(this).apply {
            setTextColor(if (dark) Color.parseColor("#CDBAA3") else Color.parseColor("#5C4B3F"))
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 48)
            text = "La biblioteca tarda en abrir.\nToca aquí para reintentar o manda una captura de este mensaje:\n\n" +
                (if (errors.isEmpty()) "Sin errores" else errors.joinToString("\n")) +
                "\n\nArchivos servidos: $served (último ${lastServed.substringAfterLast('/')}) · progreso $progress%\n" +
                traceLog.takeLast(16).joinToString("\n") +
                "\n\nWebView ${android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "?"}"
            setOnClickListener { v -> errors.clear(); this@MainActivity.overlay.removeView(v); web.reload(); web.postDelayed({ if (!webReady) showDiagnostics() }, 12000) }
        }
        overlay.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** Ejecuta JS en la página (o lo deja en cola si aún carga). */
    fun js(code: String) = runOnUiThread {
        if (pageReady) web.evaluateJavascript(code, null) else pendingJs.add(code)
    }

    fun pickEpub(cb: String) = runOnUiThread {
        pickCallback = cb
        try { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }
        catch (e: Exception) { pickCallback = null; bridge.resolve(cb, false, "error") }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        importUri(uri) { ok, payload -> if (ok) js("window.__importedFromIntent && window.__importedFromIntent($payload)") }
    }

    /** Copia un EPUB elegido o compartido a la biblioteca interna. */
    private fun importUri(uri: Uri, done: (Boolean, String) -> Unit) {
        Thread {
            try {
                var display = "libro.epub"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) display = c.getString(i) ?: display
                }
                val name = "imp-" + System.currentTimeMillis() + ".epub"
                contentResolver.openInputStream(uri)!!.use { input ->
                    File(booksDir, name).outputStream().use { input.copyTo(it) }
                }
                done(true, JSONObject().put("file", name).put("name", display).toString())
            } catch (e: Exception) {
                done(false, e.message ?: "error")
            }
        }.start()
    }
}
