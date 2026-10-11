package com.nuvio.tv.core.streams

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAvailabilityUsenetTest {
    @Test
    fun `ready built-in usenet sources let movies and episodes play without any addon`() {
        val ready = PlaybackAvailability(usenetReady = true)
        assertTrue(ready.canStream("movie", "tt123"))
        assertTrue(ready.canStream("series", "tt123:1:2"))
        assertTrue(ready.canStream("tv", "tmdb:10:0:3"))
        // The search needs a season and an episode; a series id alone finds nothing.
        assertFalse(ready.canStream("series", "tt123"))
        assertFalse(ready.canStream("series", "tmdb:10:1"))
        // Built-in sources only search movies and series.
        assertFalse(ready.canStream("channel", "channel:1"))
        assertFalse(PlaybackAvailability().canStream("movie", "tt123"))
    }
}
