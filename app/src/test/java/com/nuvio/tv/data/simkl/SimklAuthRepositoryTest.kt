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
    fun `device authorization requests write scope and keeps the device code out of state`() = runTest {
        val harness = Harness(
            response(
                200,
                """{"device_code":"secret-device-code","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}"""
            )
        )

        val session = harness.repository.startPinAuth()

        assertEquals("BDWP-HQPK", session.userCode)
        assertEquals("https://simkl.com/pin", session.verificationUri)
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", session.qrUri)
        assertEquals(900_000L, session.expiresAtEpochMs)
        assertEquals(5, session.intervalSeconds)
        assertEquals("secret-device-code", harness.storage.pinDeviceCode())
        assertFalse("secret-device-code" in harness.storage.state.value.toString())
        assertEquals(SimklConnectionMode.AWAITING_APPROVAL, harness.storage.state.value.mode)
        val request = harness.engine.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.startsWith("https://api.simkl.com/oauth2/device?"))
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals("client_id=client-id&scope=media%3Aread+media%3Awrite", request.body)
        assertFalse("Authorization" in request.headers)
    }

    @Test
    fun `qr falls back to the plain verification page`() {
        val session = session().copy(verificationUriComplete = null)

        assertEquals("https://simkl.com/pin", session.qrUri)
    }

    @Test
    fun `v1 client id is reported as an invalid client`() = runTest {
        val harness = Harness(
            response(401, """{"error":"invalid_client","error_description":"This client_id is not enabled for OAuth 2.0"}""")
        )

        val error = runCatching { harness.repository.startPinAuth() }.exceptionOrNull()

        assertEquals(SimklAuthError.INVALID_CLIENT, (error as SimklAuthException).error)
    }

    @Test
    fun `pending poll keeps the original pin session`() = runTest {
        val harness = Harness(response(400, """{"error":"authorization_pending"}"""))
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.Pending, harness.repository.pollPin())
        assertEquals("ABCD-EFGH", harness.storage.state.value.pinSession?.userCode)
        assertFalse(harness.storage.state.value.isAuthenticated)
        val request = harness.engine.requests.single()
        assertTrue(request.url.startsWith("https://api.simkl.com/oauth2/token?"))
        assertEquals(
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&client_id=client-id&device_code=device-code",
            request.body
        )
    }

    @Test
    fun `slow down adds five seconds to the poll interval`() = runTest {
        val harness = Harness(response(400, """{"error":"slow_down"}"""))
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.SlowDown, harness.repository.pollPin())
        assertEquals(10, harness.storage.state.value.pinSession?.intervalSeconds)
    }

    @Test
    fun `transient poll failures keep polling`() = runTest {
        val harness = Harness(response(503))
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.Pending, harness.repository.pollPin())
        assertEquals(1, harness.engine.requests.size)
        assertEquals("ABCD-EFGH", harness.storage.state.value.pinSession?.userCode)
    }

    @Test
    fun `expired device code stops polling`() = runTest {
        val harness = Harness(response(400, """{"error":"expired_token"}"""))
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.Expired, harness.repository.pollPin())
        assertNull(harness.storage.state.value.pinSession)
        assertNull(harness.storage.pinDeviceCode())
        assertEquals(SimklAuthError.PIN_EXPIRED, harness.storage.state.value.error)
    }

    @Test
    fun `approved device stores both tokens clears code and loads identity`() = runTest {
        val harness = Harness(
            response(200, TOKEN_RESPONSE),
            response(200, """{"user":{"name":"viewer"},"account":{"id":42}}""")
        )
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.Authorized, harness.repository.pollPin())

        val authorization = harness.storage.authorization()
        assertEquals("simkl_at_access", authorization?.accessToken)
        assertEquals("simkl_rt_refresh", authorization?.refreshToken)
        assertEquals(604_800_000L, authorization?.accessTokenExpiresAtEpochMs)
        assertNull(harness.storage.state.value.pinSession)
        assertNull(harness.storage.pinDeviceCode())
        assertTrue(harness.storage.state.value.isAuthenticated)
        assertEquals("viewer", harness.storage.state.value.username)
        assertEquals(42L, harness.storage.state.value.accountId)
        assertEquals("Bearer simkl_at_access", harness.engine.requests.last().headers["Authorization"])
    }

    @Test
    fun `approved device remains connected when identity fetch fails`() = runTest {
        val harness = Harness(
            response(200, TOKEN_RESPONSE),
            response(400, """{"error":"settings_unavailable"}""")
        )
        harness.storage.savePinSession(session(), "device-code")

        assertEquals(SimklPinPollResult.Authorized, harness.repository.pollPin())
        assertTrue(harness.storage.state.value.isAuthenticated)
        assertEquals("simkl_at_access", harness.storage.accessToken())
        assertNull(harness.storage.state.value.username)
    }

    @Test
    fun `connected account can recover a missing normalized identity`() = runTest {
        val harness = Harness(
            response(200, """{"user":{"name":"  Actual Viewer  "},"account":{"id":42}}""")
        )
        harness.storage.completeDeviceAuthorization(grant(), harness.storage.currentScope())

        assertEquals("Actual Viewer", harness.repository.refreshUserSettings())
        assertEquals("Actual Viewer", harness.storage.state.value.username)
        assertEquals(42L, harness.storage.state.value.accountId)
        assertTrue(harness.storage.state.value.hasFetchedUserSettings)
    }

    @Test
    fun `session without a device code is invalidated without a network request`() = runTest {
        val harness = Harness()
        harness.storage.savePinSession(session(), "device-code")
        harness.storage.forgetDeviceCode()

        assertEquals(SimklPinPollResult.Invalidated, harness.repository.pollPin())
        assertTrue(harness.engine.requests.isEmpty())
        assertNull(harness.storage.state.value.pinSession)
        assertEquals(SimklAuthError.PIN_INVALIDATED, harness.storage.state.value.error)
    }

    @Test
    fun `expired pin stops without a network request`() = runTest {
        val harness = Harness()
        harness.now = 20_000L
        harness.storage.savePinSession(session(expiresAt = 10_000L), "device-code")

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
    fun `disconnect removes identity pin and tokens then revokes the grant`() = runTest {
        val harness = Harness(response(200))
        harness.storage.completeDeviceAuthorization(grant(), harness.storage.currentScope())
        harness.storage.saveIdentity("viewer", 42)
        harness.storage.savePinSession(session(), "device-code")

        harness.repository.disconnect()

        assertNull(harness.storage.accessToken())
        assertEquals(SimklAuthState(), harness.storage.state.value)
        val request = harness.engine.requests.single()
        assertTrue(request.url.startsWith("https://api.simkl.com/oauth2/revoke?"))
        assertEquals("client_id=client-id&token=simkl_rt_refresh", request.body)
        assertFalse("Authorization" in request.headers)
    }

    @Test
    fun `disconnect stays signed out when revoke fails`() = runTest {
        val harness = Harness(response(400, """{"error":"invalid_request"}"""))
        harness.storage.completeDeviceAuthorization(grant(), harness.storage.currentScope())

        harness.repository.disconnect()

        assertNull(harness.storage.accessToken())
        assertFalse(harness.storage.state.value.isAuthenticated)
    }

    @Test
    fun `approved device is discarded when the active profile changes during polling`() = runTest {
        val harness = Harness(response(200, TOKEN_RESPONSE))
        harness.storage.savePinSession(session(), "device-code")
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
        harness.storage.completeDeviceAuthorization(grant("profile-one-token"), harness.storage.currentScope())
        harness.storage.switchProfile(2)
        harness.storage.completeDeviceAuthorization(grant("profile-two-token"), harness.storage.currentScope())
        harness.storage.switchProfile(1)
        harness.storage.dropRefreshToken()
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
            onTokensRefreshed = storage::saveRefreshedTokens,
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
            val profile = currentProfile()
            val token = profile.token ?: return null
            return SimklAuthorization(currentScope(), token, profile.refreshToken, profile.expiresAt)
        }

        override fun savePinSession(
            session: SimklPinSession,
            deviceCode: String,
            scope: SimklAuthScope
        ): Boolean = mutate(scope) { profile ->
            profile.deviceCode = deviceCode
            profile.state = profile.state.copy(pinSession = session, error = null)
        }

        override fun updatePinInterval(intervalSeconds: Int, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.state = profile.state.copy(
                    pinSession = profile.state.pinSession?.copy(intervalSeconds = intervalSeconds)
                )
            }

        override fun pinDeviceCode(): String? = currentProfile().deviceCode

        override fun clearPinSession(error: SimklAuthError?, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.deviceCode = null
                profile.state = profile.state.copy(pinSession = null, error = error)
            }

        override fun completeDeviceAuthorization(grant: SimklTokenGrant, scope: SimklAuthScope): Boolean =
            mutate(scope) { profile ->
                profile.token = grant.accessToken
                profile.refreshToken = grant.refreshToken
                profile.expiresAt = grant.accessTokenExpiresAtEpochMs
                profile.deviceCode = null
                profile.state = profile.state.copy(
                    isAuthenticated = true,
                    pinSession = null,
                    error = null
                )
            }

        override fun saveRefreshedTokens(
            previous: SimklAuthorization,
            grant: SimklTokenGrant
        ): SimklAuthorization? {
            if (!isCurrent(previous.scope) || currentProfile().refreshToken != previous.refreshToken) return null
            val profile = currentProfile()
            profile.token = grant.accessToken
            profile.refreshToken = grant.refreshToken
            profile.expiresAt = grant.accessTokenExpiresAtEpochMs
            publish()
            return authorization()
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
            profile.refreshToken = null
            profile.expiresAt = null
            profile.deviceCode = null
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

        fun forgetDeviceCode() {
            currentProfile().deviceCode = null
        }

        fun dropRefreshToken() {
            currentProfile().refreshToken = null
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
            var refreshToken: String? = null,
            var expiresAt: Long? = null,
            var deviceCode: String? = null,
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
        userCode = "ABCD-EFGH",
        verificationUri = "https://simkl.com/pin",
        expiresAtEpochMs = expiresAt,
        intervalSeconds = 5,
        verificationUriComplete = "https://simkl.com/pin?user_code=ABCD-EFGH"
    )

    private fun grant(accessToken: String = "simkl_at_access") = SimklTokenGrant(
        accessToken = accessToken,
        refreshToken = "simkl_rt_refresh",
        accessTokenExpiresAtEpochMs = 604_800_000L,
        scope = SIMKL_REQUESTED_SCOPE
    )

    private fun response(status: Int, body: String = "") = SimklRawHttpResponse(status, body)

    private companion object {
        const val TOKEN_RESPONSE =
            """{"access_token":"simkl_at_access","token_type":"Bearer","expires_in":604800,"refresh_token":"simkl_rt_refresh","scope":"media:read media:write"}"""
    }
}
