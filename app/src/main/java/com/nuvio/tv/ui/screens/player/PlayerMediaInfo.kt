package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioCodec: String? = null,
    val audioProfiles: List<String> = emptyList(),
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
        audioProfiles = listOfNotNull(property("current-tracks/audio/codec-profile")),
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
        videoCodec = exoVideoCodec(video),
        videoWidth = video?.width?.takeIf { it > 0 },
        videoHeight = video?.height?.takeIf { it > 0 },
        audioCodec = CustomDefaultTrackNameProvider.formatNameFromMime(audio?.sampleMimeType),
        audioProfiles = exoAudioProfiles(audio),
        audioChannels = audio?.channelCount?.takeIf { it > 0 },
        hdr = video?.hdrFormats().orEmpty(),
        bitDepth = video?.exoBitDepth()
    )

private fun exoVideoCodec(video: Format?): String? {
    if (video == null) return null
    val mime = video.sampleMimeType
    // For Dolby Vision the mime is always video/dolby-vision regardless of the base codec.
    // Resolve the actual base codec from the codecs string prefix.
    if (mime == MimeTypes.VIDEO_DOLBY_VISION) {
        val c = video.codecs.orEmpty().lowercase()
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> "HEVC"
            c.startsWith("dvav") || c.startsWith("dva1") -> "AVC"
            c.startsWith("dav1") -> "AV1"
            else -> "HEVC" // safe default for unknown DV profiles
        }
    }
    return CustomDefaultTrackNameProvider.resolveCodecName(mime, video.codecs)
}

private val DTS_HD_MA_LABEL = Regex("""\b(ma|master audio)\b""")

@OptIn(UnstableApi::class)
internal fun exoAudioProfiles(audio: Format?): List<String> {
    if (audio == null) return emptyList()
    val mime = audio.sampleMimeType.orEmpty().lowercase()
    val codecs = audio.codecs.orEmpty().lowercase()
    val label = audio.label.orEmpty().lowercase()
    val combined = "$codecs $label"
    return buildList {
        if (combined.contains("atmos")) add("Atmos")
        if (combined.contains("dts:x") || combined.contains("dtsx")
            || mime == MimeTypes.AUDIO_DTS_X) add("DTS:X")
        val isDtsHd = combined.contains("dts-hd") || combined.contains("dtshd")
            || codecs.startsWith("dtsh") || codecs.startsWith("dtsl")
        if (codecs.startsWith("dtsl") || (isDtsHd && DTS_HD_MA_LABEL.containsMatchIn(combined))) add("DTS-HD MA")
        else if (isDtsHd) add("DTS-HD")
    }
}

private fun Format.hdrFormats(): List<String> = buildList {
    val lowerCodecs = codecs.orEmpty().lowercase()
    // Dolby Vision: mime type or codecs string (dual-layer DV has HEVC mime but dvhe/dav1 codecs)
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        || lowerCodecs.contains("dvhe") || lowerCodecs.contains("dvh1")
        || lowerCodecs.contains("dvav")
        || lowerCodecs.contains("dav1") || lowerCodecs.contains("dva1")
    ) {
        add("DV")
    }
    when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> add("HDR10")
        C.COLOR_TRANSFER_HLG -> add("HLG")
    }
}

/**
 * Resolve bit depth from colorInfo, falling back to codecs string heuristics.
 */
private fun Format.exoBitDepth(): Int? {
    colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { return it }
    // Heuristic: HEVC Main 10 profiles have ".2." in the codecs string (profile = 2)
    val c = codecs.orEmpty().lowercase()
    return when {
        c.startsWith("hev1.2.") || c.startsWith("hvc1.2.") -> 10
        c.contains("dvhe") || c.contains("dvh1") -> 10 // DV profile 5/7/8: 10-bit HEVC
        c.startsWith("dav1") -> 10 // DV profile 10: 10-bit AV1
        // dvav/dva1 (DV profile 9) is 8-bit AVC — no fallback needed
        else -> null
    }
}

private fun mpvBitDepth(pixelFormat: String): Int? = when {
    pixelFormat.contains("12") -> 12
    pixelFormat.contains("10") -> 10
    else -> null
}

private fun mpvCodecLabel(codec: String): String? =
    when (val c = codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "dts" -> "DTS"
        else -> when {
            c.startsWith("pcm") -> "PCM"
            else -> codec.trim().uppercase()
        }
    }
