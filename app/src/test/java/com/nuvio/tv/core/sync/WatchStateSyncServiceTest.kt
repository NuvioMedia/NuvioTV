package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.mutationKey
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateSyncServiceTest {
    @Test
    fun `progress push sends only pending mutations to the requested profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val preferences = mockk<WatchProgressPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val pending = progress("pending")
        coEvery { mutationStore.pendingProgressDeletes(2) } returns emptySet()
        coEvery { mutationStore.pendingProgressUpserts(2) } returns mapOf("pending" to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = progressService(postgrest, preferences, mutationStore)

        service.pushToRemote(2).getOrThrow()

        assertEquals(listOf("sync_push_watch_progress"), calls.map { it.first })
        assertEquals(2, calls.single().second.getValue("p_profile_id").jsonPrimitive.int)
        assertEquals(
            listOf("pending"),
            calls.single().second.getValue("p_entries").jsonArray.map {
                it.jsonObject.getValue("progress_key").jsonPrimitive.content
            }
        )
        coVerify(exactly = 0) { preferences.getAllRawEntries(any()) }
        coVerify(exactly = 1) {
            mutationStore.acknowledgeProgressUpserts(mapOf("pending" to pending), 2)
        }
    }

    @Test
    fun `progress flush deletes before upserting for the same profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        coEvery { mutationStore.pendingProgressDeletes(3) } returns setOf("removed")
        coEvery { mutationStore.pendingProgressUpserts(3) } returns mapOf("saved" to progress("saved"))
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = progressService(
            postgrest,
            mockk(relaxed = true),
            mutationStore
        )

        service.pushToRemote(3).getOrThrow()

        assertEquals(
            listOf("sync_delete_watch_progress", "sync_push_watch_progress"),
            calls.map { it.first }
        )
        assertTrue(calls.all { it.second.getValue("p_profile_id").jsonPrimitive.int == 3 })
    }

    @Test
    fun `failed progress push keeps its pending mutation`() = runTest {
        val postgrest = mockk<Postgrest>()
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val pending = progress("pending")
        coEvery { mutationStore.pendingProgressDeletes(4) } returns emptySet()
        coEvery { mutationStore.pendingProgressUpserts(4) } returns mapOf("pending" to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } throws IllegalStateException("offline")
        val service = progressService(
            postgrest,
            mockk(relaxed = true),
            mutationStore,
            canRefreshJwt = false
        )

        assertTrue(service.pushToRemote(4).isFailure)
        coVerify(exactly = 0) { mutationStore.acknowledgeProgressUpserts(any(), any()) }
    }

    @Test
    fun `watched push sends only pending mutations to the requested profile`() = runTest {
        val postgrest = mockk<Postgrest>()
        val preferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val pending = watched("pending")
        coEvery { mutationStore.pendingWatchedDeletes(2) } returns emptySet()
        coEvery { mutationStore.pendingWatchedUpserts(2) } returns mapOf(pending.mutationKey() to pending)
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } coAnswers {
            calls += firstArg<String>() to secondArg<JsonObject>()
            mockk<PostgrestResult>(relaxed = true)
        }
        val service = watchedService(postgrest, preferences, mutationStore)

        service.pushToRemote(2).getOrThrow()

        assertEquals(listOf("sync_push_watched_items"), calls.map { it.first })
        assertEquals(2, calls.single().second.getValue("p_profile_id").jsonPrimitive.int)
        assertEquals(
            listOf("pending"),
            calls.single().second.getValue("p_items").jsonArray.map {
                it.jsonObject.getValue("content_id").jsonPrimitive.content
            }
        )
        coVerify(exactly = 0) { preferences.getAllItems(any()) }
        coVerify(exactly = 1) {
            mutationStore.acknowledgeWatchedUpserts(match { it.toList() == listOf(pending) }, 2)
        }
    }

    private fun progressService(
        postgrest: Postgrest,
        preferences: WatchProgressPreferences,
        mutationStore: WatchStateMutationStore,
        canRefreshJwt: Boolean = false
    ): WatchProgressSyncService {
        val authManager = mockk<AuthManager>(relaxed = true)
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns canRefreshJwt
        return WatchProgressSyncService(
            authManager = authManager,
            postgrest = postgrest,
            watchProgressPreferences = preferences,
            trackingProviderRegistry = TrackingProgressProviderRegistry(emptySet()),
            traktSettingsDataStore = mockk(relaxed = true),
            profileManager = profileManager(),
            syncClientIdentity = syncClientIdentity(),
            mutationStore = mutationStore
        )
    }

    private fun watchedService(
        postgrest: Postgrest,
        preferences: WatchedItemsPreferences,
        mutationStore: WatchStateMutationStore
    ): WatchedItemsSyncService {
        val authManager = mockk<AuthManager>(relaxed = true)
        coEvery { authManager.refreshSessionIfJwtExpired(any()) } returns false
        return WatchedItemsSyncService(
            authManager = authManager,
            postgrest = postgrest,
            watchedItemsPreferences = preferences,
            trackingProviderRegistry = TrackingProgressProviderRegistry(emptySet()),
            traktSettingsDataStore = mockk(relaxed = true),
            profileManager = profileManager(),
            syncClientIdentity = syncClientIdentity(),
            mutationStore = mutationStore
        )
    }

    private fun profileManager(): ProfileManager = mockk<ProfileManager>().also {
        every { it.activeProfileId } returns MutableStateFlow(1)
    }

    private fun syncClientIdentity(): SyncClientIdentity = mockk<SyncClientIdentity>().also {
        every { it.currentClientId() } returns "nuvio-tv-test-client"
    }

    private fun progress(contentId: String) = WatchProgress(
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
        position = 2_000L,
        duration = 10_000L,
        lastWatched = 100L
    )

    private fun watched(contentId: String) = WatchedItem(
        contentId = contentId,
        contentType = "movie",
        title = contentId,
        season = null,
        episode = null,
        watchedAt = 100L
    )
}
