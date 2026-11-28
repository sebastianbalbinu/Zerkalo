package com.sebas.zerkalo.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pasar página como papel, pero sin imitarlo del todo: se toma una foto de la página, debajo ya está
 * la siguiente de verdad, y encima la foto se dobla sobre un cilindro suave. El reverso deja ver el
 * mismo texto al revés, tenue, como una hoja a contraluz. Sin sombras negras.
 *
 * Doblar una foto es muy liviano: funciona en celulares modestos. Si aun así va lento, avisa
 * para cambiar a «Deslizar».
 */
class CurlView(context: Context) : View(context) {
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var dir = 1               // 1 = adelante (la hoja se va a la izquierda), -1 = atrás
    private var dark = false
    var progress = 0f                 // 0 = hoja quieta, 1 = ya pasó
        private set
    private var tilt = 0f             // inclinación del pliegue según dónde va el dedo
    private var anim: ValueAnimator? = null
    val busy get() = visibility == VISIBLE

    private val COLS = 22
    private val ROWS = 14
    private val meshF = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val meshB = FloatArray((COLS + 1) * (ROWS + 1) * 2)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    // Para notar si el teléfono no da abasto
    private var frames = 0
    private var startNs = 0L
    var onSlow: (() -> Unit)? = null

    init { visibility = GONE }

    /** Muestra la foto de la página, quieta, tapando todo. [paper] es el color del papel (para el reverso). */
    fun prepare(photo: Bitmap, direction: Int, paper: Int, isDark: Boolean) {
        anim?.cancel()
        dir = direction
        dark = isDark
        front?.takeIf { it !== photo }?.recycle()
        back?.recycle()
        // Hacia atrás se trabaja en espejo: la foto se voltea y todo se dibuja reflejado
        front = if (dir > 0) photo else flip(photo, recycle = true)
        back = makeBack(front!!, paper)
        progress = 0f
        tilt = 0f
        frames = 0; startNs = System.nanoTime()
        visibility = VISIBLE
        invalidate()
    }

    fun follow(p: Float, fingerY: Float) {
        progress = p.coerceIn(0f, 1f)
        tilt = (((fingerY / height.coerceAtLeast(1)) - 0.5f) * -0.22f).coerceIn(-0.12f, 0.12f)
        invalidate()
    }

