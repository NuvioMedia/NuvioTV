package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingNextUpSeedPolicyTest {
    @Test
    fun `furthest mode selects the highest episode even when it was watched earlier`() {
        val result = selectPreferredTrackingNextUpSeed(
            candidates = listOf(
                progress("show", season = 1, episode = 8, lastWatched = 100L),
                progress("show", season = 1, episode = 3, lastWatched = 200L)
            ),
            preferFurthestEpisode = true
        )

        assertEquals(8, result?.episode)
        assertEquals(100L, result?.lastWatched)
    }

    @Test
    fun `recent mode selects the newest timestamp even when the episode is earlier`() {
        val result = selectPreferredTrackingNextUpSeed(
            candidates = listOf(
                progress("show", season = 1, episode = 8, lastWatched = 100L),
                progress("show", season = 1, episode = 3, lastWatched = 200L)
            ),
            preferFurthestEpisode = false
        )

        assertEquals(3, result?.episode)
        assertEquals(200L, result?.lastWatched)
    }

    @Test
    fun `equal timestamps use episode position deterministically`() {
        val result = selectPreferredTrackingNextUpSeed(
            candidates = listOf(
                progress("show", season = 1, episode = 1, lastWatched = 200L),
                progress("show", season = 2, episode = 1, lastWatched = 200L),
                progress("show", season = 1, episode = 8, lastWatched = 200L)
            ),
            preferFurthestEpisode = false
        )

        assertEquals(2, result?.season)
        assertEquals(1, result?.episode)
    }

    @Test
    fun `selection ranks shows using the chosen seed timestamp and episode`() {
        val result = selectPreferredTrackingNextUpSeeds(
            candidates = listOf(
                progress("show-a", season = 1, episode = 8, lastWatched = 100L),
                progress("show-a", season = 1, episode = 3, lastWatched = 300L),
                progress("show-b", season = 2, episode = 1, lastWatched = 200L)
            ),
            preferFurthestEpisode = true
        )

        assertEquals(listOf("show-b", "show-a"), result.map(WatchProgress::contentId))
        assertEquals(listOf(200L, 100L), result.map(WatchProgress::lastWatched))
    }

    @Test
    fun `provider source precedence remains ahead of episode ordering`() {
        val result = selectPreferredTrackingNextUpSeed(
            candidates = listOf(
                progress(
                    contentId = "show",
                    season = 4,
                    episode = 8,
                    lastWatched = 300L,
                    source = WatchProgress.SOURCE_TRAKT_HISTORY
                ),
                progress(
                    contentId = "show",
                    season = 4,
                    episode = 6,
                    lastWatched = 200L,
                    source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
                )
            ),
            preferFurthestEpisode = true
        )

        assertEquals(6, result?.episode)
    }

    @Test
    fun `empty candidates produce no seed`() {
        assertNull(
            selectPreferredTrackingNextUpSeed(
                candidates = emptyList(),
                preferFurthestEpisode = true
            )
        )
    }

    private fun progress(
        contentId: String,
        season: Int,
        episode: Int,
        lastWatched: Long,
        source: String = WatchProgress.SOURCE_SIMKL_PLAYBACK
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = source
    )
}
