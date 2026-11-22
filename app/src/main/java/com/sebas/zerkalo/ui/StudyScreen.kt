package com.sebas.zerkalo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Card
import com.sebas.zerkalo.data.Cards
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Mark
import com.sebas.zerkalo.data.Prefs
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

/** El recuadro de repaso arriba en Estudio. Si no hay tarjetas, solo una explicación corta. */
@Composable
fun StudyBox(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val cards by Cards.cards.collectAsState()
    val days by Cards.days.collectAsState()
    if (cards.isEmpty()) {
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.card.copy(alpha = 0.7f)).padding(18.dp)) {
            Text("Tarjetas de estudio", style = Type.title.copy(fontSize = 19.sp), color = p.text)
            Text(
                "Cuando subrayes o traduzcas algo que quieras recordar, toca «Tarjeta». Tus subrayados y notas siguen siendo solo tuyos: nada se vuelve tarjeta solo.",
                style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "¿Cómo funcionan?", style = Type.ui.copy(fontWeight = FontWeight.SemiBold), color = p.accent,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { a.open(Screen.Review(introOnly = true)) }.padding(vertical = 4.dp),
            )
        }
        return
    }
    val due = Cards.dueToday(cards).size
    val streak = Cards.streak(days)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.text).padding(20.dp)) {
        Text(
            if (due > 0) (if (due == 1) "1 tarjeta para hoy" else "$due tarjetas para hoy") else "Estás al día",
            style = Type.title.copy(fontSize = 22.sp), color = p.bg,
        )
        val mins = (due * 12 + 59) / 60
        Text(
            listOfNotNull(
                if (due > 0) "Unos ${mins.coerceAtLeast(1)} min" else "Mañana vuelven las que tocan",
                if (streak > 1) "llevas $streak días seguidos" else null,
            ).joinToString(" · "),
            style = Type.uiSmall, color = p.bg.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(p.accent).clickable { a.open(Screen.Review(practice = due == 0)) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(if (due > 0) "Repasar" else "Práctica libre", style = Type.ui, color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                "Mis tarjetas (${cards.size})", style = Type.uiSmall, color = p.bg.copy(alpha = 0.8f),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { a.open(Screen.CardList) }.padding(6.dp),
            )
        }
    }
}

private sealed class Page {
    data class Intro(val i: Int) : Page()
    data class C(val card: Card) : Page()
    data class Surprise(val mark: Mark) : Page()
    data object End : Page()
}

private val INTRO = listOf(
    "Tu memoria es como un caminito en el pasto" to "Si no lo pisas, el pasto lo tapa. Cada vez que te acuerdas de algo, el caminito queda más marcado y aguanta más días sin pisarlo.",
    "Primero intentas recordar" to "Ves la pregunta y tratas de responder de memoria, sin mirar. Ese esfuerzo es lo que fija el recuerdo: está comprobado que funciona mejor que releer.",
    "Luego dices la verdad" to "Volteas y tocas «Me acordé» o «No me acordé». Si te acordaste, la tarjeta vuelve más lejos (1, 3, 7, 16, 35 días…). Si no, vuelve mañana. Así repasas justo antes de olvidar.",
)

/** Orden de repaso: mezcla los libros (mezclar temas ayuda a recordar mejor). */
private fun interleave(list: List<Card>, r: Random): List<Card> {
    val groups = list.shuffled(r).groupBy { it.bookId }.values.map { it.toMutableList() }.toMutableList()
    val out = mutableListOf<Card>()
    while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) out += g.removeAt(0) }
    return out
}

