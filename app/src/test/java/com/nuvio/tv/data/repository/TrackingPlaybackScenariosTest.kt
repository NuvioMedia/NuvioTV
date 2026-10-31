package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mdblist.MdbListDroppedRecord
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListMedia
import com.nuvio.tv.data.mdblist.MdbListPlayback
import com.nuvio.tv.data.mdblist.MdbListProgressProjection
import com.nuvio.tv.data.mdblist.MdbListSyncSnapshot
import com.nuvio.tv.data.mdblist.MdbListWatchedRecord
import com.nuvio.tv.data.simkl.SimklEpisode
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklMedia
import com.nuvio.tv.data.simkl.SimklMediaType
import com.nuvio.tv.data.simkl.SimklPlaybackEpisode
import com.nuvio.tv.data.simkl.SimklScrobbleOutcome
import com.nuvio.tv.data.simkl.SimklScrobbleResult
import com.nuvio.tv.data.simkl.SimklSeason
import com.nuvio.tv.data.simkl.SimklSnapshotProjection
import com.nuvio.tv.data.simkl.SimklSyncSnapshot
import com.nuvio.tv.data.simkl.applyScrobbleResult
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class TrackingPlaybackScenariosTest {

    @Test
    fun `scenario 1 - frequent pause and resume in long series updates episode progress without re-indexing library`() {
        // Simkl: Set up a show with 3 seasons of historical episodes
        val showMedia = SimklMedia(
            title = "Long Running Show",
            ids = mapOf("imdb" to JsonPrimitive("tt_show_simkl"))
        )
        val initialEntries = listOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                show = showMedia,
                seasons = (1..3).map { s ->
                    SimklSeason(
                        number = s,
                        episodes = (1..10).map { ep ->
                            SimklEpisode(number = ep, watchedAt = "2026-01-01T00:00:00Z")
                        }
                    )
                }
            )
        )
        var simklSnapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = initialEntries,
            playback = emptyList()
        )
        var simklProjection = SimklSnapshotProjection.create(simklSnapshot)

        // Simulate watching S04E01 with frequent pause/resume events
        val pausePercents = listOf(10.0, 25.5, 45.0, 68.5, 82.0)
        pausePercents.forEach { percent ->
            val pauseResult = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.PAUSE,
                playbackId = 1001L,
                progress = percent,
                mediaType = SimklMediaType.SHOWS,
                media = showMedia,
                episode = SimklPlaybackEpisode(season = 4, number = 1)
            )
            simklSnapshot = simklSnapshot.applyScrobbleResult(pauseResult, System.currentTimeMillis())
            val updated = simklProjection.withUpdatedPlayback(simklSnapshot)

            // Verify library index is preserved by reference (no re-indexing)
            assertTrue(simklProjection.library === updated.library)
            assertTrue(simklProjection.watched === updated.watched)
            assertTrue(simklProjection.watchedShowEpisodes === updated.watchedShowEpisodes)
            assertTrue(simklProjection.showIdSiblings === updated.showIdSiblings)

            // Verify episode progress is correctly reflected
            val epProgress = updated.episodeProgress("tt_show_simkl")[4 to 1]
            assertNotNull(epProgress)
            assertEquals(percent.toFloat(), epProgress?.progressPercent ?: -1f, 0.01f)

            simklProjection = updated
        }

        // MDBList: Frequent pause/resume on S02E05
        val mdbShowIds = MdbListIds(imdb = "tt_show_mdblist")
        val mdbSnapshot = MdbListSyncSnapshot(
            accountId = 555,
            watched = listOf(
                MdbListWatchedRecord(
                    type = MdbListItemType.EPISODE,
                    media = MdbListMedia(ids = mdbShowIds, title = "MDBList Show"),
                    season = 1,
                    episode = 1,
                    watchedAt = "2026-01-01T00:00:00Z"
                )
            ),
            playback = emptyList()
        )
        var mdbProjection = MdbListProgressProjection(mdbSnapshot)

        listOf(15f, 30f, 52f, 74f).forEach { progress ->
            val newPlayback = listOf(
                MdbListPlayback(
                    id = 2002L,
                    type = MdbListItemType.EPISODE,
                    media = MdbListMedia(ids = mdbShowIds, title = "MDBList Show"),
                    season = 2,
                    episode = 5,
                    progress = progress,
                    updatedAt = "2026-01-02T12:00:00Z"
                )
            )
            val updated = mdbProjection.withUpdatedPlayback(newPlayback)

            // Verify watched collections are preserved by reference
            assertTrue(mdbProjection.watchedItems === updated.watchedItems)
            assertTrue(mdbProjection.watchedMovieIds === updated.watchedMovieIds)
            assertTrue(mdbProjection.watchedShowEpisodes === updated.watchedShowEpisodes)

            val epProgress = updated.episodeProgress("tt_show_mdblist")[2 to 5]
            assertNotNull(epProgress)
            assertEquals(progress, epProgress?.progressPercent ?: -1f, 0.01f)

            mdbProjection = updated
        }
    }

    @Test
    fun `scenario 2 - movie left at 40 percent appears in continue watching and completes when finished`() {
        val movieMedia = SimklMedia(
            title = "Inception",
            ids = mapOf("imdb" to JsonPrimitive("tt1375666"))
        )
        var snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.MOVIES,
                    movie = movieMedia
                )
            ),
            playback = emptyList()
        )
        var projection = SimklSnapshotProjection.create(snapshot)

        // 1. User stops watching at 40%
        val pauseAt40 = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 4040L,
            progress = 40.0,
            mediaType = SimklMediaType.MOVIES,
            media = movieMedia,
            episode = null
        )
        snapshot = snapshot.applyScrobbleResult(pauseAt40, System.currentTimeMillis())
        projection = projection.withUpdatedPlayback(snapshot)

        // Verify: Appears in Continue Watching, in progress, NOT in watched movie IDs
        assertEquals(1, projection.progress.size)
        val item = projection.progress.first()
        assertEquals("tt1375666", item.contentId)
        assertEquals(40f, item.progressPercent ?: -1f, 0.01f)
        assertTrue(item.isInProgress())
        assertFalse(item.isCompleted())
        assertFalse(projection.watchedMovieIds.contains("tt1375666"))

        // 2. User resumes from Continue Watching and completes it
        val completedScrobble = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.SCROBBLE,
            playbackId = 4040L,
            progress = 100.0,
            mediaType = SimklMediaType.MOVIES,
            media = movieMedia,
            episode = null,
            watchedAt = "2026-01-02T22:00:00Z"
        )
        snapshot = snapshot.applyScrobbleResult(completedScrobble, System.currentTimeMillis())
        // Completed scrobble updates entries, so full projection create is called
        val completedProjection = SimklSnapshotProjection.create(snapshot)

        // Verify: Removed from playback, added to watched movie IDs
        assertEquals(0, completedProjection.progress.size)
        assertTrue(completedProjection.watchedMovieIds.contains("tt1375666"))
    }

    @Test
    fun `scenario 3 - switching profiles isolates progress for the same content without cross-profile leakage`() = runTest {
        val activeProfile = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)

        val profile1Progress = WatchProgress(
            contentId = "tt_shared_movie",
            contentType = "movie",
            name = "Shared Movie",
            poster = "poster.jpg",
            backdrop = null,
            logo = null,
            videoId = "tt_shared_movie",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 30_000L,
            duration = 100_000L,
            lastWatched = 100L
        )

        val profile2Progress = WatchProgress(
            contentId = "tt_shared_movie",
            contentType = "movie",
            name = "Shared Movie",
            poster = "poster.jpg",
            backdrop = null,
            logo = null,
            videoId = "tt_shared_movie",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 85_000L,
            duration = 100_000L,
            lastWatched = 200L
        )

        coEvery { progressPreferences.getProgress("tt_shared_movie", 1) } returns flowOf(profile1Progress)
        coEvery { progressPreferences.getProgress("tt_shared_movie", 2) } returns flowOf(profile2Progress)

        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false },
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(emptySet()),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mockk<WatchStateMutationStore>(relaxed = true),
            serverWatched = mockk(relaxed = true)
        )

        // Profile 1 saves its progress
        repository.saveProgress(profile1Progress, profileId = 1, syncRemote = false)
        coVerify(exactly = 1) { progressPreferences.saveProgress(profile1Progress, profileId = 1) }

        // Switch to Profile 2
        activeProfile.value = 2
        repository.saveProgress(profile2Progress, profileId = 2, syncRemote = false)
        coVerify(exactly = 1) { progressPreferences.saveProgress(profile2Progress, profileId = 2) }

        // Verify Profile 1's progress was not touched when Profile 2 saved
        coVerify(exactly = 0) { progressPreferences.saveProgress(profile2Progress, profileId = 1) }
    }

    @Test
    fun `scenario 4 - series with dropped season correctly excludes dropped season from next up candidates`() {
        val showIds = MdbListIds(imdb = "tt_dropped_series")
        val media = MdbListMedia(ids = showIds, title = "Dropped Series")

        val snapshot = MdbListSyncSnapshot(
            accountId = 777,
            watched = listOf(
                MdbListWatchedRecord(
                    type = MdbListItemType.EPISODE,
                    media = media,
                    season = 1,
                    episode = 10,
                    watchedAt = "2026-01-01T00:00:00Z"
                ),
                MdbListWatchedRecord(
                    type = MdbListItemType.EPISODE,
                    media = media,
                    season = 2,
                    episode = 5,
                    watchedAt = "2026-01-02T00:00:00Z"
                )
            ),
            // User explicitly dropped Season 2
            dropped = listOf(
                MdbListDroppedRecord(ids = showIds, season = 2)
            ),
            playback = emptyList()
        )

        val projection = MdbListProgressProjection(snapshot)

        // Verify Season 2 is hidden/dropped, Season 1 is not
        assertTrue(projection.isHidden("tt_dropped_series", season = 2))
        assertFalse(projection.isHidden("tt_dropped_series", season = 1))

        // Next-Up candidate must include Season 2 in excludedNextUpSeasons
        val nextUpSeeds = projection.nextUp(preferFurthest = true)
        val seed = nextUpSeeds.firstOrNull { it.contentId == "tt_dropped_series" }
        assertNotNull(seed)
        assertTrue(seed!!.excludedNextUpSeasons.contains(2))
        assertFalse(seed.excludedNextUpSeasons.contains(1))
    }

    @Test
    fun `scenario 5 - large library with thousands of entries scrobbles without frame drops or re-allocation`() {
        // Generate massive library: 2,500 historical entries
        val historicalEntries = (1..2500).map { i ->
            val isMovie = i % 2 == 0
            SimklLibraryEntry(
                mediaType = if (isMovie) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (isMovie) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null,
                show = if (!isMovie) SimklMedia(title = "Show $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = historicalEntries,
            playback = emptyList()
        )
        val initialProjection = SimklSnapshotProjection.create(snapshot)

        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 9999L,
            progress = 55.0,
            mediaType = SimklMediaType.MOVIES,
            media = SimklMedia(title = "Movie 2500", ids = mapOf("imdb" to JsonPrimitive("tt2500"))),
            episode = null
        )
        val updatedSnapshot = snapshot.applyScrobbleResult(pauseResult, System.currentTimeMillis())

        var updatedProjection: SimklSnapshotProjection? = null
        val durationMs = measureTimeMillis {
            updatedProjection = initialProjection.withUpdatedPlayback(updatedSnapshot)
        }

        // Must execute under 20 ms (zero UI thread freezing)
        assertTrue("Expected withUpdatedPlayback < 20ms, took ${durationMs}ms", durationMs < 20)

        // Verify zero re-allocation of large structures
        val result = requireNotNull(updatedProjection)
        assertTrue(initialProjection.library === result.library)
        assertTrue(initialProjection.watched === result.watched)
        assertTrue(initialProjection.watchedShowEpisodes === result.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === result.showIdSiblings)
        assertEquals(1, result.progress.size)
    }
}
