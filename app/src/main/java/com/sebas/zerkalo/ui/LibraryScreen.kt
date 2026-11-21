package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(haze: HazeState, onReady: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val sorted = remember(books) { books.sortedByDescending { maxOf(it.lastOpened, it.addedAt) } }
    val list = remember(sorted, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) sorted else sorted.filter { (it.title + " " + it.author).lowercase().contains(q) }
    }
    val reading = list.filter { it.reading }
    val unread = list.filter { !it.reading && !it.finished }
    val done = list.filter { it.finished }
    val current = if (query.isBlank()) reading.firstOrNull() else null

    LaunchedEffect(Unit) { onReady() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze),
            contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Caption(SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(Date()))
                        Text("Biblioteca", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    }
                    CircleButton("search", { searching = !searching; if (!searching) query = "" })
                    Spacer(Modifier.width(10.dp))
                    CircleButton("gear", { a.open(Screen.Settings) })
                }
            }
            if (searching) item(key = "search") { SearchField(query, { query = it }, "Título o autor", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }

            if (books.isEmpty()) {
                item(key = "empty") { EmptyLibrary() }
            } else {
                current?.let { b -> item(key = "cont") { ContinueCard(b) } }
                val rest = if (current != null) reading.drop(1) else reading
                if (rest.isNotEmpty()) item(key = "s-reading") { ShelfRow("Leyendo", count(reading.size), rest) }
                item(key = "s-unread") { ShelfRow("Por leer", if (unread.isEmpty()) "" else count(unread.size), unread, addSlot = query.isBlank()) }
                if (done.isNotEmpty()) item(key = "s-done") { ShelfRow("Terminados", count(done.size), done) }
                if (query.isNotBlank() && list.isEmpty()) item(key = "none") {
                    Text("Nada con «$query» en tu estante.", style = Type.ui, color = p.text2, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center)
                }
            }
        }
        ProgressiveEdge(haze, top = true, height = top + 26.dp, modifier = Modifier.align(Alignment.TopCenter))
        ProgressiveEdge(haze, top = false, height = 150.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun count(n: Int) = if (n == 1) "1 libro" else "$n libros"

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(p.surface.copy(alpha = 0.92f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZIcon("search", p.text2, 19.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.ui.copy(fontSize = 16.sp), color = p.text2.copy(alpha = 0.8f))
            BasicTextField(
                value, onChange, singleLine = true, textStyle = Type.ui.copy(fontSize = 16.sp, color = p.text),
                cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
            )
        }
        if (value.isNotEmpty()) ZIcon("close", p.text2, 18.dp, modifier = Modifier.clickable { onChange("") })
    }
}

@Composable
private fun EmptyLibrary() {
    val p = LocalPalette.current
    val a = LocalActions.current
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp), PaddingValues(28.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            EnsoImage(p.isDark, 84.dp)
            Gap(14)
            Text("Tu estante está esperando", style = Type.title, color = p.text, textAlign = TextAlign.Center)
            Gap(8)
            Text(
                "Trae tus propios EPUB o busca en el Tansu entre las bibliotecas libres del mundo.",
                style = Type.body, color = p.text2, textAlign = TextAlign.Center,
            )
            Gap(20)
            PillButton("Importar EPUB", a.importEpub, icon = "import", modifier = Modifier.fillMaxWidth())
            Gap(10)
            PillButton("Abrir el Tansu", { a.goTab(1) }, ghost = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ContinueCard(b: Book) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val pct = (b.progress * 100).toInt()
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), PaddingValues(18.dp)) {
        Column {
            Row {
                BookCover(b, 96.dp, 140.dp, Modifier.clickable { a.open(Screen.BookDetail(b.id)) }, ribbon = true, elevation = 10.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).height(140.dp)) {
                    Caption("Leyendo", accent = true)
                    Text(b.title, style = Type.title.copy(fontSize = 21.sp, lineHeight = 26.sp), color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(b.author, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(b.progress.toFloat(), Modifier.weight(1f))
                        Text("$pct %", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            Gap(16)
            PillButton("Seguir leyendo", { a.read(b) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ShelfRow(title: String, more: String, books: List<Book>, addSlot: Boolean = false) {
    val p = LocalPalette.current
    val a = LocalActions.current
    Column(Modifier.padding(top = 30.dp)) {
        SectionTitle(title, more)
        Gap(14)
        Shelf {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(books, key = { it.id }, contentType = { "libro" }) { b ->
                    BookCover(b, 100.dp, 148.dp, Modifier.clickable { a.open(Screen.BookDetail(b.id)) }, ribbon = b.reading)
                }
                if (addSlot) item(key = "add") {
                    Column(
                        Modifier.size(100.dp, 148.dp).clip(RoundedCornerShape(6.dp)).background(p.surface.copy(alpha = 0.7f)).clickable { a.importEpub() },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        ZIcon("plus", p.text2, 24.dp)
                        Text("Importar\nEPUB", style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
