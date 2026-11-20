package com.sebas.zerkalo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Pantalla de carga: el ensō se traza como una pincelada, aparece el sello
 * y luego respira despacio mientras la biblioteca se prepara.
 */
class LoadingView(ctx: Context, dark: Boolean) : View(ctx) {
    private val bmp = BitmapFactory.decodeResource(resources, if (dark) R.drawable.enso_cream else R.drawable.enso_ink)
    private val bg = if (dark) 0xFF2E231D.toInt() else 0xFFF7F2E8.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFFCDBAA3.toInt() else 0xFF5C4B3F.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        letterSpacing = 0.32f
    }
    private val path = Path()
    private val oval = RectF()
    private val dst = RectF()
    private var sweep = 0f
    private var breath = 1f
    private val anims = mutableListOf<ValueAnimator>()

    init {
        anims += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            startDelay = 120
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
        }
        anims += ValueAnimator.ofFloat(1f, 0.72f).apply {
            duration = 1700
            startDelay = 1700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
        }
        anims.forEach { it.start() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anims.forEach { it.cancel() }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val size = min(width, height) * 0.40f
        val cx = width / 2f
        val cy = height * 0.45f
        val h = size * bmp.height / bmp.width
        dst.set(cx - size / 2, cy - h / 2, cx + size / 2, cy + h / 2)
        // La pincelada empieza arriba a la derecha y gira en sentido antihorario, como el trazo original.
        oval.set(cx - size, cy - size, cx + size, cy + size)
        path.reset()
        path.moveTo(cx, cy)
        path.arcTo(oval, -55f, -360f * sweep)
        path.close()
        c.save()
        if (sweep < 1f) c.clipPath(path)
        paint.alpha = (255 * breath).toInt()
        c.drawBitmap(bmp, null, dst, paint)
        c.restore()
        text.textSize = size * 0.12f
        text.alpha = (230 * min(1f, sweep * 1.3f)).toInt()
        c.drawText("ZERKALO", cx, dst.bottom + size * 0.32f, text)
    }
}
