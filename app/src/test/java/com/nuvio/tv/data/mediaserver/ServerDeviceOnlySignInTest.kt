package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDeviceOnlySignInTest {
    private class DeviceOnlyProvider(base: ServerProvider = FakeServerProvider(id = "local")) : ServerProvider by base {
        override val syncsSignIn: Boolean = false
    }

    private val synced = FakeServerProvider()
    private val local = DeviceOnlyProvider()
    private val repository = ServerRepository(MemoryServerPersistence(), listOf(synced, local), CoroutineScope(Dispatchers.Unconfined))

    private fun connection(id: String, providerId: String) = ServerConnection(
        id = id,
        providerId = providerId,
        name = "Box",
        address = "https://$id.example",
        remoteServerId = "server-$id",
        remoteUserId = "user-1",
        userName = "viewer",
        credentialRef = "k$id"
    )

    @Test
    fun deviceOnlySignInsStayOutOfTheAccountSnapshot() {
        repository.store(connection("c1", synced.id), "synced-token")
        repository.store(connection("c2", local.id), "local-token")

        val snapshot = repository.syncSnapshot(1)!!

        assertEquals(listOf("c1"), snapshot.servers.map { it.id })
    }

    @Test
    fun applyingTheAccountListKeepsDeviceOnlySignIns() {
        repository.store(connection("c1", synced.id), "synced-token")
        repository.store(connection("c2", local.id), "local-token")
        val snapshot = repository.syncSnapshot(1)!!
        val remote = connection("c3", synced.id).toSynced("remote-token")

        assertTrue(repository.applySync(snapshot, listOf(remote), setOf(remote.key)))

        assertEquals(setOf("c3", "c2"), repository.uiState.value.connections.map { it.id }.toSet())
        assertEquals("local-token", repository.session("c2")?.token)
        assertEquals("remote-token", repository.session("c3")?.token)
    }

    @Test
    fun savesAConnectionFromAnyKindOfSignIn() = runTest {
        val connection = repository.connect(local) {
            ServerSignIn(
                address = "https://media.example",
                serverName = "Home",
                serverId = "server-9",
                userId = "user-9",
                userName = "viewer",
                token = "code-token"
            )
        }

        assertEquals(local.id, connection.providerId)
        assertEquals("code-token", repository.session(connection.id)?.token)
        assertEquals(listOf("10", "20"), connection.libraries.map { it.id })
    }
}
