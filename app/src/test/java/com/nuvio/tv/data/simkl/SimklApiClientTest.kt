package com.nuvio.tv.data.simkl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklApiClientTest {
    @Test
    fun `response classification retries only transient statuses`() {
        assertEquals(SimklResponseAction.SUCCESS, classifySimklResponse(200))
        assertEquals(SimklResponseAction.REAUTHENTICATE, classifySimklResponse(401))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(429))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(500))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(502))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(503))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(400))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(409))
        assertEquals(SimklResponseAction.SOFT_SUCCESS, classifySimklResponse(409, true))
    }

    @Test
    fun `retry schedule is exponential bounded and honors retry after`() {
        assertEquals(1_000L, retryDelayMs(0, null, 0))
        assertEquals(2_000L, retryDelayMs(1, null, 0))
        assertEquals(4_000L, retryDelayMs(2, null, 0))
        assertEquals(8_000L, retryDelayMs(3, null, 0))
        assertEquals(16_000L, retryDelayMs(4, null, 0))
        assertEquals(30_250L, retryDelayMs(0, "30", 250))
        assertEquals(60_000L, retryDelayMs(0, "120", 1_000))
    }

    @Test
    fun `required metadata and headers cannot be overridden`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = Harness(engine)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/activities",
                query = mapOf(
                    "client_id" to "spoofed",
                    "app-name" to "spoofed",
                    "app-version" to "spoofed"
                )
            )
        )

        val request = engine.requests.single()
        assertFalse("spoofed" in request.url)
        assertTrue("client_id=client-id" in request.url)
        assertTrue("app-name=nuvio" in request.url)
        assertTrue("app-version=1.0" in request.url)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("NuvioTV/1.0", request.headers["User-Agent"])
    }

    @Test
    fun `bodyless post has json content type and empty payload`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = Harness(engine)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/users/settings"))

        val request = engine.requests.single()
        assertEquals("", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `sync diagnostics omit body and query values`() {
        val body = """{"movies":[{"movie":{"title":"Private title"}}]}"""
        val request = SimklApiRequest(
            method = SimklHttpMethod.GET,
            path = "/sync/all-items/",
            query = mapOf("date_from" to "2026-07-22T08:48:07Z")
        )

        val message = simklSyncResponseLogMessage(
            request,
            response(200, body, mapOf("Content-Type" to "application/json")),
            1
        )

        assertTrue("queryKeys=[date_from]" in message)
        assertTrue("bodyChars=${body.length}" in message)
        assertFalse("Private title" in message)
        assertFalse("2026-07-22T08:48:07Z" in message)
    }

    @Test
    fun `authenticated calls are serialized at method rate limits`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200), response(200), response(200))
        val harness = Harness(engine)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/one"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/two"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/three"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/four"))

        assertEquals(listOf(100L, 1_000L), harness.sleeps)
        assertEquals(listOf(0L, 100L, 100L, 1_100L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `post cooldown starts after the previous response completes`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200))
        val harness = Harness(engine, responseDurationMs = 400L)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/oauth/token",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER
            )
        )
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/users/settings"))

        assertEquals(listOf(1_000L), harness.sleeps)
        assertEquals(listOf(0L, 1_400L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `transient errors retry while deterministic errors do not`() = runBlocking {
        val transientEngine = RecordingEngine(response(503), response(502), response(200))
        val transientHarness = Harness(transientEngine)

        transientHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/retry"))

        assertEquals(listOf(1_000L, 2_000L), transientHarness.sleeps)
        assertEquals(3, transientEngine.requests.size)

        val invalidEngine = RecordingEngine(response(400, """{"error":"wrong_parameter"}"""))
        val invalidHarness = Harness(invalidEngine)
        val error = assertThrows(SimklApiException::class.java) {
            runBlocking { invalidHarness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/bad")) }
        }
        assertEquals("wrong_parameter", error.errorCode)
        assertEquals(1, invalidEngine.requests.size)
    }

    @Test
    fun `transient errors stop after five attempts`() {
        val engine = RecordingEngine(*Array(6) { response(503) })
        val harness = Harness(engine)

        assertThrows(SimklApiException::class.java) {
            runBlocking { harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/unavailable")) }
        }

        assertEquals(5, engine.requests.size)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), harness.sleeps)
    }

    @Test
    fun `sync write lock retries once and scrobble never replays`() = runBlocking {
        val lockedEngine = RecordingEngine(
            response(400, """{"error":"rate_limit"}"""),
            response(200)
        )
        val lockedHarness = Harness(lockedEngine)

        lockedHarness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )

        assertEquals(2, lockedEngine.requests.size)
        assertEquals(listOf(3_000L), lockedHarness.sleeps)

        val scrobbleEngine = RecordingEngine(response(503), response(200))
        val scrobbleHarness = Harness(scrobbleEngine)
        assertThrows(SimklApiException::class.java) {
            runBlocking {
                scrobbleHarness.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = "/scrobble/start",
                        retryPolicy = SimklRetryPolicy.NEVER
                    )
                )
            }
        }
        assertEquals(1, scrobbleEngine.requests.size)
    }

    @Test
    fun `unauthenticated requests preserve sessions and duplicate stop is soft success`() = runBlocking {
        val unauthorizedEngine = RecordingEngine(response(401))
        val unauthorizedHarness = Harness(unauthorizedEngine)
        assertThrows(SimklApiException::class.java) {
            runBlocking {
                unauthorizedHarness.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.GET,
                        path = "/oauth/pin",
                        requiresAuthentication = false
                    )
                )
            }
        }
        assertFalse(unauthorizedHarness.wasUnauthorized)

        val conflictEngine = RecordingEngine(response(409))
        val conflictHarness = Harness(conflictEngine)
        val result = conflictHarness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/stop",
                retryPolicy = SimklRetryPolicy.NEVER,
                scrobbleStopConflictIsSuccess = true
            )
        )
        assertTrue(result.isSoftSuccess)
    }

    @Test
    fun `expired access token is refreshed once and the request replayed`() = runBlocking {
        val engine = RecordingEngine(
            response(401, """{"error":"user_token_failed"}"""),
            response(200, REFRESH_RESPONSE),
            response(200, "{}")
        )
        val harness = RefreshHarness(engine)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities"))

        assertEquals(3, engine.requests.size)
        assertEquals("Bearer old-access", engine.requests[0].headers["Authorization"])
        val refresh = engine.requests[1]
        assertTrue(refresh.url.startsWith("https://api.simkl.com/oauth2/token?"))
        assertEquals("application/x-www-form-urlencoded", refresh.headers["Content-Type"])
        assertFalse("Authorization" in refresh.headers)
        assertEquals("grant_type=refresh_token&client_id=client-id&refresh_token=refresh", refresh.body)
        assertEquals("Bearer new-access", engine.requests[2].headers["Authorization"])
        assertEquals("new-access", harness.current?.accessToken)
        assertFalse(harness.wasUnauthorized)
    }

    @Test
    fun `scrobbles that never retry still replay after a refresh`() = runBlocking {
        val engine = RecordingEngine(
            response(401),
            response(200, REFRESH_RESPONSE),
            response(201, "{}")
        )
        val harness = RefreshHarness(engine)

        val result = harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/start",
                body = "{}",
                retryPolicy = SimklRetryPolicy.NEVER
            )
        )

        assertEquals(201, result.status)
        assertEquals("Bearer new-access", engine.requests.last().headers["Authorization"])
    }

    @Test
    fun `token near expiry is refreshed before the request`() = runBlocking {
        val engine = RecordingEngine(response(200, REFRESH_RESPONSE), response(200, "{}"))
        val harness = RefreshHarness(engine, expiresAt = 60L * 60L * 1_000L)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities"))

        assertTrue(engine.requests[0].url.startsWith("https://api.simkl.com/oauth2/token?"))
        assertEquals("Bearer new-access", engine.requests[1].headers["Authorization"])
    }

    @Test
    fun `still valid token survives a failed proactive refresh`() = runBlocking {
        val engine = RecordingEngine(response(500), response(200, "{}"))
        val harness = RefreshHarness(engine, expiresAt = 60L * 60L * 1_000L)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities"))

        assertEquals("Bearer old-access", engine.requests[1].headers["Authorization"])
        assertEquals("old-access", harness.current?.accessToken)
        assertFalse(harness.wasUnauthorized)
    }

    @Test
    fun `rejected refresh signs the user out`() {
        val engine = RecordingEngine(
            response(401),
            response(400, """{"error":"invalid_grant"}""")
        )
        val harness = RefreshHarness(engine)

        val error = assertThrows(SimklApiException::class.java) {
            runBlocking { harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities")) }
        }

        assertEquals(401, error.status)
        assertTrue(harness.wasUnauthorized)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `refresh result is dropped when the profile changed meanwhile`() {
        val engine = RecordingEngine(response(401), response(200, REFRESH_RESPONSE))
        val harness = RefreshHarness(engine, acceptRefresh = false)

        val error = assertThrows(SimklApiException::class.java) {
            runBlocking { harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/sync/activities")) }
        }

        assertEquals("auth_scope_changed", error.errorCode)
        assertFalse(harness.wasUnauthorized)
    }

    @Test
    fun `token response parsing keeps the refresh token when it is omitted`() {
        val grant = SimklTokenResponse(
            accessToken = "access",
            tokenType = "bearer",
            expiresIn = 604_800L,
            scope = "media:read media:write"
        ).toGrant(nowEpochMs = 1_000L, fallbackRefreshToken = "refresh")

        assertEquals("refresh", grant?.refreshToken)
        assertEquals(604_801_000L, grant?.accessTokenExpiresAtEpochMs)
        assertTrue(grant?.canWrite == true)
    }

    private class RefreshHarness(
        engine: RecordingEngine,
        expiresAt: Long = 7L * 24L * 60L * 60L * 1_000L,
        acceptRefresh: Boolean = true
    ) {
        var now = 0L
        var wasUnauthorized = false
        var current: SimklAuthorization? = testSimklAuthorization(token = "old-access").copy(
            refreshToken = "refresh",
            accessTokenExpiresAtEpochMs = expiresAt
        )
        val client = SimklApiClient(
            engine = engine.also { it.now = { now } },
            configuration = SimklApiConfiguration("client-id", "nuvio", "1.0"),
            authorization = { current },
            onUnauthorized = { wasUnauthorized = true },
            onTokensRefreshed = { previous, grant ->
                if (!acceptRefresh) {
                    null
                } else {
                    previous.copy(
                        accessToken = grant.accessToken,
                        refreshToken = grant.refreshToken,
                        accessTokenExpiresAtEpochMs = grant.accessTokenExpiresAtEpochMs
                    ).also { current = it }
                }
            },
            nowEpochMs = { now },
            sleep = { delay -> now += delay },
            retryJitterMs = { 0L }
        )
    }

    private class Harness(
        engine: RecordingEngine,
        responseDurationMs: Long = 0L
    ) {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        var wasUnauthorized = false
        val client = SimklApiClient(
            engine = engine.also {
                it.now = { now }
                it.onResponse = { now += responseDurationMs }
            },
            configuration = SimklApiConfiguration("client-id", "nuvio", "1.0"),
            authorization = { testSimklAuthorization() },
            onUnauthorized = { wasUnauthorized = true },
            nowEpochMs = { now },
            sleep = { delay -> sleeps += delay; now += delay },
            retryJitterMs = { 0L }
        )
    }

    private class RecordingEngine(vararg responses: SimklRawHttpResponse) : SimklHttpEngine {
        val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<RecordedRequest>()
        var now: () -> Long = { 0L }
        var onResponse: () -> Unit = {}

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String
        ): SimklRawHttpResponse {
            requests += RecordedRequest(method, url, headers, body, now())
            return responses.removeFirst().also { onResponse() }
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val atEpochMs: Long
    )

    private fun response(
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap()
    ) = SimklRawHttpResponse(status, body, headers)

    private companion object {
        const val REFRESH_RESPONSE =
            """{"access_token":"new-access","token_type":"Bearer","expires_in":604800,"refresh_token":"refresh","scope":"media:read media:write"}"""
    }
}
