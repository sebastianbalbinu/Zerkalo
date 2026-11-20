package com.sebas.zerkalo.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Papel de la app: Sistema, Crema (Chashitsu), Nogal o Sumi (OLED). */
enum class PaperPref { SISTEMA, CREMA, NOGAL, SUMI }

data class ReaderSettings(
    val font: Int = 0,          // índice en ReaderFonts
    val size: Int = 100,        // porcentaje
    val lineHeight: Float = 1.6f,
    val margin: Int = 1,        // 0 estrechos, 1 medios, 2 amplios
    val justify: Boolean = true,
    val paper: Int = -1,        // -1 = el mismo de la app; 0 crema, 1 nogal, 2 sumi
)

data class OpdsCatalog(val name: String, val url: String)

object Prefs {
    private lateinit var sp: SharedPreferences
    val paper = MutableStateFlow(PaperPref.SISTEMA)
    val reader = MutableStateFlow(ReaderSettings())
    val catalogs = MutableStateFlow<List<OpdsCatalog>>(emptyList())

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("zerkalo", Context.MODE_PRIVATE)
        paper.value = runCatching { PaperPref.valueOf(sp.getString("paper", "SISTEMA")!!) }.getOrDefault(PaperPref.SISTEMA)
        reader.value = ReaderSettings(
            font = sp.getInt("rFont", 0), size = sp.getInt("rSize", 100), lineHeight = sp.getFloat("rLh", 1.6f),
            margin = sp.getInt("rMargin", 1), justify = sp.getBoolean("rJustify", true), paper = sp.getInt("rPaper", -1),
        )
        catalogs.value = runCatching {
            val a = JSONArray(sp.getString("opds", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> OpdsCatalog(o.getString("name"), o.getString("url")) } }
        }.getOrDefault(emptyList())
    }

    fun setPaper(p: PaperPref) { paper.value = p; sp.edit().putString("paper", p.name).apply() }

    fun setReader(r: ReaderSettings) {
        reader.value = r
        sp.edit().putInt("rFont", r.font).putInt("rSize", r.size).putFloat("rLh", r.lineHeight)
            .putInt("rMargin", r.margin).putBoolean("rJustify", r.justify).putInt("rPaper", r.paper).apply()
    }

    fun setCatalogs(list: List<OpdsCatalog>) {
        catalogs.value = list
        sp.edit().putString("opds", JSONArray(list.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()).apply()
    }

    var tansuLang: String
        get() = sp.getString("tansuLang", null) ?: java.util.Locale.getDefault().language.takeIf { l -> Catalogs.LANGS.any { it.id == l } } ?: "es"
        set(v) { sp.edit().putString("tansuLang", v).apply() }

    var secPerPosition: Float
        get() = sp.getFloat("spp", 45f)
        set(v) { sp.edit().putFloat("spp", v).apply() }

    var welcomed: Boolean
        get() = sp.getBoolean("welcomed", false)
        set(v) { sp.edit().putBoolean("welcomed", v).apply() }

    var reminderOn: Boolean
        get() = sp.getBoolean("remOn", false)
        set(v) { sp.edit().putBoolean("remOn", v).apply() }
    val reminderHour get() = sp.getInt("remH", 21)
    val reminderMinute get() = sp.getInt("remM", 30)
}
