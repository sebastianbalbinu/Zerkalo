package com.sebas.zerkalo.reader

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import com.irurueta.android.gl.curl.CurlPage
import com.irurueta.android.gl.curl.CurlTextureView
import kotlin.math.abs
import kotlin.math.min

/**
 * Pasar página como un libro de papel, en 3D (con la tarjeta gráfica del teléfono).
 *
 * Cómo funciona, sin cortes:
 * - Adelante: se toma la foto de la página y se pone encima, en 3D. Debajo, el libro ya pasa a la
 *   página siguiente de verdad. La foto se dobla desde la esquina y deja ver la página real.
 * - Atrás: con la foto de la página encima, el libro vuelve a la anterior por debajo; se dibuja esa
 *   anterior y es ella la que entra doblándose desde la izquierda.
 * - Si sueltas a medias, la hoja vuelve sola a su sitio y el libro también.
 *
 * Detrás de la hoja se ve el mismo texto al revés, tenue, como papel a contraluz.
 */
class PaperFlip(context: Context) {
    /** Capa que se pone encima del libro. No recibe toques: se los pasamos nosotros. */
    val layer: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false
    }
    private val view = CurlTextureView(context)
    private val low = Snap.isLow(context)

    private val pages = arrayOfNulls<Bitmap>(2)
    private var count = 0

    /** Color del papel (para el reverso de la hoja). */
    var paper: Int = Color.rgb(0xF4, 0xEC, 0xDC)
    var dark = false

    /** 1 = pasando adelante, -1 = atrás, 0 = quieta. */
    var dir = 0
        private set
    val busy get() = dir != 0

    /** Al terminar: dirección y si de verdad pasó la página (false = volvió a su sitio). */
    var onFinished: ((dir: Int, completed: Boolean) -> Unit)? = null

    private var awaiting: (() -> Unit)? = null // esperando a que la hoja se dibuje
    private var armed = false                   // ya empezó a doblarse
    private var downTime = 0L
    private var tapAnim: ValueAnimator? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        // El dibujo del doblez se ajusta antes de darle las páginas
        view.maxCurlSplitsInMesh = if (low) 14 else 30
        view.drawShadowInMesh = true
        view.shadowInnerColorInMesh = floatArrayOf(0f, 0f, 0f, 0.16f) // sombra suave, nada de negro marcado
        view.shadowOuterColorInMesh = floatArrayOf(0f, 0f, 0f, 0f)
        // Papel mate: el doblez se oscurece apenas (sin brillo de plástico)
        view.colorFactorOffsetInMesh = 0.84f
        // Radio del doblez como el de una hoja de libro (ni tubo apretado ni lámina rígida)
        view.restPressure = 0.6f
        view.catchUpDurationTime = 60L
        view.allowLastPageCurl = true
        view.renderLeftPage = false
        view.enableTouchPressure = false
        view.animationDurationTime = 230
        view.setBackgroundColor(Color.TRANSPARENT)
        view.pageProvider = object : CurlTextureView.PageProvider {
            override val pageCount: Int get() = count
            override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int, backIndex: Int?) {
                val b = pages.getOrNull(index)
                if (b != null && !b.isRecycled) {
                    // La librería se queda con el dibujo (y lo recicla): se le da una copia
                    page.setTexture(b.copy(Bitmap.Config.ARGB_8888, false), CurlPage.SIDE_BOTH)
                } else {
                    page.setColor(paper, CurlPage.SIDE_BOTH)
                    page.setTexture(null, CurlPage.SIDE_BOTH)
                }
                // Reverso: papel opaco, un poco más oscuro (le da menos luz), con la tinta del otro lado apenas insinuada,
                // como en un libro impreso (no una hoja transparente)
                val k = if (dark) 0.92f else 0.95f
                val back = Color.rgb((Color.red(paper) * k).toInt(), (Color.green(paper) * k).toInt(), (Color.blue(paper) * k).toInt())
                page.setColor(Color.argb(if (dark) 0x12 else 0x10, Color.red(back), Color.green(back), Color.blue(back)), CurlPage.SIDE_BACK)
            }
        }
        view.currentIndexChangedListener = object : CurlTextureView.CurrentIndexChangedListener {
            override fun onCurrentIndexChanged(view: CurlTextureView, currentIndex: Int) {
                // 1) Primera vez tras preparar: la hoja ya está dibujada encima
                awaiting?.let { go -> awaiting = null; layer.postOnAnimation { layer.postOnAnimation(Runnable { go() }) }; return }
                // 2) Terminó el doblez
                if (!armed || dir == 0) return
                val d = dir
                val completed = if (d > 0) currentIndex >= 1 else currentIndex == 0
                armed = false
                onFinished?.invoke(d, completed)
            }
        }
        layer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun onResume() = runCatching { view.onResume() }
    fun onPause() = runCatching { view.onPause() }

    /** Pone [shown] encima (la página de ahora) y avisa con [ready] cuando ya se ve. */
    fun cover(direction: Int, shown: Bitmap, ready: () -> Unit) {
        tapAnim?.cancel()
        dir = direction
        armed = false
        clearPages()
        if (direction > 0) { pages[0] = shown; count = 1; show(0, ready) }
        else { pages[1] = shown; count = 2; show(1, ready) }
    }

    /** Atrás: ya se tiene la página anterior; se pone lista para entrar desde la izquierda. */
    fun setPrevious(prev: Bitmap, ready: () -> Unit) {
        pages[0]?.recycle()
        pages[0] = prev
        show(1, ready)
    }

    private fun show(index: Int, ready: () -> Unit) {
        var done = false
        val go = { if (!done) { done = true; ready() } }
        awaiting = go
        view.setCurrentIndex(index)
        // Por si el dibujo tarda (primera vez): no se queda esperando para siempre
        main.postDelayed({ if (!done) { awaiting = null; go() } }, 450)
    }

    // ---------------- el dedo ----------------
    private fun ev(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

    private fun send(e: MotionEvent) { view.onTouchEvent(e); e.recycle() }

    /** Empieza el doblez desde el borde (adelante: borde derecho; atrás: izquierdo), a la altura [y]. */
    fun press(y: Float) {
        val w = view.width.toFloat().coerceAtLeast(1f)
        downTime = SystemClock.uptimeMillis()
        armed = true
        send(ev(MotionEvent.ACTION_DOWN, if (dir > 0) w - 2f else 2f, y))
    }

    fun drag(x: Float, y: Float) = send(ev(MotionEvent.ACTION_MOVE, x, y))

    /**
     * Suelta donde está el dedo, sin saltos. [completed] decide si pasa o vuelve; [vx] es la velocidad del dedo
     * (px/s, positiva hacia la derecha): la hoja sigue con ese impulso y se asienta, como una hoja que cae.
     */
    fun release(x: Float, y: Float, completed: Boolean, vx: Float = 0f, maxMs: Int = 380) {
        val w = view.width.toFloat().coerceAtLeast(1f)
        // Adónde va la hoja: adelante y pasa → izquierda; atrás y pasa → derecha; si vuelve, al revés
        val toLeft = (dir > 0) == completed
        val target = if (toLeft) 0f else w
        val remaining = abs(target - x)
        val speed = if (toLeft) -vx else vx // velocidad hacia donde va
        val ms = if (speed > 300f) 3f * remaining / speed * 1000f else 150f + 230f * remaining / w
        view.forcedTarget = if (toLeft) 1 else 2
        view.nextReleaseDuration = ms.toInt().coerceIn(130, maxMs)
        send(ev(MotionEvent.ACTION_MOVE, x, y))
        send(ev(MotionEvent.ACTION_UP, x, y))
        // Si el dedo casi no movió la hoja, la librería no avisa: se da por devuelta
        val d = dir
        val token = ++releases
        main.postDelayed({
            if (armed && dir == d && token == releases) { armed = false; onFinished?.invoke(d, false) }
        }, 1100)
    }
    private var releases = 0

    /** Un toque a un lado: la esquina de abajo se levanta y la hoja cae con impulso, como al pasarla con el pulgar. */
    fun flip() {
        val w = view.width.toFloat().coerceAtLeast(1f)
        val h = view.height.toFloat().coerceAtLeast(1f)
        val y0 = h * 0.93f
        val y1 = h * 0.86f
        val reach = w * 0.42f
        val lift = 120L
        press(y0)
        tapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = lift
            interpolator = android.view.animation.AccelerateInterpolator(1.2f) // el pulgar empuja y la hoja toma vuelo
            addUpdateListener {
                val t = it.animatedValue as Float
                val x = if (dir > 0) (w - 2f) - t * reach else 2f + t * reach
                drag(x, y0 + (y1 - y0) * t)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val x = if (dir > 0) (w - 2f) - reach else 2f + reach
                    val v = reach / lift * 1000f * 1.6f * (if (dir > 0) -1f else 1f)
                    release(x, y1, completed = true, vx = v, maxMs = 300)
                }
            })
            start()
        }
    }

    /** Quita la hoja (ya no se ve nada encima del libro). */
    fun clear() {
        tapAnim?.cancel()
        awaiting = null
        armed = false
        dir = 0
        clearPages()
        count = 0
        runCatching { view.setCurrentIndex(0) }
    }

    private fun clearPages() {
        for (i in pages.indices) { pages[i]?.takeIf { !it.isRecycled }?.recycle(); pages[i] = null }
    }
}

