package com.nuvio.tv.ui.screens.player

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

private val HARDWARE_FIRST_VIDEO_TYPES = setOf(
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_VP9,
    MimeTypes.VIDEO_AV1,
    MimeTypes.VIDEO_DOLBY_VISION,
)

private val SOFTWARE_DECODER_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.")

/**
 * Android's software decoders are left out only when a hardware decoder supports this exact format
 * (profile, level, size), so a stream beyond the hardware still plays in software.
 */
internal fun <T> hardwareFirstVideoDecoders(
    mimeType: String?,
    decoders: List<T>,
    isSoftware: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
): List<T> {
    if (mimeType !in HARDWARE_FIRST_VIDEO_TYPES) return decoders
    val hardware = decoders.filterNot(isSoftware)
    return if (hardware.any(supportsFormat)) hardware else decoders
}

internal fun hardwareFirstVideoDecoders(format: Format, decoders: List<MediaCodecInfo>): List<MediaCodecInfo> =
    hardwareFirstVideoDecoders(format.sampleMimeType, decoders, ::isSoftwareDecoder) {
        runCatching { it.isFormatSupported(format) }.getOrDefault(false)
    }

/** The platform video renderer with [hardwareFirstVideoDecoders] applied to its decoder list. */
internal class HardwareFirstVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        hardwareFirstVideoDecoders(format, super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder))
}

internal fun isSoftwareDecoder(info: MediaCodecInfo): Boolean =
    info.softwareOnly || hasSoftwareDecoderName(info.name)

internal fun hasSoftwareDecoderName(name: String): Boolean {
    val lower = name.lowercase()
    return SOFTWARE_DECODER_PREFIXES.any { lower.startsWith(it) }
}

/** Extension decoders (libdav1d, libgav1, ffmpeg) are always software. */
internal fun isSoftwareVideoDecoderName(name: String, softwareOnlyNames: Set<String>): Boolean {
    val lower = name.lowercase()
    return lower in softwareOnlyNames ||
        hasSoftwareDecoderName(lower) ||
        lower.startsWith("lib") ||
        lower.startsWith("ffmpeg")
}

internal val softwareOnlyDecoderNames: Set<String> by lazy {
    if (Build.VERSION.SDK_INT < 29) return@lazy emptySet()
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.isSoftwareOnly }
            .map { it.name.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
}
