package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePcmTimelineCursorTest {
    @Test
    fun `anchored cursor ignores a large renderer timestamp origin`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 1_238_885L)

        val range = cursor.map(
            rawPresentationTimeUs = 90_373_000_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(1_238_885L, range.startMs)
        assertEquals(1_239_885L, range.endMs)
    }

    @Test
    fun `anchored cursor advances by decoded frames instead of raw timestamp jumps`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 10_000L)
        cursor.map(
            rawPresentationTimeUs = 8_000_000_000L,
            frameCount = 24_000,
            sampleRate = 48_000
        )

        val second = cursor.map(
            rawPresentationTimeUs = 99_000_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(10_500L, second.startMs)
        assertEquals(11_500L, second.endMs)
    }

    @Test
    fun `reset returns an anchored cursor to requested probe position`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 25_000L)
        cursor.map(
            rawPresentationTimeUs = 5_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        cursor.reset()
        val afterReset = cursor.map(
            rawPresentationTimeUs = 25_000_000_000L,
            frameCount = 4_800,
            sampleRate = 48_000
        )

        assertEquals(25_000L, afterReset.startMs)
        assertEquals(25_100L, afterReset.endMs)
    }

    @Test
    fun `unanchored cursor preserves renderer timeline`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = null)

        val range = cursor.map(
            rawPresentationTimeUs = 12_345_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(12_345L, range.startMs)
        assertEquals(13_345L, range.endMs)
    }
}
