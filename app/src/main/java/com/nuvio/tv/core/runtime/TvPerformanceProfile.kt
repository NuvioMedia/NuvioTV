package com.nuvio.tv.core.runtime

import android.content.Context
import com.nuvio.tv.domain.model.AnimationIntensity
import com.nuvio.tv.domain.model.NavigationMotion

/** Resource limits for image work on Android TV devices with different memory budgets. */
internal data class TvPerformanceProfile(
    val imageMemoryCachePercent: Double,
    val imageRequests: Int,
    val imageRequestsPerHost: Int,
    val parallelImageDecodes: Int,
    val homeHorizontalImagePrefetchItems: Int,
    val homeImagePrefetchAheadRows: Int,
    val homeCatalogPrefetchAheadRows: Int,
    val homeNestedPrefetchItemCount: Int,
    val allowsRealtimeBlur: Boolean,
    val reducesDefaultMotion: Boolean,
    val trailerPreviewFocusDelayMillis: Long,
    val seekFilmstripDelayMillis: Long?,
    val seekFrameCacheBytes: Long,
    val seekFrameThrottleMillis: Long,
) {
    fun effectiveNavigationMotion(preference: NavigationMotion): NavigationMotion =
        if (reducesDefaultMotion && preference == NavigationMotion.FULL) NavigationMotion.REDUCED else preference

    fun effectiveAnimationIntensity(preference: AnimationIntensity): AnimationIntensity =
        if (reducesDefaultMotion && preference == AnimationIntensity.STANDARD) AnimationIntensity.SUBTLE else preference

    companion object {
        fun detect(context: Context): TvPerformanceProfile {
            return forCapabilities(DeviceCapabilities.memory(context))
        }

        fun forDevice(
            totalMemoryBytes: Long,
            memoryClassMb: Int,
            isLowRamDevice: Boolean,
        ): TvPerformanceProfile = forCapabilities(
            DeviceMemoryCapabilities(totalMemoryBytes, memoryClassMb, isLowRamDevice, Runtime.getRuntime().maxMemory())
        )

        private fun forCapabilities(memory: DeviceMemoryCapabilities): TvPerformanceProfile = when (
            DeviceCapabilities.resourceTier(memory)
        ) {
            DeviceResourceTier.LOW_RAM, DeviceResourceTier.UNKNOWN -> LOW_RAM
            DeviceResourceTier.BALANCED -> BALANCED
            DeviceResourceTier.HIGH_CAPACITY -> HIGH_CAPACITY
        }

        private val LOW_RAM = TvPerformanceProfile(0.08, 4, 2, 1, 0, 0, 0, 0, false, true, 1_500L, null, 1L * MIB, 300L)
        private val BALANCED = TvPerformanceProfile(0.16, 16, 8, 2, 1, 1, 1, 1, true, false, 1_200L, 1_200L, 2L * MIB, 180L)
        private val HIGH_CAPACITY = TvPerformanceProfile(0.25, 32, 16, 4, 2, 2, 2, 2, true, false, 1_200L, 650L, 4L * MIB, 120L)
    }
}
