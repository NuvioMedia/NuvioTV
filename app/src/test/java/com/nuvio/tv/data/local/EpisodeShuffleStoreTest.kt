package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeShuffleStoreTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val activeProfile = MutableStateFlow(1)
    private val available = mutableMapOf<Int, MutableStateFlow<Boolean>>()
    private val stores = mutableMapOf<Pair<Int, String>, MemoryPreferences>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryPreferences)
        }
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns this@EpisodeShuffleStoreTest.activeProfile }
    private val layout = mockk<LayoutPreferenceDataStore> {
        every { randomEpisodeEnabledForProfile(any()) } answers {
            available.getOrPut(firstArg()) { MutableStateFlow(true) }
        }
    }
    private fun store() = EpisodeShuffleStore(factory, profiles, layout)

    @Test
    fun `show settings survive store recreation and remain independent`() = runTest {
        store().save("tmdb:123", EpisodeShuffleSettings(true, true), 1)
        store().save("tt456", EpisodeShuffleSettings(true, false), 1)
        val restored = store().observeProfile(1).first()
        assertEquals(EpisodeShuffleSettings(true, true), restored.settings("tmdb:123", "series"))
        assertEquals(EpisodeShuffleSettings(true, false), restored.settings("tt456", "tv"))
        assertFalse(restored.settings("other", "series").enabled)
        assertFalse(restored.settings("tt456", "movie").enabled)
    }

    @Test
    fun `disabling a show retains its pool`() = runTest {
        val store = store()
        store.save("show", EpisodeShuffleSettings(true, true), 1)
        store.save("show", EpisodeShuffleSettings(false, true), 1)
        assertEquals(EpisodeShuffleSettings(false, true), store.observeProfile(1).first().shows["show"])
    }

    @Test
    fun `global switch pauses shuffle without clearing saved choices`() = runTest {
        val store = store()
        store.save("show", EpisodeShuffleSettings(true, true), 1)
        assertTrue(store.observeProfile(1).first().settings("show", "series").enabled)
        available.getValue(1).value = false
        val paused = store.observeProfile(1).first()
        assertFalse(paused.settings("show", "series").enabled)
        assertEquals(EpisodeShuffleSettings(true, true), paused.shows["show"])
        available.getValue(1).value = true
        assertTrue(store.observeProfile(1).first().settings("show", "SERIES").enabled)
    }

    @Test
    fun `profile changes do not move writes into another profile`() = runTest {
        val store = store()
        activeProfile.value = 2
        store.save("show", EpisodeShuffleSettings(true, false), 1)
        assertTrue(store.profiles.first().shows.isEmpty())
        assertTrue(store.observeProfile(1).first().settings("show", "series").enabled)
        store.save("show", EpisodeShuffleSettings(true, true), 2)
        assertTrue(store.profiles.first().settings("show", "series").includeWatched)
        assertFalse(store.observeProfile(1).first().settings("show", "series").includeWatched)
    }

    @Test
    fun `empty show ids do not create preferences`() = runTest {
        val store = store()
        store.save(" ", EpisodeShuffleSettings(true, true), 1)
        assertTrue(store.profiles.first().shows.isEmpty())
    }
}
