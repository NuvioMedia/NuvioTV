package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListTrackingProgressProviderTest {
    @Test
    fun `all consumers reuse the fresh cached projection without issuing reads`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(harness.snapshot().copy(
            watched = listOf(mdbListTestMovie(), mdbListTestEpisode()), playback = listOf(mdbListTestPlayback())
        ))
        val provider = provider(harness)
        assertEquals(2, provider.watchedItems.first().size)
        assertTrue(provider.isWatched("tmdb:1", null, 1, 1).first())
        assertEquals(listOf("tt1"), provider.nextUpSeeds.first().map { it.contentId })
        assertTrue(provider.watchedMovieIds.first().containsAll(listOf("tt1", "tmdb:1")))
        runCurrent()
        assertTrue(provider.remoteProgressLoaded.first())
        assertTrue(harness.remote.calls.isEmpty())
        assertTrue(harness.http.engine.requests.isEmpty())
    }

    @Test
    fun `profile switching publishes only data belonging to the authorized scope`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.seed(harness.snapshot(99).copy(watched = listOf(mdbListTestMovie(2))), profileId = 2)
        val provider = provider(harness)
        var visible = emptyList<WatchedItem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.watchedItems.collect { visible = it } }
        runCurrent()
        assertEquals(listOf("tt1"), visible.map { it.contentId })
        harness.activeProfile.value = 2
        runCurrent()
        assertTrue(visible.isEmpty())
        assertFalse(provider.remoteProgressLoaded.first())
        harness.http.store.selectProfile(2)
        harness.http.connected()
        harness.http.store.saveUser(MdbListUser(99, "second-viewer"), harness.http.store.scope())
        runCurrent()
        assertEquals(listOf("tt2"), visible.map { it.contentId })
        assertTrue(provider.remoteProgressLoaded.first())
        harness.http.store.clearAuth()
        runCurrent()
        assertTrue(visible.isEmpty())
        assertFalse(provider.isAuthenticated.first())
    }

    @Test
    fun `exact episode IDs are checked while unresolved anime keeps local tracking`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(harness.snapshot().copy(watched = listOf(mdbListTestEpisode(2, 3))))
        harness.repository.ensureLoaded()
        val provider = provider(harness)
        assertEquals(true, provider.isWatchedByVideoId("tt1:2:3", 3))
        assertEquals(false, provider.isWatchedByVideoId("tmdb:1:2:4", 4))
        assertEquals(null, provider.isWatchedByVideoId("mal:1:2:3", 3))
        assertTrue(provider.retainsLocalProgress("kitsu:123"))
        assertFalse(provider.retainsLocalProgress("tt1"))
    }

    @Test
    fun `anime split differently by the addon is read in the addon's numbering`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(harness.snapshot().copy(watched = listOf(mdbListTestEpisode(1, 34), mdbListTestEpisode(1, 35))))
        harness.repository.ensureLoaded()
        val provider = provider(harness, splitAnimeMapping())

        assertEquals(true, provider.isWatchedByVideoId("tt1:2:11", 11))
        assertEquals(false, provider.isWatchedByVideoId("tt1:2:12", 12))
        assertEquals(null, provider.isWatchedByVideoId("tt1:1:35", 35))
        assertTrue(provider.isWatched("tt1", "tt1:2:11", 2, 11).first())

        val episodes = provider.episodeProgress("tt1").first()
        assertEquals(setOf(2 to 10, 2 to 11), episodes.keys)
        assertEquals("tt1:2:11", episodes.getValue(2 to 11).videoId)
        assertTrue(episodes.getValue(2 to 11).isCompleted())

        val seed = provider.prepareNextUpSeed(seedProgress(season = 1, episode = 35))
        assertEquals(2, seed.season)
        assertEquals(11, seed.episode)
        assertEquals("tt1:2:11", seed.videoId)
    }

    private fun seedProgress(season: Int, episode: Int) = WatchProgress(
        contentId = "tt1", contentType = "series", name = "Show", poster = null, backdrop = null, logo = null,
        videoId = "tt1:$season:$episode", season = season, episode = episode, episodeTitle = null,
        position = 0, duration = 0, lastWatched = 0, progressPercent = 100f
    )

    private fun provider(
        harness: MdbListSyncTestHarness,
        mapping: MdbListEpisodeMapping? = null
    ): MdbListTrackingProgressProvider {
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns harness.activeProfile }
        val layout = mockk<LayoutPreferenceDataStore> { every { nextUpFromFurthestEpisode } returns MutableStateFlow(true) }
        val episodeMappings = mockk<MdbListEpisodeMappingService> {
            every { cached(any()) } returns mapping
            coEvery { resolve(any(), any()) } returns mapping
        }
        return MdbListTrackingProgressProvider(
            harness.repository, MdbListScrobbleService(harness.http.api, harness.repository), episodeMappings,
            harness.http.store, profiles, layout
        )
    }
}

/** One TMDB season of 48 episodes that the addon splits into two seasons of 24. */
internal fun splitAnimeMapping() = MdbListEpisodeMapping(
    addonEpisodes = (1..2).flatMap { season ->
        (1..24).map { EpisodeMappingEntry(season, it, videoId = "tt1:$season:$it") }
    },
    tmdbEpisodes = (1..48).map { EpisodeMappingEntry(1, it) }
)
