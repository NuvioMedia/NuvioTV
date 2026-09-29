package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pull replaces local watched items with what the remote returned, so anything the
 * remote does not know about is dropped unless it was marked after the last successful
 * push. These pin that boundary, including the case where nothing has ever been pushed.
 */
class WatchedItemsPullPreservationTest {

    private val gson = Gson()
    private val watchedItemsKey = stringSetPreferencesKey("watched_items")

    @Test
    fun `a device that has never pushed keeps items the remote does not return`() = runTest {
        val preferences = harness(item("local", watchedAt = 50L))

        preferences.replaceWithRemoteItems(
            remoteItems = listOf(item("remote", watchedAt = 10L)),
            lastSuccessfulPushMs = 0L
        )

        assertEquals(
            setOf("local", "remote"),
            preferences.getAllItems().map { it.contentId }.toSet()
        )
    }

    @Test
    fun `items marked after the last push are kept`() = runTest {
        val preferences = harness(item("unsynced", watchedAt = 200L))

        preferences.replaceWithRemoteItems(
            remoteItems = listOf(item("remote", watchedAt = 10L)),
            lastSuccessfulPushMs = 100L
        )

        assertEquals(
            setOf("unsynced", "remote"),
            preferences.getAllItems().map { it.contentId }.toSet()
        )
    }

    @Test
    fun `items marked before the last push are dropped when the remote omits them`() = runTest {
        val preferences = harness(item("synced", watchedAt = 50L))

        preferences.replaceWithRemoteItems(
            remoteItems = listOf(item("remote", watchedAt = 10L)),
            lastSuccessfulPushMs = 100L
        )

        assertEquals(
            setOf("remote"),
            preferences.getAllItems().map { it.contentId }.toSet()
        )
    }

    private fun item(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "movie",
        title = contentId,
        watchedAt = watchedAt
    )

    private fun harness(vararg local: WatchedItem): WatchedItemsPreferences {
        val seeded = emptyPreferences().toMutablePreferences().apply {
            this[watchedItemsKey] = local.map { gson.toJson(it) }.toSet()
        }.toPreferences()
        val store = TestPreferencesDataStore(seeded)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return WatchedItemsPreferences(factory, profileManager)
    }

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            return mutex.withLock {
                transform(state.value).also { state.value = it }
            }
        }
    }
}
