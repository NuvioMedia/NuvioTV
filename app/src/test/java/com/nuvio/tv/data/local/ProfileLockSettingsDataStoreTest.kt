package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLockSettingsDataStoreTest {
    private val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
    private val factory = mockk<ProfileDataStoreFactory>().also { factory ->
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
    }
    private val dataStore = ProfileLockSettingsDataStore(factory)

    @Test
    fun `profiles ask for their PIN on app start until turned off`() = runTest {
        assertTrue(dataStore.askPinOnAppStart(1).first())
        assertTrue(dataStore.askPinOnAppStart(4).first())
    }

    @Test
    fun `each profile keeps its own choice`() = runTest {
        dataStore.setAskPinOnAppStart(2, false)

        assertFalse(dataStore.askPinOnAppStart(2).first())
        assertTrue(dataStore.askPinOnAppStart(1).first())
        assertTrue(dataStore.askPinOnAppStart(3).first())

        dataStore.setAskPinOnAppStart(2, true)

        assertTrue(dataStore.askPinOnAppStart(2).first())
    }

    @Test
    fun `the choice is stored with the profile's synced settings`() = runTest {
        dataStore.setAskPinOnAppStart(3, false)

        assertTrue(stores.containsKey(3 to ProfileLockSettingsDataStore.FEATURE))
    }
}
