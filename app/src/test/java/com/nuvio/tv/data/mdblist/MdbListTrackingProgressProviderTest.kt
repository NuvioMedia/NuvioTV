package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchedItem
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

    private fun provider(harness: MdbListSyncTestHarness): MdbListTrackingProgressProvider {
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns harness.activeProfile }
        val layout = mockk<LayoutPreferenceDataStore> { every { nextUpFromFurthestEpisode } returns MutableStateFlow(true) }
        return MdbListTrackingProgressProvider(
            harness.repository, MdbListScrobbleService(harness.http.api, harness.repository), harness.http.store, profiles, layout
        )
    }
}
