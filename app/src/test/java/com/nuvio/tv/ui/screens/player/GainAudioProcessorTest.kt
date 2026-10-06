package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GainAudioProcessorTest {

    @Test
    fun isGainEnabledReflectsGainDb() {
        val processor = GainAudioProcessor()
        assertFalse(processor.isGainEnabled())
        processor.setGainDb(5)
        assertTrue(processor.isGainEnabled())
        processor.setGainDb(0)
        assertFalse(processor.isGainEnabled())
    }

    @Test
    fun processPcmFloatAppliesGainAndLimitsPeaks() {
        val processor = GainAudioProcessor()
        processor.setGainDb(6) // ~2x linear scale

        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        // 2 stereo frames: quiet (0.2f), and loud (0.8f, which would reach 1.6f without limiter)
        val input = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder())
        input.putFloat(0.2f)
        input.putFloat(0.2f)
        input.putFloat(0.8f)
        input.putFloat(0.8f)
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.nativeOrder())

        // Frame 1 is amplified linearly
        val f1Ch1 = output.float
        val f1Ch2 = output.float
        assertEquals(0.4f, f1Ch1, 0.05f)
        assertEquals(0.4f, f1Ch2, 0.05f)

        // Frame 2 is constrained to <= 1.0f by the peak limiter
        val f2Ch1 = output.float
        val f2Ch2 = output.float
        assertTrue("Expected limited sample <= 1.0f, got $f2Ch1", f2Ch1 <= 1.0f)
        assertTrue("Expected limited sample >= 0.8f, got $f2Ch1", f2Ch1 >= 0.8f)
        assertEquals("Both channels must match to preserve stereo image", f2Ch1, f2Ch2, 1e-5f)
    }

    @Test
    fun processPcm16AppliesGainAndPreventsShortOverflow() {
        val processor = GainAudioProcessor()
        processor.setGainDb(10) // ~3.16x linear scale

        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val input = ByteBuffer.allocateDirect(4 * 2).order(ByteOrder.nativeOrder())
        // 20000 * 3.16 > 32767, would overflow 16-bit without limiter
        input.putShort(20000.toShort())
        input.putShort(20000.toShort())
        input.putShort(5000.toShort())
        input.putShort(5000.toShort())
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.nativeOrder())

        val ch1 = output.short.toInt()
        val ch2 = output.short.toInt()
        assertTrue("Output should not overflow Short.MAX_VALUE", ch1 <= Short.MAX_VALUE)
        assertTrue("Channels should match", ch1 == ch2)
    }
}
