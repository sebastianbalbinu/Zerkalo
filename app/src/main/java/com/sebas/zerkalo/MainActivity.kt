package com.sebas.zerkalo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Zerkalo es una interfaz web (assets/www) dentro de un WebView a pantalla completa.
 * Todo se sirve desde https://appassets.androidplatform.net, así el lector y los libros
 * comparten origen y funcionan sin internet.
 */
class MainActivity : ComponentActivity() {

    lateinit var web: WebView
    lateinit var bridge: Bridge
    private var pageReady = false
    private val pendingJs = mutableListOf<String>()
    private var insetsJs = ""

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

        web = WebView(this)
        web.setBackgroundColor(if (isNight()) Color.parseColor("#2E231D") else Color.parseColor("#F7F2E8"))
        setContentView(web)
        bridge = Bridge(this)

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
        }
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.addJavascriptInterface(bridge, "ZerkaloNative")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                // Enlaces externos (créditos, donaciones): al navegador.
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
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

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        handleIntent(intent)
        Updates.schedule(this)
        lifecycleScope.launch { Updates.check(this@MainActivity) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
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
