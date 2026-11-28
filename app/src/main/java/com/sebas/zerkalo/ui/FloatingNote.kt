package com.sebas.zerkalo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Apuntes
import kotlin.math.roundToInt

/** Cómo está el apunte: escondido del todo, como pestañita en el borde, bolita, ventanita o grande. */
enum class NoteMode { HIDDEN, TAB, BUBBLE, OPEN, BIG }

/** Estado del apunte flotante. Lo guarda quien lo muestra (lector de PDF o de EPUB). */
class NoteState(initial: NoteMode) {
    var mode by mutableStateOf(initial)
    /** Dónde está la ventanita o la bolita (esquina de arriba a la izquierda, en px). Sin fijar = donde se dejó la última vez. */
    var pos by mutableStateOf(Offset.Unspecified)

    val isOpen get() = mode == NoteMode.OPEN || mode == NoteMode.BIG
    /** Encoger: vuelve a bolita, o a pestañita si la persona la tiene escondida. */
    fun minimize() { mode = if (Apuntes.hidden) NoteMode.TAB else NoteMode.BUBBLE }
    fun open() { if (!isOpen) mode = NoteMode.OPEN }
    fun toggle() { if (isOpen) minimize() else mode = NoteMode.OPEN }

    companion object {
        /** Igual en EPUB y PDF: la bolita, o la pestañita si se escondió. */
        fun start() = NoteState(if (Apuntes.hidden) NoteMode.TAB else NoteMode.BUBBLE)
    }
}

/** El botón 記 de los controles: abre o cierra el apunte aunque esté vacío. */
@Composable
fun NoteButton(state: NoteState, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val p = LocalPalette.current
    Box(
        Modifier.size(size).shadow(6.dp, CircleShape).clip(CircleShape).background(p.accent).clickable { state.toggle() },
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Text("記", style = Type.title.copy(fontSize = (size.value * 0.42f).sp), color = Color.White) }
}

/**
 * El apunte flotante: una ventanita de texto que no tapa toda la pantalla, para ir pegando frases
 * del libro y escribiendo al lado mientras se lee. Se arrastra a donde estorbe menos y se encoge a una bolita 記.
 * Si la bolita se arrastra contra un borde, queda escondida como una pestañita bermellón (como un shiori).
 * Ocupa toda la pantalla solo para poder moverse: lo que no es la ventanita deja pasar los toques al libro.
 */
