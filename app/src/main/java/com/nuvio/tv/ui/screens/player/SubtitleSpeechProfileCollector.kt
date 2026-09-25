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
import kotlin.math.abs
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
    anchorMs: Long?
) {
    private companion object {
        const val MAX_FORWARD_PTS_GAP_US = 30_000_000L
        const val MAX_BACKWARD_PTS_JITTER_US = 5_000L
    }

    private var anchorUs = anchorMs?.coerceAtLeast(0L)?.times(1_000L)
    private var segmentAnchorUs = anchorUs
    private var rawBaseUs: Long? = null
    private var nextNormalizedUs: Long? = anchorUs

    fun map(
        rawPresentationTimeUs: Long,
        frameCount: Int,
        sampleRate: Int
    ): SubtitlePcmTimelineRange {
        val currentAnchorUs = segmentAnchorUs
        val expectedStartUs = nextNormalizedUs
        val startUs = if (currentAnchorUs == null) {
            rawPresentationTimeUs
        } else {
            val baseUs = rawBaseUs
            if (baseUs == null) {
                rawBaseUs = rawPresentationTimeUs
                expectedStartUs ?: currentAnchorUs
            } else {
                val mappedRawUs = currentAnchorUs + (rawPresentationTimeUs - baseUs)
                when {
                    expectedStartUs == null -> mappedRawUs
                    mappedRawUs < expectedStartUs - MAX_BACKWARD_PTS_JITTER_US -> expectedStartUs
                    mappedRawUs > expectedStartUs + MAX_FORWARD_PTS_GAP_US -> expectedStartUs
                    else -> mappedRawUs
                }
            }
        }
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

    fun reset(anchorMs: Long? = anchorUs?.div(1_000L)) {
        anchorUs = anchorMs?.coerceAtLeast(0L)?.times(1_000L)
        segmentAnchorUs = anchorUs
        rawBaseUs = null
        nextNormalizedUs = anchorUs
    }

    fun onDiscontinuity() {
        if (anchorUs == null) return
        segmentAnchorUs = nextNormalizedUs ?: anchorUs
        rawBaseUs = null
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
    private var resampleAccumulator = 0.0
    private var resampleAccumulatorCount = 0
    private var vadFrame = ShortArray(VAD_FRAME_SAMPLES)
    private var vadFrameSize = 0
    private var vadFrameStartMs = 0L
    private var vad: VadWebRTC? = null
    private var initializationFailure: String? = null
    private val speechSpans = mutableListOf<SubtitleSyncSpan>()
    private val observedSpans = mutableListOf<SubtitleSyncSpan>()
    private val timelineCursor = SubtitlePcmTimelineCursor(timelineAnchorMs)

    @Synchronized
    fun beginSession(key: String, timelineAnchorMs: Long? = null) {
        sessionKey = key
        collecting = false
        speechSpans.clear()
        observedSpans.clear()
        timelineCursor.reset(timelineAnchorMs)
        // A reusable probe player does not necessarily call AudioSink.configure() after every
        // seek. Keep the already negotiated PCM format while clearing all timing/VAD state.
        resetAudioState(clearFormat = false)
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
            format.pcmEncoding == C.ENCODING_PCM_FLOAT ||
            format.pcmEncoding == C.ENCODING_PCM_24BIT ||
            format.pcmEncoding == C.ENCODING_PCM_32BIT
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
            bytesPerSample = when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_FLOAT -> 4
                else -> 0
            }
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

        val frameSamples = DoubleArray(channelCount)
        repeat(inputFrameCount) { inputFrameIndex ->
            repeat(channelCount) { channel ->
                frameSamples[channel] = readPcmSample(input)
            }
            val mono = downmixSubtitlePcmFrame(frameSamples)
            val sampleTimeMs = startMs + (inputFrameIndex * 1_000L / sampleRate)
            resampleAndEmit(mono, sampleTimeMs)
        }
    }

    @Synchronized
    fun onDiscontinuity() {
        timelineCursor.onDiscontinuity()
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

    private fun resampleAndEmit(mono: Double, sampleTimeMs: Long) {
        if (sampleRate >= TARGET_SAMPLE_RATE) {
            // Average every source bucket before decimation. This inexpensive low-pass filter
            // avoids the strong aliasing produced by selecting one sample out of every 2-3.
            resampleAccumulator += mono
            resampleAccumulatorCount++
            resamplePhase += TARGET_SAMPLE_RATE
            if (resamplePhase >= sampleRate) {
                resamplePhase -= sampleRate
                val filtered = resampleAccumulator / resampleAccumulatorCount.toDouble()
                resampleAccumulator = 0.0
                resampleAccumulatorCount = 0
                emitSample((filtered.coerceIn(-1.0, 1.0) * Short.MAX_VALUE)
                    .roundToInt().toShort(), sampleTimeMs)
            }
            return
        }

        // Very low-rate PCM is uncommon. Preserve duration by duplicating the current sample;
        // WebRTC still receives a valid 16 kHz timeline instead of a shortened signal.
        resamplePhase += TARGET_SAMPLE_RATE
        while (resamplePhase >= sampleRate) {
            resamplePhase -= sampleRate
            emitSample((mono.coerceIn(-1.0, 1.0) * Short.MAX_VALUE)
                .roundToInt().toShort(), sampleTimeMs)
        }
    }

    private fun readPcmSample(input: ByteBuffer): Double = when (pcmEncoding) {
        C.ENCODING_PCM_FLOAT -> input.float.coerceIn(-1f, 1f).toDouble()
        C.ENCODING_PCM_16BIT -> input.short.toDouble() / 32_768.0
        C.ENCODING_PCM_24BIT -> {
            val raw = (input.get().toInt() and 0xff) or
                ((input.get().toInt() and 0xff) shl 8) or
                (input.get().toInt() shl 16)
            raw.toDouble() / 8_388_608.0
        }
        C.ENCODING_PCM_32BIT -> input.int.toDouble() / 2_147_483_648.0
        else -> 0.0
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
        resampleAccumulator = 0.0
        resampleAccumulatorCount = 0
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

/** Center-aware mono fold-down used only by Auto Sync's speech detector. */
internal fun downmixSubtitlePcmFrame(samples: DoubleArray): Double {
    if (samples.isEmpty()) return 0.0
    if (samples.size == 1) return samples[0].coerceIn(-1.0, 1.0)
    if (samples.size == 2) {
        val average = (samples[0] + samples[1]) * 0.5
        val strongest = if (abs(samples[0]) >= abs(samples[1])) samples[0] else samples[1]
        return if (abs(average) < abs(strongest) * 0.12) {
            (strongest * 0.70).coerceIn(-1.0, 1.0)
        } else {
            average.coerceIn(-1.0, 1.0)
        }
    }

    if (samples.size == 4) {
        // Quad PCM normally has no centre or LFE channel.
        return (samples[0] * 0.35 + samples[1] * 0.35 +
            samples[2] * 0.15 + samples[3] * 0.15).coerceIn(-1.0, 1.0)
    }

    // Android decoder PCM normally follows FL, FR, FC, [LFE], surrounds. Dialogue is commonly in
    // FC; LFE is deliberately excluded when present. Extra channels receive a small weight.
    var weighted = samples[0] * 0.25 + samples[1] * 0.25 + samples[2]
    var totalWeight = 1.50
    val firstSurround = if (samples.size >= 6) 4 else 3
    for (index in firstSurround until samples.size) {
        weighted += samples[index] * 0.15
        totalWeight += 0.15
    }
    return (weighted / totalWeight).coerceIn(-1.0, 1.0)
}
