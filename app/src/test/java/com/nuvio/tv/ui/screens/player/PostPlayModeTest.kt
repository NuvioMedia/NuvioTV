package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayModeTest {

    private val episodeA = NextEpisodeInfo(
        videoId = "v1",
        season = 1,
        episode = 2,
        title = "Old title",
        thumbnail = null,
        overview = null,
        released = null,
        hasAired = true,
        unairedMessage = null,
    )

    private val episodeAEnriched = episodeA.copy(
        title = "Enriched title",
        overview = "Enriched overview",
    )

    @Test
    fun `copyWithNextEpisode replaces episode on AutoPlay variant and preserves other fields`() {
        val mode = PostPlayMode.AutoPlay(
            nextEpisode = episodeA,
            searching = true,
            sourceName = "ExampleAddon",
            countdownSec = 2,
        )

        val updated = mode.copyWithNextEpisode(episodeAEnriched)

        assertTrue(updated is PostPlayMode.AutoPlay)
        updated as PostPlayMode.AutoPlay
        assertEquals(episodeAEnriched, updated.nextEpisode)
        assertEquals(true, updated.searching)
        assertEquals("ExampleAddon", updated.sourceName)
        assertEquals(2, updated.countdownSec)
    }

    @Test
    fun `copyWithNextEpisode replaces episode on StillWatching variant and preserves countdown`() {
        val mode = PostPlayMode.StillWatching(
            nextEpisode = episodeA,
            countdownSec = 47,
        )

        val updated = mode.copyWithNextEpisode(episodeAEnriched)

        assertTrue(updated is PostPlayMode.StillWatching)
        updated as PostPlayMode.StillWatching
        assertEquals(episodeAEnriched, updated.nextEpisode)
        assertEquals(47, updated.countdownSec)
    }

    @Test
    fun `copyWithNextEpisode returns same instance when episode unchanged`() {
        val mode = PostPlayMode.AutoPlay(nextEpisode = episodeA)
        assertSame(mode, mode.copyWithNextEpisode(episodeA))
    }

    @Test
    fun `blocksNaturalCompletion is true for StillWatching always`() {
        assertTrue(PostPlayMode.StillWatching(nextEpisode = episodeA).blocksNaturalCompletion())
        assertTrue(
            PostPlayMode.StillWatching(nextEpisode = episodeA, countdownSec = 1).blocksNaturalCompletion()
        )
    }

    @Test
    fun `blocksNaturalCompletion is true for AutoPlay only while searching or counting down`() {
        assertTrue(
            PostPlayMode.AutoPlay(nextEpisode = episodeA, searching = true).blocksNaturalCompletion()
        )
        assertTrue(
            PostPlayMode.AutoPlay(nextEpisode = episodeA, sourceName = "X", countdownSec = 1)
                .blocksNaturalCompletion()
        )
    }

    @Test
    fun `blocksNaturalCompletion is false for passive AutoPlay card`() {
        assertFalse(PostPlayMode.AutoPlay(nextEpisode = episodeA).blocksNaturalCompletion())
    }
}