/** Repaso en flujo vertical, como una red social, pero con un final del día. */
@Composable
fun ReviewScreen(practice: Boolean, introOnly: Boolean) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pages = remember {
        val r = Random(System.currentTimeMillis())
        val all = Cards.cards.value
        val out = mutableListOf<Page>()
        if (introOnly || !Prefs.cardsIntroSeen) INTRO.indices.forEach { out += Page.Intro(it) }
        if (!introOnly) {
            val due = Cards.dueToday(all)
            val queue = if (practice) interleave(all.filterNot { c -> due.any { it.id == c.id } }.ifEmpty { all }, r).take(15) else interleave(due, r)
            // Frases que amaste: de vez en cuando vuelven entre tarjetas (no se califican, solo se disfrutan)
            val now = System.currentTimeMillis()
            val loved = Library.marks.value.filter { it.type == "hl" && it.text.length in 25..420 }
                .let { xs -> xs.filter { now - it.at > 7 * 86_400_000L }.ifEmpty { xs } }.shuffled(r).toMutableList()
            var surprises = 0
            queue.forEachIndexed { i, c ->
                out += Page.C(c)
                if (i % 4 == 3 && loved.isNotEmpty() && surprises < 3 && r.nextFloat() < 0.6f) { out += Page.Surprise(loved.removeAt(0)); surprises++ }
            }
            out += Page.End
        }
        out.toList()
    }
    val pager = rememberPagerState { pages.size }
    val revealed = remember { mutableStateMapOf<Int, Long>() } // página → cuándo se volteó
    val answered = remember { mutableStateMapOf<Int, Boolean>() }
    val shownAt = remember { mutableStateMapOf<Int, Long>() }
    var remembered by remember { mutableIntStateOf(0) }
    var matured by remember { mutableStateOf<Card?>(null) }
    val started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val total = pages.count { it is Page.C }
    val cur = pager.currentPage
    if (cur !in shownAt) shownAt[cur] = System.currentTimeMillis()
    fun next() = scope.launch { if (pager.currentPage < pages.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) }
    val canScroll = when (val pg = pages.getOrNull(cur)) {
        is Page.C -> answered[cur] == true
        is Page.Intro -> true
        is Page.Surprise -> true
        else -> false
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        VerticalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = canScroll, beyondViewportPageCount = 1) { i ->
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            ) {
                when (val pg = pages[i]) {
                    is Page.Intro -> IntroPage(pg.i) {
                        if (pg.i == INTRO.lastIndex) Prefs.cardsIntroSeen = true
                        if (i == pages.lastIndex) a.back() else next()
                    }
                    is Page.C -> CardPage(
                        pg.card, revealed[i] != null, answered[i], practice,
                        onReveal = { if (revealed[i] == null) { revealed[i] = System.currentTimeMillis(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
                        onAnswer = { ok ->
                            if (answered[i] == null) {
                                answered[i] = ok
                                if (ok) remembered++
                                val ms = (revealed[i] ?: System.currentTimeMillis()) - (shownAt[i] ?: System.currentTimeMillis())
                                val n = Cards.review(pg.card.id, ok, ms, practice)
                                if (n != null && !practice && pg.card.box < Cards.MATURE && n.box >= Cards.MATURE) matured = n
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                next()
                            }
                        },
                        onDrop = { Cards.remove(pg.card.id); answered[i] = false; a.toast("Ya no la estudiarás. Tu subrayado sigue en el libro."); next() },
                    )
                    is Page.Surprise -> SurprisePage(pg.mark, onOpen = { Library.book(pg.mark.bookId)?.let { a.read(it) } }) { next() }
                    Page.End -> EndPage(practice, total, remembered, ((System.currentTimeMillis() - started) / 60_000L).toInt(), matured)
                }
            }
        }
        // Arriba: cerrar, título, racha y avance del día
        Column(Modifier.fillMaxWidth().background(p.bg).windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 18.dp, top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton("close", { a.back() }, size = 40.dp)
                Text(
                    if (introOnly) "¿Cómo funcionan las tarjetas?" else if (practice) "Práctica libre" else "Repaso de hoy",
                    style = Type.ui, color = p.text, modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                val streak = Cards.streak(Cards.days.collectAsState().value)
                if (streak > 0 && !introOnly) Text("🔥 $streak ${if (streak == 1) "día" else "días"}", style = Type.uiSmall, color = p.text2)
            }
            if (total > 0) {
                val done = answered.size.coerceAtMost(total)
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.line)) {
                    Box(Modifier.fillMaxWidth(done / total.toFloat()).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.text))
                }
            }
        }
    }
}

@Composable
private fun IntroPage(i: Int, onNext: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).padding(24.dp)) {
            Caption("Tarjetas de estudio · ${i + 1} de ${INTRO.size}", accent = true)
            Hanko(listOf("道", "思", "真")[i], 86.dp, Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally), tilt = listOf(-4f, 3f, -2f)[i])
            Text(INTRO[i].first, style = Type.title.copy(fontSize = 24.sp), color = p.text, modifier = Modifier.padding(top = 26.dp))
            Text(INTRO[i].second, style = Type.body.copy(fontSize = 16.sp), color = p.text2, modifier = Modifier.padding(top = 12.dp))
            if (i == INTRO.lastIndex) Text(
                "Y no es solo memorizar: al intentar recordar entiendes mejor, conectas ideas y llegas tranquilo a un examen o a una conversación.",
                style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 14.dp),
            )
        }
        PillButton(if (i == INTRO.lastIndex) "Entendido" else "Siguiente", onNext, Modifier.fillMaxWidth().padding(top = 14.dp))
    }
}

