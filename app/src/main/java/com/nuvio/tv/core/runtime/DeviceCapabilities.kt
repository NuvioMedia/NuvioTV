package com.nuvio.tv.core.runtime

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/** A process-cached view of device memory facts used by playback, cache, and UI policies. */
data class DeviceMemoryCapabilities(
    val totalMemoryBytes: Long?,
    val memoryClassMb: Int?,
    val isLowRamDevice: Boolean?,
    val maxAppHeapBytes: Long,
)

enum class DeviceResourceTier { LOW_RAM, BALANCED, HIGH_CAPACITY, UNKNOWN }

/** Display facts are read on demand because HDMI capabilities can change while the app runs. */
data class DeviceDisplayCapabilities(
    val supportedModes: List<Display.Mode>,
    val currentModeId: Int,
    /** Null means the platform could not report HDR capabilities; an empty array means SDR. */
    val supportedHdrTypes: IntArray?,
)

/** Dolby Vision decoder profiles advertised by Android's regular codec list. */
data class DolbyVisionDecoderCapabilities(
    val profile7: Boolean = false,
    val profile5: Boolean = false,
    val profile8: Boolean = false,
)

/** Advertised video decoder facts; null values mean Android could not report that limit. */
data class DeviceVideoDecoderCapability(
    val mimeType: String,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val hardwareAccelerated: Boolean?
)

/** A null decoder list means enumeration failed; an empty list is a known empty inventory. */
data class DeviceVideoCodecCapabilities(
    val decoders: List<DeviceVideoDecoderCapability>?
)

object DeviceCapabilities {
    private const val GIB = 1024L * 1024L * 1024L
    private const val LOW_RAM_MAX_GIB = 2L
    private const val BALANCED_MAX_GIB = 3L

    @Volatile
    private var cachedMemory: DeviceMemoryCapabilities? = null

    @Volatile
    private var cachedVideoCodecs: DeviceVideoCodecCapabilities? = null

    /** Reads static hardware facts once per process; the returned values contain no user data. */
    fun memory(context: Context): DeviceMemoryCapabilities {
        cachedMemory?.let { return it }
        return synchronized(this) {
            cachedMemory ?: readMemory(context.applicationContext ?: context).also { cachedMemory = it }
        }
    }

    /** Reads display and HDR facts through one shared platform boundary. */
    fun display(context: Context, display: Display?): DeviceDisplayCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || display == null) {
            return DeviceDisplayCapabilities(emptyList(), -1, null)
        }
        val modes = runCatching { display.supportedModes.toList() }.getOrDefault(emptyList())
        val currentModeId = runCatching { display.mode.modeId }.getOrDefault(-1)
        val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            runCatching {
                val displayManager = context.getSystemService(DisplayManager::class.java)
                displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes
            }.getOrNull()
        } else {
            null
        }
        return DeviceDisplayCapabilities(modes, currentModeId, hdrTypes)
    }

    /** Enumerates the platform's regular video decoders once, keeping query failures unknown. */
    fun videoCodecCapabilities(): DeviceVideoCodecCapabilities {
        cachedVideoCodecs?.let { return it }
        return synchronized(this) {
            cachedVideoCodecs ?: readVideoCodecCapabilities().also { cachedVideoCodecs = it }
        }
    }

    private fun readVideoCodecCapabilities(): DeviceVideoCodecCapabilities {
        val codecInfos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrElse { return DeviceVideoCodecCapabilities(decoders = null) }
        val decoders = runCatching {
            buildList {
                codecInfos.asSequence().filterNot { it.isEncoder }.forEach { codec ->
                    runCatching { codec.supportedTypes.toList() }.getOrDefault(emptyList()).asSequence()
                    .filter { it.startsWith(VIDEO_MIME_PREFIX, ignoreCase = true) }
                    .forEach { mimeType ->
                        val videoCapabilities = runCatching {
                            codec.getCapabilitiesForType(mimeType).videoCapabilities
                        }.getOrNull()
                        val width = runCatching { videoCapabilities?.supportedWidths?.upper }
                            .getOrNull()?.takeIf { it > 0 }
                        val height = runCatching { videoCapabilities?.supportedHeights?.upper }
                            .getOrNull()?.takeIf { it > 0 }
                        val isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            runCatching { codec.isHardwareAccelerated }.getOrNull()
                        } else {
                            null
                        }
                        add(
                            DeviceVideoDecoderCapability(
                                mimeType = mimeType.lowercase(java.util.Locale.ROOT),
                                maxWidth = width,
                                maxHeight = height,
                                hardwareAccelerated = isHardwareAccelerated
                            )
                        )
                    }
                }
            }
        }.getOrElse { return DeviceVideoCodecCapabilities(decoders = null) }
            .distinctBy { listOf(it.mimeType, it.maxWidth, it.maxHeight, it.hardwareAccelerated) }
        return DeviceVideoCodecCapabilities(decoders = decoders)
    }

    /** Enumerates advertised Dolby Vision profiles; failures remain an all-false snapshot. */
    fun dolbyVisionDecoderCapabilities(): DolbyVisionDecoderCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return DolbyVisionDecoderCapabilities()
        return runCatching {
            val profiles = mutableSetOf<Int>()
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .filter { codec -> codec.supportedTypes.any { it.equals(DOLBY_VISION_MIME, true) } }
                .forEach { codec ->
                    runCatching { codec.getCapabilitiesForType(DOLBY_VISION_MIME).profileLevels }
                        .getOrNull()
                        ?.forEach { profiles += it.profile }
                }
            dolbyVisionDecoderCapabilities(profiles)
        }.getOrDefault(DolbyVisionDecoderCapabilities())
    }

    internal fun dolbyVisionDecoderCapabilities(profiles: Iterable<Int>): DolbyVisionDecoderCapabilities {
        val profileSet = profiles.toSet()
        return DolbyVisionDecoderCapabilities(
            profile7 = MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb in profileSet,
            profile5 = MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn in profileSet,
            profile8 = MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt in profileSet,
        )
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

    internal fun clearCachedVideoCodecsForTests() {
        cachedVideoCodecs = null
    }

    private fun readProcMemoryBytes(): Long? = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            val firstLine = lines.firstOrNull().orEmpty()
            Regex("^MemTotal:\\s+(\\d+)\\s+kB$").find(firstLine)
                ?.groupValues?.get(1)?.toLongOrNull()?.times(1024L)
        }
    }.getOrNull()?.takeIf { it > 0L }

    private const val MIB = 1024L * 1024L
    private const val DOLBY_VISION_MIME = "video/dolby-vision"
    private const val VIDEO_MIME_PREFIX = "video/"
}
