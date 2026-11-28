package com.sebas.zerkalo.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Perfiles de lectura: un toque y cambian letra, papel, luz y márgenes juntos.
 * Los de Zerkalo solo tocan lo que su nombre promete (Noche no te cambia la letra);
 * los tuyos guardan todo como lo dejaste.
 */
data class Profile(val id: String, val name: String, val values: JSONObject, val builtin: Boolean, val edited: Boolean = false)

object Profiles {
    /** Lo que guarda un perfil (todo lo que se ve y se siente al leer; no los botones ni la pluma). */
    private val KEYS = listOf(
        "font", "size", "lineHeight", "paper", "ink", "customInk", "customInkLight", "brightness", "warmth", "veil",
        "paragraph", "spacing", "dyslexia", "mmLeft", "mmRight", "mmTop", "mmBottom", "respectCamera", "align", "layout",
        "continuous", "info", "ruler", "cpl", "weight", "texture", "pageAnim",
    )

    fun ReaderSettings.toJson(): JSONObject = JSONObject().apply {
        put("font", font); put("size", size); put("lineHeight", lineHeight.toDouble()); put("paper", paper); put("ink", ink)
        put("customInk", customInk); put("customInkLight", customInkLight); put("brightness", brightness.toDouble()); put("warmth", warmth.toDouble())
        put("veil", veil.toDouble()); put("paragraph", paragraph); put("spacing", spacing); put("dyslexia", dyslexia)
        put("mmLeft", mmLeft.toDouble()); put("mmRight", mmRight.toDouble()); put("mmTop", mmTop.toDouble()); put("mmBottom", mmBottom.toDouble())
        put("respectCamera", respectCamera); put("align", align); put("layout", layout); put("continuous", continuous)
        put("info", info); put("ruler", ruler); put("cpl", cpl); put("weight", weight); put("texture", texture)
        put("pageAnim", if (animate) pageAnim else 0)
    }

    /** Los ajustes de ahora con lo que el perfil cambia encima. */
    fun apply(r0: ReaderSettings, o: JSONObject): ReaderSettings {
        // Lo del aspecto que el perfil no menciona vuelve a lo normal; lo demás (márgenes, gestos…) se respeta
        val base = ReaderSettings()
        val r = r0.copy(
            font = base.font, size = base.size, lineHeight = base.lineHeight, weight = base.weight, align = base.align, justify = base.justify,
            paragraph = base.paragraph, spacing = base.spacing, cpl = base.cpl, texture = base.texture, paper = base.paper, ink = base.ink,
            warmth = base.warmth, veil = base.veil, dyslexia = base.dyslexia, layout = base.layout, pageAnim = base.pageAnim, animate = base.animate,
        )
        fun i(k: String, d: Int) = if (o.has(k)) o.optInt(k, d) else d
        fun f(k: String, d: Float) = if (o.has(k)) o.optDouble(k, d.toDouble()).toFloat() else d
        fun b(k: String, d: Boolean) = if (o.has(k)) o.optBoolean(k, d) else d
        val cont = b("continuous", r.continuous)
        val al = i("align", r.align)
        return r.copy(
            font = i("font", r.font), size = i("size", r.size), lineHeight = f("lineHeight", r.lineHeight), paper = i("paper", r.paper),
            ink = i("ink", r.ink), customInk = i("customInk", r.customInk), customInkLight = i("customInkLight", r.customInkLight),
            brightness = f("brightness", r.brightness), warmth = f("warmth", r.warmth), veil = f("veil", r.veil),
            paragraph = i("paragraph", r.paragraph), spacing = i("spacing", r.spacing), dyslexia = b("dyslexia", r.dyslexia),
            mmLeft = f("mmLeft", r.mmLeft), mmRight = f("mmRight", r.mmRight), mmTop = f("mmTop", r.mmTop), mmBottom = f("mmBottom", r.mmBottom),
            respectCamera = b("respectCamera", r.respectCamera), align = al, justify = al != 0, layout = i("layout", r.layout),
            continuous = cont, scroll = cont, info = b("info", r.info), ruler = b("ruler", r.ruler),
            cpl = i("cpl", r.cpl), weight = i("weight", r.weight), texture = b("texture", r.texture),
            animate = if (o.has("pageAnim")) o.optInt("pageAnim") != 0 else r.animate,
            pageAnim = i("pageAnim", if (r.animate) r.pageAnim else 0).let { if (it == 0) r.pageAnim else it },
        )
    }

    /** ¿Los ajustes de ahora son los del perfil? (para marcarlo y ofrecer guardar los cambios) */
    fun matches(r: ReaderSettings, o: JSONObject): Boolean {
        val now = r.toJson()
        return o.keys().asSequence().filter { it in KEYS }.all { k ->
            val a = now.opt(k); val b = o.opt(k)
            if (a is Number && b is Number) kotlin.math.abs(a.toDouble() - b.toDouble()) < 0.011 else a == b
        }
    }

