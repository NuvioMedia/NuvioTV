package com.nuvio.tv.ui.screens.player.autosync

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSubtitleTimelineLoaderTest {
    @Test
    fun concurrentIndexLoadsShareOneDownload(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeadersDelay(300L, TimeUnit.MILLISECONDS),
            )
            val url = server.url("/shared.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }

            withTimeout(3_000L) {
                assertNull(prefetch.await())
                assertNull(autoSync.await())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun joinedIndexLoadRestartsWhenOwningLoadIsCancelled(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val url = server.url("/handoff.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            assertNotNull(
                withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) },
            )
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            yield()

            server.enqueue(MockResponse().setResponseCode(404))
            withTimeout(1_000L) { prefetch.cancelAndJoin() }

            assertNull(withTimeout(3_000L) { autoSync.await() })
            assertEquals(2, server.requestCount)
        }
    }
}
