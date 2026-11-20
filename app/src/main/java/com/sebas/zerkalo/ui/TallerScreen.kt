package com.sebas.zerkalo.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** Pestaña Taller: encuaderna el último libro abierto, o elige otro. */
@Composable
fun TallerTab(haze: HazeState, books: List<Book>) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sorted = books.sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Caption("Encuadernación japonesa")
                    Text("Taller", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Viste cualquier libro con washi, costura y etiqueta daisen. Elige uno:",
                        style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (sorted.isEmpty()) item {
                Card(Modifier.padding(16.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Primero agrega un libro para encuadernarlo.", style = Type.ui, color = p.text2, textAlign = TextAlign.Center)
                        Gap(14)
                        PillButton("Abrir el Tansu", { a.goTab(1) }, ghost = true)
                    }
                }
            } else item {
                Shelf(Modifier.padding(top = 24.dp)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        items(sorted, key = { it.id }) { b ->
                            BookCover(b, 100.dp, 148.dp, Modifier.clickable { a.open(Screen.Taller(b.id)) })
                        }
                    }
                }
            }
        }
        ProgressiveEdge(haze, top = true, height = top + 26.dp, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
fun TallerScreen(id: String) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val ctx = LocalContext.current
    val books by Library.books.collectAsState()
    val book = books.firstOrNull { it.id == id } ?: return
    val b = book.binding
    val washi = Taller.WASHI.getOrElse(b.washi) { Taller.WASHI[0] }
    val thread = Taller.THREAD.getOrElse(b.thread) { Taller.THREAD[0] }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    fun set(nb: com.sebas.zerkalo.data.Binding) = Library.update(id) { it.copy(binding = nb) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(washi.color.copy(alpha = 0.32f), washi.color.copy(alpha = 0.06f), Color.Transparent)))) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 8.dp, bottom = 50.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleButton("back", a.back)
                    Text("Taller", style = Type.bookTitle, color = p.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    CircleButton("share", {
                        val text = "Mi encuadernación de «${book.title}» en Zerkalo 鏡\nWashi ${washi.name} · ${Taller.PATTERNS[b.pattern]} · hilo ${thread.name.substringBefore(" ·")}"
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir"))
                    })
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(170.dp, 248.dp)) {
                        BookCover(book.copy(useBinding = true), 170.dp, 248.dp, elevation = 20.dp)
                    }
                }
            }
            item {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column {
                        Label("Washi", washi.name)
                        Swatches(Taller.WASHI.map { it.color }, b.washi) { set(b.copy(washi = it)) }
                        Label("Patrón")
                        Segmented(Taller.PATTERNS, b.pattern, { set(b.copy(pattern = it)) })
                        Label("Hilo", thread.name)
                        Swatches(Taller.THREAD.map { it.color }, b.thread) { set(b.copy(thread = it)) }
                        Label("Etiqueta")
                        Segmented(Taller.LABELS.map { it.name }, b.label, { set(b.copy(label = it)) })
                        Gap(8)
                        ListRow("Usar en mi estante", "En lugar de la portada original") {
                            ZSwitch(book.useBinding) { on -> Library.update(id) { it.copy(useBinding = on) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String, sub: String = "") {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, style = Type.ui, color = p.text)
        if (sub.isNotEmpty()) Text("  $sub", style = Type.uiSmall, color = p.text2)
    }
}

@Composable
private fun Swatches(colors: List<Color>, selected: Int, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c)
                    .border(if (i == selected) 2.5.dp else 0.5.dp, if (i == selected) p.accent else p.line, CircleShape)
                    .clickable { onPick(i) },
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
