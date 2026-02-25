package com.nuvio.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nuvio.tv.core.recommendations.RecommendationConstants
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.worker.TvRecommendationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var tvRecommendationManager: TvRecommendationManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initializeTvRecommendations()
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .bitmapFactoryMaxParallelism(2)
            .allowRgb565(true)
            .crossfade(false)
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
        val workRequest = PeriodicWorkRequestBuilder<TvRecommendationWorker>(
            RecommendationConstants.SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecommendationConstants.WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
