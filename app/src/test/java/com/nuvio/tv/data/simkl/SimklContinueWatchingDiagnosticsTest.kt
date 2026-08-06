package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklContinueWatchingDiagnosticsTest {
    @Test
    fun `imported completion exposes summary count gap and null timestamps`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                completedEntry(
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(
                                SimklEpisode(1, "2026-07-24T10:00:00Z"),
                                SimklEpisode(2, "2026-07-24T10:00:00Z"),
                                SimklEpisode(3, null),
                                SimklEpisode(4, null)
                            )
                        )
                    )
                )
            )
        )
        val seeds = snapshot.toSimklNextUpSeeds(preferFurthestEpisode = true)

        val record = buildSimklSeedDiagnosticReport(
            snapshot = snapshot,
            seeds = seeds,
            preferFurthestEpisode = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertEquals(4, record.apiWatchedCount)
        assertEquals(4, record.returnedEpisodeRows)
        assertEquals(2, record.exactWatchedCount)
        assertEquals(2, record.nullTimestampRows)
        assertEquals(SimklDiagnosticEpisode(1, 4), record.apiLastWatched)
        assertEquals(SimklDiagnosticEpisode(1, 2), record.selected)
        assertTrue(SimklSeedDiagnosticFlag.API_COUNT_EXCEEDS_EXACT_HISTORY in record.flags)
        assertTrue(SimklSeedDiagnosticFlag.API_REPORTS_CAUGHT_UP in record.flags)
        assertTrue(SimklSeedDiagnosticFlag.COMPLETED_STATUS in record.flags)
        assertTrue(SimklSeedDiagnosticFlag.TIMESTAMP_TIE in record.flags)
    }

    @Test
    fun `aligned exact history has no count or seed mismatch`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                completedEntry(
                    watchedCount = 2,
                    totalCount = 4,
                    status = SimklListStatus.WATCHING,
                    lastWatched = "S01E02",
                    nextToWatch = "S01E03",
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(
                                SimklEpisode(1, "2026-07-24T09:00:00Z"),
                                SimklEpisode(2, "2026-07-24T10:00:00Z")
                            )
                        )
                    )
                )
            )
        )

        val record = buildSimklSeedDiagnosticReport(
            snapshot = snapshot,
            seeds = snapshot.toSimklNextUpSeeds(preferFurthestEpisode = true),
            preferFurthestEpisode = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertEquals(SimklDiagnosticEpisode(1, 2), record.selected)
        assertEquals(SimklDiagnosticEpisode(1, 3), record.apiNextToWatch)
        assertFalse(SimklSeedDiagnosticFlag.API_COUNT_EXCEEDS_EXACT_HISTORY in record.flags)
        assertFalse(SimklSeedDiagnosticFlag.SEED_BEHIND_FURTHEST in record.flags)
        assertFalse(SimklSeedDiagnosticFlag.SEED_NOT_IN_EXACT_HISTORY in record.flags)
    }

    @Test
    fun `diagnostic identifies a seed behind exact history`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                completedEntry(
                    watchedCount = 2,
                    totalCount = 4,
                    status = SimklListStatus.WATCHING,
                    lastWatched = "S01E02",
                    nextToWatch = "S01E03",
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(
                                SimklEpisode(1, "2026-07-24T09:00:00Z"),
                                SimklEpisode(2, "2026-07-24T10:00:00Z")
                            )
                        )
                    )
                )
            )
        )

        val record = buildSimklSeedDiagnosticReport(
            snapshot = snapshot,
            seeds = listOf(seed(1, 1, 1_721_812_400_000L)),
            preferFurthestEpisode = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertTrue(SimklSeedDiagnosticFlag.SEED_BEHIND_FURTHEST in record.flags)
    }

    @Test
    fun `on hold history is reported without a missing seed warning`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                completedEntry(
                    watchedCount = 1,
                    totalCount = 4,
                    status = SimklListStatus.ON_HOLD,
                    lastWatched = "S01E01",
                    nextToWatch = "S01E02",
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(
                                SimklEpisode(1, "2026-07-24T09:00:00Z")
                            )
                        )
                    )
                )
            )
        )

        val record = buildSimklSeedDiagnosticReport(
            snapshot = snapshot,
            seeds = snapshot.toSimklNextUpSeeds(preferFurthestEpisode = true),
            preferFurthestEpisode = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertTrue(SimklSeedDiagnosticFlag.ON_HOLD_STATUS in record.flags)
        assertFalse(SimklSeedDiagnosticFlag.SEED_MISSING in record.flags)
    }

    @Test
    fun `formatted diagnostics omit media identity title and timestamps`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                completedEntry(
                    seasons = listOf(
                        SimklSeason(
                            1,
                            listOf(SimklEpisode(1, "2026-07-24T10:00:00Z"))
                        )
                    )
                )
            )
        )
        val record = buildSimklSeedDiagnosticReport(
            snapshot = snapshot,
            seeds = snapshot.toSimklNextUpSeeds(preferFurthestEpisode = true),
            preferFurthestEpisode = true,
            aliasFor = { "cw-test" }
        ).records.single()

        val line = record.logLine()

        assertTrue("key=cw-test" in line)
        assertFalse("tt-private-id" in line)
        assertFalse("Private Imported Show" in line)
        assertFalse("2026-07-24" in line)
    }

    private fun completedEntry(
        watchedCount: Int = 4,
        totalCount: Int = 4,
        status: SimklListStatus = SimklListStatus.COMPLETED,
        lastWatched: String? = "S01E04",
        nextToWatch: String? = null,
        seasons: List<SimklSeason>
    ) = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = status,
        lastWatched = lastWatched,
        nextToWatch = nextToWatch,
        watchedEpisodesCount = watchedCount,
        totalEpisodesCount = totalCount,
        show = SimklMedia(
            title = "Private Imported Show",
            ids = buildJsonObject {
                put("simkl", 9001)
                put("imdb", "tt-private-id")
            }
        ),
        seasons = seasons
    )

    private fun seed(season: Int, episode: Int, lastWatched: Long) = WatchProgress(
        contentId = "tt-private-id",
        contentType = "series",
        name = "Private Imported Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "private-video",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
        trackingProviderId = "simkl"
    )
}
