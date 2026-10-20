package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.runtime.DeviceVideoCodecCapabilities
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.getStreamUrl() != null ||
            stream.isTorrent() ||
            stream.isDirectDebrid() ||
            // A YouTube id is resolved to a playable URL on the device when the stream is played.
            // Builds without in-app YouTube playback open the watch page instead, an external link.
            (AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null)
    }



    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        deviceVideoCodecs: DeviceVideoCodecCapabilities? = null
    ): Stream? {
        if (streams.isEmpty()) return null

        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null

        // Binge group matching takes priority over mode — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
            // When bingeGroupOnly is set (MANUAL mode with only binge-group
            // preference enabled), don't fall back to a non-matching stream —
            // return null so the caller shows the stream picker instead.
            if (bingeGroupOnly) return null
        }

        if (bingeGroupOnly) return null

        if (mode == StreamAutoPlayMode.MANUAL) return null

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { isPlayable(it) }
            StreamAutoPlayMode.BEST_QUALITY, StreamAutoPlayMode.BALANCED, StreamAutoPlayMode.DATA_SAVER ->
                candidateStreams.filter { isPlayable(it) }.maxByOrNull {
                    scoreSource(it, mode, deviceVideoCodecs).total
                }
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null
                matchingStreams.firstOrNull { isPlayable(it) }
            }

        }
    }

    enum class DecoderSupport { SUPPORTED, SOFTWARE_ONLY, UNSUPPORTED, UNKNOWN }

    /** Every ranking component is explicit; codec evidence stays unknown when either side is unknown. */
    data class SourceScore(
        val resolutionPoints: Int,
        val sizePoints: Int,
        val cachedPoints: Int,
        val decoderCompatibilityPoints: Int,
        val decoderSupport: DecoderSupport,
        val declaredCodecMime: String?,
        val declaredHeight: Int?,
        val declaredBytes: Long?
    ) {
        val total: Int get() = resolutionPoints + sizePoints + cachedPoints + decoderCompatibilityPoints
    }

    fun scoreSource(
        stream: Stream,
        mode: StreamAutoPlayMode,
        deviceVideoCodecs: DeviceVideoCodecCapabilities? = null
    ): SourceScore {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val declaredResolution = parsed?.resolution?.trim()?.lowercase(java.util.Locale.ROOT)
        val height = (when (declaredResolution) {
            "4k", "2160p", "2160" -> 2160
            "1080p", "1080" -> 1080
            "720p", "720" -> 720
            "480p", "480" -> 480
            "360p", "360" -> 360
            else -> stream.qualityValue
        }).takeIf { it in setOf(360, 480, 720, 800, 1080, 1440, 2160, 4320) }
        val bytes = stream.behaviorHints?.videoSize?.takeIf { it > 0 }
            ?: stream.clientResolve?.stream?.raw?.size?.takeIf { it > 0 }
        val resolutionPoints = when (mode) {
            StreamAutoPlayMode.BEST_QUALITY -> height?.div(10) ?: 0
            StreamAutoPlayMode.BALANCED -> when {
                height == null -> 0
                height == 1080 -> 120
                height in 720..800 -> 100
                height < 720 -> 50
                else -> 70
            }
            StreamAutoPlayMode.DATA_SAVER -> when {
                height == null -> 0
                height <= 720 -> 120
                height <= 1080 -> 50
                else -> 10
            }
            else -> 0
        }
        val sizePoints = if (mode == StreamAutoPlayMode.DATA_SAVER && bytes != null) {
            80 - (bytes / (256L * 1024 * 1024)).coerceAtMost(80).toInt()
        } else 0
        val cachedPoints = if (mode.usesSourceScore && stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) 10 else 0
        val declaredCodecMime = declaredVideoMime(parsed?.codec)
        val decoderSupport = decoderSupport(
            declaredCodecMime = declaredCodecMime,
            declaredHeight = height,
            deviceVideoCodecs = deviceVideoCodecs
        )
        val decoderCompatibilityPoints = when {
            decoderSupport == DecoderSupport.UNSUPPORTED -> UNSUPPORTED_DECODER_PENALTY
            decoderSupport == DecoderSupport.SOFTWARE_ONLY && mode != StreamAutoPlayMode.BEST_QUALITY ->
                SOFTWARE_ONLY_DECODER_PENALTY
            else -> 0
        }
        return SourceScore(
            resolutionPoints = resolutionPoints,
            sizePoints = sizePoints,
            cachedPoints = cachedPoints,
            decoderCompatibilityPoints = decoderCompatibilityPoints,
            decoderSupport = decoderSupport,
            declaredCodecMime = declaredCodecMime,
            declaredHeight = height,
            declaredBytes = bytes
        )
    }

    internal fun decoderSupport(
        declaredCodecMime: String?,
        declaredHeight: Int?,
        deviceVideoCodecs: DeviceVideoCodecCapabilities?
    ): DecoderSupport {
        if (declaredCodecMime == null) return DecoderSupport.UNKNOWN
        val decoders = deviceVideoCodecs?.decoders ?: return DecoderSupport.UNKNOWN
        val matchingDecoders = decoders.filter { it.mimeType.equals(declaredCodecMime, ignoreCase = true) }
        if (matchingDecoders.isEmpty()) return DecoderSupport.UNSUPPORTED
        val capableDecoders = if (declaredHeight == null) {
            matchingDecoders
        } else {
            val expectedWidth = expectedWidthForHeight(declaredHeight)
            matchingDecoders.filter { decoder ->
                (decoder.maxHeight == null || decoder.maxHeight >= declaredHeight) &&
                    (expectedWidth == null || decoder.maxWidth == null || decoder.maxWidth >= expectedWidth)
            }
        }
        if (capableDecoders.isEmpty()) return DecoderSupport.UNSUPPORTED
        return if (capableDecoders.all { it.hardwareAccelerated == false }) {
            DecoderSupport.SOFTWARE_ONLY
        } else {
            DecoderSupport.SUPPORTED
        }
    }

    internal fun declaredVideoMime(codecLabel: String?): String? {
        val normalized = codecLabel?.lowercase(java.util.Locale.ROOT)
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when {
            normalized.contains("dolbyvision") || normalized.contains("dovi") ||
                normalized.contains("dvhe") || normalized.contains("dvh1") -> "video/dolby-vision"
            normalized.contains("hevc") || normalized.contains("h265") || normalized.contains("x265") ||
                normalized.contains("hev1") || normalized.contains("hvc1") -> "video/hevc"
            normalized.contains("avc") || normalized.contains("h264") || normalized.contains("x264") ||
                normalized.contains("avc1") || normalized.contains("avc3") -> "video/avc"
            normalized.contains("av1") || normalized.contains("av01") -> "video/av01"
            normalized.contains("vp9") || normalized.contains("vp09") -> "video/x-vnd.on2.vp9"
            normalized.contains("vp8") || normalized.contains("vp08") -> "video/x-vnd.on2.vp8"
            normalized.contains("mpeg2") -> "video/mpeg2"
            else -> null
        }
    }

    private fun expectedWidthForHeight(height: Int): Int? = when (height) {
        360 -> 640
        480 -> 854
        720, 800 -> 1280
        1080 -> 1920
        1440 -> 2560
        2160 -> 3840
        4320 -> 7680
        else -> null
    }

    private const val UNSUPPORTED_DECODER_PENALTY = -600
    private const val SOFTWARE_ONLY_DECODER_PENALTY = -160
}
