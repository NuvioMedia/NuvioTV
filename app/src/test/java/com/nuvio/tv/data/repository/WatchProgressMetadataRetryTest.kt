package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProgressMetadataRetryTest {

    @Test
    fun `failed metadata fetch is retried without a profile change`() = runBlocking {
        val contentId = "tt-retry"
        val progress = watchProgress(contentId)
        val providerProgress = MutableStateFlow(listOf(progress))
        val provider = FakeProgressProvider(providerProgress)
        val profileProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
        val activeProfile = MutableStateFlow(1)
        val attempts = AtomicInteger()

        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true) {
            every { observeAllProgress(1) } returns profileProgress
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.SIMKL)
        }
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMetaFromPrimaryAddon(any(), contentId) } answers {
                if (attempts.incrementAndGet() == 1) {
                    flowOf<NetworkResult<Meta>>(NetworkResult.Error("offline"))
                } else {
                    flowOf(NetworkResult.Success(meta(contentId)))
                }
            }
        }

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false },
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mockk<WatchStateMutationStore>(relaxed = true),
            serverWatched = mockk(relaxed = true)
        )
        repository.metadataRetryWindowMs = 0L

        val emissions = Channel<List<WatchProgress>>(Channel.UNLIMITED)
        val collection = launch(Dispatchers.Default) {
            repository.allProgress.collect { emissions.send(it) }
        }
        try {
            var initial: List<WatchProgress>? = null
            withTimeout(5_000) {
                while (initial.isNullOrEmpty()) initial = emissions.receive()
            }
            assertNull(initial!!.single().poster)
            withTimeout(5_000) {
                while (attempts.get() < 1) delay(10)
            }

            var enriched: WatchProgress? = null
            var lastWatched = 101L
            withTimeout(5_000) {
                while (enriched == null) {
                    providerProgress.value = listOf(progress.copy(lastWatched = lastWatched++))
                    enriched = emissions.receive().firstOrNull { it.poster == "poster.jpg" }
                    if (enriched == null) delay(25)
                }
            }

            assertNotNull(enriched)
            assertEquals(2, attempts.get())
        } finally {
            collection.cancel()
        }
    }

    @Test
    fun `repeated metadata failures stop retrying after reaching attempt cap`() = runBlocking {
        val failId = "tt-fail"
        val successId = "tt-success"
        val failProgress = watchProgress(failId)
        val successProgress = watchProgress(successId)
        val providerProgress = MutableStateFlow(listOf(failProgress, successProgress))
        val provider = FakeProgressProvider(providerProgress)
        val profileProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
        val activeProfile = MutableStateFlow(1)
        val failAttempts = AtomicInteger()
        val successAttempts = AtomicInteger()

        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true) {
            every { observeAllProgress(1) } returns profileProgress
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.SIMKL)
        }
        val metaRepository = mockk<MetaRepository>(relaxed = true) {
            every { getMetaFromPrimaryAddon(any(), failId) } answers {
                failAttempts.incrementAndGet()
                flowOf<NetworkResult<Meta>>(NetworkResult.Error("permanent failure"))
            }
            every { getMetaFromPrimaryAddon(any(), successId) } answers {
                successAttempts.incrementAndGet()
                flowOf(NetworkResult.Success(meta(successId)))
            }
        }

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false },
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mockk<WatchStateMutationStore>(relaxed = true),
            serverWatched = mockk(relaxed = true)
        )
        repository.metadataRetryWindowMs = 0L

        val emissions = Channel<List<WatchProgress>>(Channel.UNLIMITED)
        val collection = launch(Dispatchers.Default) {
            repository.allProgress.collect { emissions.send(it) }
        }
        try {
            withTimeout(5_000) {
                while (failAttempts.get() < 1 || successAttempts.get() < 1) delay(10)
            }

            for (i in 2..6) {
                providerProgress.value = listOf(
                    failProgress.copy(lastWatched = 100L + i),
                    successProgress.copy(lastWatched = 100L + i)
                )
                delay(30)
            }

            assertEquals(3, failAttempts.get())
            assertEquals(1, successAttempts.get())
        } finally {
            collection.cancel()
        }
    }

    private fun watchProgress(contentId: String) = WatchProgress(
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
        position = 30_000L,
        duration = 100_000L,
        lastWatched = 100L
    )

    private fun meta(contentId: String) = Meta(
        id = contentId,
        type = ContentType.MOVIE,
        name = "Retried Movie",
        poster = "poster.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private class FakeProgressProvider(
        override val allProgress: MutableStateFlow<List<WatchProgress>>
    ) : TrackingProgressProvider {
        override val providerId = TrackingProviderId.SIMKL
        override val isAuthenticated = flowOf(true)
        override val remoteProgressLoaded = flowOf(true)
        override val nextUpSeeds = flowOf(emptyList<WatchProgress>())
        override val watchedMovieIds = flowOf(emptySet<String>())
        override val watchedItems = flowOf(emptyList<WatchedItem>())

        override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
            flowOf(emptyMap())

        override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

        override fun isWatched(
            contentId: String,
            videoId: String?,
            season: Int?,
            episode: Int?
        ): Flow<Boolean> = flowOf(false)

        override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> = emptyMap()

        override suspend fun showIdSiblings(): Map<String, Set<String>> = emptyMap()

        override suspend fun refresh(intent: TrackingRefreshIntent) = Unit

        override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) = Unit

        override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

        override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

        override fun clearOptimistic() = Unit

        override fun isHiddenFromProgress(contentId: String): Boolean = false

        override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
    }
}
