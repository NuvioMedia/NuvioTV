package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rewatch sessions of an anime whose seasons Simkl lists as separate items.
 *
 * Each season is its own item with its own session, numbered from 1. Read with the library read's
 * detail (`full_anime_seasons`, `episode_tvdb_id`), a season's session carries the series ids and the
 * TVDB season of every episode, which is what puts the seasons back together as one series.
 */
class SimklRewatchAnimeSeasonsTest {

    @Test
    fun `sessions of one anime on two Simkl items make one run under the series id`() {
        val run = deriveSimklRewatchRuns(
            entries = listOf(firstSeasonSession(watchedAt = NEWER), secondSeasonSession(watchedAt = OLDER)),
            offerRuns = true
        ).single()

        assertEquals(SERIES_ID, run.contentId)
        assertEquals(1 to 6, run.seasonNumber to run.episodeNumber)
    }

    @Test
    fun `a season item's session is placed in its catalogue season`() {
        val run = deriveSimklRewatchRuns(
            entries = listOf(firstSeasonSession(watchedAt = OLDER), secondSeasonSession(watchedAt = NEWER)),
            offerRuns = true
        ).single()

        assertEquals(SERIES_ID, run.contentId)
        assertEquals(2 to 1, run.seasonNumber to run.episodeNumber)
        // Every id form of both items finds the run, whichever one a card or a dismissal carries.
        assertTrue(run.matches("kitsu:45857"))
        assertTrue(run.matches("mal:40748"))
        assertTrue(run.matches("tvdb:377543"))
    }

    @Test
    fun `the episodes a series rewatched are read in catalogue seasons across its items`() {
        assertEquals(
            (1..6).map { 1 to it }.toSet() + (2 to 1),
            sessions().rewatchedCatalogueEpisodesOf(SERIES_ID)
        )
    }

    @Test
    fun `a card closes the session on every item of its series, each named by its own ids`() {
        val running = sessions().runningRewatchSessionsOf(SERIES_ID)

        assertEquals(setOf(FIRST_REWATCH_ID, SECOND_REWATCH_ID), running.map { it.rewatchId }.toSet())
        val secondSeason = running.single { it.rewatchId == SECOND_REWATCH_ID }.rewatchSessionMediaReference()!!
        assertEquals(TrackingMediaKind.ANIME, secondSeason.kind)
        assertEquals(SECOND_SIMKL_ID, secondSeason.ids.simkl)
    }

    @Test
    fun `a mark of the second season pins the second season's session, not the newer first one`() {
        val sessions = listOf(firstSeasonSession(watchedAt = NEWER), secondSeasonSession(watchedAt = OLDER))

        assertEquals(SECOND_REWATCH_ID, sessions.activeRewatchSessionId(seriesMark(season = 2, episode = 2), library()))
        assertEquals(FIRST_REWATCH_ID, sessions.activeRewatchSessionId(seriesMark(season = 1, episode = 7), library()))
    }

    @Test
    fun `a mark of a season with no running session pins none`() {
        val sessions = listOf(firstSeasonSession(watchedAt = NEWER))

        assertNull(sessions.activeRewatchSessionId(seriesMark(season = 2, episode = 1), library()))
    }

    @Test
    fun `a flat mark names its own item, so another season's session is never pinned`() {
        val sessions = listOf(firstSeasonSession(watchedAt = NEWER))

        // Written flat to the second season's item by its Kitsu id, with no session there yet.
        assertNull(sessions.activeRewatchSessionId(flatMark(kitsu = 45857, episode = 1), library()))
        // The first season's own item keeps its session.
        assertEquals(FIRST_REWATCH_ID, sessions.activeRewatchSessionId(flatMark(kitsu = 42765, episode = 7), library()))
    }

    @Test
    fun `a removal names the session of the season it removes, whatever ids the reference took`() {
        val sessions = listOf(firstSeasonSession(watchedAt = NEWER), secondSeasonSession(watchedAt = OLDER))
        // The reference was enriched from the first library row, so it carries that item's ids.
        val removal = seriesMark(season = 2, episode = 1).copy(
            ids = TrackingExternalIds(simkl = FIRST_SIMKL_ID, imdb = SERIES_ID, mal = 40748, tvdb = "377543")
        )

        val rewatchId = sessions.activeRewatchSessionIdForRemoval(
            media = removal,
            seasonNumber = 2,
            episodeNumber = 1,
            animeEpisodeNumber = 1,
            library = library()
        )

        assertEquals(SECOND_REWATCH_ID, rewatchId)
    }

