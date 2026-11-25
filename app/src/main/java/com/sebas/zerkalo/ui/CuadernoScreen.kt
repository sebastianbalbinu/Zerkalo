package com.sebas.zerkalo.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Mark
import com.sebas.zerkalo.data.Study
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** Recuerda por dónde ibas en las listas aunque cambies de pestaña. */
object ScrollMemory {
    val notas = androidx.compose.foundation.lazy.LazyListState()
    val escuchar = androidx.compose.foundation.lazy.LazyListState()
}

/** Notas: todo lo que subrayaste, anotaste y guardaste, agrupado por libro, con su resumen de estudio. */
@Composable
fun NotasScreen(haze: HazeState) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val marks by Library.marks.collectAsState()
    val sessions by Library.sessions.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var query by rememberSaveable { mutableStateOf("") }
    var summaryFor by rememberSaveable { mutableStateOf<String?>(null) }

    val byId = remember(books) { books.associateBy { it.id } }
    val notes = remember(marks) { marks.filter { it.type == "hl" || it.type == "word" || it.type == "sticky" } }
    // Libros con algo guardado, el más reciente primero
    val groups = remember(notes, byId) {
        notes.groupBy { it.bookId }.mapNotNull { (id, xs) -> byId[id]?.let { it to xs } }.sortedByDescending { (_, xs) -> xs.maxOf { it.at } }
    }
    val found = remember(notes, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) emptyList() else notes.filter { (it.text + " " + it.note + " " + it.context).lowercase().contains(q) }.sortedByDescending { it.at }
    }
    val done = remember(books) { books.filter { it.finished }.sortedByDescending { it.finishedAt } }
    val week = remember(sessions) { (sessions.filter { System.currentTimeMillis() - it.at < 7 * 86_400_000L }.sumOf { it.ms } / 60_000).toInt() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().hazeSource(haze), state = ScrollMemory.notas, contentPadding = PaddingValues(top = top + 18.dp, bottom = 150.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Caption("Tarjetas, subrayados y palabras")
                        Text("Estudio", style = Type.display, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    }
                    CircleButton("gear", { a.open(Screen.Settings) })
                }
            }
            item(key = "repaso") { StudyBox(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) }
            item(key = "stats") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Esta semana", if (week < 60) "$week min" else "${week / 60} h ${week % 60}", Modifier.weight(1f))
                    Stat("Palabras", notes.count { it.type == "word" }.toString(), Modifier.weight(1f))
                    Stat("Terminados", done.size.toString(), Modifier.weight(1f))
                }
            }
            item(key = "stats2") { ReadingStats(sessions, books) }
            if (notes.isNotEmpty()) item(key = "search") {
                SearchField(query, { query = it }, "Buscar en tus notas", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            }

            when {
                notes.isEmpty() -> item(key = "empty") {
                    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), PaddingValues(22.dp)) {
                        Column {
                            Text("Aquí se juntan tus notas", style = Type.title, color = p.text)
                            Help("Mantén presionado el texto para subrayar o escribir una nota.")
                            Help("Activa el modo estudio (文A arriba en el lector) y toca una palabra para traducirla y guardarla.")
                            Help("Cada libro tendrá su resumen de estudio, listo para compartir o guardar en PDF.")
                        }
                    }
                }
                query.isNotBlank() -> {
                    if (found.isEmpty()) item(key = "none") {
                        Text("Nada con «$query».", style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
                    }
                    items(found.take(100), key = { it.id }) { m -> NoteRow(m, byId[m.bookId]?.title.orEmpty(), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) }
                }
                else -> items(groups, key = { it.first.id }) { (b, xs) ->
                    BookNotes(b, xs) { summaryFor = b.id }
                }
            }

            if (done.isNotEmpty() && query.isBlank()) {
                item(key = "done-t") { SectionTitle("Terminados", "su sello está en Sellos", modifier = Modifier.padding(top = 30.dp)) }
                item(key = "done") {
                    val stamps = remember(books) { com.sebas.zerkalo.data.Stamps.bookStamps(books).reversed() }
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(stamps, key = { it.book.id }) { s ->
                            Column(Modifier.width(96.dp).clickable { a.goTab(4) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Hanko(s.kanji, 76.dp, ink = s.ink, wax = s.wax, tilt = listOf(-4f, 3f, -2f, 4f, -3f)[(s.book.id.hashCode() and 0xff) % 5], seed = s.book.id.hashCode())
                                Text(s.book.title, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        CollapsingTop("Estudio", ScrollMemory.notas, Modifier.align(Alignment.TopCenter))
        ProgressiveEdge(haze, top = false, height = 150.dp, modifier = Modifier.align(Alignment.BottomCenter))
    }

    summaryFor?.let { id -> byId[id]?.let { StudySheet(it, marks) { summaryFor = null } } }
}

@Composable
private fun Help(t: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 10.dp)) {
        Text("·", style = Type.ui, color = p.accent, modifier = Modifier.padding(end = 8.dp))
        Text(t, style = Type.ui, color = p.text2)
    }
}

@Composable
private fun BookNotes(b: Book, xs: List<Mark>, onSummary: () -> Unit) {
    val p = LocalPalette.current
    // En el orden del libro, no en el orden en que se subrayaron.
    val hls = xs.filter { it.type == "hl" || it.type == "sticky" }.sortedBy { it.progress }
    val words = xs.count { it.type == "word" }
    var open by remember { mutableStateOf(false) }
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), PaddingValues(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(b, 46.dp, 68.dp, elevation = 3.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(b.title, style = Type.ui.copy(fontSize = 16.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            hls.size.takeIf { it > 0 }?.let { if (it == 1) "1 subrayado" else "$it subrayados" },
                            words.takeIf { it > 0 }?.let { if (it == 1) "1 palabra" else "$it palabras" },
                        ).joinToString(" · "),
                        style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            hls.take(if (open) 30 else 2).forEach { m -> NoteRow(m, "", Modifier.padding(top = 12.dp), bare = true) }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Resumen de estudio", onSummary, Modifier.weight(1f), icon = "notas")
                if (hls.size > 2) PillButton(if (open) "Menos" else "Ver todo", { open = !open }, ghost = true)
            }
        }
    }
}

@Composable
private fun NoteRow(m: Mark, book: String, modifier: Modifier, bare: Boolean = false) {
    val p = LocalPalette.current
    val body: @Composable () -> Unit = {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (m.type == "word") p.accent else if (m.type == "sticky") StickyPaper else highlightColor(m.color)))
            Column(Modifier.padding(start = 12.dp)) {
                if (m.type == "word") {
                    Text(m.text, style = Type.ui.copy(fontSize = 16.sp), color = p.text)
                    Text(m.note, style = Type.ui, color = p.text2)
                } else if (m.type == "sticky") {
                    // Nota adhesiva: primero lo que escribiste, debajo la frase o la página donde quedó
                    Text(m.note, style = Type.body.copy(fontSize = 15.sp), color = p.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (m.text.isNotBlank()) "«${m.text.take(80)}» · " else "") + m.chapter,
                        style = Type.uiSmall, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text("«${m.text.take(240)}»", style = Type.body.copy(fontSize = 15.sp), color = p.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    if (m.note.isNotBlank()) Text(m.note, style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp))
                }
                if (book.isNotBlank()) Text(book, style = Type.caption.copy(letterSpacing = 1.sp), color = p.text2, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (bare) Box(modifier) { body() } else Card(modifier, PaddingValues(14.dp)) { body() }
}

/** Estadísticas de lectura: la semana día por día, racha, mes, año y a qué hora lees más. */
@Composable
private fun ReadingStats(sessions: List<com.sebas.zerkalo.data.Session>, books: List<Book>) {
    val p = LocalPalette.current
    val st = remember(sessions, books) { computeStats(sessions, books) }
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), PaddingValues(18.dp)) {
        Column {
            Caption("Tu lectura")
            // Últimos 7 días
            val max = (st.week.maxOrNull() ?: 0).coerceAtLeast(10)
            Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                st.week.forEachIndexed { i, m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        if (m > 0) Text("$m", style = Type.uiSmall.copy(fontSize = 10.sp), color = p.text2)
                        Box(
                            Modifier.padding(top = 3.dp).width(16.dp).height((70f * m / max).coerceAtLeast(3f).dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i == 6) p.accent else p.text2.copy(alpha = if (m > 0) 0.55f else 0.18f)),
                        )
                        Text(st.dayNames[i], style = Type.uiSmall.copy(fontSize = 10.5.sp), color = if (i == 6) p.text else p.text2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Text("minutos por día", style = Type.uiSmall.copy(fontSize = 10.5.sp), color = p.text2, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Racha", if (st.streak == 1) "1 día" else "${st.streak} días", Modifier.weight(1f))
                MiniStat("Este mes", hm(st.monthMin), Modifier.weight(1f))
                MiniStat("Este año", hm(st.yearMin), Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Libros del año", st.yearBooks.toString(), Modifier.weight(1f))
                MiniStat("Racha más larga", "${st.bestStreak} d", Modifier.weight(1f))
                MiniStat("Lees más", st.favTime, Modifier.weight(1f))
            }
            st.topBook?.let { Text("Lo que más leíste este mes: $it", style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(value, style = Type.title.copy(fontSize = 17.sp), color = p.text, maxLines = 1)
        Text(label, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, maxLines = 1)
    }
}

private fun hm(m: Int) = if (m < 60) "$m min" else "${m / 60} h ${m % 60}"

private data class Stats(
    val week: List<Int>, val dayNames: List<String>, val streak: Int, val bestStreak: Int, val monthMin: Int, val yearMin: Int,
    val yearBooks: Int, val favTime: String, val topBook: String?,
)

private fun computeStats(sessions: List<com.sebas.zerkalo.data.Session>, books: List<Book>): Stats {
    val cal = java.util.Calendar.getInstance()
    fun dayKey(t: Long): Int { cal.timeInMillis = t; return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR) }
    val byDay = HashMap<Int, Long>()
    sessions.forEach { byDay[dayKey(it.at)] = (byDay[dayKey(it.at)] ?: 0L) + it.ms }
    val now = System.currentTimeMillis()
    val names = listOf("D", "L", "M", "X", "J", "V", "S")
    val week = (6 downTo 0).map { d -> ((byDay[dayKey(now - d * 86_400_000L)] ?: 0L) / 60_000).toInt() }
    val dayNames = (6 downTo 0).map { d -> cal.timeInMillis = now - d * 86_400_000L; names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] }
    // Racha: días seguidos con al menos 1 minuto (hoy cuenta si ya leíste; si no, desde ayer)
    var streak = 0
    var d = if ((byDay[dayKey(now)] ?: 0L) >= 60_000) 0 else 1
    while ((byDay[dayKey(now - d * 86_400_000L)] ?: 0L) >= 60_000) { streak++; d++ }
    var best = 0; var cur = 0; var prev = -1L
    byDay.filterValues { it >= 60_000 }.keys.sorted().forEach { k ->
        // Días consecutivos dentro del mismo año o en el cambio de año
        val kl = k.toLong()
        val consecutive = prev >= 0 && (kl - prev == 1L || (kl / 1000 - prev / 1000 == 1L && kl % 1000 == 1L))
        cur = if (consecutive) cur + 1 else 1; best = maxOf(best, cur); prev = kl
    }
    cal.timeInMillis = now
    val y = cal.get(java.util.Calendar.YEAR); val mo = cal.get(java.util.Calendar.MONTH)
    var month = 0L; var year = 0L
    val hours = IntArray(4)
    val perBook = HashMap<String, Long>()
    sessions.forEach { s ->
        cal.timeInMillis = s.at
        if (cal.get(java.util.Calendar.YEAR) == y) {
            year += s.ms
            if (cal.get(java.util.Calendar.MONTH) == mo) { month += s.ms; perBook[s.bookId] = (perBook[s.bookId] ?: 0L) + s.ms }
        }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        hours[when (h) { in 5..11 -> 0; in 12..17 -> 1; in 18..22 -> 2; else -> 3 }] += (s.ms / 60_000).toInt()
    }
    val fav = if (hours.all { it == 0 }) "—" else listOf("Mañana", "Tarde", "Noche", "Madrugada")[hours.indices.maxBy { hours[it] }]
    val yearBooks = books.count { b -> b.finished && run { cal.timeInMillis = b.finishedAt; cal.get(java.util.Calendar.YEAR) == y } }
    val top = perBook.maxByOrNull { it.value }?.key?.let { id -> books.firstOrNull { it.id == id }?.title }
    return Stats(week, dayNames, streak, maxOf(best, streak), (month / 60_000).toInt(), (year / 60_000).toInt(), yearBooks, fav, top)
}

/** Vista previa del resumen de estudio, con compartir y guardar en PDF. */
@Composable
private fun StudySheet(b: Book, marks: List<Mark>, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = remember(b, marks) { Study.of(b, marks) }
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Caption("Resumen de estudio")
            Text(b.title, style = Type.title, color = p.text, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${s.highlights} subrayados · ${s.notes} notas · ${s.words.size} palabras · ${(b.progress * 100).toInt()} % leído",
                style = Type.uiSmall, color = p.text2, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Guardar PDF", { (ctx as? Activity)?.let { Study.pdf(it, s) } }, Modifier.weight(1f), icon = "download")
                PillButton("Compartir", { Study.share(ctx, s) }, Modifier.weight(1f), ghost = true, icon = "share")
            }
            Spacer(Modifier.height(16.dp))
            Segmented(listOf("Subrayados · ${s.highlights}", "Vocabulario · ${s.words.size}"), tab, { tab = it })
            LazyColumn(Modifier.heightIn(max = 470.dp).padding(top = 8.dp)) {
                if (tab == 0) {
                    if (s.chapters.isEmpty()) item { Text("Todavía no subrayaste nada en este libro.", style = Type.ui, color = p.text2, modifier = Modifier.padding(12.dp)) }
                    s.chapters.forEach { c ->
                        item { Text(c.title, style = Type.ui.copy(fontSize = 15.sp), color = p.accent, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                        items(c.marks, key = { it.id }) { m -> NoteRow(m, "", Modifier.padding(top = 8.dp), bare = true) }
                    }
                } else {
                    if (s.words.isEmpty()) item { Text("En el lector, activa el modo estudio (文A) y toca una palabra para guardarla aquí.", style = Type.ui, color = p.text2, modifier = Modifier.padding(12.dp)) }
                    items(s.words, key = { it.id }) { w ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(w.text, style = Type.ui.copy(fontSize = 16.sp), color = p.text)
                                Text("  ${w.note}", style = Type.ui, color = p.text2, modifier = Modifier.weight(1f))
                                Text("Quitar", style = Type.uiSmall, color = p.text2.copy(alpha = 0.7f), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Library.removeMark(w.id) }.padding(4.dp))
                            }
                            if (w.context.isNotBlank()) Text(w.context.take(220), style = Type.uiSmall.copy(fontStyle = FontStyle.Italic), color = p.text2.copy(alpha = 0.85f), modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val p = LocalPalette.current
    Card(modifier, PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column {
            Caption(label)
            Text(value, style = Type.title.copy(fontSize = 20.sp), color = p.text, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        }
    }
}

/** Color de un subrayado: los antiguos guardaban un índice (0..3); los nuevos, el color exacto (ARGB). */
fun highlightColor(i: Int): Color =
    if (i in 0..15) Color(com.sebas.zerkalo.data.InkColor.DEFAULTS.getOrElse(i) { com.sebas.zerkalo.data.InkColor.DEFAULTS[0] }.argb) else Color(i)

/** Nombre del color de un subrayado según tus colores. */
fun inkName(i: Int): String {
    val argb = if (i in 0..15) com.sebas.zerkalo.data.InkColor.DEFAULTS.getOrElse(i) { com.sebas.zerkalo.data.InkColor.DEFAULTS[0] }.argb else i
    return com.sebas.zerkalo.data.Prefs.inks.value.firstOrNull { it.argb == argb }?.name ?: ""
}
