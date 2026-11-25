package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Docs
import com.sebas.zerkalo.data.Library
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Documentos: los PDF que abriste desde otra app (WhatsApp, Drive, Archivos). Aparte de los libros,
 * en una lista sencilla por fecha. Uno que sí es libro se pasa a la Biblioteca con un toque.
 */
@Composable
fun DocumentsScreen() {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val docs = remember(books) { books.filter { it.doc }.sortedByDescending { maxOf(it.lastOpened, it.addedAt) } }
    var asking by remember { mutableStateOf<Book?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 10.dp, bottom = 60.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                CircleButton("back", a.back)
                Text("Documentos", style = Type.display, color = p.text, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                Text(
                    "Los PDF que abres desde otras apps. No llenan tus estantes, y si no abres uno en ${Docs.DAYS} días se quita solo " +
                        "(si tiene notas, antes te pregunto).",
                    style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 8.dp),
                )
            }
        }
        if (docs.isEmpty()) item {
            Text("No tienes documentos.", style = Type.ui, color = p.text2, modifier = Modifier.padding(28.dp))
        }
        items(docs, key = { it.id }) { b ->
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(p.surface).clickable { a.read(b) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(b, 46.dp, 64.dp, elevation = 3.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(b.title, style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Abierto el " + SimpleDateFormat("d MMM", Locale("es")).format(Date(maxOf(b.lastOpened, b.addedAt))) +
                            if (b.progress > 0) " · ${(b.progress * 100).toInt()} %" else "",
                        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 3.dp),
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        SmallPill("A la biblioteca", false) { Docs.toLibrary(b); a.toast("«${b.title}» pasó a tu biblioteca") }
                        Spacer(Modifier.width(8.dp))
                        SmallPill("Quitar", false) { asking = b }
                    }
                }
            }
        }
    }
    asking?.let { b ->
        ConfirmDialog(
            title = "¿Quitar «${b.title}»?",
            text = "Se borra de Zerkalo junto con sus notas. El archivo original sigue donde estaba.",
            ok = "Quitar", onOk = { Library.remove(b); asking = null }, onDismiss = { asking = null },
        )
    }
}

/** Pregunta sencilla con el estilo de Zerkalo: aceptar, otra opción (opcional) y cancelar. */
@Composable
fun ConfirmDialog(
    title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit,
    other: String? = null, onOther: () -> Unit = {}, cancel: String = "Cancelar",
) {
    val p = LocalPalette.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.surface,
        title = { Text(title, style = Type.title.copy(fontSize = 19.sp), color = p.text) },
        text = { Text(text, style = Type.ui, color = p.text2) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOk) { Text(ok, style = Type.button, color = p.accent) } },
        dismissButton = {
            Row {
                if (other != null) androidx.compose.material3.TextButton(onClick = onOther) { Text(other, style = Type.button, color = p.text) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(cancel, style = Type.button, color = p.text2) }
            }
        },
    )
}
