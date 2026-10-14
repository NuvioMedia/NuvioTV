package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerSourceSelectionStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `rank mode survives recreation and profile changes without changing shared legacy mode`() = runTest {
        val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns active }
        val stores = (1..2).associateWith { id ->
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                directory.root.resolve("player-$id.preferences_pb")
            }
        }
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "player_settings") } answers { stores.getValue(firstArg()) }
        }
        val context = mockk<Context>(relaxed = true)
        val settings = PlayerSettingsDataStore(factory, manager, context)
        settings.setStreamAutoPlayMode(StreamAutoPlayMode.FIRST_STREAM)
        settings.setStreamAutoPlayMode(StreamAutoPlayMode.BALANCED)
        val legacyKey = stringPreferencesKey("stream_auto_play_mode")
        val localKey = stringPreferencesKey("telumia_source_mode_v1")
        assertEquals("FIRST_STREAM", stores.getValue(1).data.first()[legacyKey])
        assertEquals("BALANCED", stores.getValue(1).data.first()[localKey])
        assertEquals(StreamAutoPlayMode.BALANCED, settings.playerSettings.first().streamAutoPlayMode)
        active.value = 2
        assertEquals(StreamAutoPlayMode.MANUAL, settings.playerSettings.first().streamAutoPlayMode)
        settings.setStreamAutoPlayMode(StreamAutoPlayMode.DATA_SAVER)
        active.value = 1
        val recreated = PlayerSettingsDataStore(factory, manager, context)
        assertEquals(StreamAutoPlayMode.BALANCED, recreated.playerSettings.first().streamAutoPlayMode)
        recreated.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
        assertNull(stores.getValue(1).data.first()[localKey])
        assertEquals(StreamAutoPlayMode.MANUAL, recreated.playerSettings.first().streamAutoPlayMode)
        active.value = 2
        assertEquals(StreamAutoPlayMode.DATA_SAVER, recreated.playerSettings.first().streamAutoPlayMode)
    }
}
