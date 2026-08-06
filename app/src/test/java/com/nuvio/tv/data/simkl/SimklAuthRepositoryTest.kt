package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SimklAuthRepositoryTest {
    @Test
    fun `pin creation persists documented session fields`() = runTest {
        val harness = Harness(
            response(
                200,
                """{"result":"OK","device_code":"DEVICE_CODE","user_code":"A1B2C","verification_uri":"https://simkl.com/pin","expires_in":900,"interval":5}"""
            )
        )

        val session = harness.repository.startPinAuth()

        assertEquals("A1B2C", session.userCode)
        assertEquals("https://simkl.com/pin", session.verificationUri)
        assertEquals(900_000L, session.expiresAtEpochMs)
        assertEquals(5, session.intervalSeconds)
        assertEquals(SimklConnectionMode.AWAITING_APPROVAL, harness.storage.state.value.mode)
        assertFalse("Authorization" in harness.engine.requests.single().headers)
    }

    @Test
    fun `pending poll keeps the original pin session`() = runTest {
        val harness = Harness(response(200, """{"result":"KO","message":"Authorization pending"}"""))
        harness.storage.savePinSession(session())

        assertEquals(SimklPinPollResult.Pending, harness.repository.pollPin())
        assertEquals("ABCDE", harness.storage.state.value.pinSession?.userCode)
        assertFalse(harness.storage.state.value.isAuthenticated)
    }

    @Test
    fun `approved pin stores token clears code and loads identity`() = runTest {
        val harness = Harness(
            response(200, """{"result":"OK","access_token":"secret-token"}"""),
            response(200, """{"user":{"name":"viewer"},"account":{"id":42}}""")
        )
        harness.storage.savePinSession(session())

        assertEquals(SimklPinPollResult.Authorized, harness.repository.pollPin())

        assertEquals("secret-token", harness.storage.accessToken())
        assertNull(harness.storage.state.value.pinSession)
        assertTrue(harness.storage.state.value.isAuthenticated)
        assertEquals("viewer", harness.storage.state.value.username)
        assertEquals(42L, harness.storage.state.value.accountId)
        assertEquals("Bearer secret-token", harness.engine.requests.last().headers["Authorization"])
    }

    @Test
    fun `approved pin remains connected when identity fetch fails`() = runTest {
        val harness = Harness(
            response(200, """{"result":"OK","access_token":"secret-token"}"""),
            response(400, """{"error":"settings_unavailable"}""")
        )
        harness.storage.savePinSession(session())

        assertEquals(SimklPinPollResult.Authorized, harness.repository.pollPin())
        assertTrue(harness.storage.state.value.isAuthenticated)
        assertEquals("secret-token", harness.storage.accessToken())
        assertNull(harness.storage.state.value.username)
    }

    @Test
    fun `connected account can recover a missing normalized identity`() = runTest {
        val harness = Harness(
            response(200, """{"user":{"name":"  Actual Viewer  "},"account":{"id":42}}""")
        )
        harness.storage.completePinAuthorization("secret-token", harness.storage.currentScope())

        assertEquals("Actual Viewer", harness.repository.refreshUserSettings())
        assertEquals("Actual Viewer", harness.storage.state.value.username)
        assertEquals(42L, harness.storage.state.value.accountId)
        assertTrue(harness.storage.state.value.hasFetchedUserSettings)
    }

    @Test
    fun `fresh init response while polling invalidates original code`() = runTest {
        val harness = Harness(
            response(
                200,
                """{"result":"OK","device_code":"DEVICE_CODE","user_code":"ZZZZZ","verification_uri":"https://simkl.com/pin","expires_in":900,"interval":5}"""
            )
        )
        harness.storage.savePinSession(session())

        assertEquals(SimklPinPollResult.Invalidated, harness.repository.pollPin())
        assertNull(harness.storage.state.value.pinSession)
        assertEquals(SimklAuthError.PIN_INVALIDATED, harness.storage.state.value.error)
    }

    @Test
    fun `expired pin stops without a network request`() = runTest {
        val harness = Harness()
        harness.now = 20_000L
        harness.storage.savePinSession(session(expiresAt = 10_000L))

        assertEquals(SimklPinPollResult.Expired, harness.repository.pollPin())
        assertTrue(harness.engine.requests.isEmpty())
        assertEquals(SimklAuthError.PIN_EXPIRED, harness.storage.state.value.error)
    }

    @Test
    fun `missing client id fails before network access`() {
        val harness = Harness(configuration = SimklApiConfiguration("", "nuvio", "1.0"))

        val error = assertThrows(SimklAuthException::class.java) {
            kotlinx.coroutines.runBlocking { harness.repository.startPinAuth() }
        }

        assertEquals(SimklAuthError.MISSING_CLIENT_ID, error.error)
        assertTrue(harness.engine.requests.isEmpty())
    }

    @Test
    fun `disconnect removes identity pin and access token`() {
        val harness = Harness()
        harness.storage.completePinAuthorization("token", harness.storage.currentScope())
        harness.storage.saveIdentity("viewer", 42)
        harness.storage.savePinSession(session())

        harness.repository.disconnect()

        assertNull(harness.storage.accessToken())
        assertEquals(SimklAuthState(), harness.storage.state.value)
    }

    @Test
    fun `approved pin is discarded when the active profile changes during polling`() = runTest {
        val harness = Harness(response(200, """{"result":"OK","access_token":"profile-one-token"}"""))
        harness.storage.savePinSession(session())
        harness.engine.beforeResponse = { harness.storage.switchProfile(2) }

        assertEquals(SimklPinPollResult.Invalidated, harness.repository.pollPin())
        assertNull(harness.storage.accessToken())

        harness.storage.switchProfile(1)

        assertNull(harness.storage.accessToken())
        assertFalse(harness.storage.state.value.isAuthenticated)
    }

    @Test
    fun `delayed unauthorized response cannot disconnect the newly active profile`() {
        val harness = Harness(response(401))
        harness.storage.completePinAuthorization("profile-one-token", harness.storage.currentScope())
        harness.storage.switchProfile(2)
        harness.storage.completePinAuthorization("profile-two-token", harness.storage.currentScope())
        harness.storage.switchProfile(1)
        harness.engine.beforeResponse = { harness.storage.switchProfile(2) }

        assertThrows(SimklApiException::class.java) {
            kotlinx.coroutines.runBlocking { harness.repository.refreshUserSettings() }
        }

        assertEquals("profile-two-token", harness.storage.accessToken())
        assertTrue(harness.storage.state.value.isAuthenticated)
        assertNull(harness.storage.state.value.error)
    }

    private class Harness(
        vararg responses: SimklRawHttpResponse,
        configuration: SimklApiConfiguration = SimklApiConfiguration("client-id", "nuvio", "1.0")
    ) {
        var now = 0L
        val engine = RecordingEngine(*responses)
        val storage = FakeStorage()
        val client = SimklApiClient(
            engine = engine,
            configuration = configuration,
            authorization = storage::authorization,
            onUnauthorized = { authorization ->
                storage.clearAuth(
                    error = SimklAuthError.AUTHORIZATION_REVOKED,
                    scope = authorization.scope,
                    expectedAccessToken = authorization.accessToken
                )
            },
            nowEpochMs = { now },
            sleep = { now += it },
            retryJitterMs = { 0L }
        )
        val repository = SimklAuthRepository(client, configuration, storage) { now }
    }

    private class FakeStorage : SimklAuthStorage {
        private val profiles = mutableMapOf(1 to StoredProfile())
        private val mutableState = MutableStateFlow(SimklAuthState())
        private var activeProfileId = 1
        private var generation = 0L
        override val state: StateFlow<SimklAuthState> = mutableState

        override fun currentScope() = SimklAuthScope(activeProfileId, generation)

        override fun isCurrent(scope: SimklAuthScope): Boolean = scope == currentScope()

        override fun authorization(): SimklAuthorization? {
            val token = currentProfile().token ?: return null
            return SimklAuthorization(currentScope(), token)
        }

        override fun savePinSession(session: SimklPinSession, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.state = profile.state.copy(pinSession = session, error = null)
            }

        override fun clearPinSession(error: SimklAuthError?, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.state = profile.state.copy(pinSession = null, error = error)
            }

        override fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.token = token
                profile.state = profile.state.copy(
                    isAuthenticated = true,
                    pinSession = null,
                    error = null
                )
            }

        override fun saveIdentity(
            username: String?,
            accountId: Long?,
            settingsActivityWatermark: String?,
            scope: SimklAuthScope
        ): Boolean = mutate(scope) { profile ->
            profile.state = profile.state.copy(
                username = username,
                accountId = accountId,
                hasFetchedUserSettings = true,
                settingsActivityWatermark = settingsActivityWatermark
                    ?: profile.state.settingsActivityWatermark
            )
        }

        override fun recordSettingsActivityWatermark(
            watermark: String,
            scope: SimklAuthScope
        ): Boolean = mutate(scope) { profile ->
            profile.state = profile.state.copy(settingsActivityWatermark = watermark)
        }

        override fun clearAuth(
            error: SimklAuthError?,
            scope: SimklAuthScope,
            expectedAccessToken: String?
        ): Boolean {
            if (!isCurrent(scope)) return false
            val profile = currentProfile()
            if (expectedAccessToken != null && profile.token != expectedAccessToken) return false
            profile.token = null
            profile.state = SimklAuthState(error = error)
            generation += 1L
            publish()
            return true
        }

        override fun removeProfile(profileId: Int) {
            profiles.remove(profileId)
            if (profileId == activeProfileId) {
                generation += 1L
                profiles[profileId] = StoredProfile()
                publish()
            }
        }

        override fun clearAllProfiles() {
            profiles.clear()
            generation += 1L
            profiles[activeProfileId] = StoredProfile()
            publish()
        }

        fun switchProfile(profileId: Int) {
            if (profileId != activeProfileId) {
                activeProfileId = profileId
                generation += 1L
            }
            profiles.getOrPut(profileId, ::StoredProfile)
            publish()
        }

        private fun currentProfile(): StoredProfile = profiles.getOrPut(activeProfileId, ::StoredProfile)

        private fun mutate(scope: SimklAuthScope, block: (StoredProfile) -> Unit): Boolean {
            if (!isCurrent(scope)) return false
            block(currentProfile())
            publish()
            return true
        }

        private fun publish() {
            mutableState.value = currentProfile().state
        }

        private data class StoredProfile(
            var token: String? = null,
            var state: SimklAuthState = SimklAuthState()
        )
    }

    private class RecordingEngine(vararg responses: SimklRawHttpResponse) : SimklHttpEngine {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        var beforeResponse: ((Int) -> Unit)? = null

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String
        ): SimklRawHttpResponse {
            requests += Request(method, url, headers, body)
            beforeResponse?.invoke(requests.size)
            return responses.removeFirst()
        }
    }

    private data class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun session(expiresAt: Long = 100_000L) = SimklPinSession(
        userCode = "ABCDE",
        verificationUri = "https://simkl.com/pin",
        expiresAtEpochMs = expiresAt,
        intervalSeconds = 5
    )

    private fun response(status: Int, body: String = "") = SimklRawHttpResponse(status, body)
}
