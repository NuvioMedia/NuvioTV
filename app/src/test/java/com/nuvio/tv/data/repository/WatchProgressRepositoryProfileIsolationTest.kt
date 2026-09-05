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
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchProgressRepositoryProfileIsolationTest {
    @Test
    fun `explicit save writes and queues only its playback profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.saveProgress(progress, profileId = 1, syncRemote = true)

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(mapOf("item" to progress), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.saveProgress(progress, profileId = 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.saveProgress(progress, profileId = 2)
        }
    }

    @Test
    fun `completion queues progress and watched mutations for one profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.markAsCompleted(
            progress = progress,
            profileId = 1,
            broadcastTrackingHistory = false
        )

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(
                match { entries ->
                    entries.keys == setOf("item") &&
                        entries.getValue("item").position == 10_000L &&
                        entries.getValue("item").duration == 10_000L
                },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.mutationStore.queueWatchedUpserts(
                match { items -> items.single().contentId == "item" },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.watchedPreferences.markAsWatched(
                match { it.contentId == "item" },
                profileId = 1
            )
        }
    }

    @Test
    fun `profile switch during delete key lookup cannot redirect local deletion`() = runTest {
        val harness = harness(activeProfileId = 1)
        val progress = progress("item", position = 2_000L, duration = 10_000L)
        coEvery { harness.progressPreferences.getAllRawEntries(1) } coAnswers {
            harness.activeProfile.value = 2
            mapOf("item" to progress)
        }

        harness.repository.removeProgress("item")

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressDeletes(listOf("item"), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
    }

    private fun harness(activeProfileId: Int): Harness {
        val activeProfile = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = watchedPreferences,
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(emptySet()),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mutationStore
        )
        return Harness(
            repository = repository,
            activeProfile = activeProfile,
            progressPreferences = progressPreferences,
            watchedPreferences = watchedPreferences,
            mutationStore = mutationStore
        )
    }

    private fun progress(contentId: String, position: Long, duration: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 100L
    )

    private data class Harness(
        val repository: WatchProgressRepositoryImpl,
        val activeProfile: MutableStateFlow<Int>,
        val progressPreferences: WatchProgressPreferences,
        val watchedPreferences: WatchedItemsPreferences,
        val mutationStore: WatchStateMutationStore
    )
}
