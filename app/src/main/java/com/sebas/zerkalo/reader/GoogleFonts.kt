package com.sebas.zerkalo.reader

import android.content.Context
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.sebas.zerkalo.data.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Tamaño de la letra en puntos, como en Word. Por dentro se sigue guardando el porcentaje de siempre
 * (100 % = 16 px de pantalla ≈ 12 pt), así los ajustes que ya tenías no cambian.
 * También aquí: cómo se acomoda la página cuando la letra es grande.
 */
object Letra {
    const val PT_MIN = 9
    const val PT_MAX = 32
    val QUICK = listOf(12, 14, 16, 18, 22)

    fun pt(size: Int): Int = (size * 12f / 100f).roundToInt().coerceIn(PT_MIN, PT_MAX)
    fun pct(pt: Int): Int = (pt.coerceIn(PT_MIN, PT_MAX) * 100f / 12f).roundToInt()

    /** Desde 18 pt los márgenes de los lados se achican poco a poco (hasta un poco más de la mitad). */
    fun sideK(size: Int): Float = if (size <= 150) 1f else (1f - (size - 150) / 240f).coerceAtLeast(0.55f)

    /** Con letra grande el interlineado baja un poco: las líneas ya están lejos entre sí. */
    fun lineHeight(r: ReaderSettings): Float =
        if (r.size <= 150 || r.dyslexia) r.lineHeight else (r.lineHeight - (r.size - 150) * 0.0015f).coerceAtLeast(minOf(r.lineHeight, 1.3f))

    /** Desde 22 pt no se agrega espacio entre letras (salvo en modo dislexia). */
    fun spacing(r: ReaderSettings): Int = if (r.size >= 183 && !r.dyslexia) 0 else r.spacing
}

/** Una letra de Google Fonts: se descarga la primera vez y después funciona sin internet. */
data class GFont(val family: String, val serif: Boolean, val note: String = "")

/**
 * Letras de Google Fonts. Las descargadas se guardan en files/gfonts/<nombre>/ y se numeran desde 1000
 * en los ajustes (los números de las letras que vienen con la app no cambian).
 */
object GoogleFonts {
    const val BASE = 1000

    /** Elegidas para leer largo (las que ya vienen con la app no se repiten). */
    val CURATED = listOf(
        GFont("Alegreya", true, "Caligráfica y viva, para novelas"),
        GFont("Spectral", true, "Fina y elegante, hecha para pantallas"),
        GFont("Cormorant Garamond", true, "Garamond de lujo, mejor en tamaños grandes"),
        GFont("Libre Baskerville", true, "Baskerville clásica, cómoda en pantalla"),
        GFont("Libre Caslon Text", true, "La letra de los libros de antes"),
        GFont("PT Serif", true, "Sobria y muy clara"),
        GFont("Noto Serif", true, "Serifa con todos los idiomas"),
        GFont("Gentium Book Plus", true, "De SIL, pensada para leer mucho"),
        GFont("Vollkorn", true, "Cálida y oscura, de lectura diaria"),
        GFont("Cardo", true, "Para clásicos y humanidades"),
        GFont("Old Standard TT", true, "Como un libro del siglo XIX"),
        GFont("Domine", true, "Firme, se lee bien en letra chica"),
        GFont("Petrona", true, "Moderna y tranquila"),
        GFont("Fraunces", true, "Suave, con carácter"),
        GFont("Gelasio", true, "Parecida a Georgia"),
        GFont("Zilla Slab", true, "Serifa recta, contemporánea"),
        GFont("Roboto Serif", true, "Muy pareja en cualquier tamaño"),
        GFont("Brygada 1918", true, "Clásica polaca, de 1918"),
        GFont("Besley", true, "Antigua y robusta"),
        GFont("EB Garamond", true, "Garamond completa"),
        GFont("Alegreya Sans", false, "La hermana sin serifa de Alegreya"),
        GFont("Open Sans", false, "Neutra y muy legible"),
        GFont("Roboto", false, "La de Android"),
        GFont("Lato", false, "Cálida y redonda"),
        GFont("Work Sans", false, "Amplia, cómoda en letra grande"),
        GFont("Mulish", false, "Limpia y liviana"),
        GFont("Karla", false, "Con personalidad, fácil de leer"),
        GFont("Public Sans", false, "Neutra, del gobierno de EE. UU."),
        GFont("Manrope", false, "Moderna y geométrica"),
        GFont("DM Sans", false, "Sencilla y suave"),
        GFont("Fira Sans", false, "Clara en pantallas chicas"),
        GFont("PT Sans", false, "Humanista, con todos los acentos"),
        GFont("Cabin", false, "Humanista y amable"),
        GFont("Rubik", false, "Redondeada"),
        GFont("Raleway", false, "Fina y elegante"),
        GFont("Montserrat", false, "Geométrica, de carteles"),
        GFont("Ubuntu", false, "La de Ubuntu"),
        GFont("Noto Serif JP", true, "Mincho japonesa completa (pesa más)"),
    )