@Composable
private fun CardPage(
    c: Card, revealed: Boolean, answered: Boolean?, practice: Boolean,
    onReveal: () -> Unit, onAnswer: (Boolean) -> Unit, onDrop: () -> Unit,
) {
    val p = LocalPalette.current
    val book = remember(c.bookId) { Library.book(c.bookId) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface).clickable { onReveal() }
                .padding(24.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(if (c.kind == "word") "Palabra" else "Pregunta", accent = true, modifier = Modifier.weight(1f))
                Text("⋯", style = Type.title, color = p.text2, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { menu = !menu }.padding(horizontal = 8.dp))
            }
            if (menu) Text(
                "Ya no estudiar esto", style = Type.ui, color = p.accent,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(p.card).clickable { menu = false; onDrop() }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (c.kind == "word") {
                Text(c.front, style = Type.title.copy(fontSize = 30.sp), color = p.text, modifier = Modifier.padding(top = 18.dp))
                if (c.context.isNotBlank()) {
                    val ctx = c.context.trim()
                    val at = ctx.indexOf(c.front, ignoreCase = true)
                    Text(
                        buildAnnotatedString {
                            if (at >= 0) {
                                append("«" + ctx.substring(0, at))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = p.text)) { append(ctx.substring(at, at + c.front.length)) }
                                append(ctx.substring(at + c.front.length) + "»")
                            } else append("«$ctx»")
                        },
                        style = Type.body.copy(fontSize = 16.sp, fontStyle = FontStyle.Italic), color = p.text2, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                Text(c.front, style = Type.title.copy(fontSize = 23.sp), color = p.text, modifier = Modifier.padding(top = 18.dp))
            }
            if (revealed) {
                Box(Modifier.padding(vertical = 20.dp).fillMaxWidth().height(1.dp).background(p.line))
                Caption("Respuesta")
                Text(c.back, style = Type.body.copy(fontSize = if (c.kind == "cloze" || c.kind == "word") 22.sp else 16.sp), color = p.text, modifier = Modifier.padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(28.dp))
                Text(
                    "Piénsalo… y toca para ver la respuesta", style = Type.ui, color = p.text2, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = 0.5f)).padding(16.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(book?.title.orEmpty(), style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(revealed && answered == null, enter = fadeIn()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Answer("No me acordé", if (practice) "práctica" else "vuelve mañana", false, Modifier.weight(1f)) { onAnswer(false) }
                Answer("Me acordé", if (practice) "práctica" else "vuelve en ${Cards.nextDays(c, true)} días", true, Modifier.weight(1f)) { onAnswer(true) }
            }
        }
        if (answered != null) Text(
            "Desliza hacia arriba", style = Type.uiSmall, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
    }
}

@Composable
private fun Answer(title: String, sub: String, strong: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (strong) p.text else p.card).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Type.ui, color = if (strong) p.bg else p.text)
        Text(sub, style = Type.uiSmall.copy(fontSize = 11.sp), color = (if (strong) p.bg else p.text).copy(alpha = 0.7f))
    }
}

private fun ago(t: Long): String {
    val d = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return when {
        d < 1 -> "Hoy"
        d == 1 -> "Ayer"
        d < 30 -> "Hace $d días"
        d < 60 -> "Hace un mes"
        d < 365 -> "Hace ${d / 30} meses"
        else -> "Hace ${d / 365} ${if (d / 365 == 1) "año" else "años"}"
    }
}

@Composable
private fun SurprisePage(m: Mark, onOpen: () -> Unit, onNext: () -> Unit) {
    val p = LocalPalette.current
    val book = remember(m.bookId) { Library.book(m.bookId) }
    val ink = Color(0xFFF4ECDC)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF2E231D)).padding(26.dp).verticalScroll(rememberScrollState())) {
            Text("${ago(m.at)} subrayaste esto".uppercase(), style = Type.uiSmall.copy(fontSize = 11.sp, letterSpacing = 1.5.sp), color = Color(0xFFD8C7AE))
            Text("«${m.text.trim()}»", style = Type.body.copy(fontSize = 21.sp, fontStyle = FontStyle.Italic, lineHeight = 31.sp), color = ink, modifier = Modifier.padding(top = 18.dp))
            if (m.note.isNotBlank()) Text("Tu nota: ${m.note.trim()}", style = Type.ui, color = Color(0xFFD8C7AE), modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(20.dp))
            Text(listOfNotNull(book?.title, book?.author?.takeIf { it.isNotBlank() }).joinToString(" · "), style = Type.uiSmall, color = Color(0xFFD8C7AE))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (book != null) Answer("Abrir el libro", "seguir leyendo", false, Modifier.weight(1f), onOpen)
            Answer("Seguir", "siguiente tarjeta", true, Modifier.weight(1f), onNext)
        }
    }
}

