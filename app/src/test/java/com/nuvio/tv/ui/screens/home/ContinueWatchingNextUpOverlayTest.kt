package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
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

    @Test
    fun `a card moved on to the next episode keeps the series fields of the enriched one`() {
        val enriched = info(episode = 5, description = "Enriched").copy(
            name = "Sousou no Frieren",
            logo = "logo",
            backdrop = "backdrop",
            genres = listOf("Fantasy"),
            imdbRating = 8.9f
        )
        val moved = info(episode = 6).copy(name = "Frieren: Beyond Journey's End")

        val shown = moved.withSeriesFieldsFrom(enriched)

        assertEquals("Sousou no Frieren", shown.name)
        assertEquals("logo", shown.logo)
        assertEquals("backdrop", shown.backdrop)
        assertEquals(listOf("Fantasy"), shown.genres)
        // Episode fields belong to the new episode.
        assertEquals(6, shown.episode)
        assertEquals("Episode 6", shown.episodeTitle)
        assertNull(shown.episodeDescription)
        assertNull(shown.imdbRating)
    }

    @Test
    fun `a card that was in progress keeps its series title when it moves on`() {
        val inProgress = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt43691412",
                contentType = "series",
                name = "Sousou no Frieren",
                poster = "poster",
                backdrop = null,
                logo = null,
                videoId = "tt43691412:1:5",
                season = 1,
                episode = 5,
                episodeTitle = "Episode 5",
                position = 10L,
                duration = 100L,
                lastWatched = 1L
            ),
            episodeDescription = "Episode 5 description",
            genres = listOf("Fantasy")
        )
        val moved = info(episode = 6).copy(name = "Frieren: Beyond Journey's End")

        val shown = moved.withSeriesFieldsFrom(inProgress)

        assertEquals("Sousou no Frieren", shown.name)
        assertEquals("poster", shown.poster)
        assertEquals(listOf("Fantasy"), shown.genres)
        assertEquals("Episode 6", shown.episodeTitle)
        assertNull(shown.episodeDescription)
    }
}
