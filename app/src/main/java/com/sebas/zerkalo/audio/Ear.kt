package com.sebas.zerkalo.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.sebas.zerkalo.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * "A oído": escucha unos segundos del audiolibro (el archivo, no el micrófono), entiende las palabras
 * sin internet con Vosk y busca esa frase en el texto del capítulo.
 * El diccionario de voz se baja una sola vez por idioma (unos 40 MB).
 */
object Ear {
    private val MODELS = mapOf(
        "es" to "vosk-model-small-es-0.42",
        "en" to "vosk-model-small-en-us-0.15",
        "fr" to "vosk-model-small-fr-0.22",
        "de" to "vosk-model-small-de-0.15",
        "it" to "vosk-model-small-it-0.22",
        "pt" to "vosk-model-small-pt-0.3",
        "ru" to "vosk-model-small-ru-0.22",
    )
    private var loaded: Pair<String, org.vosk.Model>? = null

    fun supports(lang: String) = lang in MODELS
    private fun dir(ctx: Context, lang: String) = File(ctx.filesDir, "voz/$lang")
    fun hasModel(ctx: Context, lang: String) = File(dir(ctx, lang), "listo").exists()

    /** Baja y descomprime el diccionario de voz de un idioma. */
    suspend fun download(ctx: Context, lang: String, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val name = MODELS[lang] ?: error("Idioma sin diccionario de voz")
        val d = dir(ctx, lang)
        d.deleteRecursively(); d.mkdirs()
        val zip = File(ctx.cacheDir, "$name.zip")
        Net.download(listOf("https://alphacephei.com/vosk/models/$name.zip"), zip, zip = false) { p -> onProgress(p?.let { it * 0.9f }) }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                // Sin la carpeta de arriba del ZIP: el modelo queda directo en voz/<idioma>
                val rel = e.name.substringAfter('/', "")
                if (rel.isBlank() || rel.contains("..")) continue
                val f = File(d, rel)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        File(d, "listo").writeText(name)
        onProgress(1f)
    }

    private fun model(ctx: Context, lang: String): org.vosk.Model {
        loaded?.let { if (it.first == lang) return it.second }
        loaded?.second?.close()
        val m = org.vosk.Model(dir(ctx, lang).absolutePath)
        loaded = lang to m
        return m
    }

    /**
     * Las palabras que dice el narrador entre [fromMs] y [toMs] del archivo [source]
     * (ruta local o dirección de internet).
     */
    suspend fun hear(ctx: Context, lang: String, source: String, fromMs: Long, toMs: Long): String = withContext(Dispatchers.Default) {
        val pcm = decode(source, fromMs, toMs)
        if (pcm.isEmpty()) return@withContext ""
        val rec = org.vosk.Recognizer(model(ctx, lang), 16000f)
        try {
            var i = 0
            while (i < pcm.size) {
                val n = minOf(4000, pcm.size - i)
                rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)
                i += n
            }
            JSONObject(rec.finalResult).optString("text")
        } finally {
            rec.close()
        }
    }

    /** Decodifica el MP3 a sonido de 16 kHz en mono, que es lo que entiende el reconocedor. */
    private fun decode(source: String, fromMs: Long, toMs: Long): ShortArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(source)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return ShortArray(0)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ArrayList<Short>((toMs - fromMs).toInt() * 16)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var acc = 0.0 // posición para bajar a 16 kHz
            try {
                loop@ while (true) {
                    if (!inputDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > toMs * 1000) {
                                codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance()
                            }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        ob >= 0 -> {
                            val keep = info.presentationTimeUs >= fromMs * 1000 && info.presentationTimeUs <= toMs * 1000
                            if (keep && info.size > 0) {
                                val sb = codec.getOutputBuffer(ob)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val frames = sb.remaining() / channels.coerceAtLeast(1)
                                val step = rate / 16000.0
                                while (acc < frames) {
                                    val f = acc.toInt()
                                    var sum = 0
                                    for (c in 0 until channels) sum += sb.get(f * channels + c)
                                    out += (sum / channels).toShort()
                                    acc += step
                                }
                                acc -= frames
                            }
                            codec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                        }
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            return out.toShortArray()
        } finally {
            ex.release()
        }
    }

    private fun words(s: String): List<Pair<String, Int>> =
        Regex("[\\p{L}\\p{N}]+").findAll(s).map { m ->
            java.text.Normalizer.normalize(m.value.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") to (m.range.last + 1)
        }.toList()

    /**
     * Dónde está en [text] lo que se oyó: devuelve la fracción del texto (0..1) en que termina la frase,
     * o null si no se parece lo suficiente a nada.
     */
    fun locate(heard: String, text: String): Double? {
        val h = words(heard).map { it.first }.filter { it.length > 1 }
        if (h.size < 4 || text.isBlank()) return null
        val t = words(text)
        if (t.isEmpty()) return null
        val want = h.toSet()
        val pairs = h.zipWithNext().toSet()
        val n = h.size + 4
        var best = -1.0
        var bestEnd = -1
        for (start in 0..(t.size - minOf(n, t.size))) {
            val end = minOf(t.size, start + n)
            var score = 0.0
            for (k in start until end) {
                if (t[k].first in want) score += 1.0
                if (k + 1 < end && (t[k].first to t[k + 1].first) in pairs) score += 1.5
            }
            if (score > best) { best = score; bestEnd = end - 1 }
        }
        if (bestEnd < 0 || best < h.size * 0.6) return null
        return t[bestEnd].second.toDouble() / text.length
    }
}
