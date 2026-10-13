package com.nuvio.tv.ui.screens.player

import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.player.timeline.TelumiaFrameExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TimelineFramesTvTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!condition()) { check(android.os.SystemClock.elapsedRealtime() < deadline) { "Native timeline gate timeout" }; Thread.sleep(25) }
    }
    private fun assertColor(frame: TelumiaFrameExtractor.Frame, red: Boolean) {
        assertEquals(320, frame.bitmap.width); assertEquals(180, frame.bitmap.height)
        val color = frame.bitmap.getPixel(160, 90)
        if (red) assertTrue(Color.red(color) > Color.blue(color) + 80)
        else assertTrue(Color.blue(color) > Color.red(color) + 80)
    }
    private fun extractor(url: String, headers: Map<String, String> = emptyMap()): TelumiaFrameExtractor {
        val result = TelumiaFrameExtractor(context, TelumiaFrameExtractor.Configuration.Builder().build(),
            PlayerMediaSourceFactory(context).timelineMediaSourceFactory(url, headers))
        result.setMediaItem(MediaItem.fromUri(url), listOf(Presentation.createForWidthAndHeight(320, 180, Presentation.LAYOUT_SCALE_TO_FIT)))
        return result
    }

    @Test fun actualFramesKeepTheirDecodedTimestampsWithoutSeekingMainPlayback() {
        val clip = originalTimelineVideo(context)
        var reader: TelumiaFrameExtractor? = null; var primary: ExoPlayer? = null
        val ready = AtomicBoolean()
        try {
            main {
                primary = ExoPlayer.Builder(context).build().also {
                    it.addListener(object : Player.Listener { override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.set(true)
                    } })
                    it.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip))); it.prepare(); it.pause(); it.seekTo(2000)
                }
                reader = extractor(Uri.fromFile(clip).toString())
            }
            await { ready.get() }
            for ((position, red) in listOf(500L to true, 4500L to false)) {
                lateinit var future: com.google.common.util.concurrent.ListenableFuture<TelumiaFrameExtractor.Frame>
                main { future = reader!!.getFrame(position) }
                val frame = future.get(8, TimeUnit.SECONDS)
                assertEquals(position, frame.presentationTimeMs); assertColor(frame, red)
                File(context.getExternalFilesDir(null), "telumia-timeline-frame-$position.png").outputStream().use {
                    assertTrue(frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                main { assertEquals(2000L, primary!!.currentPosition); assertFalse(primary!!.playWhenReady) }
            }
        } finally { main { reader?.release(); primary?.release() }; clip.delete() }
    }

    @Test fun latestRequestWinsAndHidingClearsPendingFramesAndOwnership() {
        val clip = originalTimelineVideo(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var lane: PlayerTimelineFrames? = null
        try {
            main {
                lane = PlayerTimelineFrames(context, PlayerMediaSourceFactory(context), scope)
                val session = TimelineFrameSession(Any(), Uri.fromFile(clip).toString(), emptyMap(), 6000)
                lane!!.request(session, 500); lane!!.request(session, 4500)
            }
            await { lane!!.state.value is TimelineFrameState.Ready }
            val result = (lane!!.state.value as TimelineFrameState.Ready).image
            assertEquals(4500L, result.requestedMs); assertEquals(4500L, result.decodedMs)
            assertTrue(Color.blue(result.bitmap.getPixel(160, 90)) > 160)
            main {
                val nextOwner = TimelineFrameSession(Any(), Uri.fromFile(clip).toString(), emptyMap(), 6000)
                lane!!.request(nextOwner, 500)
                assertTrue(lane!!.state.value is TimelineFrameState.Loading)
                lane!!.hide(); assertEquals(TimelineFrameState.Idle, lane!!.state.value)
            }
            Thread.sleep(400)
            assertEquals(TimelineFrameState.Idle, lane!!.state.value)
            main { lane!!.release() }
            assertEquals(TimelineFrameState.Idle, lane!!.state.value)
        } finally { main { lane?.release(); scope.cancel() }; clip.delete() }
    }

    @Test fun unavailableSourceDoesNotInventAnImageAndCanRecoverWithANewSession() {
        val clip = originalTimelineVideo(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var lane: PlayerTimelineFrames? = null
        try {
            main {
                lane = PlayerTimelineFrames(context, PlayerMediaSourceFactory(context), scope)
                lane!!.request(TimelineFrameSession(Any(), Uri.fromFile(File(context.cacheDir, "missing-telumia-gate.mp4")).toString(), emptyMap(), 6000), 500)
            }
            await { lane!!.state.value is TimelineFrameState.Unavailable }
            main { lane!!.request(TimelineFrameSession(Any(), Uri.fromFile(clip).toString(), emptyMap(), 6000), 500) }
            await { lane!!.state.value is TimelineFrameState.Ready }
            assertEquals(500L, (lane!!.state.value as TimelineFrameState.Ready).image.decodedMs)
        } finally { main { lane?.release(); scope.cancel() }; clip.delete() }
    }

    @Test fun actualHttpExtractionCarriesExactAddonHeadersAndRangeWithoutCredentialsInState() {
        val clip = originalTimelineVideo(context)
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        // Match the IPv4 URL explicitly: Android may return ::1 for getLoopbackAddress().
        val socket = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val requestReceived = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { connection ->
                    connection.soTimeout = 3000
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val first = input.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':'); if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                    }
                    requests += headers
                    requestReceived.countDown()
                    val bytes = clip.readBytes()
                    val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
                    val begin = range?.groupValues?.get(1)?.toInt() ?: 0
                    val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                    val authorized = headers["authorization"] == "Bearer local-fixture" && headers["x-addon"] == "alpha,beta\\tail"
                    val output = connection.getOutputStream()
                    if (!authorized) output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else {
                        check(begin in 0..end)
                        val status = if (range != null) "206 Partial Content" else "200 OK"
                        val contentRange = if (range != null) "Content-Range: bytes $begin-$end/${bytes.size}\r\n" else ""
                        output.write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${contentRange}Content-Length: ${end - begin + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (!first.startsWith("HEAD ")) output.write(bytes, begin, end - begin + 1)
                    }
                    output.flush()
                }
            } catch (_: java.io.IOException) { if (socket.isClosed) break }
        }.apply { isDaemon = true; start() }
        var reader: TelumiaFrameExtractor? = null
        try {
            val url = "http://127.0.0.1:${socket.localPort}/original.mp4"
            val headers = mapOf("Authorization" to "Bearer local-fixture", "X-Addon" to "alpha,beta\\tail")
            main { reader = extractor(url, headers) }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<TelumiaFrameExtractor.Frame>
            main { future = reader!!.getFrame(4500) }
            assertTrue("The owned IPv4 fixture must receive an HTTP request", requestReceived.await(4, TimeUnit.SECONDS))
            val frame = future.get(8, TimeUnit.SECONDS)
            assertEquals(4500L, frame.presentationTimeMs); assertColor(frame, false)
            // A tiny MP4 may fit in the extractor's input buffer without reopening HTTP.
            // Exercise a real bounded range through the same Media3 DataSource transport.
            val rangeSource = PlayerPlaybackNetworking.createTimelineDataSourceFactory(context, url, headers).createDataSource()
            try {
                rangeSource.open(androidx.media3.datasource.DataSpec.Builder().setUri(url).setPosition(32).setLength(64).build())
                val received = ByteArray(64)
                var count = 0
                while (count < received.size) {
                    val read = rangeSource.read(received, count, received.size - count)
                    check(read > 0) { "Native range ended before its requested bytes" }
                    count += read
                }
                assertArrayEquals(clip.readBytes().copyOfRange(32, 96), received)
            } finally { rangeSource.close() }
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it["authorization"] == "Bearer local-fixture" && it["x-addon"] == "alpha,beta\\tail" })
            assertTrue(requests.any { it["range"]?.startsWith("bytes=") == true })
        } finally { main { reader?.release() }; socket.close(); worker.join(2000); clip.delete() }
    }

    @Test fun releaseBeforePreparationCancelsEveryQueuedFutureAndCannotRestartWork() {
        val clip = originalTimelineVideo(context)
        var reader: TelumiaFrameExtractor? = null
        lateinit var first: com.google.common.util.concurrent.ListenableFuture<TelumiaFrameExtractor.Frame>
        lateinit var second: com.google.common.util.concurrent.ListenableFuture<TelumiaFrameExtractor.Frame>
        try {
            // All operations share a main-loop turn: preparation callbacks are still queued at release.
            main {
                reader = extractor(Uri.fromFile(clip).toString())
                first = reader!!.getFrame(500)
                second = reader!!.getFrame(4500)
                reader!!.release()
                reader!!.release()
                assertTrue(reader!!.getFrame(1000).isCancelled)
            }
            assertTrue("The first queued frame must terminate", first.isDone && first.isCancelled)
            assertTrue("Later chained frames must also terminate", second.isDone && second.isCancelled)
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            assertTrue(first.isCancelled && second.isCancelled)
        } finally { main { reader?.release() }; clip.delete() }
    }

    @Test fun hidingDuringAnActiveHttpBodyClosesTheConnectionAndCanRecover() {
        val clip = originalTimelineVideo(context)
        val socket = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val bodyStarted = java.util.concurrent.CountDownLatch(1)
        val peerClosed = java.util.concurrent.CountDownLatch(1)
        val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 3000
                    val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(input.readLine()?.startsWith("GET ") == true)
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val bytes = clip.readBytes()
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes, 0, 64.coerceAtMost(bytes.size))
                    output.flush()
                    bodyStarted.countDown()
                    // The remaining video body is deliberately withheld. Only client cancellation
                    // should close this live connection before the transport's four-second timeout.
                    try {
                        if (connection.getInputStream().read() == -1) peerClosed.countDown()
                    } catch (_: java.net.SocketException) { peerClosed.countDown() }
                }
            } catch (failure: Throwable) { workerFailure.set(failure) }
        }.apply { isDaemon = true; start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var lane: PlayerTimelineFrames? = null
        try {
            main {
                lane = PlayerTimelineFrames(context, PlayerMediaSourceFactory(context), scope)
                lane!!.request(TimelineFrameSession(Any(), "http://127.0.0.1:${socket.localPort}/slow.mp4", emptyMap(), 6000), 4500)
            }
            assertTrue("The real transport must begin the withheld body", bodyStarted.await(4, TimeUnit.SECONDS))
            Thread.sleep(75)
            main { lane!!.hide(); assertEquals(TimelineFrameState.Idle, lane!!.state.value) }
            assertTrue("Hiding must cancel the active connection before its read timeout", peerClosed.await(2, TimeUnit.SECONDS))
            assertNull(workerFailure.get())
            main { lane!!.request(TimelineFrameSession(Any(), Uri.fromFile(clip).toString(), emptyMap(), 6000), 500) }
            await { lane!!.state.value is TimelineFrameState.Ready }
            assertEquals(500L, (lane!!.state.value as TimelineFrameState.Ready).image.decodedMs)
        } finally {
            main { lane?.release(); scope.cancel() }
            socket.close(); worker.join(2000); clip.delete()
        }
    }

    @Test fun filmstripContainsDistinctDecodedNeighborsWithinTheSameSession() {
        val clip = originalTimelineVideo(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var lane: PlayerTimelineFrames? = null
        try {
            main {
                lane = PlayerTimelineFrames(context, PlayerMediaSourceFactory(context), scope)
                lane!!.request(TimelineFrameSession(Any(), Uri.fromFile(clip).toString(), emptyMap(), 6000), 3000, filmstrip = true)
            }
            await { (lane!!.state.value as? TimelineFrameState.Ready)?.neighbors?.size == 3 }
            val ready = lane!!.state.value as TimelineFrameState.Ready
            assertEquals(3000L, ready.image.decodedMs)
            assertEquals(listOf(0L, 1500L, 4500L), ready.neighbors.map { it.decodedMs })
            assertTrue(ready.neighbors.all { it.bitmap.width == 320 && it.bitmap.height == 180 })
        } finally { main { lane?.release(); scope.cancel() }; clip.delete() }
    }
}
