package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailSeasonSelectionTest {
    private val season3And4Videos = listOf(
        episode("s3e12", 3, 12),
        episode("s3e13", 3, 13),
        episode("s4e1", 4, 1)
    )
    private val seasons = listOf(1, 2, 3, 4)

    @Test
    fun `restore keeps the requested episode when it is still on the visible season`() {
        val episodes = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13))

        assertEquals(
            "s3e13",
            resolveVisibleEpisodeRestoreId(
                requestedId = "s3e13",
                episodesForSeason = episodes,
                nextVideoId = "s4e1"
            )
        )
    }

    @Test
    fun `restore lands on next to watch when the played episode left the visible season`() {
        val episodes = listOf(episode("s4e1", 4, 1), episode("s4e2", 4, 2))

        assertEquals(
            "s4e1",
            resolveVisibleEpisodeRestoreId(
                requestedId = "s3e13",
                episodesForSeason = episodes,
                nextVideoId = "s4e1"
            )
        )
    }

    @Test
    fun `restore falls back to the first visible episode`() {
        val episodes = listOf(episode("s4e1", 4, 1), episode("s4e2", 4, 2))

        assertEquals(
            "s4e1",
            resolveVisibleEpisodeRestoreId(
                requestedId = "s3e13",
                episodesForSeason = episodes,
                nextVideoId = null
            )
        )
    }

    @Test
    fun `return focus prefers the advanced next to watch season`() {
        assertEquals(
            4,
            resolveReturnFocusSeason(
                playedSeason = 3,
                selectedSeason = 3,
                nextSeason = 4,
                availableSeasons = seasons
            )
        )
    }

    @Test
    fun `return focus keeps a season that already advanced`() {
        assertEquals(
            4,
            resolveReturnFocusSeason(
                playedSeason = 3,
                selectedSeason = 4,
                nextSeason = 3,
                availableSeasons = seasons,
                hasWaitedForSeasonAdvance = true
            )
        )
    }

    @Test
    fun `return focus prefers played season over a later selected when opening an earlier episode`() {
        assertEquals(
            2,
            resolveReturnFocusSeason(
                playedSeason = 2,
                selectedSeason = 4,
                nextSeason = 1,
                availableSeasons = seasons
            )
        )
    }

    @Test
    fun `return focus stays on the played season when next to watch has not advanced`() {
        assertEquals(
            3,
            resolveReturnFocusSeason(
                playedSeason = 3,
                selectedSeason = 3,
                nextSeason = 3,
                availableSeasons = seasons
            )
        )
    }

    @Test
    fun `return focus keeps played season when unwatched next to watch is still earlier`() {
        assertEquals(
            4,
            resolveReturnFocusSeason(
                playedSeason = 4,
                selectedSeason = 1,
                nextSeason = 1,
                availableSeasons = seasons
            )
        )
    }

    @Test
    fun `step selects played season when unwatched next to watch is still earlier`() {
        assertEquals(
            ReturnFocusStep.SelectSeason(4),
            resolveReturnFocusStep(
                playedSeason = 4,
                playedEpisode = 10,
                selectedSeason = 1,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = listOf(
                    episode("s1e1", 1, 1),
                    episode("s4e10", 4, 10)
                ),
                requestedEpisodeId = "s4e10",
                episodesForSeason = listOf(episode("s1e1", 1, 1)),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `step selects played season when returning from an earlier season episode`() {
        assertEquals(
            ReturnFocusStep.SelectSeason(2),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 5,
                selectedSeason = 4,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = listOf(
                    episode("s1e1", 1, 1),
                    episode("s2e5", 2, 5),
                    episode("s2e6", 2, 6),
                    episode("s4e1", 4, 1)
                ),
                requestedEpisodeId = "s2e5",
                episodesForSeason = listOf(episode("s4e1", 4, 1)),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `return focus waits only when a later season can still advance`() {
        assertTrue(shouldWaitForReturnFocusSeasonAdvance(3, 13, 3, season3And4Videos, seasons))
        assertTrue(shouldWaitForReturnFocusSeasonAdvance(3, 13, null, season3And4Videos, seasons))
        assertFalse(shouldWaitForReturnFocusSeasonAdvance(3, 13, 4, season3And4Videos, seasons))
        assertFalse(shouldWaitForReturnFocusSeasonAdvance(3, 12, 3, season3And4Videos, seasons))
        assertFalse(
            shouldWaitForReturnFocusSeasonAdvance(
                playedSeason = 3,
                playedEpisode = 13,
                nextSeason = 3,
                allVideos = season3And4Videos,
                availableSeasons = listOf(1, 2, 3)
            )
        )
    }

    @Test
    fun `restore is null when the updated season has no episodes`() {
        assertNull(
            resolveVisibleEpisodeRestoreId(
                requestedId = "s3e13",
                episodesForSeason = emptyList(),
                nextVideoId = "s4e1"
            )
        )
    }

    @Test
    fun `step waits before the first restore when next to watch is stale`() {
        assertEquals(
            ReturnFocusStep.WaitForSeasonAdvance,
            step(
                nextSeason = 3,
                selectedSeason = 3,
                episodesForSeason = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13))
            )
        )
    }

    @Test
    fun `step fallback restore after wait does not consume the request`() {
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s3e13", consumeRequest = false),
            step(
                nextSeason = 3,
                selectedSeason = 3,
                episodesForSeason = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13)),
                hasWaitedForSeasonAdvance = true
            )
        )
    }

    @Test
    fun `step stays idle after fallback while next to watch is still stale`() {
        assertEquals(
            ReturnFocusStep.Idle,
            step(
                nextSeason = 3,
                selectedSeason = 3,
                episodesForSeason = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13)),
                alreadyRestoredId = "s3e13",
                hasWaitedForSeasonAdvance = true
            )
        )
    }

    @Test
    fun `step selects the later season after a late next to watch advance`() {
        assertEquals(
            ReturnFocusStep.SelectSeason(4),
            step(
                nextSeason = 4,
                nextVideoId = "s4e1",
                selectedSeason = 3,
                episodesForSeason = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13)),
                alreadyRestoredId = "s3e13"
            )
        )
    }

    @Test
    fun `step restores next to watch after a late season switch and consumes`() {
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s4e1", consumeRequest = true),
            step(
                nextSeason = 4,
                nextVideoId = "s4e1",
                selectedSeason = 4,
                episodesForSeason = listOf(episode("s4e1", 4, 1), episode("s4e2", 4, 2)),
                alreadyRestoredId = "s3e13"
            )
        )
    }

    @Test
    fun `step restores next to watch immediately when it already advanced`() {
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s4e1", consumeRequest = true),
            step(
                nextSeason = 4,
                nextVideoId = "s4e1",
                selectedSeason = 4,
                episodesForSeason = listOf(episode("s4e1", 4, 1), episode("s4e2", 4, 2))
            )
        )
    }

    @Test
    fun `step stays idle when the updated season has no episodes yet`() {
        assertEquals(
            ReturnFocusStep.Idle,
            step(
                nextSeason = 4,
                nextVideoId = "s4e1",
                selectedSeason = 4,
                episodesForSeason = emptyList()
            )
        )
    }

    @Test
    fun `step restores the finale immediately when no later season exists`() {
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s3e13", consumeRequest = true),
            step(
                nextSeason = 3,
                selectedSeason = 3,
                availableSeasons = listOf(1, 2, 3),
                episodesForSeason = listOf(episode("s3e12", 3, 12), episode("s3e13", 3, 13))
            )
        )
    }

    @Test
    fun `step restores the exit episode on the visible season over next to watch`() {
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s4e3", consumeRequest = true),
            resolveReturnFocusStep(
                playedSeason = 4,
                playedEpisode = 3,
                selectedSeason = 4,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = listOf(
                    episode("s1e1", 1, 1),
                    episode("s4e1", 4, 1),
                    episode("s4e2", 4, 2),
                    episode("s4e3", 4, 3)
                ),
                requestedEpisodeId = "s4e3",
                episodesForSeason = listOf(
                    episode("s4e1", 4, 1),
                    episode("s4e2", 4, 2),
                    episode("s4e3", 4, 3)
                ),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `step selects exit season then restores when returning from a half-watched earlier episode`() {
        val catalog = listOf(
            episode("s1e1", 1, 1),
            episode("s1e2", 1, 2),
            episode("s1e3", 1, 3),
            episode("s4e1", 4, 1)
        )
        assertEquals(
            ReturnFocusStep.SelectSeason(1),
            resolveReturnFocusStep(
                playedSeason = 1,
                playedEpisode = 2,
                selectedSeason = 4,
                nextSeason = 4,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s1e2",
                episodesForSeason = listOf(episode("s4e1", 4, 1)),
                nextVideoId = "s4e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s1e2", consumeRequest = true),
            resolveReturnFocusStep(
                playedSeason = 1,
                playedEpisode = 2,
                selectedSeason = 1,
                nextSeason = 4,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s1e2",
                episodesForSeason = listOf(
                    episode("s1e1", 1, 1),
                    episode("s1e2", 1, 2),
                    episode("s1e3", 1, 3)
                ),
                nextVideoId = "s4e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `step selects binge exit season then restores mid-season episode`() {
        val catalog = listOf(
            episode("s1e1", 1, 1),
            episode("s2e3", 2, 3),
            episode("s2e4", 2, 4),
            episode("s2e5", 2, 5),
            episode("s4e1", 4, 1)
        )
        assertEquals(
            ReturnFocusStep.SelectSeason(2),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 4,
                selectedSeason = 4,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s2e4",
                episodesForSeason = listOf(episode("s4e1", 4, 1)),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s2e4", consumeRequest = true),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 4,
                selectedSeason = 2,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s2e4",
                episodesForSeason = listOf(
                    episode("s2e3", 2, 3),
                    episode("s2e4", 2, 4),
                    episode("s2e5", 2, 5)
                ),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `step restores next episode when next to watch advanced past the completed exit`() {
        val catalog = listOf(
            episode("s2e3", 2, 3),
            episode("s2e4", 2, 4),
            episode("s2e5", 2, 5)
        )
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s2e5", consumeRequest = true),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 4,
                selectedSeason = 2,
                nextSeason = 2,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s2e4",
                episodesForSeason = catalog,
                nextVideoId = "s2e5",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    @Test
    fun `step restores binge finale ahead of progress without waiting for season advance`() {
        val catalog = listOf(
            episode("s1e1", 1, 1),
            episode("s2e4", 2, 4),
            episode("s2e5", 2, 5),
            episode("s3e1", 3, 1),
            episode("s4e1", 4, 1)
        )
        assertEquals(
            ReturnFocusStep.SelectSeason(2),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 5,
                selectedSeason = 4,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s2e5",
                episodesForSeason = listOf(episode("s4e1", 4, 1)),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
        assertEquals(
            ReturnFocusStep.RestoreEpisode(episodeId = "s2e5", consumeRequest = true),
            resolveReturnFocusStep(
                playedSeason = 2,
                playedEpisode = 5,
                selectedSeason = 2,
                nextSeason = 1,
                availableSeasons = seasons,
                allVideos = catalog,
                requestedEpisodeId = "s2e5",
                episodesForSeason = listOf(
                    episode("s2e4", 2, 4),
                    episode("s2e5", 2, 5)
                ),
                nextVideoId = "s1e1",
                alreadyRestoredId = null,
                hasWaitedForSeasonAdvance = false
            )
        )
    }

    private fun step(
        nextSeason: Int?,
        selectedSeason: Int,
        nextVideoId: String? = null,
        availableSeasons: Collection<Int> = seasons,
        episodesForSeason: List<Video>,
        alreadyRestoredId: String? = null,
        hasWaitedForSeasonAdvance: Boolean = false
    ) = resolveReturnFocusStep(
        playedSeason = 3,
        playedEpisode = 13,
        selectedSeason = selectedSeason,
        nextSeason = nextSeason,
        availableSeasons = availableSeasons,
        allVideos = season3And4Videos,
        requestedEpisodeId = "s3e13",
        episodesForSeason = episodesForSeason,
        nextVideoId = nextVideoId,
        alreadyRestoredId = alreadyRestoredId,
        hasWaitedForSeasonAdvance = hasWaitedForSeasonAdvance
    )

    private fun episode(id: String, season: Int, number: Int) = Video(
        id = id,
        title = "S$season E$number",
        released = "2020-01-01",
        thumbnail = null,
        season = season,
        episode = number,
        overview = null
    )
}
