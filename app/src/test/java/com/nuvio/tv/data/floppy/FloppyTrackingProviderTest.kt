package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyTrackingProviderTest {
    @Test
    fun `the provider advertises playback and watched writes`() {
        val harness = Harness()

        assertEquals(TrackingProviderId.FLOPPY, harness.provider.descriptor.id)
        assertEquals(
            setOf(
                TrackingCapability.AUTHENTICATION,
                TrackingCapability.SCROBBLE,
                TrackingCapability.WATCHED_WRITE
            ),
            harness.provider.descriptor.capabilities
        )
    }

    @Test
    fun `an unconnected profile sends nothing`() = runTest {
        MockWebServer().use { server ->
            val harness = Harness()

            harness.provider.scrobbler.scrobble(TrackingScrobbleAction.START, event())

            assertFalse(harness.provider.isAuthenticated.value)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `connecting makes the registry dispatch playback to Floppy`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"detail":"accepted"}"""))
            val harness = Harness()
            val registry = TrackingProviderRegistry(listOf(harness.provider))
            assertTrue(registry.connectedScrobblers().isEmpty())

            harness.auth.save(FloppyCredentials(server.url("/").toString().trimEnd('/'), "flp_secret"))
            waitUntil { harness.provider.isAuthenticated.value }
            val scrobblers = registry.connectedScrobblers()
            assertEquals(listOf(TrackingProviderId.FLOPPY), scrobblers.map { it.providerId })
            scrobblers.single().scrobble(TrackingScrobbleAction.STOP, event(percent = 91.0))

            val sent = server.takeRequest()
            assertEquals("/api/v1/scrobble/", sent.path)
            val text = sent.body.readUtf8()
            assertTrue(text, text.contains(""""completed":true"""))
            assertTrue(text, text.contains(""""tmdb":"603""""))
        }
    }

    @Test
    fun `a refused scrobble throws so the coordinator can log it`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val harness = Harness()
            harness.auth.save(FloppyCredentials(server.url("/").toString().trimEnd('/'), "flp_secret"))

            val error = runCatching { harness.provider.scrobbler.scrobble(TrackingScrobbleAction.START, event()) }.exceptionOrNull()

            assertEquals(403, (error as FloppyApiException).status)
        }
    }

    @Test
    fun `an event Floppy cannot match is not sent`() = runTest {
        MockWebServer().use { server ->
            val harness = Harness()
            harness.auth.save(FloppyCredentials(server.url("/").toString().trimEnd('/'), "flp_secret"))
            val unmatched = TrackingScrobbleEvent(
                TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(mal = 5L)),
                50.0
            )

            harness.provider.scrobbler.scrobble(TrackingScrobbleAction.START, unmatched)

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `manual marks and unmarks use the scrobble endpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"detail":"accepted"}"""))
            server.enqueue(MockResponse().setBody("""{"detail":"accepted"}"""))
            val harness = Harness()
            harness.auth.save(FloppyCredentials(server.url("/").toString().trimEnd('/'), "flp_secret"))

            val mark = harness.history.addToHistory(
                1,
                listOf(TrackingHistoryItem(event().media, watchedAtEpochMs = 1_762_150_000_000L))
            )
            assertEquals(1, mark.attemptedCount)
            val markRequest = server.takeRequest()
            assertEquals("/api/v1/scrobble/", markRequest.path)
            assertTrue(markRequest.body.readUtf8().contains(""""manual":true"""))

            val unmark = harness.history.removeFromHistory(1, listOf(event().media))
            assertEquals(1, unmark.attemptedCount)
            val unmarkRequest = server.takeRequest()
            assertEquals("/api/v1/scrobble/", unmarkRequest.path)
            assertTrue(unmarkRequest.body.readUtf8().contains(""""action":"unwatch""""))
        }
    }

    private fun event(percent: Double = 0.0) = TrackingScrobbleEvent(
        TrackingMediaReference(TrackingMediaKind.MOVIE, title = "The Matrix", ids = TrackingExternalIds(tmdb = 603L)),
        percent
    )

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
    }

    private class Harness {
        val auth = FloppyAuthStore(MemoryPersistence())
        val api = FloppyApiClient(OkHttpClient(), "9.9.9")
        val provider = FloppyTrackingProvider(auth, FloppyTrackingScrobbler(auth, api))
        val history = FloppyTrackingHistoryWriter(auth, api)
    }
}
