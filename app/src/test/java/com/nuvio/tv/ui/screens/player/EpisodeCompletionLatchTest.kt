package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeCompletionLatchTest {

    @Test
    fun `the first claim wins`() {
        assertTrue(EpisodeCompletionLatch().claim())
    }

    @Test
    fun `a second claim without a reset does not`() {
        val latch = EpisodeCompletionLatch()
        assertTrue(latch.claim())
        assertFalse(latch.claim())
        assertFalse(latch.claim())
    }

    @Test
    fun `a reset gives the next episode its own claim`() {
        // The regression. An episode that completes must not consume the claim belonging to the
        // episode that follows it.
        val latch = EpisodeCompletionLatch()
        assertTrue(latch.claim())
        latch.reset()
        assertTrue(latch.claim())
    }

    @Test
    fun `a reset without a preceding claim changes nothing`() {
        val latch = EpisodeCompletionLatch()
        latch.reset()
        assertTrue(latch.claim())
        latch.reset()
        latch.reset()
        assertTrue(latch.claim())
        assertFalse(latch.claim())
    }
}
