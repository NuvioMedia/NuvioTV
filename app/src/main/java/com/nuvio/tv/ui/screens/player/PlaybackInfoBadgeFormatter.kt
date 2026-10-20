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

/** Audio codec display name; DD+ JOC is how Dolby Atmos is delivered. */
internal fun formatPlaybackAudioCodecLabel(codec: String?): String? {
    if (codec.isNullOrBlank()) return null
    return if (codec == "E-AC-3-JOC") "Dolby Atmos" else codec
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

/** Badge segments (resolution, HDR, audio codec, audio track) for the current state. */
internal fun buildPlaybackInfoBadgeSegments(
    video: ActivePlaybackInfo?,
    selectedAudio: TrackInfo?
): List<String> = listOfNotNull(
    formatPlaybackResolutionLabel(video?.width, video?.height),
    formatPlaybackHdrLabel(video?.mimeType, video?.colorTransfer),
    formatPlaybackAudioCodecLabel(selectedAudio?.codec),
    formatPlaybackAudioTrackLabel(
        channelCount = selectedAudio?.channelCount,
        language = selectedAudio?.language
    )
)
