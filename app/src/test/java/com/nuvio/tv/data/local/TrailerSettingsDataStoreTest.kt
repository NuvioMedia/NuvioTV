package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrailerSettingsDataStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `background playback and immediate start persist without changing another profile`() = runBlocking {
        val profileId = MutableStateFlow(1)
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns profileId

        suspend fun withStore(check: suspend (TrailerSettingsDataStore) -> Unit) {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val factory = mockk<ProfileDataStoreFactory>()
            val stores = (1..2).associateWith { id ->
                PreferenceDataStoreFactory.create(scope = scope) {
                    directory.root.resolve("trailers-$id.preferences_pb")
                }
            }
            every { factory.get(any(), "trailer_settings") } answers { stores.getValue(firstArg()) }
            try {
                check(TrailerSettingsDataStore(factory, profiles))
            } finally {
                job.cancelAndJoin()
            }
        }

        withStore { settings ->
            assertFalse(settings.settings.first().backgroundPlaybackEnabled)
            assertEquals(7, settings.settings.first().delaySeconds)
            settings.setBackgroundPlaybackEnabled(true)
            settings.setDelaySeconds(0)
            assertTrue(settings.settings.first().backgroundPlaybackEnabled)
            assertEquals(0, settings.settings.first().delaySeconds)

            profileId.value = 2
            assertFalse(settings.settings.first().backgroundPlaybackEnabled)
            assertEquals(7, settings.settings.first().delaySeconds)
        }

        // Reopen the real preference files, as after an application restart.
        profileId.value = 1
        withStore { settings ->
            assertTrue(settings.settings.first().backgroundPlaybackEnabled)
            assertEquals(0, settings.settings.first().delaySeconds)
            settings.setEnabled(false)
            assertFalse(settings.settings.first().enabled)
            settings.setEnabled(true)
            assertTrue(settings.settings.first().backgroundPlaybackEnabled)
        }
    }
}
