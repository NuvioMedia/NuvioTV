package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrHttpClientTest {
    @Test
    fun `daily quota blocks requests until the quota resets`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.reply(
            status = 429,
            body = """{"error":"QUOTA_EXCEEDED","message":"limit"}""",
            headers = mapOf("X-Quota-Reset" to "3600")
        )
        val request = WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities")

        val first = expectWeTrakrFailure<WeTrakrApiException> { harness.http.execute(request) }
        val second = expectWeTrakrFailure<WeTrakrApiException> { harness.http.execute(request) }

        assertEquals("QUOTA_EXCEEDED", first.code)
        assertEquals(harness.now + 3_600_000L, first.retryAtEpochMs)
        assertEquals(1, harness.engine.requests.size)
        assertEquals(first.retryAtEpochMs, second.retryAtEpochMs)
    }

    @Test
    fun `minute window uses the rate limit reset header`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.reply(status = 429, body = """{"message":"Too many requests. Please slow down."}""", headers = mapOf("RateLimit-Reset" to "20"))

        val failure = expectWeTrakrFailure<WeTrakrApiException> {
            harness.http.execute(WeTrakrHttpRequest(WeTrakrHttpMethod.GET, "/sync/last_activities"))
        }

        assertEquals("rate_limited", failure.code)
        assertEquals(harness.now + 20_000L, failure.retryAtEpochMs)
    }

    @Test
    fun `requests carry the app key version and bearer token`() {
        val engine = OkHttpWeTrakrEngine(OkHttpClient(), WeTrakrConfiguration("client-key", "1.2.3"))

        val request = engine.buildRequest(
            WeTrakrHttpRequest(
                WeTrakrHttpMethod.POST,
                "/scrobble/start",
                body = """{"progress":10}""",
                accessToken = "token"
            )
        )

        assertEquals("client-key", request.header("wetrakr-api-key"))
        assertEquals("1", request.header("wetrakr-api-version"))
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("NuvioTV/1.2.3", request.header("User-Agent"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/json"))
        assertEquals("https://api.wetrakr.com/scrobble/start", request.url.toString())
    }

    @Test
    fun `error code reads both error shapes`() {
        assertEquals("JOURNAL_EXPIRED", WeTrakrHttpResponse(400, """{"error":"JOURNAL_EXPIRED"}""").errorCode())
        assertEquals("INVALID_MEDIA_ID", WeTrakrHttpResponse(400, """{"success":false,"error":{"code":"INVALID_MEDIA_ID"}}""").errorCode())
        assertNull(WeTrakrHttpResponse(400, "not json").errorCode())
    }
}
