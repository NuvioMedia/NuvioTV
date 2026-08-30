package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressPreferencesStorageTest {
    private val gson = Gson()

    @Test
    fun `empty legacy storage initializes without writing bucket files`() = runTest {
        val harness = harness(emptyMap())

        val result = harness.preferences.getAllRawEntries()

        assertTrue(result.isEmpty())
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `first save writes only the recent store`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(progress("item", lastWatched = 1L))

        assertEquals(setOf("item"), harness.recent.keys())
        assertTrue(harness.archive.keys().isEmpty())
        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `legacy snapshot migrates into recent and archive stores`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(200, harness.recent.keys().size)
        assertEquals(50, harness.archive.keys().size)
        assertNull(harness.metadata.value[watchProgressEntriesKey])
        assertEquals(
            WATCH_PROGRESS_STORAGE_VERSION,
            harness.metadata.value[watchProgressStorageVersionKey]
        )
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertEquals(1, harness.metadata.updateCount)
    }

    @Test
    fun `migration preserves a fifteen thousand item library`() = runTest {
        val entries = (0 until 15_000).associate { index ->
            "large$index" to progress("large$index", lastWatched = index.toLong())
        }
        val harness = harness(entries)

        val result = harness.preferences.getAllRawEntries()

        assertEquals(15_000, result.size)
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(14_800, harness.archive.keys().size)
        assertTrue("large14999" in harness.recent.keys())
        assertTrue("large0" in harness.archive.keys())
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("large14999").copy(position = 5_000L, lastWatched = 20_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `migration is idempotent after storage version is recorded`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val second = WatchProgressPreferences(harness.factory, harness.profileManager)

        val result = second.getAllRawEntries()

        assertEquals(250, result.size)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
    }

    @Test
    fun `saving an existing recent item writes only the recent store`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item249").copy(position = 5_000L, lastWatched = 1_000L)
        )

        assertEquals(1, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
        assertEquals(0, harness.metadata.updateCount)
        assertEquals(5_000L, harness.preferences.getAllRawEntries().getValue("item249").position)
    }

    @Test
    fun `saving an archived item promotes it and evicts the oldest recent item`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )

        val recentKeys = harness.recent.keys()
        val archiveKeys = harness.archive.keys()
        assertEquals(200, recentKeys.size)
        assertEquals(50, archiveKeys.size)
        assertTrue("item0" in recentKeys)
        assertTrue("item50" in archiveKeys)
        assertFalse("item0" in archiveKeys)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `removing an archived item does not rewrite the recent store`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.removeProgress("item0")

        assertEquals(0, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
        assertFalse("item0" in harness.preferences.getAllRawEntries())
    }

    @Test
    fun `batch saves preserve the recent limit and merged reads`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val updates = (0 until 5).map { index ->
            entries.getValue("item$index").copy(lastWatched = 2_000L + index)
        }

        harness.preferences.saveProgressBatch(updates)

        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(250, harness.preferences.getAllRawEntries().size)
        updates.forEach { update ->
            assertTrue(update.contentId in harness.recent.keys())
        }
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote merge rebalances new entries across both buckets`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to remote),
            removeMissingRemoteEntries = false
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(251, result.size)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
        assertEquals(51, harness.archive.keys().size)
        assertEquals(1, harness.recent.updateCount)
        assertEquals(1, harness.archive.updateCount)
    }

    @Test
    fun `remote delta deletes archived entries and promotes new progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()
        val remote = progress("remote", lastWatched = 2_000L)

        harness.preferences.applyRemoteChanges(
            upserts = mapOf("remote" to remote),
            deletes = listOf("item0")
        )

        val result = harness.preferences.getAllRawEntries()
        assertEquals(250, result.size)
        assertFalse("item0" in result)
        assertEquals(remote, result["remote"])
        assertTrue("remote" in harness.recent.keys())
        assertEquals(WATCH_PROGRESS_RECENT_LIMIT, harness.recent.keys().size)
    }

    @Test
    fun `empty remote replacement preserves local progress`() = runTest {
        val harness = harness(legacyEntries())
        harness.preferences.getAllRawEntries()
        harness.resetUpdateCounts()

        harness.preferences.replaceWithRemoteEntries(emptyMap())

        assertEquals(250, harness.preferences.getAllRawEntries().size)
        assertEquals(0, harness.recent.updateCount)
        assertEquals(0, harness.archive.updateCount)
    }

    @Test
    fun `clear preserving non trakt ids filters both buckets`() = runTest {
        val entries = legacyEntries().toMutableMap().apply {
            this["custom:recent"] = progress("custom:recent", lastWatched = 2_000L)
            this["custom:archive"] = progress("custom:archive", lastWatched = -1L)
        }
        val harness = harness(entries)
        harness.preferences.getAllRawEntries()

        harness.preferences.clearAllPreservingNonTraktIds { it.startsWith("custom:") }

        val result = harness.preferences.getAllRawEntries()
        assertEquals(setOf("custom:recent", "custom:archive"), result.keys)
        assertEquals(setOf("custom:recent", "custom:archive"), harness.recent.keys())
        assertTrue(harness.archive.keys().isEmpty())
    }

    @Test
    fun `progress flow merges both stores after migration`() = runTest {
        val harness = harness(legacyEntries())

        val result = harness.preferences.allRawProgress.first()

        assertEquals(250, result.size)
        assertEquals("item249", result.first().contentId)
        assertEquals("item0", result.last().contentId)
    }

    @Test
    fun `progress flow does not expose an intermediate bucket write`() = runTest {
        val entries = legacyEntries()
        val harness = harness(entries)
        harness.preferences.allRawProgress.first()
        val initialEmission = CompletableDeferred<Unit>()
        val nextEmission = async {
            var initialSeen = false
            harness.preferences.allRawProgress.first {
                if (!initialSeen) {
                    initialSeen = true
                    initialEmission.complete(Unit)
                    false
                } else {
                    true
                }
            }
        }
        initialEmission.await()

        harness.preferences.saveProgress(
            entries.getValue("item0").copy(position = 5_000L, lastWatched = 1_000L)
        )
        val result = nextEmission.await()

        assertEquals(250, result.size)
        assertEquals(5_000L, result.first { it.contentId == "item0" }.position)
    }

    // A pull deletes local entries the remote does not return, so the sync point decides
    // what counts as "already pushed". These pin that boundary, because a sync point taken
    // later than the data it describes silently deletes everything saved in between.

    @Test
    fun `progress saved after the sync point survives a pull that omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("unsynced", lastWatched = 200L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertTrue(harness.preferences.getAllRawEntries().containsKey("unsynced"))
    }

    @Test
    fun `progress older than the sync point is dropped when the remote omits it`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("synced", lastWatched = 50L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertFalse(harness.preferences.getAllRawEntries().containsKey("synced"))
    }

    @Test
    fun `an entry saved exactly at the sync point counts as pushed`() = runTest {
        val harness = harness(emptyMap())
        harness.preferences.saveProgress(progress("boundary", lastWatched = 100L))

        harness.preferences.mergeRemoteEntries(
            remoteEntries = mapOf("remote" to progress("remote", lastWatched = 50L)),
            lastSuccessfulPushMs = 100L
        )

        assertFalse(harness.preferences.getAllRawEntries().containsKey("boundary"))
    }

    private fun harness(entries: Map<String, WatchProgress>): Harness {
        val metadata = TestPreferencesDataStore(preferences(entries = entries))
        val recent = TestPreferencesDataStore()
        val archive = TestPreferencesDataStore()
        val stores = mapOf(
            WATCH_PROGRESS_METADATA_FEATURE to metadata,
            WATCH_PROGRESS_RECENT_FEATURE to recent,
            WATCH_PROGRESS_ARCHIVE_FEATURE to archive
        )
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getValue(secondArg<String>())
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(
            preferences = WatchProgressPreferences(factory, profileManager),
            factory = factory,
            profileManager = profileManager,
            metadata = metadata,
            recent = recent,
            archive = archive
        )
    }

    private fun legacyEntries(): Map<String, WatchProgress> {
        return (0 until 250).associate { index ->
            "item$index" to progress("item$index", lastWatched = index.toLong())
        }
    }

    private fun progress(contentId: String, lastWatched: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched
    )

    private fun preferences(entries: Map<String, WatchProgress>): Preferences {
        return emptyPreferences().toMutablePreferences().apply {
            this[watchProgressEntriesKey] = gson.toJson(entries)
        }.toPreferences()
    }

    private fun TestPreferencesDataStore.keys(): Set<String> {
        val json = value[watchProgressEntriesKey] ?: "{}"
        return gson.fromJson(json, JsonObject::class.java).keySet()
    }

    private data class Harness(
        val preferences: WatchProgressPreferences,
        val factory: ProfileDataStoreFactory,
        val profileManager: ProfileManager,
        val metadata: TestPreferencesDataStore,
        val recent: TestPreferencesDataStore,
        val archive: TestPreferencesDataStore
    ) {
        fun resetUpdateCounts() {
            metadata.resetUpdateCount()
            recent.resetUpdateCount()
            archive.resetUpdateCount()
        }
    }

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        var updateCount: Int = 0
            private set

        val value: Preferences
            get() = state.value

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                transform(state.value).also { updated ->
                    updateCount += 1
                    state.value = updated
                }
            }
        }

        fun resetUpdateCount() {
            updateCount = 0
        }
    }
}
