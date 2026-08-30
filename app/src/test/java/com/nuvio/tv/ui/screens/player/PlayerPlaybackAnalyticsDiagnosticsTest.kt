package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPlaybackAnalyticsDiagnosticsTest {

    @Test
    fun `vod buffer below duration reports integer percent`() {
        assertEquals(0, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 10_000L))
        assertEquals(25, safeBufferedPercentage(bufferedPositionMs = 2_500L, durationMs = 10_000L))
        assertEquals(99, safeBufferedPercentage(bufferedPositionMs = 9_999L, durationMs = 10_000L))
    }

    @Test
    fun `fully buffered or empty duration reports 100`() {
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 10_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 12_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 0L))
    }

    @Test
    fun `unset or negative times are omitted`() {
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = C.TIME_UNSET))
        assertNull(safeBufferedPercentage(bufferedPositionMs = C.TIME_UNSET, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = -1L, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = -5L))
    }

    @Test
    fun `live iptv timestamps that overflow Media3 percentInt clamp to 100`() {
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_535_769_691_039L, durationMs = 10L)
        )
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_243_001_841_137L, durationMs = 25L)
        )
    }
}
