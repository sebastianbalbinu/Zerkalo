package com.sebas.zerkalo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.sebas.zerkalo.data.Catalogs
import com.sebas.zerkalo.data.Library
import com.sebas.zerkalo.data.Prefs
import com.sebas.zerkalo.data.ReadiumCore

class ZerkaloApp : Application(), ImageLoaderFactory {
    /** Readium se prepara solo cuando se necesita (abrir o importar un libro). */
    val readium: ReadiumCore by lazy { ReadiumCore(this) }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        runCatching { Library.dedupe() }.onSuccess { if (it > 0) android.util.Log.i("Zerkalo", "Repetidos quitados: $it") }
        com.sebas.zerkalo.data.Cards.init(this)
        com.sebas.zerkalo.data.Stamps.init(this)
        Catalogs.init(this)
        com.sebas.zerkalo.data.AudioStore.init(this)
        com.sebas.zerkalo.data.Explore.init(this)
        com.sebas.zerkalo.data.Scanner.init(this)
    }

    /** Portadas: caché en memoria pequeña y caché en disco, para que el Tansu vuele la segunda vez. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("portadas")).maxSizeBytes(80L * 1024 * 1024).build() }
        .crossfade(160)
        .allowRgb565(true) // las portadas no tienen transparencia: la mitad de memoria
        .respectCacheHeaders(false)
        .build()
}