    private fun j(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    /** Los que trae Zerkalo. */
    // Cada perfil trae su letra, su tamaño, su papel y su forma de pasar página: con solo tocarlo, todo cambia.
    // (Letras: 0 Literata, 2 Garamond, 5 Atkinson, 7 Inter, 17 Lora, 20 Bitter.)
    val BUILTIN: List<Profile> = listOf(
        Profile("dia", "Día", j("font" to 0, "size" to 100, "lineHeight" to 1.6, "weight" to 0, "paper" to 0, "ink" to 0, "brightness" to -1.0, "warmth" to 0.0, "veil" to 0.0,
            "texture" to true, "info" to true, "align" to 2, "paragraph" to 0, "continuous" to false, "pageAnim" to 1), true),
        Profile("noche", "Noche", j("font" to 20, "size" to 104, "lineHeight" to 1.65, "weight" to 0, "paper" to 1, "ink" to 0, "brightness" to 0.45, "warmth" to 0.25, "veil" to 0.0,
            "texture" to false, "info" to true, "align" to 2, "paragraph" to 0, "continuous" to false, "pageAnim" to 1), true),
        Profile("oscuro", "Oscuro total", j("font" to 20, "size" to 106, "lineHeight" to 1.65, "weight" to 1, "paper" to 2, "ink" to 3, "brightness" to 0.12, "warmth" to 0.35, "veil" to 0.0,
            "texture" to false, "info" to false, "align" to 2, "paragraph" to 0, "continuous" to false, "pageAnim" to 0), true),
        Profile("sol", "Sol", j("font" to 5, "size" to 125, "lineHeight" to 1.55, "weight" to 2, "paper" to 0, "ink" to 0, "brightness" to 1.0, "warmth" to 0.0, "veil" to 0.0,
            "texture" to false, "info" to true, "align" to 0, "paragraph" to 1, "continuous" to false, "pageAnim" to 1), true),
        Profile("cama", "Cama", j("font" to 17, "size" to 112, "lineHeight" to 1.7, "weight" to 0, "paper" to 1, "ink" to 4, "brightness" to 0.2, "warmth" to 0.7, "veil" to 0.0,
            "texture" to false, "info" to false, "align" to 2, "paragraph" to 0, "continuous" to false, "pageAnim" to 1), true),
        Profile("rapida", "Rápida", j("font" to 7, "size" to 100, "lineHeight" to 1.6, "weight" to 0, "cpl" to 38, "align" to 0, "paragraph" to 0, "texture" to false,
            "continuous" to false, "pageAnim" to 0), true),
        Profile("estudio", "Estudio", j("font" to 0, "size" to 100, "lineHeight" to 1.75, "weight" to 0, "paper" to 5, "ink" to 0, "texture" to true, "paragraph" to 1,
            "continuous" to false, "cpl" to 0, "align" to 2, "pageAnim" to 1), true),
        // Como un libro de Alfaguara: Garamond justificada con sangría, papel crema con textura, la hoja se dobla
        Profile("realista", "Realista", j("font" to 2, "size" to 106, "lineHeight" to 1.5, "weight" to 0, "paper" to 0, "ink" to 0, "texture" to true, "continuous" to false,
            "align" to 1, "paragraph" to 0, "spacing" to 0, "layout" to 1, "cpl" to 0, "info" to true, "pageAnim" to 2), true),
    )

    val list = MutableStateFlow<List<Profile>>(emptyList())

    fun init() { list.value = load() }

    private fun load(): List<Profile> {
        val saved = runCatching { JSONArray(Prefs.profilesJson) }.getOrNull() ?: JSONArray()
        val mine = (0 until saved.length()).mapNotNull { k ->
            saved.optJSONObject(k)?.let { o -> Profile(o.optString("id"), o.optString("name"), o.optJSONObject("v") ?: JSONObject(), o.optBoolean("b"), o.optBoolean("e")) }
        }
        // Los de Zerkalo siempre están (con tus cambios si los guardaste), en su orden; después los tuyos
        val out = BUILTIN.map { b -> mine.firstOrNull { it.id == b.id }?.let { b.copy(values = it.values, edited = true) } ?: b }
        return out + mine.filter { m -> BUILTIN.none { it.id == m.id } }
    }

    private fun save(all: List<Profile>) {
        list.value = all
        Prefs.profilesJson = JSONArray(all.filter { !it.builtin || it.edited }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("v", it.values).put("b", it.builtin).put("e", it.edited)
        }).toString()
    }

    fun get(id: String) = list.value.firstOrNull { it.id == id }

    /** Guarda los ajustes de ahora en un perfil (uno de Zerkalo queda "editado" y se puede restaurar). */
    fun update(id: String, r: ReaderSettings) {
        save(list.value.map { if (it.id == id) it.copy(values = r.toJson(), edited = it.builtin) else it })
    }

    fun create(name: String, r: ReaderSettings): Profile {
        val p = Profile("p" + System.currentTimeMillis(), name.trim().ifBlank { "Mi perfil" }.take(20), r.toJson(), false)
        save(list.value + p)
        return p
    }

    fun rename(id: String, name: String) = save(list.value.map { if (it.id == id && !it.builtin) it.copy(name = name.trim().take(20)) else it })

    fun delete(id: String) {
        val p = get(id) ?: return
        if (p.builtin) save(list.value.map { if (it.id == id) BUILTIN.first { b -> b.id == id } else it })
        else save(list.value.filter { it.id != id })
        if (Prefs.profile == id) Prefs.profile = ""
    }

    /** Qué perfil toca a esta hora (cambio automático): Día, Noche u Oscuro total. */
    fun forHour(h: Int): String {
        val (day, night, dark) = Prefs.profileHours
        return when {
            h in day until night -> "dia"
            h >= night && h < dark -> "noche"
            dark > night && (h >= dark || h < day) -> "oscuro"
            else -> "noche"
        }
    }
}
