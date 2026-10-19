package com.nuvio.tv.core.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE

/** Resource limits for image work on Android TV devices with different memory budgets. */
internal data class TvPerformanceProfile(
    val imageMemoryCachePercent: Double,
    val imageRequests: Int,
    val imageRequestsPerHost: Int,
    val parallelImageDecodes: Int,
    val homeImagePrefetchAheadRows: Int,
    val homeCatalogPrefetchAheadRows: Int,
    val homeNestedPrefetchItemCount: Int,
    val allowsRealtimeBlur: Boolean,
) {
    companion object {
        private const val GIB = 1024L * 1024L * 1024L

        fun detect(context: Context): TvPerformanceProfile {
            val activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return forDevice(
                totalMemoryBytes = memoryInfo.totalMem,
                memoryClassMb = activityManager.memoryClass,
                isLowRamDevice = activityManager.isLowRamDevice,
            )
        }

        fun forDevice(
            totalMemoryBytes: Long,
            memoryClassMb: Int,
            isLowRamDevice: Boolean,
        ): TvPerformanceProfile = when {
            isLowRamDevice || totalMemoryBytes <= 2L * GIB || memoryClassMb <= 192 -> LOW_RAM
            totalMemoryBytes <= 3L * GIB || memoryClassMb <= 256 -> BALANCED
            else -> HIGH_CAPACITY
        }

        private val LOW_RAM = TvPerformanceProfile(0.08, 8, 4, 1, 0, 0, 0, false)
        private val BALANCED = TvPerformanceProfile(0.16, 16, 8, 2, 1, 1, 1, true)
        private val HIGH_CAPACITY = TvPerformanceProfile(0.25, 32, 16, 4, 2, 2, 2, true)
    }
}
