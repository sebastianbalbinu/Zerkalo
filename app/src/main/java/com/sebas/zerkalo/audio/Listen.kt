package com.sebas.zerkalo.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sebas.zerkalo.data.AudioBook
import com.sebas.zerkalo.data.AudioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** El reproductor de audiolibros visto desde la app: qué suena, por dónde va y sus controles. */
object Listen {
    data class State(
        val book: AudioBook? = null,
        val index: Int = 0,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val position: Long = 0,
        val duration: Long = 0,
        val speed: Float = 1f,
        val sleepAt: Long = 0,          // hora en que se detiene (0 = sin temporizador)
        val sleepEndOfTrack: Boolean = false,
        val error: String? = null,
        val sample: Boolean = false,    // muestra de 30 s: no cambia por dónde vas
    )

    val state = MutableStateFlow(State())
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var sampleJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) persist()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Capítulo terminado: la próxima vez empieza desde el inicio.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) state.value.book?.let { b -> if (!state.value.sample) AudioStore.finishTrack(b.id, state.value.index) }
            // "Hasta el fin del capítulo": se detiene al pasar al siguiente.
            if (state.value.sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                state.value = state.value.copy(sleepEndOfTrack = false)
            }
            refresh(); persist()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            state.value = state.value.copy(error = "No se pudo reproducir: revisa la conexión", playing = false)
        }
    }

    private fun connect(ctx: Context, then: (MediaController) -> Unit) {
        controller?.let { then(it); return }
        val app = ctx.applicationContext
        val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        f.addListener({
            runCatching { f.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                then(c)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun refresh() {
        val c = controller ?: return
        state.value = state.value.copy(
            index = c.currentMediaItemIndex.coerceAtLeast(0),
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            position = c.currentPosition.coerceAtLeast(0),
            duration = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
        )
        if (c.isPlaying) startTicker()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(1000)
                val c = controller ?: break
                state.value = state.value.copy(position = c.currentPosition.coerceAtLeast(0), duration = c.duration.takeIf { it > 0 } ?: 0)
                if (++n % 5 == 0) persist()
                if (!c.isPlaying) { persist(); break }
            }
        }
    }

    private fun persist() {
        val b = state.value.book ?: return
        if (state.value.sample) return
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        AudioStore.progress(b.id, c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition.coerceAtLeast(0))
    }

    /**
     * Empieza (o retoma) un audiolibro. Usa los capítulos descargados si existen.
     * Al retomar, vuelve 3 segundos atrás para recordar dónde ibas.
     */
    fun play(ctx: Context, book: AudioBook, index: Int = book.index, position: Long = book.spot(index), sample: Boolean = false) {
        persist() // guarda el que sonaba antes de cambiar
        val start = if (position > 5_000) position - 3_000 else position
        val spots = AudioStore.get(book.id)?.spots ?: book.spots
        if (!sample) AudioStore.put(book.copy(index = index, position = start, spots = spots, updatedAt = System.currentTimeMillis()))
        state.value = state.value.copy(book = book, index = index, error = null, sample = sample)
        connect(ctx) { c ->
            val items = book.tracks.mapIndexed { i, t ->
                val local = AudioStore.localFile(book.id, i)
                MediaItem.Builder()
                    .setMediaId("${book.id}#$i")
                    .setUri(if (local.length() > 0) Uri.fromFile(local) else Uri.parse(t.url))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(t.title).setArtist(book.author).setAlbumTitle(book.title)
                            .setArtworkUri(Uri.parse(book.cover)).build(),
                    )
                    .build()
            }
            c.setMediaItems(items, index.coerceIn(0, (items.size - 1).coerceAtLeast(0)), start)
            c.prepare()
            c.play()
            sampleJob?.cancel()
            // Muestra: 30 segundos para conocer la voz antes de decidir.
            if (sample) sampleJob = scope.launch { delay(30_000); controller?.pause() }
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) { c.pause(); return }
        // Si sigues después de la muestra, ya cuenta como escuchar: desde aquí se guarda por dónde vas.
        if (state.value.sample) {
            sampleJob?.cancel()
            state.value.book?.let { AudioStore.put(it.copy(index = c.currentMediaItemIndex.coerceAtLeast(0), position = c.currentPosition.coerceAtLeast(0), updatedAt = System.currentTimeMillis())) }
            state.value = state.value.copy(sample = false)
        }
        c.play()
    }
    /** Seguir después de la muestra: desde aquí cuenta como escuchar y se guarda por dónde vas. */
    fun keepListening() {
        val c = controller ?: return
        if (c.isPlaying && state.value.sample) c.pause()
        toggle()
    }

    fun pause() { controller?.pause() }
    fun seekBy(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    /** Salta a un capítulo y lo retoma donde lo dejaste. */
    fun track(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        persist()
        val at = state.value.book?.let { AudioStore.get(it.id)?.spot(i) } ?: 0L
        c.seekTo(i, at)
    }
    /** Salta a un punto exacto de un capítulo (para seguir desde donde vas leyendo). */
    fun seekTrack(i: Int, ms: Long) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        persist()
        c.seekTo(i, ms.coerceAtLeast(0))
    }
    fun speed(v: Float) { controller?.playbackParameters = PlaybackParameters(v); state.value = state.value.copy(speed = v) }

    /** Temporizador para dormir: minutos, o -1 = al terminar el capítulo, 0 = apagado. */
    fun sleep(minutes: Int) {
        sleepJob?.cancel()
        when {
            minutes > 0 -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                state.value = state.value.copy(sleepAt = at, sleepEndOfTrack = false)
                sleepJob = scope.launch { delay(minutes * 60_000L); controller?.pause(); state.value = state.value.copy(sleepAt = 0) }
            }
            minutes < 0 -> state.value = state.value.copy(sleepAt = 0, sleepEndOfTrack = true)
            else -> state.value = state.value.copy(sleepAt = 0, sleepEndOfTrack = false)
        }
    }

    fun stop() {
        persist()
        controller?.stop()
        controller?.clearMediaItems()
        state.value = State()
    }
}
