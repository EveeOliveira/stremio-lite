package app.stremiolite

import android.app.Application
import app.stremiolite.data.Repo
import app.stremiolite.net.Http
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
    }

    /** Carregador de imagens enxuto: cache pequeno, RGB565, sem crossfade. */
    val images: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(60L * 1024 * 1024).build() }
            .allowRgb565(true)
            .crossfade(false)
            .respectCacheHeaders(false)
            // o gralloc do T950S falha ao alocar hardware bitmaps (ion OOM): usar bitmaps comuns
            .allowHardware(false)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
        /** Escopo que sobrevive as Activities (gravar progresso ao sair do player). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        const val TAG = "StremioLite"
    }
}
