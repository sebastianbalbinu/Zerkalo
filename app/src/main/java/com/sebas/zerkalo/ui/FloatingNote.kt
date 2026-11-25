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

/** Cómo está el apunte: escondido, como bolita, como ventanita o grande para escribir con calma. */
enum class NoteMode { HIDDEN, BUBBLE, OPEN, BIG }

/** Estado del apunte flotante. Lo guarda quien lo muestra (lector de PDF o de EPUB). */
class NoteState(initial: NoteMode) {
    var mode by mutableStateOf(initial)
    /** Dónde está la ventanita o la bolita (esquina de arriba a la izquierda, en px). Sin fijar = abajo a la derecha. */
    var pos by mutableStateOf(Offset.Unspecified)
}

/**
 * El apunte flotante: una ventanita de texto que no tapa toda la pantalla, para ir pegando frases
 * del libro y escribiendo al lado mientras se lee. Se arrastra a donde estorbe menos y se encoge a una bolita 記.
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
        val big = state.mode == NoteMode.BIG
        val bubble = state.mode == NoteMode.BUBBLE
        val w by animateDpAsState(if (bubble) 56.dp else if (big) maxWidth - 24.dp else minOf(280.dp, maxWidth * 0.74f), label = "w")
        val h by animateDpAsState(if (bubble) 56.dp else if (big) maxHeight * 0.62f else minOf(320.dp, maxHeight * 0.48f), label = "h")
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val start = if (state.pos == Offset.Unspecified) Offset(maxW - wPx - margin, maxH - hPx - with(density) { bottomSpace.toPx() }) else state.pos
        // Siempre dentro de la pantalla (también cuando sale el teclado y el espacio se achica)
        val x = start.x.coerceIn(margin, (maxW - wPx - margin).coerceAtLeast(margin))
        val y = start.y.coerceIn(margin, (maxH - hPx - margin).coerceAtLeast(margin))
        val drag = Modifier.pointerInput(bookId, wPx, hPx, maxW, maxH) {
            detectDragGestures { change, amount ->
                change.consume()
                val cur = if (state.pos == Offset.Unspecified) Offset(x, y) else state.pos
                state.pos = Offset(
                    (cur.x + amount.x).coerceIn(margin, (maxW - wPx - margin).coerceAtLeast(margin)),
                    (cur.y + amount.y).coerceIn(margin, (maxH - hPx - margin).coerceAtLeast(margin)),
                )
            }
        }
        val box = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(w, h)

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
                HeadButton("minus", "Encoger") { state.mode = NoteMode.BUBBLE }
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
