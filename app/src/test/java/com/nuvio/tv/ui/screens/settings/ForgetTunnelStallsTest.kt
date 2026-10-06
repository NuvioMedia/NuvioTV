package com.nuvio.tv.ui.screens.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.ui.screens.player.PlayerTunnelAvSyncPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForgetTunnelStallsTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    private val classesKey = stringSetPreferencesKey("tunnel_dead_audio_classes")
    private val signatureKey = stringPreferencesKey("tunnel_dead_audio_signature")
    private val tunnelingKey = booleanPreferencesKey("tunneling_enabled")

    @Before
    fun clean() {
        PlayerTunnelAvSyncPolicy.resetMemo()
    }

    @After
    fun cleanup() {
        PlayerTunnelAvSyncPolicy.resetMemo()
    }

    @Test
    fun forget_clearsStoredClassesSignatureAndMemoOfActiveProfileOnly() = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        val viewModel = PlaybackSettingsViewModel(
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        try {
            settings.setTunnelingEnabled(true)
            settings.recordTunnelDeadAudioClass("audio/eac3", "chain-a")
            settings.recordTunnelDeadAudioClass("pcm", "chain-a")
            active.value = 1
            settings.recordTunnelDeadAudioClass("audio/ac3", "chain-b")
            active.value = 0
            val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("audio/eac3", "pcm"), "chain-a", "chain-a")
            assertEquals(setOf("audio/eac3", "pcm"), seeded)

            viewModel.forgetTunnelStalls()

            val profile0 = stores[0].data.value
            assertNull(profile0[classesKey])
            assertNull(profile0[signatureKey])
            assertEquals(true, profile0[tunnelingKey])
            assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())
            assertEquals(setOf("audio/ac3"), stores[1].data.value[classesKey])

            val reseeded = PlayerTunnelAvSyncPolicy.seedFromStore(
                profile0[classesKey].orEmpty(), profile0[signatureKey], "chain-a"
            )
            assertTrue(reseeded.isEmpty())
            assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun subtitle_listsRememberedSoundTypesOrSaysNothingIsRemembered() {
        val remembered = { formats: String -> "Tunneling is skipped for $formats" }
        assertEquals(
            "Tunneling is skipped for E-AC-3, PCM",
            tunnelStallSubtitle(setOf("pcm", "audio/eac3"), remembered, "No tunnel stalls remembered.")
        )
        assertEquals(
            "No tunnel stalls remembered.",
            tunnelStallSubtitle(emptySet(), remembered, "No tunnel stalls remembered.")
        )
    }

    @Test
    fun rememberedClasses_combineTheStoredListWithStallsFoundThisRun() {
        PlayerTunnelAvSyncPolicy.deadAudioClasses.add("pcm")
        val classes = rememberedTunnelStallClasses(PlayerSettings(tunnelDeadAudioClasses = setOf("audio/eac3")))
        assertEquals(setOf("audio/eac3", "pcm"), classes)
        PlayerTunnelAvSyncPolicy.resetMemo()
        assertTrue(rememberedTunnelStallClasses(PlayerSettings()).isEmpty())
    }
}
