package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

internal class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var gainDb: Int = AUDIO_AMPLIFICATION_MIN_DB

    @Volatile
    private var gainScale: Float = 1f

    fun setGainDb(db: Int) {
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clampedDb
        gainScale = gainToLinearScale(clampedDb)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)
        val scale = gainScale

        if (scale == 1f) {
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

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toInt()
            val amplified = (sample * scale)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(amplified.toShort())
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= 4) {
            val sample = inputBuffer.float
            val amplified = (sample * scale).coerceIn(-1f, 1f)
            outputBuffer.putFloat(amplified)
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
