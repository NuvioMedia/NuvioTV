package com.nuvio.tv.ui.screens.player

import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.nuvio.tv.core.player.DecodeOrderTimestamps
import java.nio.ByteBuffer

/**
 * MediaCodec video renderer that repairs presentation times for AVI and VFW-style tracks (VC-1, XviD, DivX).
 *
 * Those containers carry decode-order times, so a hardware decoder hands back display-order pictures with
 * times that step backwards and roughly one frame in three is dropped as late. See [DecodeOrderTimestamps].
 * Every other format passes through untouched.
 *
 * Also leaves Android's software decoders out of the fallback list when a hardware decoder supports the
 * format, see [hardwareFirstVideoDecoders], and Android's software AV1 decoders out for the streams dav1d takes,
 * see [withoutAndroidSoftwareAv1].
 *
 * Buffer state advances only when super consumes the buffer: a held (early) buffer is offered again on the
 * next render pass.
 */
internal class DecodeOrderPtsVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    /** Also ask Amlogic decoders to rebuild the times themselves (vendor keys, ignored elsewhere). */
    private val vendorUnstablePts: Boolean = false,
    private val dav1dLoaded: () -> Boolean = { false },
) : MediaCodecVideoRenderer(builder) {

    private var inputFormat: Format? = null
    private var timestamps: DecodeOrderTimestamps? = null
    private var consumed = 0L
    private var loggedEngage = false

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(withoutAndroidSoftwareAv1(mediaCodecSelector, format, dav1dLoaded), format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> = hardwareFirstVideoDecoders(
        format,
        super.getDecoderInfos(
            withoutAndroidSoftwareAv1(mediaCodecSelector, format, dav1dLoaded),
            format,
            requiresSecureDecoder
        )
    )

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val evaluation = super.onInputFormatChanged(formatHolder)
        val format = formatHolder.format
        if (format != null && !sameTrackKind(format, inputFormat)) {
            val depth = DecodeOrderTimestamps.maxReorderFramesFor(format)
            timestamps = depth?.let { DecodeOrderTimestamps(it) }
            consumed = 0L
            loggedEngage = false
        }
        inputFormat = format
        return evaluation
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        timestamps?.onInputQueued(buffer.timeUs)
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        val repair = timestamps
        val presentationTimeUs = repair?.outputTimeUs(bufferPresentationTimeUs) ?: bufferPresentationTimeUs
        // The caller judged "before the seek position" on the decode-order label.
        val decodeOnly = if (repair != null && repair.engaged) {
            presentationTimeUs < lastResetPositionUs
        } else {
            isDecodeOnlyBuffer
        }
        val wasConsumed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            presentationTimeUs,
            decodeOnly,
            isLastBuffer,
            format
        )
        if (wasConsumed && repair != null) {
            repair.onOutputConsumed(bufferPresentationTimeUs)
            logProgress(repair, format)
        }
        return wasConsumed
    }

    // Every codec flush: seeks, and the drop-to-keyframe path when playback falls far behind.
    override fun resetCodecStateForFlush() {
        timestamps?.flush()
        super.resetCodecStateForFlush()
    }

    override fun onCodecReleased(name: String) {
        timestamps?.flush()
        super.onCodecReleased(name)
    }

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(
            format,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId
        )
        if (vendorUnstablePts &&
            DecodeOrderTimestamps.maxReorderFramesFor(format) != null
        ) {
            mediaFormat.setInteger(KEY_AMLOGIC_UNSTABLE_PTS, 1)
            if (format.containerMimeType.equals(MimeTypes.VIDEO_AVI, ignoreCase = true)) {
                mediaFormat.setInteger(KEY_AMLOGIC_AVI_DISCARD, 1)
            }
            Log.i(TAG, "vendor unstable-pts requested: mime=${format.sampleMimeType} container=${format.containerMimeType}")
        }
        return mediaFormat
    }

    private fun logProgress(repair: DecodeOrderTimestamps, format: Format) {
        consumed++
        if (repair.engaged && !loggedEngage) {
            loggedEngage = true
            Log.i(
                TAG,
                "engaged: decode-order times detected, mime=${format.sampleMimeType} " +
                    "container=${format.containerMimeType} frame=$consumed"
            )
        }
        if (repair.engaged && consumed % LOG_EVERY == 0L) {
            Log.i(TAG, "processed=$consumed repaired=${repair.repaired} discarded=${repair.discarded}")
        }
    }

    private fun sameTrackKind(a: Format, b: Format?): Boolean =
        b != null && a.sampleMimeType == b.sampleMimeType && a.containerMimeType == b.containerMimeType

    private companion object {
        const val TAG = "PTS_REPAIR"
        const val KEY_AMLOGIC_UNSTABLE_PTS = "vendor.unstable-pts.enable"
        const val KEY_AMLOGIC_AVI_DISCARD = "vendor.is-avi-discard.enable"
        const val LOG_EVERY = 500L
    }
}
