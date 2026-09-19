package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class SubtitlePcmTimelineRange(
    val startMs: Long,
    val endMs: Long
)

/**
 * Maps decoded PCM onto the public media timeline.
 *
 * Some renderers expose a large container/period origin in AudioSink presentation timestamps.
 * A probe supplies its requested seek position as [anchorMs], then advances solely by decoded
 * frame duration. Normal playback leaves [anchorMs] null and retains the renderer timestamps.
 */
internal class SubtitlePcmTimelineCursor(
    private val anchorMs: Long?
) {
    private val anchorUs = anchorMs?.coerceAtLeast(0L)?.times(1_000L)
    private var nextNormalizedUs: Long? = anchorUs

    fun map(
        rawPresentationTimeUs: Long,
        frameCount: Int,
        sampleRate: Int
    ): SubtitlePcmTimelineRange {
        val startUs = nextNormalizedUs ?: rawPresentationTimeUs
        val durationUs = if (frameCount > 0 && sampleRate > 0) {
            frameCount.toLong() * 1_000_000L / sampleRate.toLong()
        } else {
            0L
        }
        val endUs = startUs + durationUs
        if (anchorUs != null) nextNormalizedUs = endUs
        return SubtitlePcmTimelineRange(
            startMs = startUs / 1_000L,
            endMs = endUs / 1_000L
        )
    }

    fun reset() {
        nextNormalizedUs = anchorUs
    }
}

