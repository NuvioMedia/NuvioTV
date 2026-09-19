package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw `getPlaybackHeadPosition()` sequences, including the ones logged on a Fire TV Stick 4K Max
 * (mt8696) where a seek left the head 2^32 or 2^33 ahead of the written frames.
 */
class IecPlaybackHeadTrackerTest {

    private fun raw(unsignedValue: Long): Int = unsignedValue.toInt()

    @Test
    fun freshTrack_reportsTheRawHead() {
        val tracker = IecPlaybackHeadTracker()
        assertEquals(0L, tracker.frames(0))
        assertEquals(960_000L, tracker.frames(960_000))
        assertEquals(1_920_000L, tracker.frames(1_920_000))
    }

    @Test
    fun staleHeadAfterFlush_thenZero_isNotAWrap() {
        // Logged: head=6280791 before the seek, 8192 twenty ms after the flush, then 0, then 968834.
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(6_280_791)
        tracker.onFlush()
        assertEquals("paused and flushed: the head is 0 whatever the HAL says", 0L, tracker.frames(8_192))
        tracker.onPlay(8_192)
        assertEquals(0L, tracker.frames(8_192))
        assertEquals(0L, tracker.frames(0))
        assertEquals(968_834L, tracker.frames(968_834))
        assertEquals(1_930_027L, tracker.frames(1_930_027))
    }

    @Test
    fun largeStaleHeadAfterFlush_thenZero_isNotAWrap() {
        // Logged on TrueHD: 2832427 straight after a flush.
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(2_832_427)
        tracker.onFlush()
        assertEquals(0L, tracker.frames(2_832_427))
        tracker.onPlay(2_832_427)
        assertEquals(0L, tracker.frames(0))
        assertEquals(481_813L, tracker.frames(481_813))
    }

    @Test
    fun staleHeadSeenTwice_neverReachesTwoToTheThirtyTwo() {
        // The capture showed 2^33 after some seeks: two drops in a row.
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(5_000_000)
        tracker.onFlush()
        tracker.onPlay(8_192)
        val seen = listOf(0, 8_192, 0, 19_200, 960_000).map { tracker.frames(it) }
        assertTrue("head stayed below 2^32: $seen", seen.all { it < (1L shl 32) })
        assertEquals(960_000L, seen.last())
    }

    @Test
    fun halThatNeverZeroesItsCounter_reportsProgressSinceTheFlush() {
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(4_000_000)
        tracker.onFlush()
        assertEquals(0L, tracker.frames(4_000_000))
        tracker.onPlay(4_000_000)
        assertEquals(0L, tracker.frames(4_000_000))
        assertEquals(192_000L, tracker.frames(4_192_000))
    }

    @Test
    fun cleanFlush_countsFromZero() {
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(7_000_000)
        tracker.onFlush()
        tracker.onPlay(0)
        assertEquals(0L, tracker.frames(0))
        assertEquals(524_669L, tracker.frames(524_669))
    }

    @Test
    fun pauseAndResumeWithoutFlush_changesNothing() {
        val tracker = IecPlaybackHeadTracker()
        assertEquals(1_000_000L, tracker.frames(1_000_000))
        tracker.onPlay(1_000_000)
        assertEquals(1_000_000L, tracker.frames(1_000_000))
        assertEquals(1_500_000L, tracker.frames(1_500_000))
    }

    @Test
    fun crossingTwoToTheThirtyOne_isNotAWrap() {
        // The raw value is an Int: past 2^31 it is negative. 3.1 h at 192 kHz.
        val tracker = IecPlaybackHeadTracker()
        assertEquals(0x7FFF_FF00L, tracker.frames(raw(0x7FFF_FF00L)))
        assertEquals(0x8000_0100L, tracker.frames(raw(0x8000_0100L)))
        assertEquals(0x9000_0000L, tracker.frames(raw(0x9000_0000L)))
    }

    @Test
    fun genuineWrap_addsTwoToTheThirtyTwo() {
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(raw(0x8000_0000L))
        assertEquals(0xFFFF_F000L, tracker.frames(raw(0xFFFF_F000L)))
        assertEquals((1L shl 32) + 0x100L, tracker.frames(raw(0x1_0000_0100L)))
        assertEquals((1L shl 32) + 0x2000L, tracker.frames(raw(0x1_0000_2000L)))
    }

    @Test
    fun flushAfterAWrap_startsAgainFromZero() {
        val tracker = IecPlaybackHeadTracker()
        tracker.frames(raw(0xFFFF_F000L))
        tracker.frames(raw(0x1_0000_0100L))
        tracker.onFlush()
        tracker.onPlay(0)
        assertEquals(96_000L, tracker.frames(96_000))
    }
}
