package com.sebas.zerkalo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.zerkalo.data.Cards
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Prefs
import com.sebas.zerkalo.data.Stamps
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Colores del pasaporte: papel y tinta, iguales en cualquier tema
private val Paper = Color(0xFFF4ECDC)
private val PaperInk = Color(0xFF2E231D)
private val PaperSoft = Color(0xFF6E5E50)
private val Vermilion = Color(0xFFA3402A)

private sealed class Entry {
    abstract val at: Long
    data class B(val s: Stamps.BookStamp) : Entry() { override val at get() = s.book.finishedAt }
    data class S(val s: Stamps.Special) : Entry() { override val at get() = s.at ?: 0L }
}

private sealed class SPage {
    data object Welcome : SPage()
    data object NoBooks : SPage()
    data class Month(val month: String, val items: List<Entry>) : SPage()
}

private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale("es"))
private val shortFmt = SimpleDateFormat("d MMM", Locale("es"))
private val longFmt = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es"))
private val monthFmt = SimpleDateFormat("LLLL yyyy", Locale("es"))

private val GUIDE_TABS = listOf("Qué son", "Kanji", "Tintas", "Cera", "Especiales")

/** El libro de sellos: solo sellos, en orden de fecha. Las explicaciones viven en la Guía. */
@Composable
fun SellosScreen(haze: HazeState) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val sessions by Library.sessions.collectAsState()
    val cards by Cards.cards.collectAsState()
    val version by Stamps.version.collectAsState()
    val haptic = LocalHapticFeedback.current
    var guide by remember { mutableStateOf(if (Prefs.stampsIntroHidden) -1 else 0) } // pestaña abierta de la Guía, -1 cerrada
    var detail by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(books) { Stamps.settle(books) }
    val stamps = remember(books, version) { Stamps.bookStamps(books) }
    val (specials, fresh) = remember(books, sessions, cards, version) { Stamps.specials(books, sessions, cards) }
    LaunchedEffect(fresh) {
        if (fresh.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            a.toast("Nuevo sello: " + fresh.joinToString(", ") { "${it.kanji} ${it.name}" })
        }
    }

    val pages = remember(stamps, specials) {
        val entries = (stamps.map { Entry.B(it) } + specials.filter { it.at != null && it.id != "bienvenida" }.map { Entry.S(it) }).sortedBy { it.at }
        buildList {
            add(SPage.Welcome)
            if (stamps.isEmpty()) add(SPage.NoBooks)
            entries.groupBy { monthFmt.format(Date(it.at)) }.forEach { (m, xs) -> xs.chunked(4).forEach { add(SPage.Month(m, it)) } }
        }
    }
    // Se abre en tu último sello
    val pager = rememberPagerState(initialPage = pages.lastIndex) { pages.size }
    LaunchedEffect(pages.size) { pager.scrollToPage(pages.lastIndex) }

    Box(Modifier.fillMaxSize().hazeSource(haze)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption("Pasaporte de lectura")
                    Text("Sellos", style = Type.display, color = p.text)
                }
                PillButton("Guía", { guide = 0 }, ghost = true)
            }
            // Resumen de un vistazo; cada cuadrito abre su parte de la Guía
            val have = stamps.map { it.kanji }.toSet()
            val ink = Stamps.inkFor(stamps.size.coerceAtLeast(1))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${Stamps.KANJI.count { it.second in have }}/${Stamps.KANJI.size}", "kanji", null, Modifier.weight(1f)) { guide = 1 }
                Stat(ink.kanji, "tu tinta", Color(ink.light), Modifier.weight(1f)) { guide = 2 }
                Stat("${stamps.count { it.wax != null }}", "de cera", null, Modifier.weight(1f)) { guide = 3 }
                Stat("${specials.count { it.at != null }}/${specials.size}", "especiales", null, Modifier.weight(1f)) { guide = 4 }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp), pageSpacing = 12.dp) { i ->
                PassportPage(i, pages.size) {
                    when (val pg = pages[i]) {
                        SPage.Welcome -> WelcomePage()
                        SPage.NoBooks -> NoBooksPage()
                        is SPage.Month -> MonthPage(pg) { detail = it }
                    }
                }
            }
            Spacer(Modifier.height(104.dp))
        }
    }
    if (guide >= 0) GuideSheet(guide, stamps, specials, { guide = it }, { detail = it }) { Prefs.stampsIntroHidden = true; guide = -1 }
    detail?.let { d -> StampDetail(d) { detail = null } }
}

