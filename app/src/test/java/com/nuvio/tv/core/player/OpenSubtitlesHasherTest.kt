package com.nuvio.tv.core.player

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenSubtitlesHasherTest {
    @Test fun validRangesProduceExpectedHash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Length", 131072))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(65536)))) }
            val result = OpenSubtitlesHasher.compute(server.url("/valid.mkv").toString(), emptyMap(), OkHttpClient())
            assertEquals("0000000000020000", result?.hash)
            assertEquals(131072L, result?.fileSize)
            server.takeRequest()
            assertEquals("bytes=0-65535", server.takeRequest().getHeader("Range"))
            assertEquals("bytes=65536-131071", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun ignoredRangeDoesNotProduceIncorrectHash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Length", 131072))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(65536))))
            assertNull(OpenSubtitlesHasher.compute(server.url("/ignored.mkv").toString(), emptyMap(), OkHttpClient()))
        }
    }

    @Test fun stalledServerIsBoundedByHashBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
            // Exclude first-use client/dispatcher initialization from the network budget assertion.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { }
            val started = System.nanoTime()
            val result = OpenSubtitlesHasher.compute(server.url("/slow.mkv").toString(), emptyMap(),
                client, timeoutMs = 200)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertNull(result)
            assertTrue("Hash probe took ${elapsed}ms", elapsed < 2000)
        }
    }
}
