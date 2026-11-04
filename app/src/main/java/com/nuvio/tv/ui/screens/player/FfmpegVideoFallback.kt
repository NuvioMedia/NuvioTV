package com.nuvio.tv.ui.screens.player

import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Software decoding through the FFmpeg extension for video codecs that many Android TV chips have
 * no hardware decoder for (VC-1, common on Blu-ray remuxes).
 *
 * The FFmpeg video renderer sits after the platform renderer, so a working hardware decoder is still
 * preferred. When the hardware decoder fails, the stream is retried with the platform decoders for
 * these codecs hidden, which leaves the FFmpeg renderer as the only one able to play it.
 */
internal object FfmpegVideoFallback {

    /** [ExperimentalFfmpegVideoRenderer.getName]. */
    const val RENDERER_NAME = "ExperimentalFfmpegVideoRenderer"

    /** Video MIME types the bundled FFmpeg build decodes. */
    val SOFTWARE_VIDEO_MIME_TYPES: Set<String> = setOf(MimeTypes.VIDEO_VC1)

    val isVc1DecoderAvailable: Boolean by lazy {
        runCatching { FfmpegLibrary.supportsFormat(MimeTypes.VIDEO_VC1) }.getOrDefault(false)
    }

    /** FFmpeg decoder names start with "ffmpeg" (see `FfmpegVideoDecoder.getName`). */
    fun isFfmpegVideoDecoderName(name: String?): Boolean =
        name?.startsWith("ffmpeg", ignoreCase = true) == true

    /** [selector] without platform decoders for [SOFTWARE_VIDEO_MIME_TYPES]. */
    fun withoutPlatformDecoders(selector: MediaCodecSelector): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            if (mimeType in SOFTWARE_VIDEO_MIME_TYPES) {
                emptyList()
            } else {
                selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }

    fun createRenderer(
        allowedJoiningTimeMs: Long,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
    ): Renderer? = runCatching {
        ExperimentalFfmpegVideoRenderer(
            allowedJoiningTimeMs,
            eventHandler,
            eventListener,
            DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        )
    }.getOrNull()

    /**
     * Whether a VC-1 failure should be retried with FFmpeg software decoding: once per stream, only
     * for a decoder failure (a renderer error, or no first frame without any error), only when the
     * FFmpeg decoder exists, and not when FFmpeg was the decoder that failed.
     */
    fun shouldRetryWithSoftwareDecoder(
        alreadyRetried: Boolean,
        softwareDecoderAvailable: Boolean,
        isDecoderFailure: Boolean,
        failedRendererName: String?,
        activeVideoDecoderName: String?,
    ): Boolean {
        if (alreadyRetried || !softwareDecoderAvailable || !isDecoderFailure) return false
        if (failedRendererName == RENDERER_NAME) return false
        if (isFfmpegVideoDecoderName(activeVideoDecoderName)) return false
        return true
    }
}
