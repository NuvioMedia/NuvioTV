package com.nuvio.tv.core.runtime

import org.junit.Assert.assertEquals
import com.nuvio.tv.domain.model.AnimationIntensity
import com.nuvio.tv.domain.model.NavigationMotion
import org.junit.Test

class TvPerformanceProfileTest {
    @Test
    fun lowRamDevicesReduceImageCacheAndParallelWork() {
        val profile = TvPerformanceProfile.forDevice(4L * GIB, 256, isLowRamDevice = true)

        assertEquals(0.08, profile.imageMemoryCachePercent, 0.0)
        assertEquals(8, profile.imageRequests)
        assertEquals(4, profile.imageRequestsPerHost)
        assertEquals(1, profile.parallelImageDecodes)
        assertEquals(0, profile.homeImagePrefetchAheadRows)
        assertEquals(0, profile.homeCatalogPrefetchAheadRows)
        assertEquals(0, profile.homeNestedPrefetchItemCount)
        assertEquals(false, profile.allowsRealtimeBlur)
        assertEquals(null, profile.seekFilmstripDelayMillis)
        assertEquals(1L * MIB, profile.seekFrameCacheBytes)
        assertEquals(NavigationMotion.REDUCED, profile.effectiveNavigationMotion(NavigationMotion.FULL))
        assertEquals(NavigationMotion.REDUCED, profile.effectiveNavigationMotion(NavigationMotion.REDUCED))
        assertEquals(NavigationMotion.OFF, profile.effectiveNavigationMotion(NavigationMotion.OFF))
        assertEquals(AnimationIntensity.SUBTLE, profile.effectiveAnimationIntensity(AnimationIntensity.STANDARD))
        assertEquals(AnimationIntensity.CINEMATIC, profile.effectiveAnimationIntensity(AnimationIntensity.CINEMATIC))
        assertEquals(1_500L, profile.trailerPreviewFocusDelayMillis)
    }

    @Test
    fun twoGigabyteDeviceUsesLowRamProfile() {
        val profile = TvPerformanceProfile.forDevice(2L * GIB, 256, isLowRamDevice = false)

        assertEquals(0.08, profile.imageMemoryCachePercent, 0.0)
        assertEquals(1, profile.parallelImageDecodes)
    }

    @Test
    fun midRangeDevicesUseBalancedImageWork() {
        val profile = TvPerformanceProfile.forDevice(3L * GIB, 256, isLowRamDevice = false)

        assertEquals(0.16, profile.imageMemoryCachePercent, 0.0)
        assertEquals(16, profile.imageRequests)
        assertEquals(8, profile.imageRequestsPerHost)
        assertEquals(2, profile.parallelImageDecodes)
        assertEquals(1, profile.homeNestedPrefetchItemCount)
        assertEquals(true, profile.allowsRealtimeBlur)
        assertEquals(1_200L, profile.seekFilmstripDelayMillis)
        assertEquals(2L * MIB, profile.seekFrameCacheBytes)
        assertEquals(NavigationMotion.FULL, profile.effectiveNavigationMotion(NavigationMotion.FULL))
        assertEquals(AnimationIntensity.STANDARD, profile.effectiveAnimationIntensity(AnimationIntensity.STANDARD))
        assertEquals(1_200L, profile.trailerPreviewFocusDelayMillis)
    }

    @Test
    fun highCapacityDevicesKeepFullImageThroughput() {
        val profile = TvPerformanceProfile.forDevice(4L * GIB, 384, isLowRamDevice = false)

        assertEquals(0.25, profile.imageMemoryCachePercent, 0.0)
        assertEquals(32, profile.imageRequests)
        assertEquals(16, profile.imageRequestsPerHost)
        assertEquals(4, profile.parallelImageDecodes)
        assertEquals(2, profile.homeImagePrefetchAheadRows)
        assertEquals(2, profile.homeCatalogPrefetchAheadRows)
        assertEquals(2, profile.homeNestedPrefetchItemCount)
        assertEquals(true, profile.allowsRealtimeBlur)
        assertEquals(650L, profile.seekFilmstripDelayMillis)
        assertEquals(4L * MIB, profile.seekFrameCacheBytes)
        assertEquals(1_200L, profile.trailerPreviewFocusDelayMillis)
    }

    private companion object {
        const val GIB = 1024L * 1024 * 1024
        const val MIB = 1024L * 1024
    }
}
