package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateSampleDurationTest {

    // I, then groups of P8 B4 B2 B1 B3 B6 B5 B7: decode order of a stream with B-frame pyramids.
    private fun decodeOrder(sampleCount: Int, frameUs: Double): List<Long> {
        val order = ArrayList<Long>()
        order += 0L
        var base = 0
        while (order.size < sampleCount) {
            for (offset in intArrayOf(8, 4, 2, 1, 3, 6, 5, 7)) {
                if (order.size == sampleCount) break
                order += Math.round((base + offset) * frameUs)
            }
            base += 8
        }
        return order
    }

    @Test
    fun `24 fps in decode order cut inside a group reads as 24 fps`() {
        val frameUs = 1_000_000.0 / 24.0
        val average = FrameRateUtils.averageSampleDurationUs(decodeOrder(350, frameUs), 3, 16)!!
        assertEquals(frameUs, average.toDouble(), 1.0)
    }

    @Test
    fun `23_976 fps in decode order cut inside a group reads as 23_976 fps`() {
        val frameUs = 1_001_000.0 / 24.0
        val average = FrameRateUtils.averageSampleDurationUs(decodeOrder(350, frameUs), 3, 16)!!
        assertEquals(frameUs, average.toDouble(), 1.0)
    }

    @Test
    fun `without the margin the same 23_976 fps window is off by more than the 24 fps gap`() {
        val frameUs = 1_001_000.0 / 24.0
        val average = FrameRateUtils.averageSampleDurationUs(decodeOrder(350, frameUs), 3, 0)!!
        val error = average.toDouble() / frameUs - 1.0
        assertTrue("error $error", error > 0.005)
    }

    @Test
    fun `a read that stops early inside a group still reads as 23_976 fps`() {
        val frameUs = 1_001_000.0 / 24.0
        val average = FrameRateUtils.averageSampleDurationUs(decodeOrder(203, frameUs), 3, 16)!!
        assertEquals(frameUs, average.toDouble(), 1.0)
    }

    @Test
    fun `too few samples give no result`() {
        assertNull(FrameRateUtils.averageSampleDurationUs(decodeOrder(40, 40_000.0), 3, 16))
    }
}