/**
 * Capa que decide si un gesto es para pasar página: un deslizamiento claro de lado.
 * Lo demás (tocar, seleccionar texto, bajar) sigue al libro como siempre.
 */
class CurlRoot(context: Context) : FrameLayout(context) {
    /** ¿Ahora se puede doblar la página con el dedo? */
    var enabled: () -> Boolean = { false }
    /** El dedo tocó: buen momento para tomar la foto de la página (así el doblez arranca al instante). */
    var onDown: () -> Unit = {}
    /** Empieza un doblez (dirección 1 adelante, -1 atrás). Devuelve false si no se puede. */
    var onStart: (dir: Int, y: Float) -> Boolean = { _, _ -> false }
    /** El dedo se mueve: [dx] cuánto se corrió de lado desde que tocó (siempre ≥ 0, en la dirección del doblez). */
    var onMove: (dx: Float, y: Float) -> Unit = { _, _ -> }
    var onEnd: (dx: Float, y: Float, complete: Boolean, vx: Float) -> Unit = { _, _, _, _ -> }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var multi = false
    private var dir = 0
    private var vt: VelocityTracker? = null

    private fun dxOf(ev: MotionEvent) = (if (dir > 0) downX - ev.x else ev.x - downX).coerceAtLeast(0f)

    // Se mira el dedo aquí (antes que el libro): la página del libro pide que nadie más tome sus gestos,
    // así que si se esperara a que los suelte, el doblez nunca empezaría.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dragging) { follow(ev); return true }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = ev.eventTime; dragging = false; multi = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
                if (enabled()) onDown()
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true // dos dedos: es para agrandar, no para pasar
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                // De lado y claro (no un dedo que baja ni uno que se quedó quieto para seleccionar texto)
                if (!multi && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f && ev.eventTime - downAt < 400 && enabled()) {
                    val d = if (dx < 0) 1 else -1
                    if (onStart(d, ev.y)) {
                        dir = d; dragging = true
                        // El libro suelta el gesto
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(c); c.recycle()
                        follow(ev)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun follow(ev: MotionEvent) {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onMove(dxOf(ev), ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val w = width.coerceAtLeast(1).toFloat()
                val dx = dxOf(ev)
                val forwardFling = if (dir > 0) vx < -600 else vx > 600
                val backFling = if (dir > 0) vx > 600 else vx < -600
                dragging = false
                vt?.recycle(); vt = null
                // Como Kindle: pasa si un toque rápido lo lanza, o si ya se llevó un tercio de la hoja
                val complete = ev.actionMasked == MotionEvent.ACTION_UP && !backFling && (forwardFling || dx > w * 0.33f)
                onEnd(dx, ev.y, complete, vx)
            }
        }
    }
}

object Snap {
    fun isLow(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isLowRamDevice || am.memoryClass < 256
    }

