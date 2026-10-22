package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.util.languageCodeToName

/**
 * Snapshot of the active video track used by the playback info badge beside the
 * player title. [mimeType]/[colorTransfer] stay null on mpv, where HDR type is
 * not exposed, so the HDR segment simply drops out of the badge.
 */
data class ActivePlaybackInfo(
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val colorTransfer: Int? = null
)

/** Video and audio chip groups rendered either side of a divider. */
data class PlaybackInfoBadge(
    val videoSegments: List<PlaybackInfoSegment> = emptyList(),
    val audioSegments: List<PlaybackInfoSegment> = emptyList()
) {
    val isEmpty: Boolean get() = videoSegments.isEmpty() && audioSegments.isEmpty()
}

/** Which leading icon a chip shows; null means text only. */
enum class PlaybackInfoIcon { HDR, AUDIO_FORMAT, AUDIO_TRACK }

/** One chip in the badge: its label plus an optional leading icon. */
data class PlaybackInfoSegment(
    val text: String,
    val icon: PlaybackInfoIcon? = null
)

/** Resolution tier label ("4K", "1080p", ...) from the active video dimensions. */
internal fun formatPlaybackResolutionLabel(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    // Rotated streams report swapped dimensions; classify by the longer edge.
    val longEdge = maxOf(width, height)
    return when {
        longEdge >= 3800 -> "4K"
        longEdge >= 2500 -> "1440p"
        longEdge >= 1900 -> "1080p"
        longEdge >= 1050 -> "720p"
        else -> "${minOf(width, height)}p"
    }
}

/**
 * HDR format label for what is actually playing. Mirrors the diagnostics'
 * friendlyVideoHdrType for the common (non-DV-conversion) cases; the badge
 * cannot see the DV conversion mode at track-update time, so a stripped
 * DV7 base layer may still report as Dolby Vision.
 */
internal fun formatPlaybackHdrLabel(mimeType: String?, colorTransfer: Int?): String? {
    val mime = mimeType?.lowercase()
    return when {
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        colorTransfer == C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
}

/** mpv reports short codec ids; map them to the names used elsewhere in the app. */
private val SHORT_AUDIO_CODEC_NAMES = mapOf(
    "eac3" to "E-AC-3",
    "ac3" to "AC-3",
    "truehd" to "TrueHD",
    "dts" to "DTS",
    "dts-hd" to "DTS-HD",
    "aac" to "AAC",
    "flac" to "FLAC",
    "opus" to "Opus",
    "vorbis" to "Vorbis",
    "mp3" to "MP3",
    "alac" to "ALAC"
)

/** Audio codec display name; DD+ JOC is how Dolby Atmos is delivered. */
internal fun formatPlaybackAudioCodecLabel(codec: String?): String? {
    if (codec.isNullOrBlank()) return null
    if (codec == "E-AC-3-JOC") return "Dolby Atmos"
    return SHORT_AUDIO_CODEC_NAMES[codec.lowercase()] ?: codec
}

/** "English 5.1" style segment from the selected audio track, or null. */
internal fun formatPlaybackAudioTrackLabel(
    channelCount: Int?,
    language: String?
): String? {
    val layout = CustomDefaultTrackNameProvider.getChannelLayoutName(channelCount ?: 0)
    val parts = listOfNotNull(
        language?.takeIf { it.isNotBlank() && it != "und" }?.let { languageCodeToName(it) },
        layout
    )
    if (parts.isEmpty()) return null
    return parts.joinToString(" ")
}

/** Badge chip groups for the current state; unknown values drop out. */
internal fun buildPlaybackInfoBadge(
    video: ActivePlaybackInfo?,
    selectedAudio: TrackInfo?
): PlaybackInfoBadge = PlaybackInfoBadge(
    videoSegments = listOfNotNull(
        formatPlaybackResolutionLabel(video?.width, video?.height)
            ?.let { PlaybackInfoSegment(it) },
        formatPlaybackHdrLabel(video?.mimeType, video?.colorTransfer)
            ?.let { PlaybackInfoSegment(it, PlaybackInfoIcon.HDR) }
    ),
    audioSegments = listOfNotNull(
        formatPlaybackAudioCodecLabel(selectedAudio?.codec)
            ?.let { PlaybackInfoSegment(it, PlaybackInfoIcon.AUDIO_FORMAT) },
        formatPlaybackAudioTrackLabel(
            channelCount = selectedAudio?.channelCount,
            language = selectedAudio?.language
        )?.let { PlaybackInfoSegment(it, PlaybackInfoIcon.AUDIO_TRACK) }
    )
)
