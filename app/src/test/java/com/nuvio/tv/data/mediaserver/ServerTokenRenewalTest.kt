package com.nuvio.tv.data.mediaserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTokenRenewalTest {
    private class RenewingProvider(base: ServerProvider = FakeServerProvider(id = "renewing")) : ServerProvider by base {
        var due = false
        var renewals = 0
        var refuse = false

        override val syncsSignIn: Boolean = false

        override fun renewalDue(session: ServerSession): Boolean = due

        override suspend fun renewToken(session: ServerSession, failure: ServerException?): String? {
            if (refuse) throw ServerException(ServerFailure.AUTH_REQUIRED)
            renewals++
            return "token-${renewals + 1}"
        }
    }

    private val provider = RenewingProvider()
    private val persistence = MemoryServerPersistence()
    private val repository = ServerRepository(persistence, listOf(provider), CoroutineScope(Dispatchers.Unconfined))
    private val connection = ServerConnection(
        id = "c1",
        providerId = provider.id,
        name = "Box",
        address = "https://box.example",
        remoteServerId = "server-1",
        remoteUserId = "user-1",
        userName = "viewer",
        credentialRef = "k1"
    )

    init {
        repository.store(connection, "token-1")
    }

    @Test
    fun anExpiredTokenIsRenewedAndTheCallRepeated() = runTest {
        val seen = mutableListOf<String>()

        val result = repository.call("c1") { _, session ->
            seen += session.token
            if (session.token == "token-1") throw ServerException(ServerFailure.AUTH_REQUIRED)
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("token-1", "token-2"), seen)
        assertEquals("token-2", repository.session("c1")?.token)
        assertTrue(persistence.values.getValue(1).contains("token-2"))
    }

    @Test
    fun aTokenRunningOutIsRenewedBeforeTheCall() = runTest {
        provider.due = true

        val token = repository.call("c1") { _, session -> session.token }

        assertEquals("token-2", token)
    }

    @Test
    fun callsAtTheSameTimeShareOneRenewal() = runTest {
        val tokens = (1..5).map {
            async {
                repository.call("c1") { _, session ->
                    if (session.token == "token-1") throw ServerException(ServerFailure.AUTH_REQUIRED)
                    session.token
                }
            }
        }.awaitAll()

        assertEquals(List(5) { "token-2" }, tokens)
        assertEquals(1, provider.renewals)
    }

    @Test
    fun aKeptTokenSurvivesAnAccountCopy() {
        val keeping = object : ServerProvider by FakeServerProvider(id = "keeping") {
            override fun keepsLocalToken(local: String, fromAccount: String): Boolean = true
            override fun tokenForAccount(token: String): String = "account-copy"
        }
        val repository = ServerRepository(MemoryServerPersistence(), listOf(keeping), CoroutineScope(Dispatchers.Unconfined))
        repository.store(connection.copy(providerId = keeping.id), "device-token")
        val snapshot = repository.syncSnapshot(1)!!

        assertEquals("account-copy", snapshot.servers.single().token)
        assertTrue(repository.applySync(snapshot, snapshot.servers, snapshot.servers.map { it.key }.toSet()))
        assertEquals("device-token", repository.session("c1")?.token)
    }

    @Test
    fun anAccountCopyTooLongForTheBackendStaysOnTheDevice() {
        val long = object : ServerProvider by FakeServerProvider(id = "long") {
            override fun tokenForAccount(token: String): String = "x".repeat(1_025)
        }
        val repository = ServerRepository(MemoryServerPersistence(), listOf(long), CoroutineScope(Dispatchers.Unconfined))
        repository.store(connection.copy(providerId = long.id), "device-token")
        val snapshot = repository.syncSnapshot(1)!!

        assertTrue(snapshot.servers.isEmpty())
        assertTrue(repository.applySync(snapshot, emptyList(), emptySet()))
        assertEquals(listOf("c1"), repository.uiState.value.connections.map { it.id })
        assertEquals("device-token", repository.session("c1")?.token)
    }

    @Test
    fun aPlayingReportOutlastsAServerListChange() = runTest {
        val started = repository.session("c1")!!
        repository.remove("c1")

        val token = repository.callForPlayback(started) { _, session -> session.token }

        assertEquals("token-1", token)
    }

    @Test
    fun aPlayingReportRenewsOnceAfterAnExpiredToken() = runTest {
        val started = repository.session("c1")!!
        var calls = 0

        val token = repository.callForPlayback(started) { _, session ->
            if (calls++ == 0) throw ServerException(ServerFailure.AUTH_REQUIRED)
            session.token
        }

        assertEquals(2, calls)
        assertEquals("token-2", token)
    }

    @Test
    fun aRenewalTheAccountDoesNotNeedIsNotUploaded() = runTest {
        val quiet = object : ServerProvider by FakeServerProvider(id = "quiet") {
            override fun renewalDue(session: ServerSession): Boolean = true
            override suspend fun renewToken(session: ServerSession, failure: ServerException?): String = "token-2"
            override fun renewalChangesAccount(previous: String, renewed: String): Boolean = false
        }
        val repository = ServerRepository(MemoryServerPersistence(), listOf(quiet), CoroutineScope(Dispatchers.Unconfined))
        repository.store(connection.copy(providerId = quiet.id), "token-1")
        val version = repository.syncSnapshot(1)!!.version

        repository.call("c1") { _, _ -> Unit }

        assertEquals("token-2", repository.session("c1")?.token)
        assertEquals(version, repository.syncSnapshot(1)!!.version)
    }

    @Test
    fun anEndedSignInAsksToSignInAgain() = runTest {
        provider.refuse = true

        val error = runCatching {
            repository.call("c1") { _, _ -> throw ServerException(ServerFailure.AUTH_REQUIRED) }
        }.exceptionOrNull() as ServerException

        assertEquals(ServerFailure.AUTH_REQUIRED, error.failure)
        assertEquals(ServerFailure.AUTH_REQUIRED, repository.uiState.value.failures["c1"])
    }
}
