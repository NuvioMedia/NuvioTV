package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveTvChannelStateStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun favoritesAndRecentsPersistPerProfileAndSourceRemovalCleansThem() = runTest {
        val activeProfile = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        val dataStores = (1..2).associateWith { id ->
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                directory.root.resolve("live-tv-$id.preferences_pb")
            }
        }
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "live_tv_channel_state") } answers { dataStores.getValue(firstArg()) }
        }
        val store = LiveTvChannelStateStore(factory, manager)

        store.toggleFavorite("source-1:channel-a")
        store.recordRecent("source-1:channel-a")
        store.recordRecent("source-1:channel-b")
        store.recordRecent("source-1:channel-a")

        assertEquals(listOf("source-1:channel-a"), store.read().favoriteChannelIds)
        assertEquals(listOf("source-1:channel-a", "source-1:channel-b"), store.read().recentChannelIds)
        activeProfile.value = 2
        assertEquals(LiveTvChannelState(), store.read())
        store.toggleFavorite("source-2:channel-a")
        activeProfile.value = 1
        assertEquals(listOf("source-1:channel-a"), store.read().favoriteChannelIds)

        val recreated = LiveTvChannelStateStore(factory, manager)
        assertEquals(listOf("source-1:channel-a", "source-1:channel-b"), recreated.read().recentChannelIds)
        recreated.removeSource("source-1")
        assertEquals(LiveTvChannelState(), recreated.read())
    }

    @Test fun recentChannelsAreMostRecentFirstAndBounded() = runTest {
        val profile = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns profile }
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            directory.root.resolve("bounded-live-tv.preferences_pb")
        }
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "live_tv_channel_state") } returns dataStore
        }
        val store = LiveTvChannelStateStore(factory, manager)

        repeat(35) { store.recordRecent("source:${"channel-$it"}") }

        val state = store.read()
        assertEquals(30, state.recentChannelIds.size)
        assertEquals("source:channel-34", state.recentChannelIds.first())
        assertEquals("source:channel-5", state.recentChannelIds.last())
    }
}
