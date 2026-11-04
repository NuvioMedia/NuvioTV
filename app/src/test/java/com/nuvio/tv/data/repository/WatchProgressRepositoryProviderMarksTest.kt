package com.nuvio.tv.data.repository

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With a tracking provider as the watch progress source, its data decides what shows as watched.
 * A single item marked here that it does not report, like an add-on's YouTube video, has to stay
 * watched anyway, until the provider reports it or it is unmarked.
 */
class WatchProgressRepositoryProviderMarksTest {

    @Test
    fun `a single item marked with a provider as the source stays watched though the provider never reports it`() =
        runBlocking {
            val harness = harness()
            harness.awaitProvider(harness.repository)

            harness.repository.markAsCompleted(progress(VIDEO, "youtube"))

            assertTrue(harness.repository.watchedIdsSoon { VIDEO in it })
            assertTrue(withTimeoutOrNull(TIMEOUT_MS) { harness.repository.isWatched(VIDEO, null).first { it } } == true)
        }

    @Test
    fun `the mark is still there after the app restarts`() = runBlocking {
        val harness = harness()
        harness.awaitProvider(harness.repository)
        harness.repository.markAsCompleted(progress(VIDEO, "youtube"))

        val restarted = harness.newRepository()
        harness.awaitProvider(restarted)

        assertTrue(restarted.watchedIdsSoon { VIDEO in it })
    }

    @Test
    fun `an optimistic provider entry does not end the mark, so it outlives the overlay`() = runBlocking {
        val harness = harness()
        harness.awaitProvider(harness.repository)
        // Like Trakt's optimistic progress: shown for a while but never accepted by the provider.
        harness.providerWatched.value = setOf(SENTINEL, VIDEO)

        harness.repository.markAsCompleted(progress(VIDEO, "youtube"))
        delay(500)
        assertEquals(setOf(VIDEO), harness.watchedPreferences.observePendingProviderMarks(1).first())

        harness.providerWatched.value = setOf(SENTINEL)
        assertTrue(harness.repository.watchedIdsSoon { VIDEO in it })
    }

    @Test
    fun `once the provider reports the item its data decides, so an unmark elsewhere shows`() = runBlocking {
        val harness = harness()
        harness.awaitProvider(harness.repository)
        harness.repository.markAsCompleted(progress(MOVIE, "movie"))

        harness.providerReported.value = setOf(SENTINEL, MOVIE)
        harness.providerWatched.value = setOf(SENTINEL, MOVIE)
        val acknowledged = withTimeoutOrNull(TIMEOUT_MS) {
            harness.watchedPreferences.observePendingProviderMarks(1).first { it.isEmpty() }
        }
        assertTrue(acknowledged != null)

        // Unwatched on the provider from another device.
        harness.providerReported.value = setOf(SENTINEL)
        harness.providerWatched.value = setOf(SENTINEL)
        assertTrue(harness.repository.watchedIdsSoon { MOVIE !in it })
    }

    @Test
    fun `unmarking the item ends the mark`() = runBlocking {
        val harness = harness()
        harness.awaitProvider(harness.repository)
        harness.repository.markAsCompleted(progress(VIDEO, "youtube"))
        assertTrue(harness.repository.watchedIdsSoon { VIDEO in it })

        harness.repository.removeFromHistory(VIDEO)

        assertTrue(harness.repository.watchedIdsSoon { VIDEO !in it })
        assertEquals(emptySet<String>(), harness.watchedPreferences.observePendingProviderMarks(1).first())
    }

    @Test
    fun `without a provider a mark is not kept as pending`() = runBlocking {
        val harness = harness(source = WatchProgressSource.NUVIO_SYNC)

        harness.repository.markAsCompleted(progress(VIDEO, "youtube"))

        assertEquals(emptySet<String>(), harness.watchedPreferences.observePendingProviderMarks(1).first())
        assertTrue(harness.repository.watchedIdsSoon { VIDEO in it })
    }

    private class Harness(
        val watchedPreferences: WatchedItemsPreferences,
        val providerWatched: MutableStateFlow<Set<String>>,
        val providerReported: MutableStateFlow<Set<String>>,
        val newRepository: () -> WatchProgressRepositoryImpl
    ) {
        val repository = newRepository()

        /** The provider's sentinel id shows once the repository reads from the provider. */
        suspend fun awaitProvider(repository: WatchProgressRepositoryImpl) {
            assertTrue("the provider never became the source", repository.watchedIdsSoon { SENTINEL in it })
        }
    }

    private fun harness(source: WatchProgressSource = WatchProgressSource.TRAKT): Harness {
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val store = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val watchedPreferences = WatchedItemsPreferences(factory, profileManager)

        val providerWatched = MutableStateFlow(setOf(SENTINEL))
        val providerReported = MutableStateFlow(setOf(SENTINEL))
        val provider = mockk<TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns TrackingProviderId.TRAKT
        every { provider.isAuthenticated } returns MutableStateFlow(true)
        every { provider.watchedMovieIds } returns providerWatched
        every { provider.reportedWatchedMovieIds } returns providerReported
        every { provider.isWatched(any(), any(), any(), any()) } answers {
            val contentId = firstArg<String>()
            providerWatched.map { contentId in it }
        }

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        every { progressPreferences.allProgress } returns flowOf(emptyList())
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(source)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false

        return Harness(watchedPreferences, providerWatched, providerReported) {
            WatchProgressRepositoryImpl(
                watchProgressPreferences = progressPreferences,
                traktSettingsDataStore = traktSettings,
                layoutPreferenceDataStore = mockk(relaxed = true),
                watchProgressSyncService = mockk(relaxed = true),
                watchedItemsPreferences = watchedPreferences,
                watchedItemsSyncService = mockk(relaxed = true),
                authManager = authManager,
                metaRepository = mockk(relaxed = true),
                tmdbService = mockk(relaxed = true),
                profileManager = profileManager,
                trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
                trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
                mutationStore = mockk(relaxed = true),
                serverWatched = mockk(relaxed = true)
            )
        }
    }

    private fun progress(contentId: String, type: String) = WatchProgress(
        contentId = contentId,
        contentType = type,
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = 100L
    )

    private companion object {
        suspend fun WatchProgressRepositoryImpl.watchedIdsSoon(predicate: (Set<String>) -> Boolean): Boolean =
            withTimeoutOrNull(TIMEOUT_MS) { observeWatchedMovieIds().first(predicate) } != null

        const val VIDEO = "yt_id:abc123"
        const val MOVIE = "tt0000001"
        const val SENTINEL = "tt9999999"
        const val TIMEOUT_MS = 5_000L
    }
}
