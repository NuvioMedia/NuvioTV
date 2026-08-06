package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrackingProgressRefreshCoordinatorTest {
    @Test
    fun `refreshes every connected provider and isolates failures`() = runTest {
        val traktFailure = IllegalStateException("trakt unavailable")
        val trakt = FakeProgressProvider(
            id = TrackingProviderId.TRAKT,
            authenticated = true,
            refreshFailure = traktFailure
        )
        val simkl = FakeProgressProvider(
            id = TrackingProviderId.SIMKL,
            authenticated = true
        )
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(simkl, trakt))
        )

        val failures = coordinator.refreshConnected(TrackingRefreshIntent.INVALIDATED)

        assertEquals(1, trakt.refreshCount)
        assertEquals(1, simkl.refreshCount)
        assertEquals(TrackingRefreshIntent.INVALIDATED, trakt.lastIntent)
        assertEquals(TrackingRefreshIntent.INVALIDATED, simkl.lastIntent)
        assertEquals(listOf(TrackingProviderId.TRAKT), failures.map { it.providerId })
        assertTrue(failures.single().cause === traktFailure)
    }

    @Test
    fun `skips disconnected providers`() = runTest {
        val trakt = FakeProgressProvider(TrackingProviderId.TRAKT, authenticated = false)
        val simkl = FakeProgressProvider(TrackingProviderId.SIMKL, authenticated = true)
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(trakt, simkl))
        )

        val failures = coordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC)

        assertEquals(0, trakt.refreshCount)
        assertEquals(1, simkl.refreshCount)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `rethrows cancellation`() = runTest {
        val provider = FakeProgressProvider(
            id = TrackingProviderId.SIMKL,
            authenticated = true,
            refreshFailure = CancellationException("cancel")
        )
        val coordinator = TrackingProgressRefreshCoordinator(
            TrackingProgressProviderRegistry(setOf(provider))
        )

        try {
            coordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC)
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancel", error.message)
        }
    }

    @Test
    fun `default provider policy is neutral`() = runTest {
        val provider = FakeProgressProvider(TrackingProviderId.SIMKL, authenticated = true)
        val completed = progress(position = 90L, duration = 100L)
        val incomplete = progress(position = 50L, duration = 100L)

        assertFalse(provider.ownsCompletedHistoryProjection)
        assertNull(provider.continueWatchingCutoffEpochMs(60, 1_000L))
        assertTrue(provider.shouldUseAsNextUpSeed(completed, 1_000L))
        assertFalse(provider.shouldUseAsNextUpSeed(incomplete, 1_000L))
        assertEquals("wrapped", provider.normalizeParentContentId("wrapped", "tt123"))
        assertEquals(completed, provider.prepareNextUpSeed(completed))
    }

    private class FakeProgressProvider(
        id: TrackingProviderId,
        authenticated: Boolean,
        private val refreshFailure: Throwable? = null
    ) : TrackingProgressProvider {
        override val providerId = id
        override val isAuthenticated = MutableStateFlow(authenticated)
        override val allProgress = flowOf(emptyList<WatchProgress>())
        override val remoteProgressLoaded = flowOf(true)
        override val nextUpSeeds = flowOf(emptyList<WatchProgress>())
        override val watchedMovieIds = flowOf(emptySet<String>())
        override val watchedItems = flowOf(emptyList<WatchedItem>())
        var refreshCount = 0
        var lastIntent: TrackingRefreshIntent? = null

        override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
            flowOf(emptyMap())

        override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> =
            flowOf(emptyList())

        override fun isWatched(
            contentId: String,
            videoId: String?,
            season: Int?,
            episode: Int?
        ): Flow<Boolean> = flowOf(false)

        override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> = emptyMap()

        override suspend fun showIdSiblings(): Map<String, Set<String>> = emptyMap()

        override suspend fun refresh(intent: TrackingRefreshIntent) {
            refreshCount += 1
            lastIntent = intent
            refreshFailure?.let { throw it }
        }

        override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) = Unit

        override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

        override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

        override fun clearOptimistic() = Unit

        override fun isHiddenFromProgress(contentId: String): Boolean = false

        override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
    }

    private fun progress(position: Long, duration: Long) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt123:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 1_000L
    )
}