/** Builds an absolute-timeline speech profile from decoded PCM. */
internal class SubtitleSpeechProfileCollector(
    timelineAnchorMs: Long? = null
) {
    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val VAD_FRAME_SAMPLES = 320
        const val VAD_FRAME_MS = 20L
    }

    private var sessionKey: String? = null
    private var collecting = false
    private var sampleRate: Int = Format.NO_VALUE
    private var channelCount: Int = Format.NO_VALUE
    private var pcmEncoding: Int = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var resamplePhase = 0
    private var vadFrame = ShortArray(VAD_FRAME_SAMPLES)
    private var vadFrameSize = 0
    private var vadFrameStartMs = 0L
    private var vad: VadWebRTC? = null
    private var initializationFailure: String? = null
    private val speechSpans = mutableListOf<SubtitleSyncSpan>()
    private val observedSpans = mutableListOf<SubtitleSyncSpan>()
    private val timelineCursor = SubtitlePcmTimelineCursor(timelineAnchorMs)

    @Synchronized
    fun beginSession(key: String) {
        if (sessionKey == key) return
        sessionKey = key
        collecting = false
        speechSpans.clear()
        observedSpans.clear()
        timelineCursor.reset()
        resetAudioState(clearFormat = true)
    }

    /** Enables the relatively expensive resampling/VAD path only for an explicit Auto Sync run. */
    @Synchronized
    fun startCollecting(clearExisting: Boolean = true) {
        if (clearExisting) {
            speechSpans.clear()
            observedSpans.clear()
            timelineCursor.reset()
        }
        initializationFailure = null
        collecting = true
        resetFraming()
    }

    @Synchronized
    fun stopCollecting(clearExisting: Boolean = false) {
        collecting = false
        if (clearExisting) {
            speechSpans.clear()
            observedSpans.clear()
            timelineCursor.reset()
        }
        resetFraming()
    }

    @Synchronized
    fun configure(format: Format) {
        val supportedEncoding = format.pcmEncoding == C.ENCODING_PCM_16BIT ||
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val isSupportedPcm = format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            supportedEncoding &&
            format.sampleRate > 0 &&
            format.channelCount > 0

        if (!isSupportedPcm) {
            sampleRate = Format.NO_VALUE
            channelCount = Format.NO_VALUE
            pcmEncoding = C.ENCODING_INVALID
            bytesPerSample = 0
            resetFraming()
            return
        }

        if (
            sampleRate != format.sampleRate ||
            channelCount != format.channelCount ||
            pcmEncoding != format.pcmEncoding
        ) {
            sampleRate = format.sampleRate
            channelCount = format.channelCount
            pcmEncoding = format.pcmEncoding
            bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
            resetFraming()
        }
        if (collecting) ensureVad()
    }

    @Synchronized
    fun acceptPcm(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (!collecting || !isPcmConfigured() || presentationTimeUs == C.TIME_UNSET) return
        if (ensureVad() == null) return

        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = bytesPerSample * channelCount
        val inputFrameCount = input.remaining() / bytesPerFrame
        if (inputFrameCount <= 0) return

        val timelineRange = timelineCursor.map(
            rawPresentationTimeUs = presentationTimeUs,
            frameCount = inputFrameCount,
            sampleRate = sampleRate
        )
        val startMs = timelineRange.startMs
        val endMs = timelineRange.endMs
        appendMerged(observedSpans, SubtitleSyncSpan(startMs, endMs), allowedGapMs = 120L)

        repeat(inputFrameCount) { inputFrameIndex ->
            var sum = 0.0
            repeat(channelCount) {
                val value = if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
                    input.float.coerceIn(-1f, 1f).toDouble()
                } else {
                    input.short.toDouble() / Short.MAX_VALUE.toDouble()
                }
                sum += value
            }
            val mono = (sum / channelCount.toDouble()).coerceIn(-1.0, 1.0)
            resamplePhase += TARGET_SAMPLE_RATE
            while (resamplePhase >= sampleRate) {
                resamplePhase -= sampleRate
                val sampleTimeMs = startMs + (inputFrameIndex * 1_000L / sampleRate)
                emitSample((mono * Short.MAX_VALUE).roundToInt().toShort(), sampleTimeMs)
            }
        }
    }

    @Synchronized
    fun onDiscontinuity() {
        resetFraming()
    }

    @Synchronized
    fun resetForAudioTrackChange() {
        speechSpans.clear()
        observedSpans.clear()
        timelineCursor.reset()
        resetAudioState(clearFormat = false)
    }

    @Synchronized
    fun snapshot(): SubtitleSpeechSnapshot = SubtitleSpeechSnapshot(
        speechSpans = speechSpans.toList(),
        observedSpans = observedSpans.toList(),
        pcmAvailable = observedSpans.isNotEmpty() && initializationFailure == null,
        failureReason = initializationFailure
    )

    private fun emitSample(sample: Short, sampleTimeMs: Long) {
        if (vadFrameSize == 0) vadFrameStartMs = sampleTimeMs
        vadFrame[vadFrameSize++] = sample
        if (vadFrameSize < VAD_FRAME_SAMPLES) return

        val isSpeech = try {
            vad?.isSpeech(vadFrame) == true
        } catch (error: Throwable) {
            initializationFailure = error.message ?: error.javaClass.simpleName
            closeVad()
            false
        }
        if (isSpeech) {
            appendMerged(
                speechSpans,
                SubtitleSyncSpan(vadFrameStartMs, vadFrameStartMs + VAD_FRAME_MS),
                allowedGapMs = 300L
            )
        }
        vadFrameSize = 0
    }

    private fun ensureVad(): VadWebRTC? {
        vad?.let { return it }
        if (initializationFailure != null) return null
        return try {
            VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320,
                mode = Mode.AGGRESSIVE
            ).also { vad = it }
        } catch (error: Throwable) {
            initializationFailure = error.message ?: error.javaClass.simpleName
            null
        }
    }

    private fun isPcmConfigured(): Boolean =
        sampleRate > 0 && channelCount > 0 && bytesPerSample > 0

    private fun resetAudioState(clearFormat: Boolean) {
        if (clearFormat) {
            sampleRate = Format.NO_VALUE
            channelCount = Format.NO_VALUE
            pcmEncoding = C.ENCODING_INVALID
            bytesPerSample = 0
        }
        initializationFailure = null
        resetFraming()
    }

    private fun resetFraming() {
        resamplePhase = 0
        vadFrameSize = 0
        closeVad()
        if (collecting && isPcmConfigured()) ensureVad()
    }

    private fun closeVad() {
        val current = vad
        vad = null
        if (current != null) runCatching { current.close() }
    }

    private fun appendMerged(
        target: MutableList<SubtitleSyncSpan>,
        span: SubtitleSyncSpan,
        allowedGapMs: Long
    ) {
        if (span.endMs <= span.startMs) return
        val last = target.lastOrNull()
        if (last == null) {
            target += span
        } else if (span.startMs < last.startMs) {
            val merged = SubtitleAutoSyncEngine.mergeSpans(target + span, allowedGapMs)
            target.clear()
            target.addAll(merged)
        } else if (span.startMs > last.endMs + allowedGapMs) {
            target += span
        } else if (span.endMs > last.endMs) {
            target[target.lastIndex] = last.copy(endMs = span.endMs)
        }
    }
}
