package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import java.util.Locale

/** VC-1 / WMV detection shared by track selection, playback error handling, and first-frame recovery. */
internal object Vc1VideoFormatHeuristics {

    fun isLikelyVc1(
        sampleMimeType: String? = null,
        codecs: String? = null,
        label: String? = null,
        streamName: String? = null,
    ): Boolean {
        if (sampleMimeType?.equals(MimeTypes.VIDEO_VC1, ignoreCase = true) == true ||
            sampleMimeType?.equals("video/vc1", ignoreCase = true) == true ||
            sampleMimeType?.contains("wvc1", ignoreCase = true) == true ||
            sampleMimeType?.contains("wmv", ignoreCase = true) == true
        ) {
            return true
        }

        val haystack = listOfNotNull(sampleMimeType, codecs, label, streamName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        if (haystack.isEmpty()) return false

        return haystack.contains("wvc1") ||
            haystack.contains("vc-1") ||
            haystack.contains("wmv3") ||
            Regex("(?<![a-z0-9])vc1(?![a-z0-9])").containsMatchIn(haystack)
    }

    fun isLikelyVc1Stream(vararg hints: String?): Boolean {
        val combined = hints.filterNotNull().joinToString(" ")
        if (combined.isBlank()) return false
        return isLikelyVc1(streamName = combined)
    }

    /**
     * Determines whether a [PlaybackException] represents a failure to decode or initialize
     * a VC-1 video stream on ExoPlayer.
     */
    fun isVc1PlaybackFailure(
        error: PlaybackException,
        currentVideoTrackIsLikelyVc1: Boolean,
        currentStreamName: String? = null
    ): Boolean {
        // 1. If we already know the stream's video track is VC-1
        if (currentVideoTrackIsLikelyVc1) {
            val rendererFormat = (error as? ExoPlaybackException)?.rendererFormat
            val failingMime = rendererFormat?.sampleMimeType
            // Only attribute failure to VC-1 if it's not strictly an audio renderer failure
            val isStrictlyAudio = failingMime != null && MimeTypes.isAudio(failingMime)
            if (!isStrictlyAudio) {
                return true
            }
        }

        // 2. Direct format from ExoPlaybackException
        val rendererFormat = (error as? ExoPlaybackException)?.rendererFormat
        if (rendererFormat != null && isLikelyVc1(
                sampleMimeType = rendererFormat.sampleMimeType,
                codecs = rendererFormat.codecs,
                label = rendererFormat.label
            )
        ) {
            return true
        }

        // 3. MediaCodec DecoderInitializationException
        val decoderInit = error.findCause<MediaCodecRenderer.DecoderInitializationException>()
        if (decoderInit != null) {
            if (isLikelyVc1(sampleMimeType = decoderInit.mimeType)) return true
        }

        // 4. Exception message contains VC-1 indicators
        val errorText = buildString {
            append(error.message.orEmpty())
            append(" ")
            append(error.cause?.message.orEmpty())
        }
        if (isLikelyVc1(codecs = errorText)) {
            return true
        }

        // 5. If stream title indicates VC-1 and error is not purely network/IO
        if (isLikelyVc1(streamName = currentStreamName)) {
            val rendererFormat = (error as? ExoPlaybackException)?.rendererFormat
            val failingMime = rendererFormat?.sampleMimeType
            val isStrictlyAudio = failingMime != null && MimeTypes.isAudio(failingMime)
            val isPureIo = error.errorCode in 2000..2008
            if (!isStrictlyAudio && !isPureIo) {
                return true
            }
        }

        return false
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
