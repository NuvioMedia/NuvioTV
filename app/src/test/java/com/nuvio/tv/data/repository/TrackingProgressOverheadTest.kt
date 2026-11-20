package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListMedia
import com.nuvio.tv.data.mdblist.MdbListMutationTarget
import com.nuvio.tv.data.mdblist.MdbListPlayback
import com.nuvio.tv.data.mdblist.MdbListProgressProjection
import com.nuvio.tv.data.mdblist.MdbListScrobbleReceipt
import com.nuvio.tv.data.mdblist.MdbListSyncSnapshot
import com.nuvio.tv.data.mdblist.MdbListWatchedRecord
import com.nuvio.tv.data.mdblist.applyScrobble
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import com.nuvio.tv.data.simkl.SimklEntryIndex
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklMedia
import com.nuvio.tv.data.simkl.SimklMediaType
import com.nuvio.tv.data.simkl.SimklPlaybackEpisode
import com.nuvio.tv.data.simkl.SimklScrobbleOutcome
import com.nuvio.tv.data.simkl.SimklScrobbleResult
import com.nuvio.tv.data.simkl.SimklSnapshotProjection
import com.nuvio.tv.data.simkl.SimklSyncSnapshot
import com.nuvio.tv.data.simkl.applyScrobbleResult
import com.nuvio.tv.data.simkl.applyScrobbleResultWithIndex
import com.nuvio.tv.data.simkl.enrichMediaReference
import com.nuvio.tv.data.simkl.indexOfMatchingEntryLinear
import com.nuvio.tv.data.simkl.indexOfMatchingMediaLinear
import com.nuvio.tv.data.simkl.matchesContentWithParsedIds
import com.nuvio.tv.data.simkl.matchesSimklContentId
import com.nuvio.tv.data.simkl.mediaReference
import com.nuvio.tv.data.simkl.toTrackingExternalIds
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProgressOverheadTest {

    @Test
    fun `verify - simkl withUpdatedPlayback reuses projection collections and entryIndex when entries are unchanged`() {
        val testMedia = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt001")))
        val historicalEntries = (1..100).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "watermark-1",
            entries = historicalEntries,
            playback = emptyList()
        )
        val initialProjection = SimklSnapshotProjection.create(snapshot)

        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 999L,
            progress = 35.5,
            mediaType = SimklMediaType.MOVIES,
            media = testMedia,
            episode = null
        )

        val updatedSnapshot = snapshot.applyScrobbleResult(
            result = pauseResult,
            committedAtEpochMs = System.currentTimeMillis(),
            entryIndex = initialProjection.entryIndex
        )

        val updatedProjection = initialProjection.withUpdatedPlayback(updatedSnapshot)

        // Verify that entries-derived collections and entryIndex are REUSED by reference
        assertTrue(initialProjection.library === updatedProjection.library)
        assertTrue(initialProjection.watched === updatedProjection.watched)
        assertTrue(initialProjection.watchedShowEpisodes === updatedProjection.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === updatedProjection.showIdSiblings)
        assertTrue(initialProjection.entryIndex === updatedProjection.entryIndex)
        assertEquals(1, updatedProjection.progress.size)
    }

    @Test
    fun `verify - simkl entryIndex enriches media reference and reuses updatedIndex on completed scrobble`() {
        val entries = (1..500).map { i ->
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = SimklMedia(
                    title = "Show $i",
                    year = 2020,
                    ids = mapOf(
                        "imdb" to JsonPrimitive("tt$i"),
                        "simkl" to JsonPrimitive(i * 10)
                    )
                )
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        val projection = SimklSnapshotProjection.create(snapshot)

        val partialReference = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            title = null,
            year = null,
            ids = TrackingExternalIds(imdb = "tt250")
        )
        val enriched = snapshot.enrichMediaReference(
            reference = partialReference,
            entryIndex = projection.entryIndex
        )
        assertEquals("Show 250", enriched.title)
        assertEquals(2020, enriched.year)
        assertEquals(2500L, enriched.ids.simkl)

        val stopResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.SCROBBLE,
            playbackId = 77L,
            progress = 92.0,
            mediaType = SimklMediaType.SHOWS,
            media = SimklMedia(title = "Show 250", ids = mapOf("imdb" to JsonPrimitive("tt250"))),
            episode = SimklPlaybackEpisode(season = 1, number = 3)
        )
        val (updatedSnapshot, updatedIndex) = snapshot.applyScrobbleResultWithIndex(
            result = stopResult,
            committedAtEpochMs = 1_700_000_000_000L,
            entryIndex = projection.entryIndex
        )
        val nextProjection = SimklSnapshotProjection.create(updatedSnapshot, updatedIndex)

        // Unrelated entries are preserved by reference while entry 250 (index 249) gets the episode
        assertTrue(snapshot.entries[0] === updatedSnapshot.entries[0])
        assertEquals(1, updatedSnapshot.entries[249].seasons.single().episodes.size)
        // SimklSnapshotProjection.create reuses updatedIndex without rebuilding a second SimklEntryIndex
        assertSame(updatedIndex, nextProjection.entryIndex)
    }

    @Test
    fun `verify - simkl entryIndex matches linear implementation across bare vs prefixed ids case title year and anime preferences`() {
        val entries = listOf(
            // 0: Mixed-case IMDb + TMDB + TVDB movie
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                status = SimklListStatus.COMPLETED,
                movie = SimklMedia(
                    title = "The Matrix",
                    year = 1999,
                    ids = mapOf(
                        "simkl" to JsonPrimitive(10L),
                        "imdb" to JsonPrimitive("TT0133093"),
                        "tmdb" to JsonPrimitive(603L),
                        "tvdb" to JsonPrimitive("TVDB-603")
                    )
                )
            ),
            // 1: Split-cour anime season 1 sharing IMDb tt2560140 with MAL 16498 and Kitsu 7442
            SimklLibraryEntry(
                mediaType = SimklMediaType.ANIME,
                animeType = "tv",
                status = SimklListStatus.WATCHING,
                show = SimklMedia(
                    title = "Attack on Titan",
                    year = 2013,
                    ids = mapOf(
                        "simkl" to JsonPrimitive(100L),
                        "imdb" to JsonPrimitive("tt2560140"),
                        "mal" to JsonPrimitive(16498L),
                        "anidb" to JsonPrimitive(9541L),
                        "anilist" to JsonPrimitive(16498L),
                        "kitsu" to JsonPrimitive(7442L)
                    )
                )
            ),
            // 2: Split-cour anime season 2 sharing IMDb tt2560140 with MAL 25777 and Kitsu 8671
            SimklLibraryEntry(
                mediaType = SimklMediaType.ANIME,
                animeType = "tv",
                status = SimklListStatus.WATCHING,
                show = SimklMedia(
                    title = "Attack on Titan Season 2",
                    year = 2017,
                    ids = mapOf(
                        "simkl" to JsonPrimitive(101L),
                        "imdb" to JsonPrimitive("tt2560140"),
                        "mal" to JsonPrimitive(25777L),
                        "anidb" to JsonPrimitive(10944L),
                        "anilist" to JsonPrimitive(20958L),
                        "kitsu" to JsonPrimitive(8671L)
                    )
                )
            ),
            // 3: Title + year only show (no external IDs)
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.PLAN_TO_WATCH,
                show = SimklMedia(
                    title = "  Mystery Show  ",
                    year = 2021,
                    ids = emptyMap()
                )
            ),
            // 4: Same title but different year
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = SimklMedia(
                    title = "Mystery Show",
                    year = 2024,
                    ids = mapOf("simkl" to JsonPrimitive(555L))
                )
            )
        )
        val snapshot = SimklSyncSnapshot(isInitialized = true, entries = entries)
        val index = SimklEntryIndex(entries)

        val contentIdQueries = listOf(
            "tt0133093",
            "TT0133093",
            "imdb:tt0133093",
            "IMDB:TT0133093",
            "tmdb:603",
            "tvdb:tvdb-603",
            "TVDB:TVDB-603",
            "simkl:10",
            "tt2560140",
            "imdb:tt2560140",
            "mal:16498",
            "mal:25777",
            "anidb:10944",
            "anilist:20958",
            "kitsu:7442",
            "kitsu:8671",
            "tt9999999",
            "mal:999999"
        )
        val providerIdQueries = listOf(null, "simkl:10", "SIMKL:101", "simkl:99999")

        val previousPreference = SimklAnimeIdPreferenceHolder.current
        try {
            for (preference in SimklAnimeIdPreference.entries) {
                SimklAnimeIdPreferenceHolder.current = preference
                for (contentId in contentIdQueries) {
                    val expectedFirst = entries.firstOrNull { it.matchesSimklContentId(contentId) }
                    val actualFirst = index.firstMatchingContentId(entries, contentId)
                    assertEquals("firstMatchingContentId mismatch for $contentId ($preference)", expectedFirst, actualFirst)

                    val expectedRef = snapshot.mediaReference(contentId, "series", entryIndex = null)
                    val actualRef = snapshot.mediaReference(contentId, "series", entryIndex = index)
                    assertEquals("mediaReference mismatch for $contentId ($preference)", expectedRef, actualRef)

                    for (providerId in providerIdQueries) {
                        val parsed = parseTrackingExternalIds(contentId)
                        val expectedMatches = entries.filter {
                            it.matchesContentWithParsedIds(contentId, parsed, providerId)
                        }
                        val actualMatches = index.matchingEntriesForContent(entries, contentId, providerId)
                        assertEquals(
                            "matchingEntriesForContent mismatch for $contentId / $providerId ($preference)",
                            expectedMatches,
                            actualMatches
                        )
                    }
                }
            }
        } finally {
            SimklAnimeIdPreferenceHolder.current = previousPreference
        }

        val mediaQueries = listOf(
            // Bare and case-varied IMDb
            SimklMedia(ids = mapOf("imdb" to JsonPrimitive("tt0133093"))),
            SimklMedia(ids = mapOf("imdb" to JsonPrimitive("TT0133093"))),
            // Split-cour disambiguation via Simkl ID or MAL ID
            SimklMedia(ids = mapOf("imdb" to JsonPrimitive("tt2560140"), "mal" to JsonPrimitive(25777L))),
            SimklMedia(ids = mapOf("simkl" to JsonPrimitive(101L), "imdb" to JsonPrimitive("tt2560140"))),
            // Title + year matching (exact year 2021 -> index 3, exact year 2024 -> index 4, null year -> index 3, wrong year 1990 -> -1)
            SimklMedia(title = "mystery show", year = 2021),
            SimklMedia(title = "MYSTERY SHOW", year = 2024),
            SimklMedia(title = "Mystery Show", year = null),
            SimklMedia(title = "Mystery Show", year = 1990),
            // Unknown item
            SimklMedia(title = "Nonexistent", year = 2025, ids = mapOf("imdb" to JsonPrimitive("tt0000000")))
        )

        for (media in mediaQueries) {
            val expectedMediaIdx = entries.indexOfMatchingMediaLinear(media)
            val actualMediaIdx = index.indexOfMatchingMedia(entries, media)
            assertEquals("indexOfMatchingMedia mismatch for $media", expectedMediaIdx, actualMediaIdx)

            for (mediaType in SimklMediaType.entries) {
                val scrobbleResult = SimklScrobbleResult(
                    outcome = SimklScrobbleOutcome.PAUSE,
                    playbackId = 1L,
                    progress = 50.0,
                    mediaType = mediaType,
                    media = media,
                    episode = null
                )
                val expectedEntryIdx = entries.indexOfMatchingEntryLinear(scrobbleResult)
                val actualEntryIdx = index.indexOfMatchingEntry(entries, scrobbleResult)
                assertEquals(
                    "indexOfMatchingEntry mismatch for $media ($mediaType)",
                    expectedEntryIdx,
                    actualEntryIdx
                )
            }

            val ref = TrackingMediaReference(
                kind = TrackingMediaKind.SHOW,
                title = media.title,
                year = media.year,
                ids = media.toTrackingExternalIds()
            )
            assertEquals(
                "enrichMediaReference mismatch for $media",
                snapshot.enrichMediaReference(ref, entryIndex = null),
                snapshot.enrichMediaReference(ref, entryIndex = index)
            )
        }

        // Verify withUpdatedEntry removes stale alias when an entry's ID changes
        val mutatedEntries = entries.toMutableList()
        val replacedEntry = entries[0].copy(
            movie = SimklMedia(
                title = "The Matrix Reloaded",
                year = 2003,
                ids = mapOf("imdb" to JsonPrimitive("tt0234215"))
            )
        )
        mutatedEntries[0] = replacedEntry
        val patchedIndex = index.withUpdatedEntry(mutatedEntries, 0, replacedEntry)
        assertNull(patchedIndex.firstMatchingContentId(mutatedEntries, "tt0133093"))
        assertEquals(replacedEntry, patchedIndex.firstMatchingContentId(mutatedEntries, "tt0234215"))
    }

    @Test
    fun `verify - mdblist withUpdatedPlayback reuses watched collections and showIdSiblings without copying`() {
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = listOf(
                MdbListWatchedRecord(
                    type = MdbListItemType.MOVIE,
                    media = MdbListMedia(ids = MdbListIds(imdb = "tt100")),
                    watchedAt = "2026-01-01T00:00:00Z"
                ),
                MdbListWatchedRecord(
                    type = MdbListItemType.EPISODE,
                    media = MdbListMedia(ids = MdbListIds(imdb = "tt300", tmdb = 300L)),
                    season = 1,
                    episode = 1,
                    watchedAt = "2026-01-01T00:00:00Z"
                )
            ),
            playback = emptyList()
        )
        val initialProjection = MdbListProgressProjection(snapshot)

        // Case 1: Known show playback reuses watchedShowIdSiblings by reference
        val knownShowPlayback = listOf(
            MdbListPlayback(
                id = 1L,
                type = MdbListItemType.EPISODE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt300", tmdb = 300L)),
                season = 1,
                episode = 2,
                progress = 45f,
                updatedAt = "2026-01-02T00:00:00Z"
            )
        )
        val knownShowProjection = initialProjection.withUpdatedPlayback(knownShowPlayback)
        assertTrue(initialProjection.watchedItems === knownShowProjection.watchedItems)
        assertTrue(initialProjection.watchedMovieIds === knownShowProjection.watchedMovieIds)
        assertTrue(initialProjection.watchedShowEpisodes === knownShowProjection.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === knownShowProjection.showIdSiblings)
        assertTrue(initialProjection.mediaIndex === knownShowProjection.mediaIndex)

        // Case 2: Brand-new show playback uses overlay map and resolves both watched and new show siblings
        val newShowPlayback = listOf(
            MdbListPlayback(
                id = 2L,
                type = MdbListItemType.EPISODE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt999", tmdb = 999L)),
                season = 1,
                episode = 1,
                progress = 30f,
                updatedAt = "2026-01-02T00:00:00Z"
            )
        )
        val newShowProjection = initialProjection.withUpdatedPlayback(newShowPlayback)
        assertEquals(setOf("tt300", "imdb:tt300", "tmdb:300"), newShowProjection.showIdSiblings["tt300"])
        assertEquals(setOf("tt999", "imdb:tt999", "tmdb:999"), newShowProjection.showIdSiblings["tt999"])
    }

    @Test
    fun `verify - simkl applyScrobbleResult on pause with large library preserves entries reference`() {
        val entries = (1..1000).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        val projection = SimklSnapshotProjection.create(snapshot)
        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 42L,
            progress = 25.0,
            mediaType = SimklMediaType.MOVIES,
            media = SimklMedia(title = "Movie 500", ids = mapOf("imdb" to JsonPrimitive("tt500"))),
            episode = null
        )

        val updated = snapshot.applyScrobbleResult(pauseResult, System.currentTimeMillis(), projection.entryIndex)

        assertTrue(snapshot.entries === updated.entries)
        assertEquals(1, updated.playback.size)
    }

    @Test
    fun `verify - mdblist applyScrobble on pause and completed stop uses mediaIndex without scanning unrelated watched items`() {
        val watched = (1..2500).map { i ->
            MdbListWatchedRecord(
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt$i", tmdb = i.toLong())),
                watchedAt = "2026-01-01T00:00:00Z"
            )
        }
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = watched,
            playback = emptyList()
        )
        val projection = MdbListProgressProjection(snapshot)
        val target = MdbListMutationTarget(
            type = MdbListItemType.MOVIE,
            media = MdbListMedia(ids = MdbListIds(imdb = "tt500"))
        )
        val pauseReceipt = MdbListScrobbleReceipt(
            target = target,
            action = TrackingScrobbleAction.PAUSE,
            progress = 42f,
            timestamp = "2026-01-02T00:00:00Z",
            playbackId = 999L
        )

        val paused = snapshot.applyScrobble(pauseReceipt, projection.mediaIndex)
        assertTrue(snapshot.watched === paused.watched)
        assertEquals(1, paused.playback.size)
        assertEquals(500L, paused.playback.single().media.ids.tmdb)

        val stopReceipt = MdbListScrobbleReceipt(
            target = MdbListMutationTarget(
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(title = "Enriched Movie 500", ids = MdbListIds(imdb = "tt500", trakt = 5000L))
            ),
            action = TrackingScrobbleAction.STOP,
            progress = 90f,
            timestamp = "2026-01-03T00:00:00Z",
            playbackId = 999L
        )
        val completed = paused.applyScrobble(stopReceipt, projection.mediaIndex)
        val unindexedCompleted = paused.applyScrobble(stopReceipt, mediaIndex = null)
        assertTrue(completed.playback.isEmpty())
        assertEquals(2500, completed.watched.size)
        // Indexed completed scrobble produces the exact same normalized snapshot as unindexed normalizeMedia()
        assertEquals(unindexedCompleted.watched, completed.watched)
        // Unrelated watched record is preserved by reference at its original position
        assertTrue(snapshot.watched.first() === completed.watched.first())
        // Matched watched record was enriched and placed at the end with latest watchedAt
        assertEquals("tt500", completed.watched.last().media.ids.imdb)
        assertEquals(500L, completed.watched.last().media.ids.tmdb)
        assertEquals(5000L, completed.watched.last().media.ids.trakt)
        assertEquals("2026-01-03T00:00:00Z", completed.watched.last().watchedAt)
    }
}
