package com.nuvio.tv.core.streams

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAvailabilityUsenetTest {
    @Test
    fun `ready built-in usenet sources let movies and series play without any addon`() {
        val ready = PlaybackAvailability(usenetReady = true)
        assertTrue(ready.canStream("movie", "tt123"))
        assertTrue(ready.canStream("series", "tt123:1:2"))
        assertTrue(ready.canStream("tv", "tmdb:10:1:2"))
        // Built-in sources only search movies and series.
        assertFalse(ready.canStream("channel", "channel:1"))
        assertFalse(PlaybackAvailability().canStream("movie", "tt123"))
    }
}
