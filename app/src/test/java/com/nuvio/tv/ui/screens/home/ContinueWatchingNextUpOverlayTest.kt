package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingNextUpOverlayTest {
    private fun info(episode: Int = 6, description: String? = null) = NextUpInfo(
        contentId = "tt43691412",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt43691412:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        episodeDescription = description,
        thumbnail = null,
        hasAired = true,
        lastWatched = 1L,
        sortTimestamp = 1L
    )

    @Test
    fun `overlay enriched in this session is reused for the same episode`() {
        val overlay = info(description = "Enriched")

        assertEquals(overlay, reusableNextUpOverlay(overlay, info(), seededFromDisk = false))
    }

    @Test
    fun `overlay restored from disk does not replace enrichment`() {
        val staleSnapshot = info(description = null)

        assertNull(reusableNextUpOverlay(staleSnapshot, info(description = "From the add-on"), seededFromDisk = true))
    }

    @Test
    fun `overlay for another episode is not reused`() {
        assertNull(reusableNextUpOverlay(info(episode = 5), info(episode = 6), seededFromDisk = false))
    }

    @Test
    fun `no overlay means enriching`() {
        assertNull(reusableNextUpOverlay(null, info(), seededFromDisk = false))
    }
}
