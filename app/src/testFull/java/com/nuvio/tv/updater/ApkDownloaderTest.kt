package com.nuvio.tv.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApkDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private fun target() = File(folder.root, "update.apk")
    private val downloader = ApkDownloader(OkHttpClient.Builder().callTimeout(50, TimeUnit.MILLISECONDS).build())

    private class HttpFixture(private val response: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/update.apk"
        private var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "apk-http-test") {
            try {
                server.accept().use { accepted ->
                    socket = accepted
                    val reader = accepted.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    response(accepted)
                }
            } catch (_: java.io.IOException) { /* Test fixture shutdown/cancellation. */ }
        }
        override fun close() { socket?.close(); server.close(); worker.join(2000) }
    }

    @Test(timeout = 5000) fun slowTransferOutlivesSharedClientDeadlineAndPublishesOnlyCompleteFile() = runBlocking {
        HttpFixture { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabc".toByteArray()); flush()
                Thread.sleep(200)
                write("def".toByteArray()); flush()
            }
        }.use { server ->
            val file = target()
            val progress = mutableListOf<Long>()
            val result = downloader.download(server.url, file, 6) { bytes, total ->
                assertFalse("Final file must not expose partial bytes", file.exists())
                assertEquals(6L, total)
                progress += bytes
            }
            assertEquals("abcdef", result.getOrThrow().readText())
            assertEquals(6L, progress.last())
            assertEquals(listOf("update.apk"), folder.root.list()!!.toList())
        }
    }

    @Test(timeout = 5000) fun truncatedResponseIsNotPromotedAndPartIsRemoved() = runBlocking {
        HttpFixture { socket ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nabc".toByteArray())
        }.use { server ->
            assertTrue(downloader.download(server.url, target(), 10) { _, _ -> }.isFailure)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        }
    }

    @Test(timeout = 5000) fun chunkedResponseMustMatchReleaseAssetSize() = runBlocking {
        HttpFixture { socket ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray())
        }.use { server ->
            assertTrue(downloader.download(server.url, target(), 6) { _, _ -> }.isFailure)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        }
    }

    @Test(timeout = 5000) fun failedHttpPreservesExistingCompleteArtifact() = runBlocking {
        val file = target().apply { writeText("previous") }
        HttpFixture { socket ->
            socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }.use { server ->
            assertTrue(downloader.download(server.url, file) { _, _ -> }.isFailure)
            assertEquals("previous", file.readText())
        }
    }

    @Test(timeout = 5000) fun mismatchedHeaderAndMalformedUrlFailWithoutFiles() = runBlocking {
        assertTrue(downloader.download("not a url", target()) { _, _ -> }.isFailure)
        HttpFixture { socket ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray())
        }.use { server ->
            assertTrue(downloader.download(server.url, target(), 4) { _, _ -> }.isFailure)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        }
    }

    @Test(timeout = 5000) fun cancellationAfterPromotionBeforeCallerResumeDeletesArtifact() = runBlocking {
        HttpFixture { socket ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray())
        }.use { server ->
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
            val job = async(dispatcher) { downloader.download(server.url, target(), 3) { _, _ -> } }
            dispatcher.scheduler.runCurrent()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!target().exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The response must have reached atomic promotion", target().exists())
            // Caller continuation remains queued on the paused test dispatcher.
            job.cancel()
            dispatcher.scheduler.runCurrent()
            job.join()
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (folder.root.listFiles()!!.isNotEmpty() && System.nanoTime() < cleanupDeadline) Thread.sleep(10)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        }
    }

    @Test(timeout = 5000) fun cancelClosesActualStalledSocketAndRemovesPartialFile() = runBlocking {
        val sent = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        HttpFixture { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nx".toByteArray()); flush()
            }
            sent.countDown()
            if (socket.getInputStream().read() == -1) disconnected.countDown()
        }.use { server ->
            val job = async { downloader.download(server.url, target(), 100) { _, _ -> } }
            // Await without blocking this coroutine's event loop.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(sent.await(2, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue("Call.cancel must close the stalled HTTP socket promptly", disconnected.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (folder.root.listFiles()!!.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(folder.root.listFiles()!!.isEmpty())
        }
    }
}
