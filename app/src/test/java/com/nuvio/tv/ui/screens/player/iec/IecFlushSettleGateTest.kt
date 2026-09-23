package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settle gate against a fake clock, including the restart gaps logged on a Fire TV Stick 4K Max
 * (mt8696), where a `play()` 47 to 84 ms after `flush()` lost the flush.
 */
class IecFlushSettleGateTest {

    private var now = 0L

    private fun ms(v: Long) = v * 1_000_000L

    private fun gate(settleNanos: Long = IecFlushSettleGate.DEFAULT_SETTLE_NANOS) =
        IecFlushSettleGate(settleNanos = settleNanos, nanoTime = { now })

    @Test
    fun freshTrack_playsAtOnce() {
        now = ms(5)
        assertTrue(gate().mayPlay())
    }

    @Test
    fun shortRestarts_areHeldUntilTheSettleTime() {
        for (gap in longArrayOf(0, 1, 47, 61, 84, 97, 107, 127, 157, 249)) {
            now = ms(1_000)
            val g = gate()
            g.onFlush()
            now = ms(1_000 + gap)
            assertFalse("held at +$gap ms", g.mayPlay())
        }
    }

    @Test
    fun releasedAtTheSettleTime_andStaysReleasedWithoutAnotherFlush() {
        now = ms(1_000)
        val g = gate()
        g.onFlush()
        now = ms(1_250)
        assertTrue("released at +250 ms", g.mayPlay())
        now = ms(1_251)
        assertTrue("stays released", g.mayPlay())
        now = ms(9_000)
        assertTrue("stays released much later", g.mayPlay())
    }

    @Test
    fun drainRetriesEvery10ms_startAt250ms() {
        now = ms(2_000)
        val g = gate()
        g.onFlush()
        var refused = 0
        var startedAt = -1L
        for (t in 0L..400L step 10L) {
            now = ms(2_000 + t)
            if (g.mayPlay()) {
                startedAt = t
                break
            }
            refused++
        }
        assertEquals(250L, startedAt)
        assertEquals(25, refused)
    }

    @Test
    fun restartAfterANetworkRefill_isNotDelayed() {
        now = ms(3_000)
        val g = gate()
        g.onFlush()
        now = ms(3_000 + 2_300)
        assertTrue(g.mayPlay())
    }

    @Test
    fun secondFlush_restartsTheSettleTime() {
        now = ms(4_000)
        val g = gate()
        g.onFlush()
        now = ms(4_150)
        g.onFlush()
        now = ms(4_260)
        assertFalse("held 110 ms after the second flush", g.mayPlay())
        now = ms(4_400)
        assertTrue("released 250 ms after the second flush", g.mayPlay())
    }

    @Test
    fun flushAfterARelease_armsAgain() {
        now = ms(4_000)
        val g = gate()
        g.onFlush()
        now = ms(4_400)
        assertTrue(g.mayPlay())
        now = ms(5_000)
        g.onFlush()
        now = ms(5_100)
        assertFalse(g.mayPlay())
    }

    @Test
    fun onlyClockDifferencesMatter_negativeAndWrappingClock() {
        now = Long.MIN_VALUE + ms(10)
        var g = gate()
        g.onFlush()
        now = Long.MIN_VALUE + ms(100)
        assertFalse("negative clock: held", g.mayPlay())
        now = Long.MIN_VALUE + ms(260)
        assertTrue("negative clock: released", g.mayPlay())

        now = Long.MAX_VALUE - ms(100)
        g = gate()
        g.onFlush()
        now = Long.MAX_VALUE - ms(100) + ms(300)
        assertTrue("clock crossing Long.MAX_VALUE: released", g.mayPlay())
    }

    @Test
    fun customSettleTime() {
        now = 0
        val g = gate(settleNanos = ms(100))
        g.onFlush()
        now = ms(99)
        assertFalse(g.mayPlay())
        now = ms(100)
        assertTrue(g.mayPlay())
    }
}
