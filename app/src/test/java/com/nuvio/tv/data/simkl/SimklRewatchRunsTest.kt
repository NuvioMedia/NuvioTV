package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.core.tracking.TrackingCatalogReference
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
 * What the account's rewatch sessions are read as: which session is read, which episodes form a run,
 * and when the account is not read at all.
 */
class SimklRewatchRunsTest {

    @Test
    fun `two consecutive rewatched episodes are a run`() {
        val runs = runsFor(
            offerRuns = true,
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
    fun `a gap does not join the chain, and the newest episode still runs`() {
        // A skipped episode is its own chain, and the run is the chain the newest episode is in: a
        // rewatch the user is in is always offered, the row does not wait for two in a row.
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 9, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals(2, run.seasonNumber)
        assertEquals(9, run.episodeNumber)
    }

    @Test
    fun `a season boundary breaks the chain, and the newest episode still runs`() {
        val runs = runsFor(
            offerRuns = true,
            rewatched = listOf(Marked(season = 1, episode = 5), Marked(season = 2, episode = 1, watchedAt = NEWER)),
        )

        val run = runs.single()
        assertEquals(2, run.seasonNumber)
        assertEquals(1, run.episodeNumber)
    }

    @Test
    fun `the newest rewatched episode decides which chain is the run`() {
        // An old pair from season 1 and a single, recent episode from season 3: the run follows the
        // recent episode even though the other chain is longer.
        val runs = runsFor(
            offerRuns = true,
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
            offerRuns = true,
            rewatched = listOf(
                Marked(season = 2, episode = 6, watchedAt = OLD),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertEquals(7, runs.single().episodeNumber)
        assertEquals(parseSimklUtcEpochMs(NEWER)!!, runs.single().markedAtEpochMs)
    }

    @Test
    fun `several running rows of the same series are read together`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 1)), isRewatch = true),
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 2, watchedAt = NEWER)), isRewatch = true),
        )

        val runs = deriveSimklRewatchRuns(entries = rows, offerRuns = true)

        assertEquals(1, runs.size)
        assertEquals(2, runs.single().episodeNumber)
    }

    @Test
    fun `a session the account closed is not the run`() {
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(
                    Marked(season = 1, episode = 1),
                    Marked(season = 1, episode = 2, watchedAt = NEWER),
                ),
                isRewatch = true,
                status = "closed",
            ),
        )

        assertTrue(deriveSimklRewatchRuns(entries = rows, offerRuns = true).isEmpty())
    }

    @Test
    fun `the running session decides, a closed one beside it is not read`() {
        // Simkl closes the session and opens a fresh one when the same episode is rewatched days later,
        // so the older row holds more episodes than the one the user is actually in. The row follows the
        // running session, never the longer chain of the closed one.
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(
                    Marked(season = 1, episode = 1),
                    Marked(season = 1, episode = 2),
                    Marked(season = 1, episode = 3),
                    Marked(season = 1, episode = 4),
                    Marked(season = 1, episode = 5, watchedAt = OLD),
                ),
                isRewatch = true,
                status = "closed",
            ),
            rewatchRow(
                rewatched = listOf(Marked(season = 1, episode = 1, watchedAt = NEWER)),
                isRewatch = true,
                status = "active",
            ),
        )

        val run = deriveSimklRewatchRuns(entries = rows, offerRuns = true).single()

        assertEquals(1, run.seasonNumber)
        assertEquals(1, run.episodeNumber)
        assertEquals(parseSimklUtcEpochMs(NEWER)!!, run.markedAtEpochMs)
    }

    @Test
    fun `rewatches are not read while the user keeps them off`() {
        val runs = runsFor(
            offerRuns = false,
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

        assertTrue(deriveSimklRewatchRuns(entries = rows, offerRuns = true).isEmpty())
    }

    @Test
    fun `only the running rewatch sessions are kept from a read`() {
        val rows = listOf(
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 1)), isRewatch = true),
            // A session the user closed is a run they left, so it is not kept.
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 2)), isRewatch = true, status = "closed"),
            // And a canonical row is not a session either.
            rewatchRow(rewatched = listOf(Marked(season = 1, episode = 3)), isRewatch = false),
        )

        val kept = rows.runningRewatchSessions()

        assertEquals(1, kept.size)
        assertEquals(1, kept.single().seasons.single().episodes.single().number)
    }

    @Test
    fun `the running session tells which episodes a run has rewatched`() {
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(Marked(season = 2, episode = 6), Marked(season = 2, episode = 7, watchedAt = NEWER)),
                isRewatch = true,
            ),
            // A session the user closed is not the run and marks nothing: its episode is not in the set.
            rewatchRow(rewatched = listOf(Marked(season = 2, episode = 5)), isRewatch = true, status = "closed"),
            // And a canonical row is not a session either.
            rewatchRow(rewatched = listOf(Marked(season = 3, episode = 1)), isRewatch = false),
        )
        val contentId = rows.firstNotNullOf { row -> row.media?.canonicalContentId() }

        assertEquals(setOf(2 to 6, 2 to 7), rows.rewatchedEpisodesOf(contentId))
        // An id form that differs only in case is the same item.
        assertEquals(setOf(2 to 6, 2 to 7), rows.rewatchedEpisodesOf(contentId.uppercase()))
        // Another item has nothing of its own here.
        assertTrue(rows.rewatchedEpisodesOf("imdb:tt0000000").isEmpty())
    }

    @Test
    fun `a flat anime session holds the marked episode by its number`() {
        val sessions = listOf(animeSession(season = null, episode = 7))

        assertTrue(
            sessions.rewatchSessionHolds(
                contentId = "mal:16498",
                kind = TrackingMediaKind.ANIME,
                season = null,
                episode = 7
            )
        )
        assertFalse(
            sessions.rewatchSessionHolds(
                contentId = "mal:16498",
                kind = TrackingMediaKind.ANIME,
                season = null,
                episode = 8
            )
        )
        // A target with a season still finds the flat session by its unique number.
        assertTrue(
            sessions.rewatchSessionHolds(
                contentId = "mal:16498",
                kind = TrackingMediaKind.ANIME,
                season = 1,
                episode = 7
            )
        )
    }

    @Test
    fun `an anime number in two seasons does not settle the read back`() {
        val sessions = listOf(
            animeSession(season = null, episode = 7, rewatchId = 51L),
            animeSession(season = 2, episode = 7, rewatchId = 52L),
        )

        assertFalse(
            sessions.rewatchSessionHolds(
                contentId = "mal:16498",
                kind = TrackingMediaKind.ANIME,
                season = 1,
                episode = 7
            )
        )
    }

    @Test
    fun `a show session holds its exact episode`() {
        val sessions = listOf(session(id = 61, status = "active"))

        assertTrue(
            sessions.rewatchSessionHolds(
                contentId = "tt5753856",
                kind = TrackingMediaKind.SHOW,
                season = 2,
                episode = 7
            )
        )
        assertFalse(
            sessions.rewatchSessionHolds(
                contentId = "tt5753856",
                kind = TrackingMediaKind.SHOW,
                season = 2,
                episode = 8
            )
        )
    }

    @Test
    fun `a mark of the step after the run continues it without exact episode history`() {
        val snapshot = snapshotWithRun(Marked(season = 2, episode = 7))

        // The run position is the only history here: the episode rows the exact lookup reads are
        // missing, and the mark of the next step still has to travel as the rewatch it is.
        assertTrue(snapshot.markContinuesRunningRewatch(showEpisode(season = 2, episode = 8)))
        // An episode the session already holds is a repeat too.
        assertTrue(snapshot.markContinuesRunningRewatch(showEpisode(season = 2, episode = 7)))

        // A jump away from the run is not its step and stays a first watch.
        assertFalse(snapshot.markContinuesRunningRewatch(showEpisode(season = 2, episode = 9)))
        assertFalse(snapshot.markContinuesRunningRewatch(showEpisode(season = 1, episode = 8)))
        // A season boundary may hide more episodes of the old season, so it is not guessed.
        assertFalse(snapshot.markContinuesRunningRewatch(showEpisode(season = 3, episode = 1)))
        // Another item has no run here.
        assertFalse(
            snapshot.markContinuesRunningRewatch(
                showEpisode(imdb = "tt0000001", season = 2, episode = 8)
            )
        )
    }

    @Test
    fun `a mark of the run step stays a first watch when the account has episode rows`() {
        val session = rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = true)
        val snapshot = SimklSyncSnapshot(
            entries = listOf(session.copy(isRewatch = false, rewatchId = null, rewatchStatus = null)),
            rewatchSessions = listOf(session),
            rewatchRuns = deriveSimklRewatchRuns(entries = listOf(session), offerRuns = true),
        )

        // The rows say the next episode is not watched; the run position does not overrule them.
        assertFalse(snapshot.markContinuesRunningRewatch(showEpisode(season = 2, episode = 8)))
    }

    @Test
    fun `a mark does not continue a run the account no longer reports`() {
        val entries = listOf(
            rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = true, status = "closed")
        )

        assertFalse(snapshotOf(entries).markContinuesRunningRewatch(showEpisode(season = 2, episode = 8)))
    }

    @Test
    fun `a flat anime mark continues the run by its number`() {
        val snapshot = snapshotOf(listOf(animeSession(season = null, episode = 7)))

        assertTrue(snapshot.markContinuesRunningRewatch(animeEpisode(number = 8)))
        assertFalse(snapshot.markContinuesRunningRewatch(animeEpisode(number = 9)))
    }

    @Test
    fun `the running session of the item is the one a write joins`() {
        val sessions = listOf(
            session(id = 41, status = "completed"),
            session(id = 42, status = "closed"),
            session(id = 43, status = " ACTIVE "),
        )

        // Only the active one is pinned: a finished or closed run is not what a new viewing joins.
        assertEquals(43L, sessions.activeRewatchSessionId(episode()))
    }

    @Test
    fun `a session that is not running, not this item or not a session is never joined`() {
        val target = episode()

        // Nothing is running, so the write goes without an id and the account opens the session.
        assertNull(listOf(session(id = 44, status = "closed")).activeRewatchSessionId(target))
        assertNull(listOf(session(id = 45, status = null)).activeRewatchSessionId(target))
        // A session of another show belongs to that show.
        assertNull(
            listOf(session(id = 46, status = "active", ids = mapOf("imdb" to JsonPrimitive("tt0000001"))))
                .activeRewatchSessionId(target)
        )
        // A canonical row carries no session, whatever else it holds.
        assertNull(
            listOf(rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = false))
                .activeRewatchSessionId(target)
        )
    }

    @Test
    fun `a delta read replaces the sessions of the items it mentions and keeps the rest`() {
        val other = session(id = 50, status = "active", ids = mapOf("simkl" to JsonPrimitive("39688")))
        val stored = listOf(session(id = 1, status = "active"), other)

        val merged = mergeRewatchSessions(
            read = listOf(session(id = 2, status = "active")),
            stored = stored,
        )

        assertEquals(listOf(2L, 50L), merged.mapNotNull(SimklLibraryEntry::rewatchId))
    }

    @Test
    fun `an item the read mentions without a running session loses its stored sessions`() {
        val other = session(id = 50, status = "active", ids = mapOf("simkl" to JsonPrimitive("39688")))
        val stored = listOf(session(id = 1, status = "active"), other)

        val merged = mergeRewatchSessions(
            read = listOf(rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = false)),
            stored = stored,
        )

        assertEquals(listOf(50L), merged.mapNotNull(SimklLibraryEntry::rewatchId))
    }

    private fun runsFor(
        offerRuns: Boolean,
        rewatched: List<Marked>,
    ): List<RewatchRunPosition> = deriveSimklRewatchRuns(
        entries = listOf(rewatchRow(rewatched = rewatched, isRewatch = true)),
        offerRuns = offerRuns,
    )

    private fun rewatchRow(
        rewatched: List<Marked>,
        isRewatch: Boolean,
        status: String? = "active",
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
        rewatchStatus = status.takeIf { isRewatch },
    )

    /** One rewatch session of the account, as the read of it returns the row. */
    private fun session(
        id: Long?,
        status: String?,
        ids: Map<String, JsonPrimitive> = mapOf(
            "simkl" to JsonPrimitive("39687"),
            "imdb" to JsonPrimitive("tt5753856"),
        ),
    ): SimklLibraryEntry = rewatchRow(listOf(Marked(season = 2, episode = 7)), isRewatch = true)
        .copy(
            rewatchId = id,
            rewatchStatus = status,
            show = SimklMedia(title = "Dark", year = 2017, ids = ids),
        )

    /** One flat anime session: the account lists the episodes without a season number. */
    private fun animeSession(
        season: Int?,
        episode: Int,
        rewatchId: Long = 50L,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        isRewatch = true,
        rewatchId = rewatchId,
        rewatchStatus = "active",
        show = SimklMedia(
            title = "Attack on Titan",
            year = 2013,
            ids = mapOf("mal" to JsonPrimitive(16498L)),
        ),
        seasons = listOf(
            SimklSeason(
                number = season,
                episodes = listOf(SimklEpisode(number = episode, watchedAt = NEWER)),
            )
        ),
    )

    /** The item the sessions are looked for: the same show, at the episode the sessions hold. */
    private fun episode() = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856"),
        episode = TrackingEpisode(season = 2, number = 7),
    )

    /** The same show at the episode a mark names, the way the writer reads it before the write. */
    private fun showEpisode(
        imdb: String = "tt5753856",
        season: Int,
        episode: Int,
    ) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = imdb),
        episode = TrackingEpisode(season = season, number = episode),
        catalog = TrackingCatalogReference(contentId = imdb, contentType = "series"),
    )

    /** A flat anime mark: Simkl lists the episode without a season, by its absolute number. */
    private fun animeEpisode(number: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(mal = 16498),
        episode = TrackingEpisode(season = null, number = number),
        catalog = TrackingCatalogReference(contentId = "mal:16498", contentType = "anime"),
    )

    private fun snapshotWithRun(vararg marked: Marked): SimklSyncSnapshot =
        snapshotOf(listOf(rewatchRow(marked.toList(), isRewatch = true)))

    /**
     * A snapshot holding these session rows, with the runs the account reader would derive. The
     * canonical rows are left without episode history, which is the shape this rule is read for.
     */
    private fun snapshotOf(sessions: List<SimklLibraryEntry>): SimklSyncSnapshot = SimklSyncSnapshot(
        entries = sessions.map { session ->
            session.copy(isRewatch = false, rewatchId = null, rewatchStatus = null, seasons = emptyList())
        },
        rewatchSessions = sessions,
        rewatchRuns = deriveSimklRewatchRuns(entries = sessions, offerRuns = true),
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
