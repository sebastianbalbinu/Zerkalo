package com.sebas.zerkalo.reader

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sebas.zerkalo.data.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Traducción dentro del teléfono (ML Kit). Cada idioma se descarga una sola vez (unos 30 MB)
 * y después funciona sin internet. No pasa por ningún servidor nuestro.
 */
object Traductor {
    /** Idiomas que se ofrecen como destino, con su nombre en español. */
    val TARGETS = listOf(
        "es" to "Español", "en" to "Inglés", "fr" to "Francés", "pt" to "Portugués", "de" to "Alemán",
        "it" to "Italiano", "ru" to "Ruso", "ja" to "Japonés", "zh" to "Chino", "ko" to "Coreano",
        "nl" to "Neerlandés", "pl" to "Polaco", "uk" to "Ucraniano", "ar" to "Árabe", "el" to "Griego",
    )

    fun name(code: String) = TARGETS.firstOrNull { it.first == code }?.second
        ?: Locale(code).getDisplayLanguage(Locale("es")).replaceFirstChar { it.uppercase() }

    private val clients = HashMap<String, Translator>()

    fun code(lang: String?): String? = lang?.substringBefore('-')?.substringBefore('_')?.lowercase()
        ?.takeIf { it.isNotBlank() }?.let { TranslateLanguage.fromLanguageTag(it) }

    /** Idioma al que se traduce: el elegido, o el del teléfono; si coincide con el del libro, inglés o español. */
    fun targetFor(source: String?): String {
        val chosen = Prefs.translateTo.ifBlank { Locale.getDefault().language }
        if (code(chosen) != null && code(chosen) != code(source)) return chosen
        return if (code(source) == TranslateLanguage.ENGLISH) "es" else "en"
    }

    suspend fun needsDownload(from: String, to: String): Boolean {
        val mgr = RemoteModelManager.getInstance()
        return listOfNotNull(code(from), code(to)).filter { it != TranslateLanguage.ENGLISH }.any {
            !mgr.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await()
        }
    }

    suspend fun translate(text: String, from: String, to: String): String {
        val src = code(from) ?: throw IllegalArgumentException("Todavía no sé traducir desde ${name(from.substringBefore('-'))}")
        val dst = code(to) ?: throw IllegalArgumentException("Todavía no sé traducir a ${name(to)}")
        if (src == dst) return text
        val client = synchronized(clients) {
            clients.getOrPut("$src>$dst") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
            }
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return client.translate(text).await()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
