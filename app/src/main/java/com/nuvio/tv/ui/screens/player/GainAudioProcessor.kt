package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Amplification for decoded PCM. Peaks the gain pushes past the ceiling are held back by a
 * limiter instead of being clipped, and gain changes are smoothed so a step does not click.
 * All channels share one limiter gain, so the balance between them is kept. No look-ahead,
 * so no added latency.
 */
internal class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Int = AUDIO_AMPLIFICATION_MIN_DB

    @Volatile
    private var targetScale: Float = 1f

    private var frame = FloatArray(0)
    private var currentScale = 1f
    private var gainSmoothCoef = 0f
    private var limiterGain = 1f
    private var limiterHold = 0
    private var limiterHoldFrames = 0
    private var limiterReleaseCoef = 0f

    fun setGainDb(db: Int) {
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clampedDb
        targetScale = gainToLinearScale(clampedDb)
    }

    fun isGainEnabled(): Boolean {
        return gainDb != AUDIO_AMPLIFICATION_MIN_DB
    }

    override fun isActive(): Boolean {
        return super.isActive() && isGainEnabled()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        val format = inputAudioFormat
        if (format.channelCount <= 0 || format.sampleRate <= 0) return
        if (frame.size != format.channelCount) frame = FloatArray(format.channelCount)
        val rate = format.sampleRate.toFloat()
        gainSmoothCoef = exp(-1f / (GAIN_SMOOTH_SECONDS * rate))
        limiterReleaseCoef = exp(-1f / (LIMITER_RELEASE_SECONDS * rate))
        limiterHoldFrames = (LIMITER_HOLD_SECONDS * rate).toInt()
        currentScale = targetScale
        limiterGain = 1f
        limiterHold = 0
    }

    override fun onReset() {
        frame = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        val target = targetScale

        if (frame.isNotEmpty()) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, target)
                C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, target)
            }
        }
        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, target: Float) {
        val samples = frame
        while (inputBuffer.remaining() >= samples.size * 2) {
            for (c in samples.indices) samples[c] = inputBuffer.short / PCM16_SCALE
            processFrame(samples, target)
            for (c in samples.indices) {
                val value = (samples[c] * PCM16_SCALE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(value.toShort())
            }
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, target: Float) {
        val samples = frame
        while (inputBuffer.remaining() >= samples.size * 4) {
            for (c in samples.indices) samples[c] = inputBuffer.float
            processFrame(samples, target)
            for (c in samples.indices) outputBuffer.putFloat(samples[c])
        }
    }

    private fun processFrame(samples: FloatArray, target: Float) {
        currentScale = target + gainSmoothCoef * (currentScale - target)
        val scale = currentScale
        var peak = 0f
        for (c in samples.indices) {
            val v = samples[c].let { if (it.isFinite()) it * scale else 0f }
            samples[c] = v
            peak = max(peak, abs(v))
        }
        val needed = if (peak > LIMITER_CEILING) LIMITER_CEILING / peak else 1f
        if (needed < limiterGain) {
            limiterGain = needed
            limiterHold = limiterHoldFrames
        } else if (limiterHold > 0) {
            limiterHold--
        } else {
            limiterGain = 1f + limiterReleaseCoef * (limiterGain - 1f)
            if (limiterGain * peak > LIMITER_CEILING) limiterGain = needed
        }
        val g = limiterGain
        for (c in samples.indices) samples[c] = (samples[c] * g).coerceIn(-1f, 1f)
    }

    private fun gainToLinearScale(db: Int): Float {
        if (db == 0) return 1f
        return 10.0.pow(db / 20.0).toFloat()
    }

    internal companion object {
        const val LIMITER_CEILING = 0.891f
        private const val PCM16_SCALE = 32768f
        private const val GAIN_SMOOTH_SECONDS = 0.02f
        private const val LIMITER_HOLD_SECONDS = 0.025f
        private const val LIMITER_RELEASE_SECONDS = 0.1f
    }
}