    /** Tamaño de la foto: la tarjeta gráfica la agranda a potencia de 2, así que se limita la altura. */
    fun scale(ctx: Context, height: Int): Float {
        val maxH = if (isLow(ctx)) 1024f else 2048f
        return min(1f, maxH / height.coerceAtLeast(1))
    }

    /** Foto de lo que se ve en [view] (por la GPU: rápida). */
    fun take(window: Window, view: View, scale: Float, done: (Bitmap?) -> Unit) {
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return done(null)
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        runCatching {
            PixelCopy.request(window, rect, bmp, { r -> done(if (r == PixelCopy.SUCCESS) bmp else { bmp.recycle(); null }) }, Handler(Looper.getMainLooper()))
        }.onFailure { bmp.recycle(); done(null) }
    }

    /** Dibuja [view] directo (sin lo que haya encima), para cuando la hoja tapa la pantalla. */
    fun draw(view: View, scale: Float, background: Int): Bitmap? {
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        return runCatching {
            val c = Canvas(bmp)
            c.drawColor(background)
            c.scale(scale, scale)
            view.draw(c)
            // Si salió en blanco (algunos WebView no se dejan dibujar así), no sirve
            if (blank(bmp)) { bmp.recycle(); null } else bmp
        }.getOrElse { bmp.recycle(); null }
    }

    private fun blank(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2)
        var diff = 0
        for (i in 1..14) for (j in 1..14) {
            if (b.getPixel(b.width * i / 15, b.height * j / 15) != first) diff++
        }
        return diff < 3
    }
}
