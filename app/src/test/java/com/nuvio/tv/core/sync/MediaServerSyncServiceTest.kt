package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.FakeServerProvider
import com.nuvio.tv.data.mediaserver.MemoryServerPersistence
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.SyncedLibrary
import com.nuvio.tv.data.mediaserver.SyncedServer
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServerSyncServiceTest {
    private var remote: List<SyncedServer>? = null
    private val calls = mutableListOf<String>()
    private val authState = MutableStateFlow<AuthState>(AuthState.FullAccount("user", "user@example.com"))
    private val activeProfileId = MutableStateFlow(1)
    private val repository = ServerRepository(MemoryServerPersistence(), listOf(FakeServerProvider()), CoroutineScope(Dispatchers.Unconfined))
    private val service = MediaServerSyncService(
        postgrest = postgrest(),
        authManager = mockk<AuthManager>(relaxed = true) { every { authState } returns this@MediaServerSyncServiceTest.authState },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns this@MediaServerSyncServiceTest.activeProfileId },
        repository = repository,
        syncClientIdentity = mockk { every { currentClientId() } returns "tv-test" }
    )

    @Test
    fun firstSyncMergesServersByIdentityAndKeepsLocalIds() = runBlocking {
        repository.store(connection("local-a", server = "s1"), token = "old-token")
        repository.store(connection("local-c", server = "s3"), token = "token-c")
        remote = listOf(
            synced("remote-a", server = "s1", token = "new-token", selected = false),
            synced("remote-b", server = "s2", token = "token-b")
        )

        service.syncFromRemote(1).getOrThrow()

        val connections = repository.uiState.value.connections
        assertEquals(listOf("local-a", "remote-b", "local-c"), connections.map { it.id })
        assertEquals("new-token", repository.session("local-a")?.token)
        assertFalse(connections.first().libraries.single().selected)
        assertEquals(listOf("s1", "s2", "s3"), remote!!.map { it.remoteServerId })
        assertEquals("token-c", remote!!.last().token)
    }

    @Test
    fun keepsLocalImportSettingWhenRemoteHasNone() = runBlocking {
        repository.store(connection("local-a", server = "s1").copy(importWatchState = true), token = "token-a")
        remote = listOf(synced("remote-a", server = "s1", token = "token-a"))

        service.syncFromRemote(1).getOrThrow()

        assertTrue(repository.uiState.value.connections.single().importWatchState)
    }

    @Test
    fun syncsMixedLibraries() = runBlocking {
        val mixed = ServerLibrary("90", "Everything", ServerMediaKind.MIXED)
        repository.store(connection("local-a", server = "s1").copy(libraries = listOf(mixed)), token = "token-a")

        service.syncFromRemote(1).getOrThrow()
        assertEquals(listOf(SyncedLibrary("90", "Everything", "mixed", true)), remote!!.single().libraries)

        remote = listOf(synced("remote-a", server = "s1", token = "token-a").copy(libraries = listOf(SyncedLibrary("90", "Everything", "mixed", false))))
        service.syncFromRemote(1).getOrThrow()

        assertEquals(listOf(mixed.copy(selected = false)), repository.uiState.value.connections.single().libraries)
    }

    @Test
    fun serversRemovedOnAnotherDeviceAreRemovedLocally() = runBlocking {
        remote = listOf(synced("a", server = "s1"), synced("b", server = "s2"))
        service.syncFromRemote(1).getOrThrow()
        assertEquals(2, repository.uiState.value.connections.size)

        remote = listOf(synced("a", server = "s1"))
        service.syncFromRemote(1).getOrThrow()

        assertEquals(listOf("s1"), repository.uiState.value.connections.map { it.remoteServerId })
        assertNull(repository.session("b"))
    }

    @Test
    fun localChangesArePushedWithTokens() = runBlocking {
        remote = listOf(synced("a", server = "s1", token = "shared"))
        service.syncFromRemote(1).getOrThrow()

        repository.setLibrarySelected("a", "10", false)
        service.syncFromRemote(1).getOrThrow()

        val pushed = remote!!.single()
        assertEquals("shared", pushed.token)
        assertFalse(pushed.libraries.single().selected)
        assertEquals("sync_push_media_servers", calls.last())
    }

    @Test
    fun seedsRemoteWhenNothingIsStored() = runBlocking {
        repository.store(connection("local-a", server = "s1"), token = "token-a")

        service.syncFromRemote(1).getOrThrow()

        assertEquals(listOf("token-a"), remote!!.map { it.token })
    }

    @Test
    fun removalIsSyncedAsAnEmptyList() = runBlocking {
        remote = listOf(synced("a", server = "s1"))
        service.syncFromRemote(1).getOrThrow()

        repository.remove("a")
        service.syncFromRemote(1).getOrThrow()

        assertTrue(remote!!.isEmpty())
    }

    @Test
    fun switchingProfilesPullsThatProfilesServers() = runBlocking {
        remote = listOf(synced("a", server = "s1", token = "shared"))

        activeProfileId.value = 6

        withTimeout(5_000L) {
            while (repository.uiState.value.connections.isEmpty()) delay(20)
        }
        assertEquals(listOf("s1"), repository.uiState.value.connections.map { it.remoteServerId })
        assertEquals("shared", repository.session("a")?.token)
        assertTrue(calls.contains("sync_pull_media_servers"))
    }

    @Test
    fun skipsWithoutAnAccount() = runBlocking {
        authState.value = AuthState.SignedOut
        repository.store(connection("local-a", server = "s1"), token = "token-a")

        service.syncFromRemote(1).getOrThrow()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun pushesEveryFieldEvenWhenDefault() = runBlocking {
        repository.store(connection("local-a", server = "s1").copy(enabled = true, useCatalogMetadata = false), token = "token-a")

        service.syncFromRemote(1).getOrThrow()

        assertEquals(1, remote!!.size)
        assertTrue(remote!!.single().enabled)
    }

    private fun connection(id: String, server: String) = ServerConnection(
        id = id,
        providerId = "fake",
        name = "Box",
        address = "https://fake.example",
        remoteServerId = server,
        remoteUserId = "u1",
        userName = "viewer",
        credentialRef = "k$id",
        libraries = listOf(FakeServerProvider.MOVIE_LIBRARY)
    )

    private fun synced(id: String, server: String, token: String = "token-$id", selected: Boolean = true) = SyncedServer(
        id = id,
        providerId = "fake",
        name = "Box",
        address = "https://fake.example",
        remoteServerId = server,
        remoteUserId = "u1",
        userName = "viewer",
        token = token,
        libraries = listOf(SyncedLibrary("10", "Movies", "movie", selected))
    )

    private fun postgrest(): Postgrest {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val name = firstArg<String>()
            val params = secondArg<JsonObject>()
            synchronized(calls) { calls += name }
            val body = when (name) {
                "sync_pull_media_servers" -> JsonArray(
                    listOfNotNull(remote?.let { servers ->
                        buildJsonObject { put("servers_json", Json.encodeToJsonElement(servers)) }
                    })
                ).toString()
                "sync_push_media_servers" -> {
                    val servers = params.getValue("p_servers").jsonArray
                    servers.forEach { server -> check(server.jsonObject.keys == REQUIRED_KEYS) { "Rejected ${server.jsonObject.keys}" } }
                    remote = servers.map { Json.decodeFromJsonElement<SyncedServer>(it) }
                    "\"2026-09-26T00:00:00Z\""
                }
                else -> error("Unexpected RPC $name")
            }
            PostgrestResult(body, Headers.Empty, postgrest)
        }
        return postgrest
    }

    private companion object {
        val REQUIRED_KEYS = setOf(
            "id", "provider_id", "name", "address", "remote_server_id", "remote_user_id",
            "user_name", "token", "libraries", "enabled", "use_catalog_metadata", "import_watch_state"
        )
    }
}