@Composable
private fun EndPage(practice: Boolean, total: Int, remembered: Int, minutes: Int, matured: Card?) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val days by Cards.days.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Hanko("鏡", 74.dp, tilt = -4f)
        Text(if (total == 0) "No hay tarjetas para hoy" else if (practice) "Práctica terminada" else "Terminaste lo de hoy", style = Type.title.copy(fontSize = 26.sp), color = p.text, modifier = Modifier.padding(top = 16.dp))
        if (total > 0) Text(
            "$total ${if (total == 1) "tarjeta" else "tarjetas"} · ${minutes.coerceAtLeast(1)} min · recordaste $remembered",
            style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 6.dp),
        )
        // Este mes: un sello por cada día que repasaste; 休 el día de descanso (la racha no se pierde)
        val rest = remember(days) { Cards.restDays(days) }
        val cal = Calendar.getInstance()
        val month = java.text.SimpleDateFormat("LLLL", java.util.Locale("es")).format(cal.time).replaceFirstChar { it.uppercase() }
        val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = cal.get(Calendar.DAY_OF_MONTH)
        Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card.copy(alpha = 0.55f)).padding(16.dp)) {
            Caption(month, accent = true)
            (0 until (dim + 6) / 7).forEach { w ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..7).forEach { k ->
                        val d = w * 7 + k
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            if (d <= dim) {
                                val key = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, d)
                                when {
                                    key in days -> Hanko("判", 32.dp, seed = d)
                                    key in rest -> Hanko("休", 32.dp, ghost = true, seed = d)
                                    else -> Text("$d", style = Type.uiSmall.copy(fontSize = 11.sp), color = if (d > today) p.text2.copy(alpha = 0.35f) else p.text2)
                                }
                            }
                        }
                    }
                }
            }
            Text("判 un sello por cada día que repasaste · 休 día de descanso: la racha no se pierde", style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, modifier = Modifier.padding(top = 10.dp))
        }
        matured?.let {
            Text(
                "Una tarjeta maduró: «${it.front.take(60)}». Ya la recuerdas sin esfuerzo; vuelve en ${Cards.DAYS[it.box]} días.",
                style = Type.ui, color = p.text, modifier = Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).padding(14.dp),
            )
        }
        PillButton("Volver", { a.back() }, Modifier.fillMaxWidth().padding(top = 18.dp))
        if (!practice && Cards.cards.value.isNotEmpty()) PillButton(
            "Práctica libre (no cambia tus fechas)", { a.back(); a.open(Screen.Review(practice = true)) },
            Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true,
        )
    }
}

/** Todas tus tarjetas, para revisarlas o dejar de estudiar alguna. */
@Composable
fun CardListScreen() {
    val p = LocalPalette.current
    val a = LocalActions.current
    val cards by Cards.cards.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale("es")) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 12.dp, bottom = 120.dp, start = 16.dp, end = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleButton("back", { a.back() })
                Column(Modifier.padding(start = 10.dp)) {
                    Caption("Estudio")
                    Text("Mis tarjetas", style = Type.title, color = p.text)
                }
            }
            Text(
                "Las que se ven más lejos ya las recuerdas bien. Si una ya no te sirve, quítala: tu subrayado sigue en el libro.",
                style = Type.ui, color = p.text2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
        }
        items(cards.sortedBy { it.due }, key = { it.id }) { c ->
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card.copy(alpha = 0.6f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.front, style = Type.ui, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.back, style = Type.uiSmall, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        listOfNotNull(
                            Library.book(c.bookId)?.title,
                            if (c.box >= Cards.MATURE) "madura" else null,
                            "vuelve el " + fmt.format(java.util.Date(c.due)),
                        ).joinToString(" · "),
                        style = Type.uiSmall.copy(fontSize = 11.sp), color = p.accent, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CircleButton("trash", { Cards.remove(c.id); a.toast("Tarjeta quitada") }, size = 38.dp)
            }
        }
    }
}
