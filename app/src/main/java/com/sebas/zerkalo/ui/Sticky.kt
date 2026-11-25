package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Mark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Papel de las notas adhesivas: amarillo de papelito, igual en todos los modos. */
val StickyPaper = Color(0xFFEFD995)
private val StickyInk = Color(0xFF3B2B1A)

/** El papelito pequeño pegado en la página. Al tocarlo se agranda. */
@Composable
fun StickyChip(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(24.dp).rotate(-5f).shadow(4.dp, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 8.dp, bottomStart = 2.dp))
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 8.dp, bottomStart = 2.dp))
            .background(StickyPaper).clickable(onClick = onClick),
    ) {
        // Rayitas de texto, para que se lea como una nota
        Column(Modifier.padding(start = 5.dp, top = 7.dp)) {
            Box(Modifier.size(12.dp, 1.5.dp).background(StickyInk.copy(alpha = 0.35f)))
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(9.dp, 1.5.dp).background(StickyInk.copy(alpha = 0.35f)))
        }
    }
}

/**
 * La nota adhesiva grande: un papel amarillo para leer o escribir. Se guarda sola al cerrar
 * (tocando afuera). Si se cierra vacía, se quita.
 */
@Composable
fun StickyEditor(mark: Mark, where: String, onSave: (String) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var text by remember(mark.id) { mutableStateOf(mark.note) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(mark.id) { if (mark.note.isEmpty()) runCatching { focus.requestFocus() } }
    fun close() { if (text.isBlank()) onDelete() else { if (text != mark.note) onSave(text); onClose() } }
    Box(
        Modifier.fillMaxSize().background(Color(0x55000000))
            .clickable(remember { MutableInteractionSource() }, null) { close() }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 34.dp).fillMaxWidth().rotate(-1.2f)
                .shadow(16.dp, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomEnd = 18.dp, bottomStart = 3.dp))
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomEnd = 18.dp, bottomStart = 3.dp))
                .background(StickyPaper)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(18.dp),
        ) {
            if (mark.text.isNotBlank()) Text(
                "«${mark.text.take(120)}${if (mark.text.length > 120) "…" else ""}»",
                style = Type.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = StickyInk.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).verticalScroll(rememberScrollState())) {
                if (text.isEmpty()) Text("Escribe tu nota…", style = Type.body.copy(fontSize = 17.sp), color = StickyInk.copy(alpha = 0.4f))
                BasicTextField(
                    text, { text = it },
                    textStyle = Type.body.copy(fontSize = 17.sp, lineHeight = 25.sp, color = StickyInk),
                    cursorBrush = SolidColor(Color(0xFFC4432A)),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(where, SimpleDateFormat("d MMM", Locale("es")).format(Date(mark.at))).filter { it.isNotBlank() }.joinToString(" · "),
                    style = Type.uiSmall.copy(fontSize = 11.sp), color = StickyInk.copy(alpha = 0.6f), modifier = Modifier.weight(1f),
                )
                Text(
                    "Quitar", style = Type.uiSmall.copy(fontSize = 12.sp), color = Color(0xFFA0331F),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "Listo", style = Type.uiSmall.copy(fontSize = 12.sp), color = StickyInk,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { close() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
