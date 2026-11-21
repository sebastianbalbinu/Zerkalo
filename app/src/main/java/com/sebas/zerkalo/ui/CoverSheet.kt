package com.sebas.zerkalo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sebas.zerkalo.data.Book
import com.sebas.zerkalo.data.Catalogs
import com.sebas.zerkalo.data.CoverOption
import com.sebas.zerkalo.data.CoverSearch
import com.sebas.zerkalo.data.Genres
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Prefs
import kotlinx.coroutines.launch

/** Elegir otra portada: ediciones del mismo libro (Open Library y Google Books), Google Imágenes o la galería. */
@Composable
fun CoverSheet(start: Book, onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by Library.books.collectAsState()
    val book = books.firstOrNull { it.id == start.id } ?: start
    var options by remember { mutableStateOf<List<CoverOption>?>(null) }
    var lang by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<CoverOption?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(book.id) { options = runCatching { CoverSearch.find(book) }.getOrDefault(emptyList()) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val ok = CoverSearch.applyUri(ctx, book, uri)
            busy = false
            a.toast(if (ok) "Portada cambiada" else "No se pudo usar esa imagen")
            if (ok) onClose()
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, 56.dp, 82.dp, elevation = 4.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Elige otra portada", style = Type.title.copy(fontSize = 20.sp), color = p.text)
                    Text(book.title, style = Type.uiSmall, color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val all = options
            val langs = remember(all) { all.orEmpty().map { it.lang }.filter { it.isNotBlank() }.distinct() }
            if (langs.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                item { SmallPill("Todas", lang == null) { lang = null } }
                items(langs) { l -> SmallPill(Catalogs.LANGS.firstOrNull { it.id == l }?.name ?: l.uppercase(), lang == l) { lang = l } }
            }
            when {
                all == null -> Text("Buscando portadas de otras ediciones…", style = Type.ui, color = p.text2, modifier = Modifier.padding(vertical = 24.dp))
                all.isEmpty() -> Text(
                    "No encontré otras ediciones de este libro. Puedes buscarla en Google Imágenes o elegir una foto tuya.",
                    style = Type.ui, color = p.text2, modifier = Modifier.padding(vertical = 18.dp),
                )
                else -> {
                    val shown = all.filter { lang == null || it.lang == lang }
                    LazyVerticalGrid(
                        GridCells.Fixed(3), Modifier.fillMaxWidth().height(380.dp).padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(shown, key = { i, o -> o.thumb + i }) { _, o ->
                            Column(Modifier.clickable { picked = o }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(4.dp)).background(p.card)
                                        .then(if (picked == o) Modifier.border(3.dp, p.accent, RoundedCornerShape(4.dp)) else Modifier),
                                ) {
                                    AsyncImage(model = o.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(
                                    listOf(o.source, o.label).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = Type.uiSmall.copy(fontSize = 10.sp), color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            picked?.let { o ->
                PillButton(if (busy) "Poniendo la portada…" else "Usar esta portada", {
                    if (!busy) scope.launch {
                        busy = true
                        val ok = CoverSearch.apply(book, o)
                        busy = false
                        a.toast(if (ok) "Portada cambiada" else "No se pudo bajar esa portada")
                        if (ok) onClose()
                    }
                }, Modifier.fillMaxWidth().padding(top = 14.dp), enabled = !busy)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Google Imágenes", {
                    // Al compartir la imagen a Zerkalo desde el navegador, se le pone a este libro.
                    Prefs.pendingCover = book.id to System.currentTimeMillis()
                    a.toast("Elige una imagen y compártela a Zerkalo")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CoverSearch.googleImagesUrl(book)))) }
                }, Modifier.weight(1f), ghost = true, icon = "search")
                PillButton("Galería", { gallery.launch("image/*") }, Modifier.weight(1f), ghost = true, icon = "import")
            }
            if (CoverSearch.hasCustom(book)) Text(
                "Volver a la portada original", style = Type.uiSmall, color = p.text2,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { CoverSearch.restore(book); a.toast("Portada original"); onClose() }.padding(6.dp),
            )
        }
    }
}

/** Mantener presionado un libro en la biblioteca: favorito, estante (género), portada y ficha. */
@Composable
fun BookQuickSheet(start: Book, startMode: String = "menu", onClose: () -> Unit) {
    val p = LocalPalette.current
    val a = LocalActions.current
    val books by Library.books.collectAsState()
    val book = books.firstOrNull { it.id == start.id } ?: run { onClose(); return }
    var mode by remember { mutableStateOf(startMode) } // "menu" | "genre" | "cover"
    if (mode == "cover") { CoverSheet(book, onClose); return }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, 48.dp, 70.dp, elevation = 3.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(book.title, style = Type.title.copy(fontSize = 19.sp), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author, style = Type.uiSmall, color = p.text2, maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (mode == "menu") {
                ListRow(if (book.favorite) "♥ Quitar de favoritos" else "♡ Agregar a favoritos", onClick = {
                    Library.update(book.id) { it.copy(favorite = !it.favorite) }; onClose()
                })
                ListRow("Estante: " + Genres.name(book.genre), "Cambiar el género del libro", onClick = { mode = "genre" })
                ListRow("Cambiar portada", "Otras ediciones, Google o tu galería", onClick = { mode = "cover" })
                ListRow("Ver ficha del libro", onClick = { onClose(); a.open(Screen.BookDetail(book.id)) })
            } else {
                Text("¿En qué estante va?", style = Type.ui, color = p.text, modifier = Modifier.padding(bottom = 6.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(Catalogs.TOPICS + listOf(com.sebas.zerkalo.data.Topic("-", "Otros"))) { t ->
                        val on = book.genre == t.id || (t.id == "-" && Catalogs.TOPICS.none { it.id == book.genre })
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                Library.update(book.id) { it.copy(genre = t.id) }; onClose()
                            }.padding(vertical = 11.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.name, style = Type.ui, color = if (on) p.accent else p.text, modifier = Modifier.weight(1f))
                            if (on) ZIcon("check", p.accent, 16.dp)
                        }
                    }
                }
            }
        }
    }
}
