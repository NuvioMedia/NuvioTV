package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nuvio.tv.data.local.NightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class NightModeAudioProcessorTest {

    private val levels = listOf(NightMode.LIGHT, NightMode.MEDIUM, NightMode.STRONG)

    @Test
    fun `full scale is never lifted`() {
        levels.forEach { mode ->
            assertEquals("$mode", 0f, NightModeAudioProcessor.liftDb(mode, 1f), 0.01f)
        }
    }

    @Test
    fun `quiet passages get the full lift`() {
        val quiet = dbToLevel(-30f)
        assertEquals(6f, NightModeAudioProcessor.liftDb(NightMode.LIGHT, quiet), 0.01f)
        assertEquals(10f, NightModeAudioProcessor.liftDb(NightMode.MEDIUM, quiet), 0.01f)
        assertEquals(15f, NightModeAudioProcessor.liftDb(NightMode.STRONG, quiet), 0.01f)
    }

    @Test
    fun `lift fades out near silence`() {
        levels.forEach { mode ->
            assertEquals("$mode", 0f, NightModeAudioProcessor.liftDb(mode, dbToLevel(-70f)), 0.01f)
            val full = NightModeAudioProcessor.liftDb(mode, dbToLevel(-30f))
            val halfway = NightModeAudioProcessor.liftDb(mode, dbToLevel(-57.5f))
            assertEquals("$mode", full / 2f, halfway, 0.01f)
        }
    }

    @Test
    fun `louder input never gets more lift than quieter input`() {
        levels.forEach { mode ->
            var previous = Float.MAX_VALUE
            var db = -50f
            while (db <= 0f) {
                val lift = NightModeAudioProcessor.liftDb(mode, dbToLevel(db))
                assertTrue("$mode at $db dB", lift <= previous + 0.0001f)
                previous = lift
                db += 0.25f
            }
        }
    }

    @Test
    fun `processor is only active with a night mode level`() {
        val processor = NightModeAudioProcessor()
        processor.configure(FLOAT_STEREO)
        processor.flush()
        assertFalse(processor.isActive)

        processor.setMode(NightMode.MEDIUM)
        processor.configure(FLOAT_STEREO)
        processor.flush()
        assertTrue(processor.isActive)
    }

    @Test
    fun `medium lifts a steady quiet tone by its makeup gain`() {
        val processor = activeProcessor(NightMode.MEDIUM)
        val output = process(processor, sine(peakDb = -30f, seconds = 2f))
        // Skip the first second while the gain settles.
        val settledIn = sine(peakDb = -30f, seconds = 2f).copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        val settledOut = output.copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        assertEquals(10f, rmsDb(settledOut) - rmsDb(settledIn), 0.5f)
    }

    @Test
    fun `strong keeps a full scale tone under the limiter ceiling`() {
        val processor = activeProcessor(NightMode.STRONG)
        val output = process(processor, sine(peakDb = 0f, seconds = 1f))
        val peak = output.maxOf { abs(it) }
        assertTrue("peak $peak", peak <= 0.891f + 0.001f)
    }

    private fun activeProcessor(mode: NightMode): NightModeAudioProcessor {
        return NightModeAudioProcessor().apply {
            setMode(mode)
            configure(FLOAT_STEREO)
            flush()
        }
    }

    private fun process(processor: NightModeAudioProcessor, samples: FloatArray): FloatArray {
        val out = ArrayList<Float>(samples.size)
        val chunk = 1024 * CHANNELS
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunk, samples.size)
            val input = ByteBuffer.allocateDirect((end - offset) * 4).order(ByteOrder.nativeOrder())
            for (i in offset until end) input.putFloat(samples[i])
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            while (output.remaining() >= 4) out.add(output.float)
            offset = end
        }
        return out.toFloatArray()
    }

    private fun sine(peakDb: Float, seconds: Float): FloatArray {
        val amplitude = dbToLevel(peakDb)
        val frames = (SAMPLE_RATE * seconds).toInt()
        return FloatArray(frames * CHANNELS) { index ->
            val frame = index / CHANNELS
            amplitude * sin(2.0 * PI * 220.0 * frame / SAMPLE_RATE).toFloat()
        }
    }

    private fun rmsDb(samples: FloatArray): Float {
        val meanSquare = samples.fold(0.0) { sum, v -> sum + v * v } / samples.size
        return 20f * log10(max(sqrt(meanSquare).toFloat(), 1e-9f))
    }

    private fun dbToLevel(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        val FLOAT_STEREO = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT)
    }
}
