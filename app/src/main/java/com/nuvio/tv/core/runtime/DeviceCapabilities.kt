package com.nuvio.tv.core.runtime

import android.app.ActivityManager
import android.content.Context

/** A process-cached view of device memory facts used by playback, cache, and UI policies. */
data class DeviceMemoryCapabilities(
    val totalMemoryBytes: Long?,
    val memoryClassMb: Int?,
    val isLowRamDevice: Boolean?,
    val maxAppHeapBytes: Long,
)

enum class DeviceResourceTier { LOW_RAM, BALANCED, HIGH_CAPACITY, UNKNOWN }

object DeviceCapabilities {
    private const val GIB = 1024L * 1024L * 1024L
    private const val LOW_RAM_MAX_GIB = 2L
    private const val BALANCED_MAX_GIB = 3L

    @Volatile
    private var cachedMemory: DeviceMemoryCapabilities? = null

    /** Reads static hardware facts once per process; the returned values contain no user data. */
    fun memory(context: Context): DeviceMemoryCapabilities {
        cachedMemory?.let { return it }
        return synchronized(this) {
            cachedMemory ?: readMemory(context.applicationContext ?: context).also { cachedMemory = it }
        }
    }

    internal fun readMemory(context: Context): DeviceMemoryCapabilities {
        val activityManager = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        val memoryInfo = ActivityManager.MemoryInfo()
        val hasMemoryInfo = runCatching {
            activityManager?.getMemoryInfo(memoryInfo)
            activityManager != null && memoryInfo.totalMem > 0L
        }.getOrDefault(false)
        return DeviceMemoryCapabilities(
            totalMemoryBytes = memoryInfo.totalMem.takeIf { hasMemoryInfo && it > 0L } ?: readProcMemoryBytes(),
            memoryClassMb = runCatching { activityManager?.memoryClass?.takeIf { it > 0 } }.getOrNull(),
            isLowRamDevice = runCatching { activityManager?.isLowRamDevice }.getOrNull(),
            maxAppHeapBytes = Runtime.getRuntime().maxMemory().coerceAtLeast(0L),
        )
    }

    fun resourceTier(capabilities: DeviceMemoryCapabilities): DeviceResourceTier = resourceTier(
        totalMemoryBytes = capabilities.totalMemoryBytes,
        memoryClassMb = capabilities.memoryClassMb,
        isLowRamDevice = capabilities.isLowRamDevice,
        maxAppHeapBytes = capabilities.maxAppHeapBytes,
    )

    fun resourceTier(
        totalMemoryBytes: Long?,
        memoryClassMb: Int?,
        isLowRamDevice: Boolean?,
        maxAppHeapBytes: Long? = null,
    ): DeviceResourceTier {
        if (isLowRamDevice == true) return DeviceResourceTier.LOW_RAM
        val knownMemory = totalMemoryBytes?.takeIf { it > 0L }
        val knownClass = memoryClassMb?.takeIf { it > 0 }
        val knownHeapMb = maxAppHeapBytes?.takeIf { it > 0L }?.div(MIB)?.takeIf { it > 0L }
        if (knownMemory == null && knownClass == null && knownHeapMb == null && isLowRamDevice == null) {
            return DeviceResourceTier.UNKNOWN
        }
        if (
            (knownMemory != null && knownMemory <= LOW_RAM_MAX_GIB * GIB) ||
            (knownClass != null && knownClass <= 192) ||
            (knownHeapMb != null && knownHeapMb <= 192L)
        ) {
            return DeviceResourceTier.LOW_RAM
        }
        if (
            (knownMemory != null && knownMemory <= BALANCED_MAX_GIB * GIB) ||
            (knownClass != null && knownClass <= 256) ||
            (knownHeapMb != null && knownHeapMb <= 256L)
        ) {
            return DeviceResourceTier.BALANCED
        }
        return DeviceResourceTier.HIGH_CAPACITY
    }

    internal fun clearCachedMemoryForTests() {
        cachedMemory = null
    }

    private fun readProcMemoryBytes(): Long? = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            val firstLine = lines.firstOrNull().orEmpty()
            Regex("^MemTotal:\\s+(\\d+)\\s+kB$").find(firstLine)
                ?.groupValues?.get(1)?.toLongOrNull()?.times(1024L)
        }
    }.getOrNull()?.takeIf { it > 0L }

    private const val MIB = 1024L * 1024L
}