    data class Installed(val family: String, val serif: Boolean, val files: Map<String, String>, val removed: Boolean = false)

    private var cache: MutableList<Installed>? = null
    private val previews = HashMap<String, FontFamily>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "gfonts").apply { mkdirs() }
    private fun slug(family: String) = family.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    @Synchronized
    fun all(ctx: Context): MutableList<Installed> = cache ?: run {
        val list = mutableListOf<Installed>()
        runCatching {
            val a = JSONArray(File(dir(ctx), "index.json").readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = o.optJSONObject("files") ?: JSONObject()
                list += Installed(o.getString("family"), o.optBoolean("serif", true), f.keys().asSequence().associateWith { f.getString(it) }, o.optBoolean("removed", false))
            }
        }
        list.also { cache = it }
    }

    @Synchronized
    private fun save(ctx: Context) {
        val a = JSONArray()
        all(ctx).forEach { g -> a.put(JSONObject().put("family", g.family).put("serif", g.serif).put("removed", g.removed).put("files", JSONObject(g.files))) }
        File(dir(ctx), "index.json").writeText(a.toString())
    }

    fun isGoogle(index: Int) = index >= BASE
    fun get(ctx: Context, index: Int): Installed? = all(ctx).getOrNull(index - BASE)?.takeIf { !it.removed && File(dir(ctx), slug(it.family)).exists() }
    fun indexOf(ctx: Context, family: String): Int? = all(ctx).indexOfFirst { it.family == family && !it.removed }.takeIf { it >= 0 }?.let { it + BASE }

    /** Las letras descargadas que se pueden usar, con su número para los ajustes. */
    fun installed(ctx: Context): List<Pair<Int, Installed>> = all(ctx).mapIndexedNotNull { i, g -> get(ctx, i + BASE)?.let { (i + BASE) to it } }

    /** El archivo de una variante ("regular", "italic", "bold"); si falta, la normal. */
    fun file(ctx: Context, g: Installed, kind: String): File? =
        (g.files[kind] ?: if (kind == "bold") null else g.files["regular"])?.let { File(File(dir(ctx), slug(g.family)), it) }?.takeIf { it.exists() }

    /** Ruta que usa el modo continuo para pedir el archivo: __gfont/<carpeta>/<archivo>. */
    fun webPath(g: Installed, f: File) = "__gfont/${slug(g.family)}/${f.name}"
    fun webFile(ctx: Context, path: String): File? {
        val rel = path.removePrefix("__gfont/").substringBefore('?')
        if (rel.contains("..")) return null
        return File(dir(ctx), rel).takeIf { it.exists() }
    }

    fun remove(ctx: Context, index: Int) {
        val list = all(ctx)
        val i = index - BASE
        val g = list.getOrNull(i) ?: return
        File(dir(ctx), slug(g.family)).deleteRecursively()
        // El número queda reservado: así los ajustes de otros libros no apuntan a otra letra
        list[i] = g.copy(removed = true)
        previews.remove(g.family)
        save(ctx)
    }

    fun preview(ctx: Context, index: Int): FontFamily {
        val g = get(ctx, index) ?: return FontFamily.Default
        return previews.getOrPut(g.family) {
            runCatching {
                val list = mutableListOf<Font>()
                file(ctx, g, "regular")?.takeIf { it.extension == "ttf" || it.extension == "otf" }?.let { list += Font(it) }
                g.files["italic"]?.let { file(ctx, g, "italic") }?.takeIf { it.extension == "ttf" || it.extension == "otf" }?.let { list += Font(it, style = FontStyle.Italic) }
                g.files["bold"]?.let { file(ctx, g, "bold") }?.takeIf { it.extension == "ttf" || it.extension == "otf" }?.let { list += Font(it, weight = FontWeight.Bold) }
                if (list.isEmpty()) FontFamily.Default else FontFamily(list)
            }.getOrDefault(FontFamily.Default)
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        // Con un navegador desconocido, Google entrega archivos .ttf (los que entiende todo Android)
        c.setRequestProperty("User-Agent", "Wget/1.21")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** Descarga una familia (normal, cursiva y negrita si existen). Devuelve su número para los ajustes. */
    suspend fun download(ctx: Context, family: String, serif: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            indexOf(ctx, family)?.let { return@runCatching it }
            val q = URLEncoder.encode(family, "UTF-8").replace("%20", "+")
            val tries = listOf("$q:ital,wght@0,400;0,700;1,400", "$q:wght@400;700", q)
            var css: String? = null
            // Dos vueltas: si el internet falla un momento, se intenta de nuevo
            run vueltas@{
                repeat(2) { vuelta ->
                    if (vuelta > 0) Thread.sleep(1500)
                    for (t in tries) {
                        css = runCatching { get("https://fonts.googleapis.com/css2?family=$t") }.getOrNull()
                        if (css?.contains("@font-face") == true) return@vueltas
                    }
                }
            }
            if (css == null || !css.contains("@font-face")) throw IllegalStateException("No encontré «$family» en Google Fonts")
            val target = File(dir(ctx), slug(family)).apply { mkdirs() }
            val files = LinkedHashMap<String, String>()
            Regex("@font-face\\s*\\{([^}]*)\\}").findAll(css).forEach { m ->
                val block = m.groupValues[1]
                // Si Google parte la letra por alfabetos, se toma solo el bloque latino (el último) y el primero si no hay otro
                val italic = Regex("font-style:\\s*italic").containsMatchIn(block)
                val weight = Regex("font-weight:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 400
                val url = Regex("url\\((https://[^)]+)\\)").find(block)?.groupValues?.get(1) ?: return@forEach
                val kind = when { italic && weight < 600 -> "italic"; !italic && weight >= 600 -> "bold"; !italic -> "regular"; else -> return@forEach }
                val ext = url.substringAfterLast('.').substringBefore('?').lowercase().takeIf { it.length in 3..5 } ?: "ttf"
                val name = "$kind.$ext"
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                try {
                    if (c.responseCode !in 200..299) return@forEach
                    File(target, name).outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
                } finally { c.disconnect() }
                files[kind] = name
            }
            if ("regular" !in files) { target.deleteRecursively(); throw IllegalStateException("No pude bajar «$family»") }
            synchronized(this@GoogleFonts) {
                val list = all(ctx)
                list += Installed(family, serif, files)
                save(ctx)
                list.size - 1 + BASE
            }
        }.onFailure { Log.w("Zerkalo", "ZK_GFONT $family: ${it.message}") }
    }

    /** El catálogo completo de Google Fonts (nombre y si tiene serifa), guardado para buscar sin esperar. */
    suspend fun catalog(ctx: Context): List<GFont> = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "catalog.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000
        // La respuesta empieza con )]}' antes del JSON: se corta hasta la primera llave
        val text = (if (fresh) f.readText() else null)
            ?: runCatching { get("https://fonts.google.com/metadata/fonts").let { r -> r.substring(r.indexOf('{').coerceAtLeast(0)) } }
                .getOrNull()?.takeIf { it.startsWith("{") }?.also { f.writeText(it) }
            ?: f.takeIf { it.exists() }?.readText() ?: return@withContext emptyList()
        runCatching {
            val a = JSONObject(text).getJSONArray("familyMetadataList")
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("category") != "Icons" }.map {
                GFont(it.getString("family"), it.optString("category").let { c -> c == "Serif" || c == "Slab Serif" || c == "SERIF" })
            }
        }.getOrDefault(emptyList())
    }
}

