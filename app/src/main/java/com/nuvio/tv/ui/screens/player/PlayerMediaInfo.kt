package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioCodec: String? = null,
    val audioProfile: String? = null,
    val audioChannels: Int? = null,
    val hdr: List<String> = emptyList(),
    val bitDepth: Int? = null
)

internal fun PlayerRuntimeController.playerMediaInfo(): PlayerMediaInfo =
    if (isUsingMpvEngine()) {
        mpvView?.readPlayerMediaInfo() ?: PlayerMediaInfo()
    } else {
        exoMediaInfo(_exoPlayer?.videoFormat, _exoPlayer?.audioFormat)
    }

internal fun mpvMediaInfo(property: (String) -> String?): PlayerMediaInfo =
    PlayerMediaInfo(
        videoCodec = property("current-tracks/video/codec")?.let(::mpvCodecLabel),
        videoWidth = property("video-params/w")?.toIntOrNull()?.takeIf { it > 0 },
        videoHeight = property("video-params/h")?.toIntOrNull()?.takeIf { it > 0 },
        audioCodec = property("current-tracks/audio/codec")?.let(::mpvCodecLabel),
        audioProfile = property("current-tracks/audio/codec-profile"),
        audioChannels = property("audio-params/channel-count")?.toIntOrNull()?.takeIf { it > 0 },
        hdr = buildList {
            if (!property("current-tracks/video/dolby-vision-profile").isNullOrBlank()) add("DV")
            when (property("video-params/gamma")) {
                "pq" -> add("HDR10")
                "hlg" -> add("HLG")
            }
        },
        bitDepth = property("video-params/pixelformat")?.let(::mpvBitDepth)
    )

private fun exoMediaInfo(video: Format?, audio: Format?): PlayerMediaInfo =
    PlayerMediaInfo(
        videoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(video?.sampleMimeType),
        videoWidth = video?.width?.takeIf { it > 0 },
        videoHeight = video?.height?.takeIf { it > 0 },
        audioCodec = CustomDefaultTrackNameProvider.formatNameFromMime(audio?.sampleMimeType),
        audioChannels = audio?.channelCount?.takeIf { it > 0 },
        hdr = video?.hdrFormats().orEmpty(),
        bitDepth = video?.colorInfo?.lumaBitdepth?.takeIf { it > 0 }
    )

private fun Format.hdrFormats(): List<String> = buildList {
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) add("DV")
    when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> add("HDR10")
        C.COLOR_TRANSFER_HLG -> add("HLG")
    }
}

private fun mpvBitDepth(pixelFormat: String): Int? = when {
    pixelFormat.contains("12") -> 12
    pixelFormat.contains("10") -> 10
    else -> null
}

private fun mpvCodecLabel(codec: String): String? =
    when (codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        else -> codec.trim().uppercase()
    }
