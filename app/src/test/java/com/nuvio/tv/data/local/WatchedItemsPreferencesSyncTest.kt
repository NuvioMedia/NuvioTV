package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedItemsPreferencesSyncTest {
    @Test
    fun `snapshot is authoritative for non-pending items`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val remote = item("remote", 200L)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.replaceWithRemoteItems(listOf(remote), profileId = 1)

        assertEquals(listOf(remote), harness.preferences.getAllItems(1))
    }

    @Test
    fun `empty snapshot clears non-pending watched items`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("local", 100L), 1)

        harness.preferences.replaceWithRemoteItems(emptyList(), profileId = 1)

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `pending watched upsert survives snapshot`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        val preserved = harness.preferences.replaceWithRemoteItems(
            remoteItems = emptyList(),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertTrue(preserved)
        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `pending watched delete suppresses snapshot upsert`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.replaceWithRemoteItems(
            remoteItems = listOf(remote),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `remote delete cannot remove pending watched upsert`() = runTest {
        val harness = harness()
        val local = item("local", 100L)
        val key = WatchedMutationKey("local", 1, 2)
        harness.preferences.markAsWatched(local, 1)

        harness.preferences.applyRemoteChanges(
            upserts = emptyList(),
            deletes = listOf(Triple("local", 1, 2)),
            pendingUpsertKeys = setOf(key),
            profileId = 1
        )

        assertEquals(listOf(local), harness.preferences.getAllItems(1))
    }

    @Test
    fun `remote upsert cannot restore pending watched delete`() = runTest {
        val harness = harness()
        val remote = item("remote", 100L)
        val key = WatchedMutationKey("remote", 1, 2)

        harness.preferences.applyRemoteChanges(
            upserts = listOf(remote),
            deletes = emptyList(),
            pendingDeleteKeys = setOf(key),
            profileId = 1
        )

        assertTrue(harness.preferences.getAllItems(1).isEmpty())
    }

    @Test
    fun `explicit watched flow remains bound after active profile changes`() = runTest {
        val harness = harness()
        val firstProfileItem = item("first", 100L)
        val secondProfileItem = item("second", 200L)
        harness.preferences.markAsWatched(firstProfileItem, 1)
        harness.preferences.markAsWatched(secondProfileItem, 2)
        val fixedProfileFlow = harness.preferences.observeAllItems(1)

        harness.activeProfile.value = 2

        assertEquals(listOf(firstProfileItem), fixedProfileFlow.first())
        assertFalse(fixedProfileFlow.first().contains(secondProfileItem))
    }

    @Test
    fun `unchanged entries reuse decoded items across reads`() = runTest {
        val harness = harness()
        val first = item("first", 100L)
        val second = item("second", 200L)
        harness.preferences.markAsWatched(first, 1)
        val before = harness.preferences.getAllItems(1).single()

        harness.preferences.markAsWatched(second, 1)
        harness.preferences.advanceLastSuccessfulPushMs(300L, 1)
        val after = harness.preferences.getAllItems(1)

        assertEquals(setOf(first, second), after.toSet())
        assertSame(before, after.single { it.contentId == "first" })
        assertEquals(after.toSet(), harness.preferences.observeAllItems(1).first().toSet())
    }

    @Test
    fun `decoded items follow updates to the same entry`() = runTest {
        val harness = harness()
        harness.preferences.markAsWatched(item("show", 100L), 1)
        harness.preferences.getAllItems(1)

        harness.preferences.markAsWatched(item("show", 200L), 1)

        assertEquals(listOf(item("show", 200L)), harness.preferences.getAllItems(1))
    }

    @Test
    fun `entries that fail to parse are skipped on every read`() = runTest {
        val store = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val preferences = WatchedItemsPreferences(factory, profileManager)
        val valid = item("valid", 100L)
        preferences.markAsWatched(valid, 1)
        store.edit { prefs ->
            val key = stringSetPreferencesKey("watched_items")
            prefs[key] = prefs[key].orEmpty() + "{not json"
        }

        val first = preferences.getAllItems(1)
        val second = preferences.getAllItems(1)

        assertEquals(listOf(valid), first)
        assertEquals(listOf(valid), second)
        assertSame(first.single(), second.single())
    }

    private fun harness(): Harness {
        val activeProfile = MutableStateFlow(1)
        val stores = mutableMapOf<Int, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        return Harness(
            preferences = WatchedItemsPreferences(factory, profileManager),
            activeProfile = activeProfile
        )
    }

    private fun item(contentId: String, watchedAt: Long) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = 1,
        episode = 2,
        watchedAt = watchedAt
    )

    private data class Harness(
        val preferences: WatchedItemsPreferences,
        val activeProfile: MutableStateFlow<Int>
    )
}
