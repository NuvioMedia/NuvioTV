package com.nuvio.tv.ui.screens.player

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TimelineNetworkingTest {
    @Test fun cancellationOwnsTheResponseBodyAfterDispatcherCompletionAndKeepsOtherSessionsAlive() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed-frame").setBodyDelay(3, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("other-session"))
            val firstLifetime = TimelineCallCancellation()
            val otherLifetime = TimelineCallCancellation()
            val url = server.url("/movie").toString()
            val firstClient = PlayerPlaybackNetworking.createTimelineHttpClient(url, emptyMap(), firstLifetime)
            val otherClient = PlayerPlaybackNetworking.createTimelineHttpClient(url, emptyMap(), otherLifetime)
            val first = firstClient.newCall(Request.Builder().url(url).build())
            val other = otherClient.newCall(Request.Builder().url(url).build())
            first.execute().use {
                other.execute().use { otherResponse ->
                    assertEquals(0, firstClient.dispatcher.runningCallsCount())
                    assertFalse(first.isCanceled())
                    firstLifetime.cancel()
                    firstLifetime.cancel()
                    assertTrue(first.isCanceled())
                    assertFalse(other.isCanceled())
                    assertEquals("other-session", otherResponse.body!!.string())
                    val late = firstClient.newCall(Request.Builder().url(url).build())
                    val failure = runCatching { late.execute().close() }.exceptionOrNull()
                    assertTrue(failure is java.io.IOException)
                    assertTrue(late.isCanceled())
                    assertEquals(2, server.requestCount)
                }
            }
        }
    }
    @Test fun sameOriginRetainsExactHeadersAndGeneratedSeekRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/frame"))
            server.enqueue(MockResponse().setBody("frame"))
            val headers = mapOf("Authorization" to "Bearer local-fixture", "X-Addon" to "alpha,beta\\tail", "Range" to "addon-supplied")
            val client = PlayerPlaybackNetworking.createTimelineHttpClient(server.url("/movie").toString(), headers)
            client.newCall(Request.Builder().url(server.url("/movie")).header("Range", "bytes=123-456").build()).execute().use {
                assertEquals("frame", it.body!!.string())
            }
            repeat(2) {
                val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("Bearer local-fixture", sent.getHeader("Authorization"))
                assertEquals("alpha,beta\\tail", sent.getHeader("X-Addon"))
                assertEquals("bytes=123-456", sent.getHeader("Range"))
            }
        }
    }
    @Test fun redirectToAnotherPortCannotReceiveAddonCredentialsOrCookies() {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/frame")))
            destination.enqueue(MockResponse().setBody("frame"))
            val headers = mapOf("Authorization" to "Bearer local-fixture", "Cookie" to "session=fixture", "X-Addon-Key" to "private-fixture")
            val client = PlayerPlaybackNetworking.createTimelineHttpClient(server.url("/movie").toString(), headers)
            client.newCall(Request.Builder().url(server.url("/movie")).headers(okhttp3.Headers.headersOf(
                "Authorization", "Bearer local-fixture", "Cookie", "session=fixture", "X-Addon-Key", "private-fixture", "Range", "bytes=500-"
            )).build()).execute().use { assertEquals("frame", it.body!!.string()) }
            val sent = destination.takeRequest(3, TimeUnit.SECONDS)!!
            listOf("Authorization", "Cookie", "X-Addon-Key").forEach { assertNull(sent.getHeader(it)) }
            assertEquals("bytes=500-", sent.getHeader("Range"))
        } }
    }
    @Test fun directForeignSegmentIsAlsoStrippedAndReturningToOriginRestoresAuth() {
        MockWebServer().use { source -> MockWebServer().use { segment ->
            segment.enqueue(MockResponse().setResponseCode(302).addHeader("Location", source.url("/final")))
            source.enqueue(MockResponse().setBody("ok"))
            val client = PlayerPlaybackNetworking.createTimelineHttpClient(source.url("/manifest").toString(), mapOf("X-Key" to "local-fixture"))
            client.newCall(Request.Builder().url(segment.url("/segment")).header("X-Key", "local-fixture").build()).execute().close()
            assertNull(segment.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("X-Key"))
            assertEquals("local-fixture", source.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("X-Key"))
        } }
    }
    @Test fun strictTlsClientHasNoTrustAllFallbackOrSharedCookieJar() {
        val client = PlayerPlaybackNetworking.createTimelineHttpClient("https://example.invalid/movie", emptyMap())
        assertTrue(client.interceptors.isEmpty())
        assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(6000, client.callTimeoutMillis)
        assertSame(okhttp3.OkHttpClient().hostnameVerifier, client.hostnameVerifier)
    }
    @Test fun malformedSourcesAndHeadersFailBeforeNetworkWithoutEchoingSecrets() {
        val cases = listOf(
            "https://user:password@example.invalid/" to emptyMap(),
            "not-a-url" to emptyMap(),
            "https://example.invalid/" to mapOf("X\rKey" to "private-secret"),
            "https://example.invalid/" to mapOf("Authorization" to "private-secret\u0000"),
            "https://example.invalid/" to (0..32).associate { "X-$it" to "local" },
        )
        cases.forEach { (url, headers) ->
            val error = runCatching { PlayerPlaybackNetworking.createTimelineHttpClient(url, headers) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains("private-secret"))
            assertFalse(error.message.orEmpty().contains("password"))
        }
    }
}
