package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Library
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun CuadernoScreen(haze: HazeState) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val sessions by Library.sessions.collectAsState()
    val marks by Library.marks.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val stats = remember(sessions) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH); val year = cal.get(Calendar.YEAR)
        fun mins(xs: List<com.sebas.zerkalo.data.Session>) = (xs.sumOf { it.ms } / 60000).toInt()
        Triple(
            mins(sessions.filter { now - it.at < 7 * 86_400_000L }),
            mins(sessions.filter { val c = Calendar.getInstance().apply { timeInMillis = it.at }; c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year }),
            sessions.map { it.date }.toSet().size,
        )
    }
    val done = remember(books) { books.filter { it.finished }.sortedByDescending { it.finishedAt } }
    val hls = remember(marks) { marks.filter { it.type == "hl" }.sortedByDescending { it.at } }
    val byId = remember(books) { books.associateBy { it.id } }
    fun fmt(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Caption("Tu historia como lector")
                        Text("Cuaderno", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    }
                    CircleButton("gear", { a.open(Screen.Settings) })
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Esta semana", fmt(stats.first), Modifier.weight(1f))
                        Stat("Este mes", fmt(stats.second), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Terminados", done.size.toString(), Modifier.weight(1f))
                        Stat("Días leyendo", stats.third.toString(), Modifier.weight(1f))
                    }
                }
            }
            item { SectionTitle("Sellos", if (done.isEmpty()) "" else "un sello por libro leído", modifier = Modifier.padding(top = 30.dp)) }
            if (done.isEmpty()) item {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Text("Cuando termines un libro, su sello bermellón quedará aquí.", style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            } else {
                items(done.chunked(3).size) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        done.chunked(3)[row].forEachIndexed { i, b ->
                            Column(Modifier.width(100.dp).clickable { a.open(Screen.BookDetail(b.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Seal(b.title.take(38), 88.dp, listOf(-4f, 3f, -2f, 4f, -3f)[(row * 3 + i) % 5], fontSize = if (b.title.length > 22) 10.5f else if (b.title.length > 12) 11.5f else 13.5f)
                                Text(SimpleDateFormat("MMM yyyy", Locale("es")).format(b.finishedAt), style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Subrayados", if (hls.isEmpty()) "" else hls.size.toString(), modifier = Modifier.padding(top = 30.dp)) }
            if (hls.isEmpty()) item {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Text("Mientras lees, mantén presionado el texto para subrayar o dejar una nota.", style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            } else items(hls.take(60).size) { i ->
                val h = hls[i]
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .clickable { byId[h.bookId]?.let { a.open(Screen.BookDetail(it.id)) } },
                ) {
                    Card(padding = PaddingValues(16.dp)) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(highlightColor(h.color)))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("«${h.text.take(240)}»", style = Type.body, color = p.text)
                                if (h.note.isNotBlank()) Text(h.note, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 6.dp))
                                Text(byId[h.bookId]?.title ?: "", style = Type.caption.copy(letterSpacing = 1.sp), color = p.text2, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
        ProgressiveEdge(haze, top = true, height = top + 26.dp, modifier = Modifier.align(Alignment.TopCenter))
        ProgressiveEdge(haze, top = false, height = 150.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column {
            Caption(label)
            Text(value, style = Type.title, color = p.text, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Colores de subrayado: kin (oro), koke (musgo), shu (bermellón), ai (índigo). */
fun highlightColor(i: Int): Color = listOf(Color(0xFFE2BE52), Color(0xFF8CA05A), Color(0xFFD9694F), Color(0xFF6F8DBF)).getOrElse(i) { Color(0xFFE2BE52) }
