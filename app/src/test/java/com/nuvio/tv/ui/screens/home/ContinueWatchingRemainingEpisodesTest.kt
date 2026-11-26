package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingRemainingEpisodesTest {

    private val aired = setOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2)

    @Test
    fun `counts aired episodes that are not watched`() {
        assertEquals(3, remainingAiredEpisodeCount(aired, setOf(1 to 1, 1 to 2)))
    }

    @Test
    fun `nothing watched counts every aired episode`() {
        assertEquals(5, remainingAiredEpisodeCount(aired, emptySet()))
    }

    @Test
    fun `fully watched series has no remaining episodes`() {
        assertEquals(0, remainingAiredEpisodeCount(aired, aired))
    }

    @Test
    fun `watched episodes that are not in the aired list are ignored`() {
        assertEquals(4, remainingAiredEpisodeCount(aired, setOf(1 to 1, 0 to 1)))
    }

    @Test
    fun `watched count covering the aired list counts as caught up`() {
        // Provider uses absolute numbering while the addon numbers per season.
        val watched = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        assertEquals(0, remainingAiredEpisodeCount(aired, watched))
    }

    @Test
    fun `empty aired list has no remaining episodes`() {
        assertEquals(0, remainingAiredEpisodeCount(emptySet(), setOf(1 to 1)))
    }
}
