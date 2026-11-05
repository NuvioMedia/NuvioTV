package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.Video
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which episode the detail screen offers while a rewatch run is in progress: the step after the run
 * position, nothing at all once the run has reached the end of the catalogue, and no unaired step
 * while the user keeps unaired episodes out of the offer.
 */
class RewatchRunEpisodeSelectionTest {

    private val today = LocalDate.of(2026, 3, 10)

    @Test
    fun `the episode after the run is offered`() {
        val episodes = listOf(
            episode(1, 1),
            episode(1, 2),
            episode(1, 3),
            episode(1, 4),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("1:3", next?.id)
    }

    @Test
    fun `the run follows across a season boundary`() {
        val episodes = listOf(
            episode(1, 1),
            episode(1, 2),
            episode(2, 1),
            episode(2, 2),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("2:1", next?.id)
    }

    @Test
    fun `a run on the last episode offers nothing`() {
        val episodes = listOf(episode(2, 1), episode(2, 2))

        assertNull(nextEpisodeAfterRun(episodes, run(season = 2, episode = 2)))
    }

    @Test
    fun `the order of the list does not decide the offer`() {
        val episodes = listOf(
            episode(2, 1),
            episode(1, 4),
            episode(1, 2),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("1:4", next?.id)
    }

    @Test
    fun `a run position missing from the catalogue still offers the first episode after it`() {
        val episodes = listOf(episode(3, 1), episode(3, 2))

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 5))

        assertEquals("3:1", next?.id)
    }

    @Test
    fun `a list without episodes offers nothing`() {
        assertNull(nextEpisodeAfterRun(emptyList(), run(season = 1, episode = 1)))
    }

    @Test
    fun `an unaired run episode is skipped while unaired episodes are hidden`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(1, 3, released = "2026-03-13"),
        )

        assertNull(
            nextEpisodeAfterRun(
                episodes = episodes,
                run = run(season = 1, episode = 2),
                showUnairedNextUp = false,
                today = today,
            )
        )
    }

    @Test
    fun `the same unaired run episode is offered while unaired episodes are allowed`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(1, 3, released = "2026-03-13"),
        )

        val next = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = true,
            today = today,
        )

        assertEquals("1:3", next?.id)
    }

    @Test
    fun `an unaired run episode is skipped and the first aired episode after it decides`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(1, 3, released = "2026-03-17"),
            episode(1, 4, released = "2026-03-03"),
        )

        val next = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = false,
            today = today,
        )

        assertEquals("1:4", next?.id)
    }

    @Test
    fun `an episode that airs today counts as aired`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(1, 3, released = "2026-03-10"),
        )

        val next = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = false,
            today = today,
        )

        assertEquals("1:3", next?.id)
    }

    @Test
    fun `a next season step inside the seven day window is offered`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(2, 1, released = "2026-03-14"),
        )

        val next = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = true,
            today = today,
        )

        assertEquals("2:1", next?.id)
    }

    @Test
    fun `a next season step beyond the window is not offered`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(2, 1, released = "2026-05-04"),
        )

        assertNull(
            nextEpisodeAfterRun(
                episodes = episodes,
                run = run(season = 1, episode = 2),
                showUnairedNextUp = true,
                today = today,
            )
        )
    }

    @Test
    fun `a next season step is skipped while unaired episodes are hidden even inside the window`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(2, 1, released = "2026-03-14"),
        )

        assertNull(
            nextEpisodeAfterRun(
                episodes = episodes,
                run = run(season = 1, episode = 2),
                showUnairedNextUp = false,
                today = today,
            )
        )
    }

    @Test
    fun `an episode without an air date counts as unaired`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(1, 3, released = null),
        )

        assertNull(
            nextEpisodeAfterRun(
                episodes = episodes,
                run = run(season = 1, episode = 2),
                showUnairedNextUp = false,
                today = today,
            )
        )

        val allowed = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = true,
            today = today,
        )

        assertEquals("1:3", allowed?.id)
    }

    @Test
    fun `a next season step without an air date is left to the setting`() {
        val episodes = listOf(
            episode(1, 1, released = "2026-01-06"),
            episode(1, 2, released = "2026-02-03"),
            episode(2, 1, released = null),
        )

        assertNull(
            nextEpisodeAfterRun(
                episodes = episodes,
                run = run(season = 1, episode = 2),
                showUnairedNextUp = false,
                today = today,
            )
        )

        val allowed = nextEpisodeAfterRun(
            episodes = episodes,
            run = run(season = 1, episode = 2),
            showUnairedNextUp = true,
            today = today,
        )

        assertEquals("2:1", allowed?.id)
    }

    private fun episode(season: Int, episode: Int, released: String? = null) = Video(
        id = "$season:$episode",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
    )

    private fun run(season: Int, episode: Int) = RewatchRunPosition(
        contentId = "tt5753856",
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = 1_000L,
    )
}
