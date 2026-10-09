package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

internal class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Int = AUDIO_AMPLIFICATION_MIN_DB

    @Volatile
    private var gainScale: Float = 1f

    private var channelCount: Int = 2
    private var envelopeGain: Float = 1.0f
    private var releaseStep: Float = 1.0f / (0.050f * 48000f) // ~50ms release time

    fun setGainDb(db: Int) {
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clampedDb
        gainScale = gainToLinearScale(clampedDb)
    }

    fun isGainEnabled(): Boolean {
        return gainDb != AUDIO_AMPLIFICATION_MIN_DB
    }

    override fun isActive(): Boolean {
        return super.isActive() && isGainEnabled()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        releaseStep = 1.0f / (0.050f * rate)
        envelopeGain = 1.0f
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onReset() {
        envelopeGain = 1.0f
    }

    override fun onFlush() {
        envelopeGain = 1.0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)
        val scale = gainScale

        if (scale == 1f && envelopeGain == 1.0f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, scale)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, scale)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val channels = channelCount
        val bytesPerFrame = channels * 2
        val invMaxShort = 1f / 32767f

        while (inputBuffer.remaining() >= bytesPerFrame) {
            val frameStart = inputBuffer.position()
            var maxPeak = 0f
            for (ch in 0 until channels) {
                val sampleVal = abs(inputBuffer.getShort(frameStart + ch * 2).toFloat()) * invMaxShort
                if (sampleVal > maxPeak) {
                    maxPeak = sampleVal
                }
            }

            val boostedPeak = maxPeak * scale
            if (boostedPeak > 1.0f) {
                val targetGain = 1.0f / boostedPeak
                if (targetGain < envelopeGain) {
                    envelopeGain = targetGain
                } else {
                    envelopeGain = (envelopeGain + releaseStep).coerceAtMost(1.0f)
                }
            } else {
                envelopeGain = (envelopeGain + releaseStep).coerceAtMost(1.0f)
            }

            val effectiveScale = scale * envelopeGain
            for (ch in 0 until channels) {
                val sample = inputBuffer.short.toInt()
                val amplified = (sample * effectiveScale)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(amplified.toShort())
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val channels = channelCount
        val bytesPerFrame = channels * 4

        while (inputBuffer.remaining() >= bytesPerFrame) {
            val frameStart = inputBuffer.position()
            var maxPeak = 0f
            for (ch in 0 until channels) {
                val sampleVal = abs(inputBuffer.getFloat(frameStart + ch * 4))
                if (sampleVal > maxPeak) {
                    maxPeak = sampleVal
                }
            }

            val boostedPeak = maxPeak * scale
            if (boostedPeak > 1.0f) {
                val targetGain = 1.0f / boostedPeak
                if (targetGain < envelopeGain) {
                    envelopeGain = targetGain
                } else {
                    envelopeGain = (envelopeGain + releaseStep).coerceAtMost(1.0f)
                }
            } else {
                envelopeGain = (envelopeGain + releaseStep).coerceAtMost(1.0f)
            }

            val effectiveScale = scale * envelopeGain
            for (ch in 0 until channels) {
                val sample = inputBuffer.float
                val amplified = (sample * effectiveScale).coerceIn(-1f, 1f)
                outputBuffer.putFloat(amplified)
            }
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun gainToLinearScale(db: Int): Float {
        if (db == 0) return 1f
        return 10.0.pow(db / 20.0).toFloat()
    }
}