    @Test
    fun `the read back after a season mark waits for that season's episode`() {
        val sessions = sessions()

        // The first season holds episode 2, which must not settle a write of the second season's.
        assertFalse(sessions.rewatchSessionHolds(SERIES_ID, TrackingMediaKind.ANIME, 2, 2))
        assertTrue(sessions.rewatchSessionHolds(SERIES_ID, TrackingMediaKind.ANIME, 2, 1))
    }

    @Test
    fun `a read showing the pinned session completed answers the write`() {
        val completed = firstSeasonSession(watchedAt = NEWER).copy(rewatchStatus = "completed")

        assertTrue(listOf(completed).rewatchSessionEnded(FIRST_REWATCH_ID))
        assertFalse(listOf(firstSeasonSession(watchedAt = NEWER)).rewatchSessionEnded(FIRST_REWATCH_ID))
        assertFalse(listOf(completed).rewatchSessionEnded(SECOND_REWATCH_ID))
    }

    /** A mark the way the catalogue names it: the series and its season, anime ids stripped. */
    private fun seriesMark(season: Int, episode: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Jujutsu Kaisen",
        ids = TrackingExternalIds(imdb = SERIES_ID, tvdb = "377543"),
        episode = TrackingEpisode(season = season, number = episode)
    )

    /** A mark written flat to one item, by its anime id, the way a Kitsu video id resolves. */
    private fun flatMark(kitsu: Long, episode: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Jujutsu Kaisen",
        ids = TrackingExternalIds(kitsu = kitsu),
        episode = TrackingEpisode(season = null, number = episode)
    )

    private fun sessions() = listOf(firstSeasonSession(watchedAt = NEWER), secondSeasonSession(watchedAt = OLDER))

    /** The library rows: every episode of each item, with its TVDB season. */
    private fun library(): List<SimklLibraryEntry> = listOf(
        item(firstSeasonIds(), tvdbSeason = 1, episodes = (1..24).toList(), watchedAt = OLDEST),
        item(secondSeasonIds(), tvdbSeason = 2, episodes = (1..23).toList(), watchedAt = OLDEST)
    )

    private fun firstSeasonSession(watchedAt: String) =
        item(firstSeasonIds(), tvdbSeason = 1, episodes = (1..6).toList(), watchedAt = watchedAt)
            .asSession(FIRST_REWATCH_ID)

    private fun secondSeasonSession(watchedAt: String) =
        item(secondSeasonIds(), tvdbSeason = 2, episodes = listOf(1), watchedAt = watchedAt)
            .asSession(SECOND_REWATCH_ID)

    /** One Simkl item, numbered from 1 within the item, each episode mapped to its TVDB season. */
    private fun item(
        ids: Map<String, JsonPrimitive>,
        tvdbSeason: Int,
        episodes: List<Int>,
        watchedAt: String
    ) = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        status = SimklListStatus.COMPLETED,
        lastWatchedAt = watchedAt,
        show = SimklMedia(title = "Jujutsu Kaisen", ids = ids),
        seasons = listOf(
            SimklSeason(
                number = 1,
                episodes = episodes.map { number ->
                    SimklEpisode(
                        number = number,
                        watchedAt = watchedAt,
                        tvdb = SimklEpisodeMapping(season = tvdbSeason, episode = number)
                    )
                }
            )
        )
    )

    private fun SimklLibraryEntry.asSession(rewatchId: Long) = copy(
        status = null,
        isRewatch = true,
        rewatchId = rewatchId,
        rewatchStatus = "active"
    )

    private fun seriesIds() = mapOf(
        "imdb" to JsonPrimitive(SERIES_ID),
        "tvdb" to JsonPrimitive("377543")
    )

    private fun firstSeasonIds() = mapOf(
        "simkl" to JsonPrimitive(FIRST_SIMKL_ID),
        "mal" to JsonPrimitive("40748"),
        "kitsu" to JsonPrimitive("42765")
    ) + seriesIds()

    private fun secondSeasonIds() = mapOf(
        "simkl" to JsonPrimitive(SECOND_SIMKL_ID),
        "mal" to JsonPrimitive("51009"),
        "kitsu" to JsonPrimitive("45857")
    ) + seriesIds()

    private companion object {
        const val SERIES_ID = "tt12343534"
        const val FIRST_SIMKL_ID = 1211265L
        const val SECOND_SIMKL_ID = 1847578L
        const val FIRST_REWATCH_ID = 787331L
        const val SECOND_REWATCH_ID = 795567L
        const val OLDEST = "2024-01-01T10:00:00Z"
        const val OLDER = "2026-11-10T10:00:00Z"
        const val NEWER = "2026-11-12T10:00:00Z"
    }
}