@Composable
private fun Stat(big: String, small: String, color: Color?, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(p.card.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(big, style = Type.title.copy(fontSize = 17.sp), color = color ?: p.text, maxLines = 1)
        Text(small, style = Type.uiSmall.copy(fontSize = 11.sp), color = p.text2, maxLines = 1)
    }
}

/** La Guía: todas las explicaciones en una hoja aparte, con pestañas. */
@Composable
private fun GuideSheet(tab: Int, stamps: List<Stamps.BookStamp>, specials: List<Stamps.Special>, onTab: (Int) -> Unit, onTap: (Any) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Text("Guía de sellos", style = Type.title.copy(fontSize = 21.sp), color = PaperInk)
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GUIDE_TABS.forEachIndexed { i, t ->
                    Text(
                        t, style = Type.uiSmall, color = if (i == tab) Paper else PaperSoft,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (i == tab) PaperInk else Color(0x14000000))
                            .clickable { onTab(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Column(Modifier.padding(top = 8.dp, bottom = 32.dp).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> IntroSheet(onClose)
                    1 -> KanjiPage(stamps)
                    2 -> InksPage(stamps.size)
                    3 -> WaxPage(stamps.filter { it.wax != null }, onTap)
                    else -> SpecialsPage(specials, onTap)
                }
            }
        }
    }
}

/** Una hoja del pasaporte: papel crema con filete rojo y número de página. */
@Composable
private fun PassportPage(i: Int, n: Int, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(vertical = 14.dp).shadow(14.dp, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp))
            .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp)).background(Paper)
            .background(Brush.horizontalGradient(listOf(Color(0x33000000), Color.Transparent), endX = 60f)),
    ) {
        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).border(1.dp, Vermilion.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
        Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 24.dp, top = 24.dp, bottom = 34.dp).verticalScroll(rememberScrollState())) { content() }
        Text("— ${i + 1} de $n —", style = Type.uiSmall.copy(fontSize = 11.sp), color = PaperSoft.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@Composable
private fun Kicker(t: String) = Text(t.uppercase(), style = Type.uiSmall.copy(fontSize = 10.5.sp, letterSpacing = 2.sp), color = Vermilion)

@Composable
private fun PTitle(t: String) = Text(t, style = Type.title.copy(fontSize = 23.sp), color = PaperInk, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun PText(t: String, top: Int = 8) = Text(t, style = Type.ui.copy(lineHeight = 20.sp), color = PaperSoft, modifier = Modifier.padding(top = top.dp))

@Composable
private fun IntroSheet(onDone: () -> Unit) {
    PTitle("Tu libro de sellos")
    PText("En Japón, un hanko es un sello de tinta roja que vale como firma. Y existe el goshuinchō: un librito donde la gente colecciona el sello de cada templo que visita, con el día escrito a mano. Cada sello es el recuerdo de un lugar al que llegaste.", 10)
    PText("Aquí, cada libro que terminas es un lugar al que llegaste. Zerkalo le pone su sello a tu pasaporte, con el nombre del libro y la fecha.")
    Text("Cómo se ganan", style = Type.title.copy(fontSize = 17.sp), color = PaperInk, modifier = Modifier.padding(top = 16.dp))
    PText("· Termina un libro: su sello lleva el kanji de su género (物 novela, 詩 poesía, 論 ensayo…).", 6)
    PText("· Tu tinta cambia con cuántos libros llevas: bermellón, índigo, negra, púrpura y oro.", 4)
    PText("· A veces sale un sello de cera, mezclado con los colores de la portada del libro. Es raro e irrepetible.", 4)
    PText("· Los sellos especiales celebran momentos: tu primer libro, otro idioma, una racha… algunos son sorpresa.", 4)
    PText("Esta guía siempre está en el botón «Guía», arriba a la derecha.", 12)
    Text(
        "Entendido", style = Type.ui, color = Paper, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PaperInk).clickable { onDone() }.padding(vertical = 12.dp),
    )
}

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Kicker("Página 1")
        PTitle("Bienvenida")
        Hanko("鏡", 170.dp, Modifier.padding(top = 34.dp), tilt = -5f, seed = 7)
        Text("Parte de Zerkalo", style = Type.title.copy(fontSize = 19.sp), color = PaperInk, modifier = Modifier.padding(top = 30.dp))
        Text(longFmt.format(Date(Stamps.since())), style = Type.ui, color = PaperSoft, modifier = Modifier.padding(top = 4.dp))
        Text(
            "«鏡 kagami: espejo. Un libro es un espejo donde uno se encuentra.»",
            style = Type.body.copy(fontSize = 15.sp, fontStyle = FontStyle.Italic), color = PaperSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 26.dp),
        )
    }
}

