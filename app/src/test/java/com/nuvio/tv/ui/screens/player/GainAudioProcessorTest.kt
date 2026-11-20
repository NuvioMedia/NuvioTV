package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GainAudioProcessorTest {

    private fun processor(encoding: Int = C.ENCODING_PCM_16BIT, channels: Int = 2, gainDb: Int) =
        GainAudioProcessor().apply {
            setGainDb(gainDb)
            configure(AudioProcessor.AudioFormat(48_000, channels, encoding))
            flush()
        }

    private fun GainAudioProcessor.run16(samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        queueInput(input)
        val out = getOutput().order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun `stays out of the audio path at 0 dB`() {
        assertFalse(processor(gainDb = 0).isActive())
        assertTrue(processor(gainDb = 3).isActive())
    }

    @Test
    fun `quiet sound gets the full gain`() {
        val out = processor(gainDb = 6).run16(ShortArray(2 * 4_800) { 1_000 })
        assertTrue("${out.last()}", abs(out.last() - 1_995) <= 3)
    }

    @Test
    fun `loud sound is limited instead of clipped`() {
        val samples = ShortArray(2 * 48_000) { i -> (28_000 * sin(2.0 * PI * 100.0 * (i / 2) / 48_000)).toInt().toShort() }
        val out = processor(gainDb = 10).run16(samples)
        val ceiling = (GainAudioProcessor.LIMITER_CEILING * 32768f).toInt() + 1
        assertTrue(out.all { abs(it.toInt()) <= ceiling })
        // the waveform keeps its shape: no run of samples stuck at full scale
        assertTrue(out.none { abs(it.toInt()) >= Short.MAX_VALUE - 1 })
    }

    @Test
    fun `both channels get the same limiter gain`() {
        val samples = ShortArray(2 * 9_600) { i -> if (i % 2 == 0) 30_000 else 15_000 }
        val out = processor(gainDb = 10).run16(samples)
        for (f in 4_800 until 9_600) {
            assertEquals(0.5f, out[2 * f + 1].toFloat() / out[2 * f], 0.001f)
        }
    }

    @Test
    fun `float output stays finite and in range`() {
        val p = processor(encoding = C.ENCODING_PCM_FLOAT, channels = 6, gainDb = 10)
        val input = ByteBuffer.allocateDirect(6 * 4 * 4_800).order(ByteOrder.nativeOrder())
        repeat(6 * 4_800) { i -> input.putFloat(if (i % 7 == 0) Float.NaN else 0.9f) }
        input.flip()
        p.queueInput(input)
        val out = p.getOutput().order(ByteOrder.nativeOrder())
        while (out.hasRemaining()) {
            val v = out.float
            assertTrue("$v", v.isFinite() && abs(v) <= 1f)
        }
    }

    @Test
    fun `a gain change glides instead of jumping`() {
        val p = processor(channels = 1, gainDb = 0)
        p.setGainDb(10)
        val out = p.run16(ShortArray(4_800) { 1_000 })
        var maxStep = 0
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        assertTrue("step $maxStep", maxStep < 20)
    }

    @Test
    fun `mpv filter is empty at 0 dB and limited above`() {
        assertEquals("", mpvAmplificationFilter(0))
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "lavfi=[volume=volume=6dB:precision=float,alimiter=limit=0.8910:attack=1:release=100:level=0]",
                mpvAmplificationFilter(6)
            )
        } finally {
            Locale.setDefault(saved)
        }
    }
}
