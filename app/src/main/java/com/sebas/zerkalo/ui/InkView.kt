package com.sebas.zerkalo.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebas.zerkalo.data.InkStore
import com.sebas.zerkalo.data.Stroke
import kotlin.math.hypot

/** Herramientas de la pluma. */
enum class InkTool { PEN, MARKER, ERASER }

/** Colores de la pluma: amarillo, bermellón, azul y verde. */
val InkColors = listOf(0xFFE9C46A.toInt(), 0xFFC4432A.toInt(), 0xFF3A6EA5.toInt(), 0xFF3F7A4A.toInt())

/** Lo que muestra la barrita de la pluma (Compose la observa). */
class InkUi {
    var on by mutableStateOf(false)
    var tool by mutableStateOf(InkTool.PEN)
    var color by mutableIntStateOf(InkColors[1])
    var canUndo by mutableStateOf(false)
}

/**
 * Capa transparente encima del libro donde se raya con la pluma.
 * Con la pluma apagada solo dibuja los trazos guardados y deja pasar todos los toques.
 * Con la pluma encendida, un dedo raya; con dos dedos el gesto pasa al libro (mover y hacer zoom en PDF).
 *
 * [pages] dice qué páginas se ven y dónde están en pantalla: (clave de la página, rectángulo en px).
 */
@SuppressLint("ViewConstructor")
class InkView(
    context: Context,
    private val bookId: String,
    private val target: View,
    private val ui: InkUi,
    private val pages: () -> List<Pair<String, RectF>>,
) : View(context) {
    private val strokes = InkStore.load(bookId)
    private sealed class Step { class Added(val key: String, val s: Stroke) : Step(); class Removed(val key: String, val s: Stroke, val at: Int) : Step() }
    private val undo = ArrayList<Step>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    // El trazo que se está dibujando ahora
    private var curKey: String? = null
    private var curRect: RectF? = null
    private val cur = ArrayList<Float>()
    private var forwarding = false
    private var erasedThisGesture = false

    init { setWillNotDraw(false) }

    /** ¿Hay trazos guardados en alguna de las páginas que se ven? (para no dibujar de más). */
    fun hasAny() = strokes.values.any { it.isNotEmpty() }

    override fun onDraw(canvas: Canvas) {
        for ((key, r) in pages()) {
            strokes[key]?.forEach { drawStroke(canvas, it, r) }
            if (key == curKey && cur.size >= 4) {
                drawStroke(canvas, Stroke(ui.color, widthFor(ui.tool, r), ui.tool == InkTool.MARKER, cur.toFloatArray()), r)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, r: RectF) {
        if (s.pts.size < 4) return
        path.reset()
        path.moveTo(r.left + s.pts[0] * r.width(), r.top + s.pts[1] * r.height())
        var i = 2
        while (i + 1 < s.pts.size) {
            path.lineTo(r.left + s.pts[i] * r.width(), r.top + s.pts[i + 1] * r.height())
            i += 2
        }
        paint.color = s.color
        paint.alpha = if (s.marker) 105 else 235
        paint.strokeWidth = (s.width * r.width()).coerceAtLeast(1.5f)
        paint.strokeCap = if (s.marker) Paint.Cap.SQUARE else Paint.Cap.ROUND
        canvas.drawPath(path, paint)
    }

    /** Grosor como fracción del ancho de la página: la pluma fina, el resaltador ancho como una línea de texto. */
    private fun widthFor(tool: InkTool, r: RectF): Float {
        val px = if (tool == InkTool.MARKER) 16f * resources.displayMetrics.density else 2.6f * resources.displayMetrics.density
        return px / r.width().coerceAtLeast(1f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!ui.on) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forwarding = false; erasedThisGesture = false
                cur.clear(); curKey = null; curRect = null
                val hit = pages().firstOrNull { it.second.contains(ev.x, ev.y) }
                if (hit != null) {
                    curKey = hit.first; curRect = RectF(hit.second)
                    if (ui.tool == InkTool.ERASER) erase(ev.x, ev.y) else addPoint(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Dos dedos: se suelta el trazo y el gesto pasa al libro (mover, hacer zoom)
                if (!forwarding) {
                    forwarding = true
                    cur.clear(); curKey = null; invalidate()
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(ev.downTime, now, MotionEvent.ACTION_DOWN, ev.getX(0), ev.getY(0), 0)
                    target.dispatchTouchEvent(down)
                    down.recycle()
                }
                target.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (forwarding) { target.dispatchTouchEvent(ev); invalidate(); return true }
                if (curKey == null) return true
                if (ui.tool == InkTool.ERASER) {
                    for (h in 0 until ev.historySize) erase(ev.getHistoricalX(h), ev.getHistoricalY(h))
                    erase(ev.x, ev.y)
                } else {
                    for (h in 0 until ev.historySize) addPoint(ev.getHistoricalX(h), ev.getHistoricalY(h))
                    addPoint(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (forwarding) target.dispatchTouchEvent(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (forwarding) { target.dispatchTouchEvent(ev); forwarding = false; invalidate(); return true }
                val key = curKey
                val r = curRect
                if (key != null && r != null && ui.tool != InkTool.ERASER && ev.actionMasked == MotionEvent.ACTION_UP) {
                    // Un toque sin mover deja un puntito
                    if (cur.size == 2) { cur.add(cur[0] + 0.0005f); cur.add(cur[1]) }
                    if (cur.size >= 4) {
                        val s = Stroke(ui.color, widthFor(ui.tool, r), ui.tool == InkTool.MARKER, cur.toFloatArray())
                        strokes.getOrPut(key) { ArrayList() }.add(s)
                        undo.add(Step.Added(key, s))
                        changed()
                    }
                }
                cur.clear(); curKey = null; curRect = null
                invalidate()
            }
        }
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        val r = curRect ?: return
        val nx = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        val ny = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        // Se saltan los puntos casi iguales (trazo más liviano)
        if (cur.size >= 2 && hypot((nx - cur[cur.size - 2]) * r.width(), (ny - cur[cur.size - 1]) * r.height()) < 1.5f) return
        cur.add(nx); cur.add(ny)
        invalidate()
    }

    /** Borra los trazos que pasan cerca del dedo. */
    private fun erase(x: Float, y: Float) {
        val key = curKey ?: return
        val r = curRect ?: return
        val list = strokes[key] ?: return
        val tol = 18f * resources.displayMetrics.density
        val it = list.listIterator()
        var idx = 0
        while (it.hasNext()) {
            val s = it.next()
            var near = false
            var i = 0
            while (i + 1 < s.pts.size) {
                if (hypot(r.left + s.pts[i] * r.width() - x, r.top + s.pts[i + 1] * r.height() - y) < tol) { near = true; break }
                i += 2
            }
            if (near) { it.remove(); undo.add(Step.Removed(key, s, idx)); erasedThisGesture = true; changed(); invalidate() } else idx++
        }
    }

    fun undoLast() {
        when (val st = undo.removeLastOrNull() ?: return) {
            is Step.Added -> strokes[st.key]?.remove(st.s)
            is Step.Removed -> strokes.getOrPut(st.key) { ArrayList() }.let { l -> l.add(st.at.coerceIn(0, l.size), st.s) }
        }
        changed()
        invalidate()
    }

    private fun changed() {
        ui.canUndo = undo.isNotEmpty()
        InkStore.save(bookId, strokes)
    }
}
