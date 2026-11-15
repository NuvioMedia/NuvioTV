package com.nuvio.tv.data.trailer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

private val CODECS_PARAM_REGEX = Regex("codecs=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

/** The `codecs` parameter of a YouTube format's `mimeType`, e.g. `av01.0.12M.10`. */
internal fun videoCodecsParam(formatMimeType: String): String? =
    CODECS_PARAM_REGEX.find(formatMimeType)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The decoder MIME type for a `codecs` string, e.g. `av01.0.12M.10` -> `video/av01`. Null when
 * the codec isn't one we know, which callers treat as playable.
 */
internal fun videoCodecMimeType(codecs: String): String? {
    val codec = codecs.trim().lowercase()
    return when {
        codec.startsWith("av01") -> MimeTypes.VIDEO_AV1
        codec.startsWith("vp09") || codec.startsWith("vp9") -> MimeTypes.VIDEO_VP9
        codec.startsWith("avc1") -> MimeTypes.VIDEO_H264
        codec.startsWith("hev1") || codec.startsWith("hvc1") -> MimeTypes.VIDEO_H265
        else -> null
    }
}

/**
 * Asks the same question the player's video renderer asks: is there a decoder for this codec,
 * profile and level at this size and frame rate? YouTube serves many 4K trailers only as AV1,
 * which TV boxes like the SHIELD have no decoder for, so the trailer played its audio over a
 * black screen.
 */
internal object VideoDecoderSupport {
    private val cache = ConcurrentHashMap<String, Boolean>()

    @OptIn(UnstableApi::class)
    fun canDecode(codecs: String, width: Int, height: Int, fps: Int): Boolean =
        cache.getOrPut("$codecs|$width|$height|$fps") {
            val mimeType = videoCodecMimeType(codecs) ?: return@getOrPut true
            val format = Format.Builder()
                .setSampleMimeType(mimeType)
                .setCodecs(codecs)
                .apply {
                    if (width > 0 && height > 0) setWidth(width).setHeight(height)
                    if (fps > 0) setFrameRate(fps.toFloat())
                }
                .build()
            try {
                MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false)
                    .any { it.isFormatSupported(format) }
            } catch (e: Exception) {
                // If the device can't be asked, don't drop streams on a guess.
                true
            }
        }
}
