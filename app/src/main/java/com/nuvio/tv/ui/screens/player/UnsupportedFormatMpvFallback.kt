package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.player.DecodeOrderTimestamps
import com.nuvio.tv.data.local.InternalPlayerEngine

/**
 * Formats ExoPlayer cannot show at all (DivX 3, WMV / ASF, RealVideo, a VC-1 or AV1 decoder that will not start) go to
 * MPV, whose software decoders cover them, instead of ending on an error screen or playing sound without picture.
 * One switch per stream. Formats ExoPlayer can play never take this route.
 */
internal object UnsupportedFormatMpvFallbackPolicy {

    fun shouldSwitchForUnplayableVideoTrack(
        exoPlayerActive: Boolean,
        alreadySwitched: Boolean,
        videoTrackPresent: Boolean,
        videoTrackSelected: Boolean,
        videoFormatSupport: Int
    ): Boolean {
        if (!exoPlayerActive || alreadySwitched) return false
        if (!videoTrackPresent || videoTrackSelected) return false
        return videoFormatSupport == C.FORMAT_UNSUPPORTED_SUBTYPE ||
            videoFormatSupport == C.FORMAT_UNSUPPORTED_TYPE
    }

    fun shouldSwitchForFatalError(
        exoPlayerActive: Boolean,
        alreadySwitched: Boolean,
        hasRenderedFirstFrame: Boolean,
        errorCode: Int,
        videoMimeType: String?,
        fileName: String?,
        streamMimeType: String?
    ): Boolean {
        if (!exoPlayerActive || alreadySwitched) return false
        return when (errorCode) {
            // Dead links and web pages end here too, so the name or type must say it is such a file.
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                !hasRenderedFirstFrame && isMpvOnlyContainer(fileName, streamMimeType)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                isLegacyVideoMime(videoMimeType) ||
                    // An AV1 decoder that never starts (no hardware AV1, dav1d unavailable or refusing the stream).
                    (!hasRenderedFirstFrame && videoMimeType?.lowercase() == MimeTypes.VIDEO_AV1)
            else -> false
        }
    }

    /** A transport stream read with sound but no picture: VC-1 there has no reader in ExoPlayer. */
    fun shouldSwitchForMissingVideoTrack(
        exoPlayerActive: Boolean,
        alreadySwitched: Boolean,
        hasAudioTrack: Boolean,
        fileName: String?,
        streamMimeType: String?
    ): Boolean {
        if (!exoPlayerActive || alreadySwitched || !hasAudioTrack) return false
        if (streamMimeType?.lowercase()?.startsWith(MimeTypes.VIDEO_MP2T) == true) return true
        val name = fileName?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
        return TRANSPORT_STREAM_EXTENSIONS.any { name.endsWith(it) }
    }

    /** Containers ExoPlayer has no reader for and MPV has: WMV / ASF, RealMedia, OGM. */
    fun isMpvOnlyContainer(fileName: String?, streamMimeType: String?): Boolean {
        val mime = streamMimeType?.lowercase().orEmpty()
        if (MPV_ONLY_CONTAINER_MIMES.any { mime.startsWith(it) }) return true
        val name = fileName?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: return false
        return MPV_ONLY_CONTAINER_EXTENSIONS.any { name.endsWith(it) }
    }

    private val TRANSPORT_STREAM_EXTENSIONS = listOf(".ts", ".m2ts", ".mts", ".m2t")
    private val MPV_ONLY_CONTAINER_EXTENSIONS = listOf(".wmv", ".asf", ".rm", ".rmvb", ".ogm")
    private val MPV_ONLY_CONTAINER_MIMES = listOf(
        "video/x-ms-wmv", "video/x-ms-asf", "application/vnd.ms-asf", "application/vnd.rn-realmedia",
        "video/vnd.rn-realvideo"
    )

    fun isLegacyVideoMime(mimeType: String?): Boolean =
        DecodeOrderTimestamps.maxReorderFramesFor(mimeType, null) != null ||
            Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = mimeType)
}

/** Switches the current stream to MPV. Returns false when the stream already took this route once. */
internal fun PlayerRuntimeController.switchToMpvForUnsupportedFormat(reason: String): Boolean {
    val url = currentStreamUrl
    // Live TV and catch-up are built around ExoPlayer (channel zap keeps the player, live buffering and
    // frame rate), so they never take this route; a channel ExoPlayer cannot show keeps its own error path.
    if (com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry.isLiveTv(url) ||
        com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry.isCatchup(url) ||
        LivePlaybackUiPolicy.isLiveContentType(contentType)
    ) {
        return false
    }
    if (url.isBlank() || !mpvFormatFallbackStreamUrls.add(url)) return false
    Log.w(
        PlayerRuntimeController.TAG,
        "FORMAT_FALLBACK: ExoPlayer cannot play this stream ($reason), mime=$currentVideoTrackMimeType; switching to MPV"
    )
    switchToInternalPlayerEngine(InternalPlayerEngine.MVP_PLAYER, reason = "unsupported-format:$reason")
    return true
}

internal val PlayerRuntimeController.isExoPlayerActiveForFormatFallback: Boolean
    get() = currentInternalPlayerEngine == InternalPlayerEngine.EXOPLAYER
