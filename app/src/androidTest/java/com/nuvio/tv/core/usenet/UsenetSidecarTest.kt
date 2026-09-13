package com.nuvio.tv.core.usenet

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.Stream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsenetSidecarTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("usenet_performance", android.content.Context.MODE_PRIVATE)
    private var savedToggles = emptyMap<String, Boolean?>()

    @Before fun enablePreparationForTests() {
        savedToggles = listOf("prefetchResults", "cacheNzb", "fastMkvStartup").associateWith { prefs.all[it] as? Boolean }
        prefs.edit().putBoolean("prefetchResults", true).putBoolean("cacheNzb", false).putBoolean("fastMkvStartup", false).commit()
    }

    @After fun restoreToggles() = runBlocking {
        val sidecar = UsenetSidecar.get(context)
        val url = UsenetSidecar::class.java.getDeclaredField("playbackUrl").apply { isAccessible = true }.get(sidecar) as? String
        sidecar.release(url)
        sidecar.cancelPrefetch()
        UsenetSidecar.stopIdleOnBackground()
        val process = UsenetSidecar::class.java.getDeclaredField("process").apply { isAccessible = true }
        withTimeout(10_000) { while (process.get(sidecar) != null) { sidecar.prewarm().join(); delay(10) } }
        prefs.edit().apply { savedToggles.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
        UsenetSidecar.onAppForegrounded()
    }

    @Test fun prewarmHonorsSettingAndVisibility() = runBlocking {
        val prefs = context.getSharedPreferences("usenet_performance", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getBoolean("prewarmOnLaunch", true)
        val present = prefs.contains("prewarmOnLaunch")
        val sidecar = UsenetSidecar.get(context)
        val field = UsenetSidecar::class.java.getDeclaredField("process").apply { isAccessible = true }
        try {
            UsenetSidecar.onAppForegrounded()
            prefs.edit().putBoolean("prewarmOnLaunch", false).commit()
            sidecar.prewarm().join()
            assertNull("disabled prewarm started a child", field.get(sidecar))
            prefs.edit().putBoolean("prewarmOnLaunch", true).commit()
            sidecar.prewarm().join()
            val child = field.get(sidecar)
            assertNotNull("prewarm did not start the packaged engine", child)
            sidecar.prewarm().join()
            assertSame("repeated foreground started another engine", child, field.get(sidecar))
            UsenetSidecar.stopIdleOnBackground()
            sidecar.prewarm().join()
            assertNull("background app retained an idle engine", field.get(sidecar))
            UsenetSidecar.onAppForegrounded()
            sidecar.prewarm().join()
            assertNotNull("returning to app did not rewarm", field.get(sidecar))
            prefs.edit().putBoolean("prewarmOnLaunch", false).commit()
            sidecar.prewarm().join()
            assertNull("disabling prewarm retained the idle engine", field.get(sidecar))
        } finally {
            if (present) prefs.edit().putBoolean("prewarmOnLaunch", previous).commit()
            else prefs.edit().remove("prewarmOnLaunch").commit()
            UsenetSidecar.stopIdleOnBackground()
            sidecar.prewarm().join()
            UsenetSidecar.onAppForegrounded()
        }
    }

    @Test fun packagedEngineResolvesAddonAndServesExoPlayerRanges() = runBlocking {
        UsenetSidecar.onAppForegrounded()
        Fixture().use { fixture ->
            val stream = Stream(name = "Fixture", title = null, description = null, url = null,
                ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
                addonName = "Usenet protocol test", addonLogo = null, nzbUrl = fixture.nzbUrl,
                servers = listOf(fixture.provider), fileMustInclude = "/\\.mkv$/i")
            val sidecar = UsenetSidecar.get(context)
            val resolved = sidecar.resolve(stream, null, null)
            val url = requireNotNull(resolved.url)
            try {
                assertFalse(resolved.isUsenet())
                assertTrue(UsenetSidecar.isSessionUrl(url))
                assertNull(resolved.servers)
                assertEquals(fixture.payload.size.toLong(), resolved.behaviorHints?.videoSize)
                assertEquals(1, resolved.subtitles.size)
                val subtitle = resolved.subtitles.single()
                assertTrue(UsenetSidecar.isSubtitleUrl(subtitle.url))
                assertEquals("en", subtitle.lang)
                assertEquals("subtitles must be lazy", 0, fixture.subtitleReads)
                OkHttpClient().newCall(Request.Builder().url(subtitle.url).build()).execute().use {
                    assertEquals(200, it.code)
                    assertArrayEquals(fixture.subtitlePayload, it.body!!.bytes())
                }
                for (offset in listOf(0, 65520, 200003)) {
                    val source = DefaultHttpDataSource.Factory().createDataSource()
                    try {
                        val length = 16000
                        assertEquals(length.toLong(), source.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(offset.toLong()).setLength(length.toLong()).build()))
                        val data = ByteArray(length)
                        var read = 0
                        while (read < length) { val n = source.read(data, read, length - read); assertTrue(n > 0); read += n }
                        assertArrayEquals(fixture.payload.copyOfRange(offset, offset + length), data)
                    } finally { source.close() }
                }
                val http = OkHttpClient()
                http.newCall(Request.Builder().url(url).header("Range", "bytes=-123").build()).execute().use {
                    assertEquals(206, it.code)
                    assertArrayEquals(fixture.payload.takeLast(123).toByteArray(), it.body!!.bytes())
                }
                http.newCall(Request.Builder().url(url.substringBefore("/stream/") + "/health").build()).execute().use { assertEquals(401, it.code) }
            } finally { sidecar.release(url) }
            var deadline = SystemClock.elapsedRealtime() + 6000
            val client = OkHttpClient()
            var sessionDeleted = false
            while (!sessionDeleted && SystemClock.elapsedRealtime() < deadline) {
                sessionDeleted = client.newCall(Request.Builder().url(url).head().build()).execute().use { it.code == 404 }
                if (!sessionDeleted) SystemClock.sleep(50)
            }
            assertTrue("session was not deleted", sessionDeleted)
            val second = sidecar.resolve(stream, null, null)
            assertEquals("adjacent playback should reuse the daemon", Uri.parse(url).port, Uri.parse(second.url).port)
            sidecar.release(second.url)
            deadline = SystemClock.elapsedRealtime() + 6000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (client.newCall(Request.Builder().url(second.url!!).head().build()).execute().use { it.code == 404 }) break
                SystemClock.sleep(50)
            }
            sidecar.onAppBackgrounded()
            deadline = SystemClock.elapsedRealtime() + 6000
            var stopped = false
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                stopped = runCatching { client.newCall(Request.Builder().url(url).head().build()).execute().close(); false }.getOrDefault(true)
                if (!stopped) SystemClock.sleep(50)
            }
            assertTrue("sidecar did not stop when playback released", stopped)
            assertEquals("sizing probes are forbidden", 0, fixture.stats)
        }
    }

    @Test fun packagedProcessExitsWhenParentPipeCloses() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libnuvio_usenet.so")
        assertTrue("APK did not install an executable sidecar", binary.canExecute())
        val process = ProcessBuilder(binary.absolutePath).start()
        try {
            process.outputStream.write((JSONObject().put("token", "test-token-with-at-least-thirty-two-characters").toString()+"\n").toByteArray())
            process.outputStream.flush()
            val ready = JSONObject(process.inputStream.bufferedReader().readLine())
            assertEquals(1, ready.getInt("protocol"))
            process.outputStream.close()
            val deadline = SystemClock.elapsedRealtime() + 4000
            var exit: Int? = null
            while (exit == null && SystemClock.elapsedRealtime() < deadline) {
                exit = runCatching { process.exitValue() }.getOrNull()
                if (exit == null) SystemClock.sleep(20)
            }
            assertEquals("parent EOF must terminate the child cleanly", 0, exit)
        } finally { process.destroy() }
    }

    @Test fun preparationSharesOpeningAndHandsCacheToPlayback() = runBlocking {
        UsenetSidecar.onAppForegrounded()
        val sidecar = UsenetSidecar.get(context)
        val gate = CountDownLatch(1)
        Fixture(gate).use { fixture ->
            val stream = fixture.stream()
            val owner = Any()
            val warm = sidecar.prefetch(owner, stream, null, null, 1)
            try {
                withTimeout(10_000) { while (fixture.nzbReads.get() == 0) delay(10) }
                assertSame(warm, sidecar.prefetch(owner, stream, null, null, 1))
                val playback = async { sidecar.resolve(stream, null, null, 1) }
                delay(50)
                assertFalse("Play should join the pending preparation", playback.isCompleted)
                gate.countDown()
                val resolved = withTimeout(15_000) { playback.await() }
                assertEquals("Play downloaded the NZB twice", 1, fixture.nzbReads.get())
                sidecar.cancelPrefetch(owner)
                delay(50)
                OkHttpClient().newCall(Request.Builder().url(resolved.url!!).head().build()).execute().use {
                    assertEquals("leaving results cancelled adopted playback", 200, it.code)
                }
                sidecar.release(resolved.url)
            } finally {
                gate.countDown()
                sidecar.cancelPrefetch(owner)
                sidecar.onAppBackgrounded()
                sidecar.prewarm().join()
            }
        }
    }

    @Test fun preparedSessionIsReusedAndWrongEpisodeReopens() = runBlocking {
        UsenetSidecar.onAppForegrounded()
        val sidecar = UsenetSidecar.get(context)
        Fixture().use { fixture ->
            val stream = fixture.stream().copy(fileIdx = 0)
            val owner = Any()
            try {
                sidecar.prefetch(owner, stream, 1, 1, 1).join()
                val preparedUrl = UsenetSidecar::class.java.getDeclaredField("playbackUrl")
                    .apply { isAccessible = true }.get(sidecar)
                val resolved = sidecar.resolve(stream, 1, 1, 1)
                assertEquals(preparedUrl, resolved.url)
                assertEquals(1, fixture.nzbReads.get())
                sidecar.release(resolved.url)
                // release is queued; wait for deletion before preparing another session.
                withTimeout(10_000) {
                    val http = OkHttpClient()
                    while (http.newCall(Request.Builder().url(resolved.url!!).head().build()).execute().use { it.code != 404 }) delay(10)
                }
                sidecar.prefetch(owner, stream, 1, 1, 1).join()
                val other = sidecar.resolve(stream, 1, 2, 1)
                assertEquals("a different episode reused the prepared selection", 3, fixture.nzbReads.get())
                sidecar.release(other.url)
            } finally {
                sidecar.cancelPrefetch(owner)
                sidecar.onAppBackgrounded()
                sidecar.prewarm().join()
            }
        }
    }

    @Test fun abandoningPreparationDeletesSessionAndActivePlaybackBlocksPrefetch() = runBlocking {
        UsenetSidecar.onAppForegrounded()
        val sidecar = UsenetSidecar.get(context)
        Fixture().use { fixture ->
            val owner = Any()
            val stream = fixture.stream()
            try {
                sidecar.prefetch(owner, stream, null, null, 1).join()
                val url = UsenetSidecar::class.java.getDeclaredField("playbackUrl")
                    .apply { isAccessible = true }.get(sidecar) as String
                sidecar.cancelPrefetch(owner)
                val http = OkHttpClient()
                withTimeout(10_000) {
                    while (http.newCall(Request.Builder().url(url).head().build()).execute().use { it.code != 404 }) delay(10)
                }
                val active = sidecar.resolve(stream, null, null, 1)
                val reads = fixture.nzbReads.get()
                sidecar.prefetch(Any(), stream, 2, 1, 1).join()
                assertEquals("browsing opened a provider session during playback", reads, fixture.nzbReads.get())
                http.newCall(Request.Builder().url(active.url!!).head().build()).execute().use { assertEquals(200, it.code) }
                sidecar.release(active.url)
            } finally {
                sidecar.cancelPrefetch()
                UsenetSidecar.stopIdleOnBackground()
                sidecar.prewarm().join()
                UsenetSidecar.onAppForegrounded()
            }
        }
    }

    @Test fun prefetchToggleDisablesRequestsAndMkvToggleControlsPreparedWarmup() = runBlocking {
        assertFalse(UsenetConfiguration().prefetchResults)
        assertTrue(UsenetConfiguration().cacheNzb)
        assertTrue(UsenetConfiguration().fastMkvStartup)
        UsenetSidecar.onAppForegrounded()
        val sidecar = UsenetSidecar.get(context)
        Fixture().use { fixture ->
            val owner = Any()
            prefs.edit().putBoolean("prefetchResults", false).commit()
            sidecar.prefetch(owner, fixture.stream(), null, null, 1).join()
            assertEquals("disabled prefetch hit the indexer", 0, fixture.nzbReads.get())
            prefs.edit().putBoolean("prefetchResults", true).commit()
            for (fast in listOf(true, false)) {
                prefs.edit().putBoolean("fastMkvStartup", fast).commit()
                sidecar.settingsChanged().join()
                sidecar.prefetch(owner, fixture.stream(), null, null, 1).join()
                val url = UsenetSidecar::class.java.getDeclaredField("playbackUrl").apply { isAccessible = true }.get(sidecar) as String
                val token = UsenetSidecar::class.java.getDeclaredField("token").apply { isAccessible = true }.get(sidecar) as String
                val id = url.substringAfter("/stream/").substringBefore('/')
                val request = Request.Builder().url(url.substringBefore("/stream/") + "/sessions/$id/diagnostics")
                    .header("Authorization", "Bearer $token").build()
                OkHttpClient().newCall(request).execute().use {
                    assertEquals(200, it.code)
                    val marks = JSONObject(it.body.string()).getJSONObject("marksMs")
                    assertEquals("preparation bypassed Fast MKV Startup=$fast", fast, marks.has("warmup_started"))
                }
            }
            prefs.edit().putBoolean("prefetchResults", false).commit()
            sidecar.settingsChanged().join()
            val field = UsenetSidecar::class.java.getDeclaredField("sessionId").apply { isAccessible = true }
            assertNull("disabling prefetch retained unused session", field.get(sidecar))
        }
    }

    @Test fun nzbCacheSurvivesEngineRestartAndToggleOffClearsIt() = runBlocking {
        UsenetSidecar.onAppForegrounded()
        val sidecar = UsenetSidecar.get(context)
        Fixture().use { fixture ->
            prefs.edit().putBoolean("prefetchResults", false).putBoolean("cacheNzb", true).commit()
            val first = sidecar.resolve(fixture.stream(), null, null, 1)
            sidecar.release(first.url)
            UsenetSidecar.stopIdleOnBackground()
            val process = UsenetSidecar::class.java.getDeclaredField("process").apply { isAccessible = true }
            withTimeout(10_000) { while (process.get(sidecar) != null) { sidecar.prewarm().join(); delay(10) } }
            UsenetSidecar.onAppForegrounded()
            val second = sidecar.resolve(fixture.stream(), null, null, 1)
            assertEquals("restarting engine redownloaded cached NZB", 1, fixture.nzbReads.get())
            prefs.edit().putBoolean("cacheNzb", false).commit()
            sidecar.settingsChanged().join()
            assertTrue("disabling cache left documents on disk", File(context.cacheDir, "usenet-nzb").listFiles().orEmpty().isEmpty())
            val third = sidecar.resolve(fixture.stream(), null, null, 1)
            assertEquals("disabled cache served a cached NZB", 2, fixture.nzbReads.get())
            assertNotEquals(second.url, third.url)
            sidecar.release(third.url)
        }
    }

    private class Fixture(private val nzbGate: CountDownLatch? = null) : Closeable {
        val payload = ByteArray(256 * 1024) { ((it * 131 + it / 257) % 256).toByte() }
        val subtitlePayload = "1\n00:00:01,000 --> 00:00:03,000\nNative Usenet subtitle\n".toByteArray()
        private val pool = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val nntp = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val http = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val provider = "nntp://127.0.0.1:${nntp.localPort}/8"
        val nzbUrl = "http://127.0.0.1:${http.localPort}/fixture.nzb"
        @Volatile var stats = 0
        @Volatile var subtitleReads = 0
        val nzbReads = AtomicInteger()
        fun stream() = Stream(name = "Fixture", title = null, description = null, url = null,
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = "Usenet protocol test", addonLogo = null, nzbUrl = nzbUrl,
            servers = listOf(provider), fileMustInclude = "/\\.mkv$/i")
        private val articles = (0..3).map { index -> encode(index) }
        private val subtitleArticle = encode(0, subtitlePayload, "fixture.en.srt", 1)
        private val xml = "<nzb><file subject='\"fixture.mkv\" yEnc (1/4)' date='1'><groups><group>alt.test</group></groups><segments>" +
            articles.mapIndexed { i, bytes -> "<segment bytes='${bytes.size}' number='${i+1}'>part$i@test</segment>" }.joinToString("") + "</segments></file>" +
            "<file subject='\"fixture.en.srt\" yEnc (1/1)' date='1'><groups><group>alt.test</group></groups><segments><segment bytes='${subtitleArticle.size}' number='1'>subtitle@test</segment></segments></file></nzb>"

        init {
            accept(http) { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                nzbReads.incrementAndGet()
                nzbGate?.await(15, TimeUnit.SECONDS)
                val body = xml.toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            }
            accept(nntp) { socket ->
                val input = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()
                output.write("200 fixture ready\r\n".toByteArray()); output.flush()
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        line.startsWith("BODY ") -> {
                            if (line.contains("<subtitle@test>")) {
                                subtitleReads++
                                output.write("222 body\r\n".toByteArray()); output.write(subtitleArticle); output.write(".\r\n".toByteArray()); output.flush()
                                continue
                            }
                            val index = Regex("part([0-3])@test").find(line)?.groupValues?.get(1)?.toInt()
                            if (index == null) output.write("430 missing\r\n".toByteArray()) else {
                                output.write("222 body\r\n".toByteArray()); output.write(articles[index]); output.write(".\r\n".toByteArray())
                            }
                        }
                        line.startsWith("STAT ") -> { stats++; output.write("223 exists\r\n".toByteArray()) }
                        line.startsWith("QUIT") -> break
                        else -> output.write("200 ok\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        }

        private fun accept(server: ServerSocket, handle: (Socket) -> Unit) {
            pool.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    pool.execute { runCatching { socket.use(handle) }; sockets.remove(socket) }
                }
            }
        }

        private fun encode(index: Int, content: ByteArray = payload, name: String = "fixture.mkv", parts: Int = 4): ByteArray {
            val begin = index * 65536
            val data = content.copyOfRange(begin, minOf(begin + 65536, content.size))
            val out = ByteArrayOutputStream()
            out.write("=ybegin part=${index+1} total=$parts line=128 size=${content.size} name=$name\r\n=ypart begin=${begin+1} end=${begin+data.size}\r\n".toByteArray())
            var column = 0
            for (byte in data) {
                var value = (byte.toInt() + 42) and 255
                if (column >= 128) { out.write("\r\n".toByteArray()); column = 0 }
                if (column == 0 && value == 46) out.write(46)
                if (value in listOf(0, 10, 13, 61)) { out.write(61); value = (value + 64) and 255; column++ }
                out.write(value); column++
            }
            val crc = CRC32().apply { update(data) }.value.toString(16).padStart(8, '0')
            out.write("\r\n=yend size=${data.size} part=${index+1} pcrc32=$crc\r\n".toByteArray())
            return out.toByteArray()
        }

        override fun close() { http.close(); nntp.close(); sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
    }
}
