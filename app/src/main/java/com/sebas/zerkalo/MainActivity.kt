package com.sebas.zerkalo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.sebas.zerkalo.ui.AppEvents
import com.sebas.zerkalo.ui.ZerkaloRoot
import com.sebas.zerkalo.ui.ZerkaloTheme
import com.sebas.zerkalo.ui.currentPalette
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var readyLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Volver desde el ícono con un libro abierto: se vuelve al libro, tal como estaba (no se cierra)
        if (!isTaskRoot && intent?.action == android.content.Intent.ACTION_MAIN && intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)) {
            finish(); return
        }
        enableEdgeToEdge()
        setContent {
            val p = currentPalette()
            LaunchedEffect(p.isDark) {
                val style = if (p.isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg.toArgb()))
            }
            ZerkaloTheme {
                ZerkaloRoot(onReady = {
                    if (!readyLogged) { readyLogged = true; Log.i("Zerkalo", "ZK_READY") }
                })
            }
        }
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
    }

    override fun onStop() {
        super.onStop()
        Updates.foreground.value = false
        // Si hay versión nueva descargada y Android deja instalarla sin preguntar, se instala ahora.
        if (Updates.ready.value != null && Updates.autoEnabled(this) && Updates.silentPossible(this)) {
            lifecycleScope.launch { Updates.install(applicationContext) }
        }
    }

    /** EPUB abierto o compartido desde otra app (WhatsApp, Drive, Archivos…). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        AppEvents.testContinuous = intent.getBooleanExtra("zerkalo.continuo", false)
        AppEvents.testSheet = intent.getStringExtra("zerkalo.hoja")
        AppEvents.testPaper = intent.getBooleanExtra("zerkalo.papel", false)
        // Prueba automática: descargar una letra de Google y dejarla puesta en letra grande (22 pt)
        intent.getStringExtra("zerkalo.gfont")?.let { fam ->
            intent.removeExtra("zerkalo.gfont")
            lifecycleScope.launch {
                com.sebas.zerkalo.reader.GoogleFonts.download(applicationContext, fam, true)
                    .onSuccess {
                        val pr = com.sebas.zerkalo.data.Prefs
                        pr.setReader(pr.reader.value.copy(font = it, size = com.sebas.zerkalo.reader.Letra.pct(22)))
                        Log.i("Zerkalo", "ZK_GFONT_OK $fam -> $it")
                    }
                    .onFailure { Log.w("Zerkalo", "ZK_GFONT_FAIL $fam ${it.message}") }
            }
        }
        intent.getStringExtra("zerkalo.abrir")?.let { AppEvents.openBook.value = it; intent.removeExtra("zerkalo.abrir") }
        if (intent.hasExtra("zerkalo.tab")) { AppEvents.tab.value = intent.getIntExtra("zerkalo.tab", 0); intent.removeExtra("zerkalo.tab") }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            val image = intent.type?.startsWith("image/") == true
            intent.action = null
            if (image) AppEvents.coverIncoming.value = uri else AppEvents.incoming.value = uri
        }
    }
}
