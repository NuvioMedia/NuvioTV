package com.nuvio.tv.core.device

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DeviceTier { LOW, MEDIUM, HIGH }

@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeviceCapabilities"
        private const val LOW_HEAP_THRESHOLD_MB = 256
        private const val HIGH_HEAP_THRESHOLD_MB = 384
    }

    private val _tierOverride = MutableStateFlow<DeviceTier?>(null)
    val tierOverride: StateFlow<DeviceTier?> = _tierOverride.asStateFlow()

    private val detectedTier: DeviceTier = detectTier()

    val tier: DeviceTier
        get() = _tierOverride.value ?: detectedTier

    fun setTierOverride(override: DeviceTier?) {
        val previous = _tierOverride.value
        _tierOverride.value = override
        val effective = override ?: detectedTier
        Log.i(TAG, "Tier override changed: ${previous ?: "auto"} -> ${override ?: "auto"} (effective=$effective, detected=$detectedTier)" +
            " | poster=${tmdbPosterSize}, backdrop=${tmdbBackdropSize}, decoders=$decoderParallelism, fetchers=$fetcherParallelism" +
            " | memCache=${(memoryCachePercent * 100).toInt()}%, diskCache=${diskCacheSizeBytes / 1024 / 1024}MB" +
            " | keyThrottle=${keyRepeatThrottleMs}ms, prefetch=$nestedPrefetchItemCount")
    }

    // -- Coil image loader config --

    val memoryCachePercent: Double
        get() = when (tier) {
            DeviceTier.LOW -> 0.15
            DeviceTier.MEDIUM -> 0.20
            DeviceTier.HIGH -> 0.25
        }

    val diskCacheSizeBytes: Long
        get() = when (tier) {
            DeviceTier.LOW -> 75L * 1024 * 1024
            DeviceTier.MEDIUM -> 150L * 1024 * 1024
            DeviceTier.HIGH -> 200L * 1024 * 1024
        }

    val decoderParallelism: Int
        get() = when (tier) {
            DeviceTier.LOW -> 1
            DeviceTier.MEDIUM -> 2
            DeviceTier.HIGH -> 2
        }

    val fetcherParallelism: Int
        get() = when (tier) {
            DeviceTier.LOW -> 2
            DeviceTier.MEDIUM -> 3
            DeviceTier.HIGH -> 4
        }

    // -- TMDB image sizes --

    val tmdbPosterSize: String
        get() = when (tier) {
            DeviceTier.LOW -> "w185"
            DeviceTier.MEDIUM -> "w342"
            DeviceTier.HIGH -> "w500"
        }

    val tmdbBackdropSize: String
        get() = when (tier) {
            DeviceTier.LOW -> "w780"
            DeviceTier.MEDIUM -> "w780"
            DeviceTier.HIGH -> "w1280"
        }

    val tmdbProfileSize: String
        get() = when (tier) {
            DeviceTier.LOW -> "w185"
            DeviceTier.MEDIUM -> "w342"
            DeviceTier.HIGH -> "w500"
        }

    val tmdbLogoSize: String
        get() = when (tier) {
            DeviceTier.LOW -> "w185"
            DeviceTier.MEDIUM -> "w300"
            DeviceTier.HIGH -> "w500"
        }

    val tmdbStillSize: String
        get() = when (tier) {
            DeviceTier.LOW -> "w300"
            DeviceTier.MEDIUM -> "w300"
            DeviceTier.HIGH -> "w500"
        }

    // -- UI performance --

    val keyRepeatThrottleMs: Long
        get() = when (tier) {
            DeviceTier.LOW -> 140L
            DeviceTier.MEDIUM -> 80L
            DeviceTier.HIGH -> 80L
        }

    val nestedPrefetchItemCount: Int
        get() = when (tier) {
            DeviceTier.LOW -> 0
            DeviceTier.MEDIUM -> 2
            DeviceTier.HIGH -> 2
        }

    private fun detectTier(): DeviceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRam = activityManager.isLowRamDevice
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()

        val tier = when {
            isLowRam || maxHeapMb < LOW_HEAP_THRESHOLD_MB -> DeviceTier.LOW
            maxHeapMb < HIGH_HEAP_THRESHOLD_MB -> DeviceTier.MEDIUM
            else -> DeviceTier.HIGH
        }

        Log.i(TAG, "Device tier: $tier (lowRam=$isLowRam, maxHeap=${maxHeapMb}MB)")
        return tier
    }
}
