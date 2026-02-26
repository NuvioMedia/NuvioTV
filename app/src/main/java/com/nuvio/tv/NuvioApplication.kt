package com.nuvio.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nuvio.tv.core.device.DeviceCapabilities
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory, ComponentCallbacks2 {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var deviceCapabilities: DeviceCapabilities

    private var _imageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        val caps = deviceCapabilities
        val loader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(caps.memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(caps.diskCacheSizeBytes)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(caps.decoderParallelism))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(caps.fetcherParallelism))
            .bitmapFactoryMaxParallelism(caps.decoderParallelism)
            .allowRgb565(true)
            .crossfade(false)
            .build()
        _imageLoader = loader
        return loader
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            _imageLoader?.memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _imageLoader?.memoryCache?.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
