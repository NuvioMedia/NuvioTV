package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the account's rewatch sessions are read as: which episodes form a run, which do not, and how a
 * write that Simkl answered with "already in the history" is recognised afterwards.
 */
class SimklRewatchRunsTest {

    @Test
    fun `two consecutive rewatched episodes are a run`() {
        val runs = runsFor(
            minimumRunEpisodes = 1,
            rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 7, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals("tt5753856", run.contentId)
        assertEquals(2, run.seasonNumber)
        assertEquals(7, run.episodeNumber)
        assertEquals(parseSimklUtcEpochMs(NEWER)!!, run.markedAtEpochMs)
        assertTrue(run.matchKeys.contains("imdb:tt5753856"))
        assertTrue(run.matchKeys.contains("simkl:39687"))
        assertTrue(run.matches("simkl:39687"))
        assertTrue(run.matches("TT5753856"))
        assertFalse(run.matches("tmdb:1"))
        assertFalse(run.matches(null))
    }

    @Test
    fun `a gap is not a run`() {
        val runs = runsFor(
            minimumRunEpisodes = 2,
            rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 9, watchedAt = NEWER)),
        )

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a season boundary breaks the chain`() {
        val runs = runsFor(
            minimumRunEpisodes = 2,
            rewatched = listOf(Marked(season = 1, episode = 5), Marked(season = 2, episode = 1, watchedAt = NEWER)),
        )

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `the newest rewatched episode decides which chain is the run`() {
        // An old pair from season 1 and a single, recent episode from season 3: the run follows the
        // recent episode even though the other chain is longer.
        val runs = runsFor(
            minimumRunEpisodes = 1,
            rewatched = listOf(
                Marked(season = 1, episode = 1),
                Marked(season = 1, episode = 2),
                Marked(season = 3, episode = 4, watchedAt = NEWER),
            ),
        )

        val run = runs.single()
        assertEquals(3, run.seasonNumber)
        assertEquals(4, run.episodeNumber)
    }

    @Test
    fun `the last episode of the run is where the run stands`() {
        val runs = runsFor(
            minimumRunEpisodes = 1,
            rewatched = listOf(
                Marked(season = 2, episode = 6, watchedAt = OLD),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertEquals(7, runs.single().episodeNumber)
        assertEquals(parseSimklUtcEpochMs(NEWER)!!, runs.single().markedAtEpochMs)
    }

    @Test
    fun `sessions of the same series are merged before the chain is built`() {
        // Simkl splits a running rewatch into a new session once the same episode is rewatched, and the
        // run continues across the split, so both rows have to be read together.
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 1)), isRewatch = true),
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 2, watchedAt = NEWER)), isRewatch = true),
        )

        val runs = deriveSimklRewatchRuns(entries = rows, minimumRunEpisodes = 2)

        assertEquals(1, runs.size)
        assertEquals(2, runs.single().episodeNumber)
    }

    @Test
    fun `never reads no run at all`() {
        val runs = runsFor(
            minimumRunEpisodes = SimklRewatchNextUpMode.NEVER.minimumRunEpisodes,
            rewatched = listOf(
                Marked(season = 2, episode = 5),
                Marked(season = 2, episode = 6),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a canonical row is not a rewatch`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = false),
        )

        assertTrue(deriveSimklRewatchRuns(entries = rows, minimumRunEpisodes = 1).isEmpty())
    }

    @Test
    fun `the sessions answer whether they hold this exact episode`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = true),
        )
        val episode = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            title = "Dark",
            ids = TrackingExternalIds(imdb = "tt5753856"),
            episode = TrackingEpisode(season = 2, number = 6),
        )

        assertTrue(rows.holdsRewatchEpisode(episode))
        assertFalse(rows.holdsRewatchEpisode(episode.copy(episode = TrackingEpisode(season = 2, number = 7))))
        assertFalse(rows.holdsRewatchEpisode(episode.copy(ids = TrackingExternalIds(imdb = "tt0000000"))))
        // A movie carries no coordinates, so no session can hold them.
        assertFalse(rows.holdsRewatchEpisode(episode.copy(episode = null)))
        // And a canonical row is not a session.
        assertFalse(
            listOf(rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = false))
                .holdsRewatchEpisode(episode),
        )
    }

    @Test
    fun `any session holding the coordinates is enough, whoever the show belongs to`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = true),
        )

        assertTrue(rows.holdsRewatchAt(seasonNumber = 2, episodeNumber = 6))
        assertFalse(rows.holdsRewatchAt(seasonNumber = 2, episodeNumber = 7))
        assertFalse(rows.holdsRewatchAt(seasonNumber = 3, episodeNumber = 6))
        assertFalse(
            listOf(rewatchRow(rewatched = listOf(Marked(season = 2, episode = 6)), isRewatch = false))
                .holdsRewatchAt(seasonNumber = 2, episodeNumber = 6),
        )
    }

    private fun runsFor(
        minimumRunEpisodes: Int?,
        rewatched: List<Marked>,
    ): List<RewatchRunPosition> = deriveSimklRewatchRuns(
        entries = listOf(rewatchRow(rewatched = rewatched, isRewatch = true)),
        minimumRunEpisodes = minimumRunEpisodes,
    )

    private fun rewatchRow(
        rewatched: List<Marked>,
        isRewatch: Boolean,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.COMPLETED,
        lastWatchedAt = rewatched.maxByOrNull(Marked::watchedAt)?.watchedAt,
        show = SimklMedia(
            title = "Dark",
            year = 2017,
            ids = mapOf(
                "simkl" to JsonPrimitive("39687"),
                "imdb" to JsonPrimitive("tt5753856"),
            ),
        ),
        seasons = rewatched
            .groupBy(Marked::season)
            .map { (season, episodes) ->
                SimklSeason(
                    number = season,
                    episodes = episodes.map { marked ->
                        SimklEpisode(number = marked.episode, watchedAt = marked.watchedAt)
                    },
                )
            },
        isRewatch = isRewatch,
    )

    private data class Marked(
        val season: Int,
        val episode: Int,
        val watchedAt: String = OLD,
    )

    private companion object {
        const val OLD = "2026-03-01T20:00:00Z"
        const val NEWER = "2026-08-01T20:00:00Z"
    }
}
