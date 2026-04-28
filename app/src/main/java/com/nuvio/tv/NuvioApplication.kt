package com.nuvio.tv

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism
import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.recommendations.RecommendationConstants
import com.nuvio.tv.core.recommendations.RecommendationDataStore
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.worker.TvRecommendationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tvRecommendationManager: TvRecommendationManager
    @Inject lateinit var recommendationDataStore: RecommendationDataStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return store[url.host]?.filter { cookie ->
                    cookie.expiresAt > System.currentTimeMillis()
                } ?: emptyList()
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { newCookie ->
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PluginRuntimeHooks.onApplicationCreate(this)
        initializeTvRecommendations()
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // Use a lean OkHttpClient for image fetching — no HTTP cache (Coil's own
                // DiskCache handles caching), no cookie jar, no logging interceptors.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.33)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            .allowRgb565(true)
            .bitmapFactoryMaxParallelism(4)
            .build()
    }

    // ── TV Home Screen Recommendations ──

    private fun initializeTvRecommendations() {
        // Create channels asynchronously — no-op on non-TV devices
        appScope.launch {
            try {
                tvRecommendationManager.initializeChannels()
            } catch (_: Exception) {
            }
        }

        // Schedule periodic background sync
        scheduleRecommendationSync()
    }

    private fun scheduleRecommendationSync() {
        appScope.launch {
            recommendationDataStore.syncIntervalHoursFlow.collect { intervalHours ->
                val workManager = WorkManager.getInstance(this@NuvioApplication)
                val workName = RecommendationConstants.WORK_NAME_PERIODIC_SYNC

                if (intervalHours <= 0) {
                    workManager.cancelUniqueWork(workName)
                } else {
                    val workRequest = PeriodicWorkRequestBuilder<TvRecommendationWorker>(
                        intervalHours.toLong(), TimeUnit.HOURS
                    ).setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    ).build()

                    workManager.enqueueUniquePeriodicWork(
                        workName,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        workRequest
                    )
                }
            }
        }
    }
}
