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
        videoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(video?.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(video?.codecs),
        videoWidth = video?.width?.takeIf { it > 0 },
        videoHeight = video?.height?.takeIf { it > 0 },
        audioCodec = CustomDefaultTrackNameProvider.formatNameFromMime(audio?.sampleMimeType),
        audioProfile = exoAudioProfile(audio),
        audioChannels = audio?.channelCount?.takeIf { it > 0 },
        hdr = video?.hdrFormats().orEmpty(),
        bitDepth = video?.exoBitDepth()
    )

private fun exoAudioProfile(audio: Format?): String? {
    if (audio == null) return null
    val codecs = audio.codecs.orEmpty().lowercase()
    val label = audio.label.orEmpty().lowercase()
    val combined = "$codecs $label"
    return when {
        combined.contains("atmos") || codecs.contains("joc") -> "Atmos"
        combined.contains("dts:x") || combined.contains("dtsx") -> "DTS:X"
        combined.contains("dts-hd") && combined.contains("ma") -> "DTS-HD MA"
        combined.contains("dts-hd") || combined.contains("dtshd") -> "DTS-HD"
        else -> null
    }
}

private fun Format.hdrFormats(): List<String> = buildList {
    val lowerCodecs = codecs.orEmpty().lowercase()
    // Dolby Vision: mime type or codecs string (dual-layer DV has HEVC mime but dvhe/dav1 codecs)
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        || lowerCodecs.contains("dvhe") || lowerCodecs.contains("dvh1")
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
        c.contains("dvhe") || c.contains("dvh1") -> 10 // DV is always 10-bit
        else -> null
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
        "aac" -> "AAC"
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        "pcm" -> "PCM"
        "dts" -> "DTS"
        else -> codec.trim().uppercase()
    }