/** Nombre CSS de cualquier letra (de la app o de Google). null = la del libro. */
fun fontCss(ctx: Context, index: Int): String? =
    if (GoogleFonts.isGoogle(index)) GoogleFonts.get(ctx, index)?.family ?: ReaderFonts[0].css
    else ReaderFonts.getOrNull(index)?.css

/**
 * @font-face de una letra de Google con los archivos dentro (data:), para el lector por páginas:
 * Readium solo sirve archivos de la app, así que la letra va metida en la página.
 */
object GFontInject {
    private val scripts = HashMap<String, String>()

    fun script(ctx: Context, index: Int): String? {
        val g = GoogleFonts.get(ctx, index) ?: return null
        return scripts.getOrPut(g.family) {
            val css = StringBuilder()
            listOf(Triple("regular", "normal", 400), Triple("italic", "italic", 400), Triple("bold", "normal", 700)).forEach { (kind, style, weight) ->
                if (kind != "regular" && g.files[kind] == null) return@forEach
                val f = GoogleFonts.file(ctx, g, kind) ?: return@forEach
                val mime = when (f.extension) { "woff2" -> "font/woff2"; "woff" -> "font/woff"; "otf" -> "font/otf"; else -> "font/ttf" }
                val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                css.append("@font-face{font-family:'${g.family}';src:url(data:$mime;base64,$b64);font-style:$style;font-weight:$weight}")
            }
            val fam = JSONObject.quote(g.family)
            """(function(){var d=document,s=d.getElementById('zk-gf');if(s&&s.getAttribute('data-f')===$fam)return 'igual';
if(!s){s=d.createElement('style');s.id='zk-gf';(d.head||d.documentElement).appendChild(s);}
s.setAttribute('data-f',$fam);s.textContent=${JSONObject.quote(css.toString())};return 'ok';})()"""
        }
    }

    /** Los archivos de la letra como direcciones data: (para que Readium la meta en cada capítulo al servirlo). */
    fun dataUrls(ctx: Context, index: Int): List<Triple<String, Boolean, Boolean>> {
        val g = GoogleFonts.get(ctx, index) ?: return emptyList()
        return listOf(Triple("regular", false, false), Triple("italic", true, false), Triple("bold", false, true)).mapNotNull { (kind, it, bold) ->
            if (kind != "regular" && g.files[kind] == null) return@mapNotNull null
            val f = GoogleFonts.file(ctx, g, kind) ?: return@mapNotNull null
            val mime = when (f.extension) { "woff2" -> "font/woff2"; "woff" -> "font/woff"; "otf" -> "font/otf"; else -> "font/ttf" }
            Triple("data:$mime;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP), it, bold)
        }
    }

    /** Pregunta barata antes de mandar el archivo: ¿esta página ya tiene la letra? */
    fun check(family: String) = "(function(){var s=document.getElementById('zk-gf');return !!(s&&s.getAttribute('data-f')===${JSONObject.quote(family)});})()"
}