@Composable
fun FloatingNote(bookId: String, title: String, state: NoteState, modifier: Modifier = Modifier, bottomSpace: androidx.compose.ui.unit.Dp = 96.dp) {
    if (state.mode == NoteMode.HIDDEN) return
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val flow = remember(bookId) { Apuntes.flow(bookId) }
    val text by flow.collectAsState()
    var field by remember(bookId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Llegó una frase desde el libro: el cursor queda al final, listo para escribir debajo.
    LaunchedEffect(text) { if (text != field.text) field = TextFieldValue(text, TextRange(text.length)) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val savedY = Apuntes.yFrac.takeIf { it >= 0f }?.let { it * maxH }

        // ---------- Pestañita en el borde: la bolita escondida ----------
        if (state.mode == NoteMode.TAB) {
            val tabW = with(density) { 15.dp.toPx() }
            val tabH = with(density) { 68.dp.toPx() }
            val left = Apuntes.side == 0
            val y0 = if (state.pos != Offset.Unspecified) state.pos.y else savedY ?: (maxH - tabH - with(density) { bottomSpace.toPx() })
            val y = y0.coerceIn(margin, (maxH - tabH - margin).coerceAtLeast(margin))
            val x = if (left) 0f else maxW - tabW
            val bubblePx = with(density) { 56.dp.toPx() }
            fun showBubble() {
                Apuntes.hidden = false
                state.pos = Offset(if (left) margin else maxW - bubblePx - margin, y)
                state.mode = NoteMode.BUBBLE
            }
            var pulled by remember { mutableStateOf(0f) }
            Box(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.then(com.sebas.zerkalo.reader.Floaters.mark("nota")).size(15.dp, 68.dp)
                    .shadow(8.dp, if (left) RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp) else RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .clip(if (left) RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp) else RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .background(p.accent)
                    .clickable { showBubble() }
                    .pointerInput(left) {
                        detectDragGestures(onDragEnd = { if (kotlin.math.abs(pulled) > 30f) showBubble(); pulled = 0f }) { change, amount ->
                            change.consume()
                            pulled += if (left) amount.x else -amount.x
                            val ny = ((if (state.pos != Offset.Unspecified) state.pos.y else y) + amount.y).coerceIn(margin, maxH - tabH - margin)
                            state.pos = Offset(x, ny)
                            Apuntes.yFrac = ny / maxH
                        }
                    },
            )
            return@BoxWithConstraints
        }

        val big = state.mode == NoteMode.BIG
        val bubble = state.mode == NoteMode.BUBBLE
        val w by animateDpAsState(if (bubble) 56.dp else if (big) maxWidth - 24.dp else minOf(280.dp, maxWidth * 0.74f), label = "w")
        val h by animateDpAsState(if (bubble) 56.dp else if (big) maxHeight * 0.62f else minOf(320.dp, maxHeight * 0.48f), label = "h")
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }
        // La bolita puede llegar hasta el borde (ahí se esconde); la ventanita queda con margen.
        val mx = if (bubble) 0f else margin
        val defX = if (Apuntes.side == 0) margin else maxW - wPx - margin
        val start = if (state.pos == Offset.Unspecified) Offset(defX, savedY ?: (maxH - hPx - with(density) { bottomSpace.toPx() })) else state.pos
        // Siempre dentro de la pantalla (también cuando sale el teclado y el espacio se achica)
        val x = start.x.coerceIn(mx, (maxW - wPx - mx).coerceAtLeast(mx))
        val y = start.y.coerceIn(margin, (maxH - hPx - margin).coerceAtLeast(margin))
        val edge = with(density) { 6.dp.toPx() }
        val drag = Modifier.pointerInput(bookId, wPx, hPx, maxW, maxH, bubble) {
            detectDragGestures(
                onDragEnd = {
                    val cur = state.pos
                    if (cur != Offset.Unspecified) Apuntes.yFrac = cur.y / maxH
                    // Bolita contra el borde: se esconde como pestañita de ese lado
                    if (bubble && cur != Offset.Unspecified) {
                        if (cur.x <= edge) { Apuntes.side = 0; Apuntes.hidden = true; state.mode = NoteMode.TAB }
                        else if (cur.x >= maxW - wPx - edge) { Apuntes.side = 1; Apuntes.hidden = true; state.mode = NoteMode.TAB }
                    }
                },
            ) { change, amount ->
                change.consume()
                val c = if (state.pos == Offset.Unspecified) Offset(x, y) else state.pos
                state.pos = Offset(
                    (c.x + amount.x).coerceIn(mx, (maxW - wPx - mx).coerceAtLeast(mx)),
                    (c.y + amount.y).coerceIn(margin, (maxH - hPx - margin).coerceAtLeast(margin)),
                )
            }
        }
        val box = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.then(com.sebas.zerkalo.reader.Floaters.mark("nota")).size(w, h)

        if (bubble) {
            val n = Apuntes.quotes(text)
            Box(box.then(drag)) {
                Box(
                    Modifier.fillMaxSize().shadow(10.dp, CircleShape).clip(CircleShape).background(p.accent)
                        .clickable { state.mode = NoteMode.OPEN },
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Text("記", style = Type.title.copy(fontSize = 22.sp), color = Color.White) }
                if (n > 0) Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).clip(RoundedCornerShape(9.dp)).background(p.text).padding(horizontal = 6.dp, vertical = 1.dp),
                ) { androidx.compose.material3.Text("$n", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.bg) }
            }
            return@BoxWithConstraints
        }

        val paper = if (p.isDark) p.surface else Color(0xFFF3EBDD)
        val head = if (p.isDark) p.card else Color(0xFFE5D8C3)
        Column(
            box.shadow(18.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(paper)
                .border(1.dp, p.line, RoundedCornerShape(20.dp)),
        ) {
            // Cabecera: se arrastra desde aquí
            Row(Modifier.fillMaxWidth().background(head).then(drag).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text("Apunte", style = Type.ui.copy(fontSize = 13.sp), color = p.text, maxLines = 1)
                androidx.compose.material3.Text(
                    " · $title", style = Type.uiSmall.copy(fontSize = 12.sp), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HeadButton("minus", "Encoger") { state.minimize() }
                HeadButton("full", if (big) "Achicar" else "Agrandar") { state.mode = if (big) NoteMode.OPEN else NoteMode.BIG }
            }
            // El texto: se escribe directo, como en un bloc de notas
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (field.text.isEmpty()) androidx.compose.material3.Text(
                    "Escribe aquí mientras lees.\nO elige un texto del libro y toca «Al apunte».",
                    style = Type.body.copy(fontSize = 14.sp, lineHeight = 21.sp), color = p.text2.copy(alpha = 0.75f),
                )
                BasicTextField(
                    field, { field = it; Apuntes.set(bookId, it.text) },
                    textStyle = Type.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = p.text),
                    cursorBrush = SolidColor(p.accent), visualTransformation = QuoteLines(p.text2),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FootChip("Copiar todo") {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Apunte", field.text))
                    say(ctx, "Apunte copiado")
                }
                Spacer(Modifier.width(6.dp))
                FootChip("Compartir .txt") { shareTxt(ctx, title, field.text) }
            }
        }
    }
}

@Composable
private fun HeadButton(icon: String, desc: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        ZIcon(icon, p.text, 17.dp)
    }
}

@Composable
private fun FootChip(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(p.text.copy(alpha = 0.07f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        androidx.compose.material3.Text(label, style = Type.uiSmall.copy(fontSize = 12.sp), color = p.text2)
    }
}

/** Las frases del libro (líneas que empiezan con "› ") se ven en cursiva y un poco más suaves, con su marca bermellón. */
private class QuoteLines(private val soft: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildAnnotatedString {
            append(s)
            var i = 0
            for (line in s.split('\n')) {
                if (line.startsWith(Apuntes.QUOTE)) {
                    addStyle(SpanStyle(color = Color(0xFFC4432A)), i, i + 1)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = soft), i + 1, i + line.length)
                }
                i += line.length + 1
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

/** Manda el apunte como un archivo .txt con el nombre del libro. */
fun shareTxt(ctx: Context, title: String, text: String) {
    if (text.isBlank()) { say(ctx, "El apunte está vacío"); return }
    runCatching {
        val dir = java.io.File(ctx.cacheDir, "compartir").apply { mkdirs() }
        val name = ("Apunte - " + title.take(60)).replace(Regex("[\\\\/:*?\"<>|]"), " ").trim() + ".txt"
        val out = java.io.File(dir, name)
        out.writeText(text)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", out)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartir apunte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { say(ctx, "No se pudo compartir el apunte") }
}

private fun say(ctx: Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}