    fun animateTo(target: Float, duration: Long, done: () -> Unit) {
        anim?.cancel()
        val from = progress
        anim = ValueAnimator.ofFloat(from, target).apply {
            this.duration = (duration * abs(target - from).coerceAtLeast(0.25f)).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { done() }
            })
            start()
        }
    }

    fun hide() {
        anim?.cancel()
        visibility = GONE
        // Si casi no hubo cuadros por segundo, el teléfono no da abasto
        val ms = (System.nanoTime() - startNs) / 1e6
        if (frames in 1..5 && ms > 300) onSlow?.invoke()
        front?.recycle(); back?.recycle(); front = null; back = null
    }

    private fun flip(b: Bitmap, recycle: Boolean): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (recycle && out !== b) b.recycle()
        return out
    }

    /** El reverso: papel con el mismo texto al revés y tenue (como a contraluz). */
    private fun makeBack(f: Bitmap, paper: Int): Bitmap {
        val out = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(paper)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = if (dark) 60 else 92 }
        c.save(); c.scale(-1f, 1f, f.width / 2f, 0f); c.drawBitmap(f, 0f, 0f, p); c.restore()
        // Un velo del papel encima, para que se lea como reverso y no compita con la página nueva
        c.drawColor(Color.argb(if (dark) 70 else 55, Color.red(paper), Color.green(paper), Color.blue(paper)))
        return out
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val f = front ?: return
        val b = back ?: return
        frames++
        val W = width.toFloat()
        val H = height.toFloat()
        if (dir < 0) { canvas.save(); canvas.scale(-1f, 1f, W / 2f, 0f) }
        val R = W * 0.085f                              // radio del cilindro: un pliegue suave
        val travel = W + R * PI.toFloat()               // lo que recorre el pliegue hasta que la hoja se va del todo
        val fold0 = W - progress * travel               // dónde toca la mesa la hoja (en el centro de la altura)
        fun foldAt(y: Float) = fold0 + (y - H / 2f) * tilt
        val sx = f.width / W
        val sy = f.height / H

        // 1) La parte de la hoja que sigue plana (a la izquierda del pliegue)
        clip.reset()
        clip.moveTo(0f, 0f); clip.lineTo(foldAt(0f).coerceIn(0f, W), 0f); clip.lineTo(foldAt(H).coerceIn(0f, W), H); clip.lineTo(0f, H); clip.close()
        canvas.save(); canvas.clipPath(clip)
        canvas.save(); canvas.scale(1f / sx, 1f / sy); canvas.drawBitmap(f, 0f, 0f, bmpPaint); canvas.restore()
        canvas.restore()

        // 2) Lo que se levanta: frente curvándose y luego el reverso que vuelve hacia la izquierda
        val quarter = R * (PI / 2).toFloat()
        var k = 0
        for (r in 0..ROWS) {
            val y = H * r / ROWS
            val fx = foldAt(y)
            for (c in 0..COLS) {
                // Columna de la foto
                val s = W * c / COLS
                val d = s - fx
                // Frente: solo el tramo que sube por el cilindro (0..cuarto de vuelta)
                val df = d.coerceIn(0f, quarter)
                val xf = if (d <= 0f) s else fx + R * sin(df / R)
                meshF[k] = xf; meshF[k + 1] = y
                // Reverso: la columna c del reverso es la columna W - c de la foto
                val sb = W - s
                val db = sb - fx
                val xb = when {
                    db <= quarter -> fx + R                                    // aún de frente: se esconde sobre la cresta
                    db <= R * PI.toFloat() -> fx + R * sin(db / R)             // baja por el cilindro
                    else -> fx - (db - R * PI.toFloat())                       // ya plano, volteado
                }
                meshB[k] = xb; meshB[k + 1] = y
                k += 2
            }
        }
        canvas.save()
        // Frente que se curva: solo a la derecha del pliegue
        clip.reset()
        clip.moveTo(foldAt(0f), 0f); clip.lineTo(W + R, 0f); clip.lineTo(W + R, H); clip.lineTo(foldAt(H), H); clip.close()
        canvas.clipPath(clip)
        canvas.drawBitmapMesh(f, COLS, ROWS, meshF, 0, null, 0, bmpPaint)
        canvas.restore()
        // Brillo muy suave sobre la cresta (nada de sombra negra)
        val crest = foldAt(H / 2f)
        shade.shader = LinearGradient(
            crest, 0f, crest + R, 0f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(if (dark) 26 else 18, 0, 0, 0), Color.argb(if (dark) 10 else 30, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.save(); canvas.skew(tilt, 0f)
        canvas.drawRect(crest - R * 0.2f, -H, crest + R, H * 2, shade)
        canvas.restore()
        // Reverso encima
        canvas.drawBitmapMesh(b, COLS, ROWS, meshB, 0, null, 0, bmpPaint)
        // Una línea de luz finísima en el borde del reverso, para que se lea como hoja
        if (dir < 0) canvas.restore()
    }
}

/**
 * La raíz del lector: deja pasar todo como siempre, salvo un deslizamiento claro hacia los lados en
 * modo «Papel». Ese lo toma para que la hoja siga el dedo.
 */
class CurlRoot(context: Context) : FrameLayout(context) {
    /** ¿Ahora se puede doblar la página con el dedo? */
    var enabled: () -> Boolean = { false }
    /** Empieza un doblez (dirección 1 adelante, -1 atrás). Devuelve false si no se puede. */
    var onStart: (dir: Int, y: Float) -> Boolean = { _, _ -> false }
    var onMove: (progress: Float, y: Float) -> Unit = { _, _ -> }
    var onEnd: (complete: Boolean) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private var dir = 0
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downAt = ev.eventTime; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev) }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (dragging) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Claramente de lado, rápido (no un dedo quieto que está seleccionando texto)
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.7f && ev.eventTime - downAt < 450 && enabled()) {
                    dir = if (dx < 0) 1 else -1
                    if (onStart(dir, ev.y)) { dragging = true; return true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        vt?.addMovement(ev)
        val w = width.coerceAtLeast(1).toFloat()
        val p = ((if (dir > 0) downX - ev.x else ev.x - downX) / (w * 0.85f)).coerceIn(0f, 1f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onMove(p, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val fling = if (dir > 0) vx < -700 else vx > 700
                val back = if (dir > 0) vx > 700 else vx < -700
                dragging = false
                vt?.recycle(); vt = null
                onEnd(ev.actionMasked == MotionEvent.ACTION_UP && !back && (p > 0.32f || fling))
            }
        }
        return true
    }
}

object Snap {
    /** ¿Conviene tomar la foto a media resolución? (celulares con poca memoria) */
    fun scale(ctx: Context): Float {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (am.isLowRamDevice || am.memoryClass < 256) 0.5f else 1f
    }

    /** Foto de lo que se ve en [view] (con la página, la pluma y todo), por la GPU: rápida. */
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
}