@Composable
private fun NoBooksPage() {
    Kicker("Tus libros")
    PTitle("Aquí irán tus sellos")
    PText("Cuando termines tu primer libro, su sello aparecerá en esta página con el nombre del libro y la fecha.", 12)
    Box(Modifier.fillMaxWidth().padding(top = 30.dp), contentAlignment = Alignment.Center) { Hanko("物", 110.dp, ghost = true) }
}

@Composable
private fun MonthPage(pg: SPage.Month, onTap: (Any) -> Unit) {
    Kicker(pg.month)
    pg.items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { e ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTap(if (e is Entry.B) e.s else (e as Entry.S).s) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (e) {
                        is Entry.B -> {
                            val s = e.s
                            Hanko(s.kanji, 104.dp, ink = s.ink, wax = s.wax, tilt = listOf(-4f, 3f, -2f, 4f, -3f)[(s.book.id.hashCode() and 0xff) % 5], seed = s.book.id.hashCode())
                            Text(s.book.title, style = Type.body.copy(fontSize = 14.sp), color = PaperInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(
                                listOf(s.book.author, shortFmt.format(Date(s.book.finishedAt))).filter { it.isNotBlank() }.joinToString(" · "),
                                style = Type.uiSmall.copy(fontSize = 11.sp), color = PaperSoft, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(if (s.wax != null) "sello de cera · nº ${s.n}" else "${s.kanji} ${s.genre} · nº ${s.n}", style = Type.uiSmall.copy(fontSize = 11.sp), color = PaperSoft, textAlign = TextAlign.Center)
                        }
                        is Entry.S -> {
                            val s = e.s
                            Box(Modifier.height(104.dp), contentAlignment = Alignment.Center) { Hanko(s.kanji, 74.dp, tilt = listOf(-3f, 2f, -1f)[s.id.length % 3], seed = s.id.hashCode()) }
                            Text(s.name, style = Type.body.copy(fontSize = 14.sp), color = PaperInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text("especial · " + shortFmt.format(Date(s.at ?: 0L)), style = Type.uiSmall.copy(fontSize = 11.sp), color = PaperSoft, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KanjiPage(stamps: List<Stamps.BookStamp>) {
    val have = stamps.map { it.kanji }.toSet()
    val all = Stamps.KANJI
    Kicker("Colección de kanji")
    PTitle("${all.count { it.second in have }} de ${all.size} géneros")
    PText("El kanji de cada sello depende del género del libro. Lee de todo para completar la colección. Los que faltan se ven punteados.")
    all.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            row.forEach { (_, k, name) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Hanko(k, 58.dp, ghost = k !in have, ink = stamps.lastOrNull { it.kanji == k }?.ink ?: Stamps.INKS[0])
                    Text(name, style = Type.uiSmall.copy(fontSize = 10.sp), color = if (k in have) PaperInk else PaperSoft.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InksPage(done: Int) {
    val cur = Stamps.inkFor(done.coerceAtLeast(1))
    val next = Stamps.INKS.firstOrNull { it.from > done }
    Kicker("Tintas")
    PTitle("Tu tinta cambia con tus libros")
    PText("En el antiguo Japón, el rango de una persona se reconocía por el color que vestía. Aquí, el color de tus sellos cuenta cuántos libros has terminado. Los sellos viejos conservan su tinta: así se ve tu camino.")
    Stamps.INKS.forEach { ink ->
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Hanko(ink.kanji, 56.dp, ink = ink, seed = ink.id.hashCode())
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    ink.name + if (ink == cur) "  · tu tinta" else "",
                    style = Type.body.copy(fontSize = 15.sp, fontWeight = if (ink == cur) FontWeight.SemiBold else FontWeight.Normal), color = PaperInk,
                )
                Text(if (ink.from == 1) "1 a 99 libros" else "desde el libro ${ink.from}", style = Type.uiSmall.copy(fontSize = 11.sp), color = Vermilion)
                Text(ink.why, style = Type.uiSmall, color = PaperSoft, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x14000000)).padding(12.dp)) {
        Text(
            if (next != null) "Llevas $done ${if (done == 1) "libro" else "libros"}. Faltan ${next.from - done} para la tinta ${next.name.substringBefore(" ·")}."
            else "Llevas $done libros: tienes la tinta más alta.",
            style = Type.ui, color = PaperInk,
        )
        if (next != null) {
            val prev = Stamps.INKS.last { it.from <= done.coerceAtLeast(1) }.from.let { if (it == 1) 0 else it }
            val f = ((done - prev).toFloat() / (next.from - prev)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22000000))) {
                Box(Modifier.fillMaxWidth(f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(cur.light)))
            }
        }
    }
}

@Composable
private fun WaxPage(wax: List<Stamps.BookStamp>, onTap: (Any) -> Unit) {
    Kicker("Sellos de cera")
    PTitle("Hechos con su portada")
    PText("Como el lacre de las cartas antiguas, a veces la tinta se mezcla con los colores de la portada del libro. No se sabe cuándo sale uno: más o menos uno de cada diez libros. Cada uno es único e irrepetible. La portada que tenga el libro cuando lo termines es la que cuenta, bonita o fea.")
    if (wax.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(top = 26.dp), contentAlignment = Alignment.Center) { Hanko("書", 96.dp, ghost = true) }
        PText("Todavía no te ha salido ninguno. Sigue leyendo…", 14)
    } else wax.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { s ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTap(s) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Hanko(s.kanji, 100.dp, wax = s.wax, tilt = -2f, seed = s.book.id.hashCode())
                    Text(s.book.title, style = Type.body.copy(fontSize = 14.sp), color = PaperInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        s.wax?.forEach { c -> Box(Modifier.size(13.dp, 8.dp).clip(RoundedCornerShape(2.dp)).background(Color(c))) }
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpecialsPage(list: List<Stamps.Special>, onTap: (Any) -> Unit) {
    Kicker("Sellos especiales")
    PTitle("${list.count { it.at != null }} de ${list.size}")
    PText("Celebran momentos importantes. Los que ya tienes están en rojo; los que faltan, punteados con su pista. Algunos son sorpresa.")
    list.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            row.forEach { s ->
                val got = s.at != null
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTap(s) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Hanko(if (got || !s.secret) s.kanji else "？", 64.dp, ghost = !got, tilt = if (got) listOf(-3f, 2f, -1f)[s.id.length % 3] else 0f, seed = s.id.hashCode())
                    Text(if (got || !s.secret) s.name else "Sorpresa", style = Type.uiSmall.copy(fontSize = 11.sp), color = if (got) PaperInk else PaperSoft.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    if (!got && !s.secret) Text(s.hint, style = Type.uiSmall.copy(fontSize = 10.sp), color = PaperSoft.copy(alpha = 0.7f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Detalle al tocar un sello. */
@Composable
private fun StampDetail(d: Any, onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (d) {
                is Stamps.BookStamp -> {
                    Hanko(d.kanji, 120.dp, ink = d.ink, wax = d.wax, tilt = -3f, seed = d.book.id.hashCode())
                    Text(d.book.title, style = Type.title, color = p.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                    if (d.book.author.isNotBlank()) Text(d.book.author, style = Type.ui, color = p.text2)
                    Text(
                        "Terminado el ${longFmt.format(Date(d.book.finishedAt))} · libro nº ${d.n}\n${d.kanji} ${d.genre} · " +
                            (if (d.wax != null) "sello de cera con los colores de su portada" else "tinta ${d.ink.name}"),
                        style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                    )
                    PillButton("Abrir el libro", { onClose(); a.read(d.book) }, Modifier.fillMaxWidth().padding(top = 18.dp))
                }
                is Stamps.Special -> {
                    val got = d.at != null
                    Hanko(if (got || !d.secret) d.kanji else "？", 110.dp, ghost = !got, tilt = -3f, seed = d.id.hashCode())
                    Text(if (got || !d.secret) d.name else "Sello sorpresa", style = Type.title, color = p.text, modifier = Modifier.padding(top = 18.dp))
                    Text(
                        when {
                            got -> d.hint + "\n" + longFmt.format(Date(d.at!!))
                            d.secret -> "Se gana sin aviso, haciendo algo especial mientras lees."
                            else -> d.hint
                        },
                        style = Type.ui, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
