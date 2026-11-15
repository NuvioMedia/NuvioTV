package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RedirectReportingDataSourceTest {

    private val file = ByteArray(600 * 1024) { (it * 31 + 7).toByte() }
    private val server = MockWebServer()
    private val reported = mutableListOf<String>()

    // Where /play currently redirects; null serves the file at /play itself.
    @Volatile private var redirectTarget: String? = "/release-b"

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns url.substringAfter("127.0.0.1").substringAfter('/').let { "/$it" }
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val target = redirectTarget
                if (path.startsWith("/play") && target != null) {
                    return MockResponse().setResponseCode(307).setHeader("Location", target)
                }
                if (path == "/subtitle") {
                    return MockResponse().setResponseCode(307).setHeader("Location", "/cdn/subtitle")
                }
                return serveFile(request.getHeader("Range"))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Uri::class)
    }

    private fun serveFile(range: String?): MockResponse {
        if (range == null) return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
        val (from, to) = range.removePrefix("bytes=").split("-").let { parts ->
            val start = parts[0].toInt()
            val end = parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1)
            start to minOf(end, file.size - 1)
        }
        return MockResponse().setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $from-$to/${file.size}")
            .setBody(Buffer().write(file, from, to - from + 1))
    }

    private fun url(path: String) = server.url(path).toString()

    private fun reportingFactory(upstream: DataSource.Factory = OkHttpDataSource.Factory(OkHttpClient())) =
        RedirectReportingDataSource.Factory(upstream, url("/play")) { resolvedUrl -> reported += resolvedUrl }

    private fun readAll(factory: DataSource.Factory, path: String = "/play", position: Long = 0L): ByteArray {
        val source = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(uriOf(url(path))).setPosition(position).build()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            source.open(spec)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `a stream served where it was requested reports nothing`() {
        redirectTarget = null
        assertArrayEquals(file, readAll(reportingFactory()))
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `a redirect reports where the stream was served from and reads through unchanged`() {
        assertArrayEquals(file, readAll(reportingFactory()))
        assertEquals(listOf(url("/release-b")), reported)
    }

    @Test
    fun `a seek that lands on the same target reports nothing new`() {
        val factory = reportingFactory()
        readAll(factory)
        readAll(factory, position = 300L * 1024L)
        assertEquals(listOf(url("/release-b")), reported)
    }

    @Test
    fun `a reopen that lands somewhere new is reported again`() {
        val factory = reportingFactory()
        readAll(factory)
        redirectTarget = "/release-c"
        readAll(factory, position = 300L * 1024L)
        assertEquals(listOf(url("/release-b"), url("/release-c")), reported)
    }

    @Test
    fun `a redirect on another url through the same chain is not reported`() {
        readAll(reportingFactory(), path = "/subtitle")
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `buffer reads still reach the parallel source behind it`() {
        val factory = reportingFactory(
            ParallelRangeDataSource.Factory(
                OkHttpDataSource.Factory(OkHttpClient()),
                parallelConnections = 2,
                chunkSize = 128L * 1024L
            )
        )
        val source = factory.createDataSource() as androidx.media3.common.ByteBufferDataReader
        val out = ByteArrayOutputStream()
        val target = ByteBuffer.allocateDirect(64 * 1024)
        try {
            (source as DataSource).open(DataSpec.Builder().setUri(uriOf(url("/play"))).build())
            while (true) {
                target.clear()
                val read = source.read(target, target.capacity())
                if (read == C.RESULT_END_OF_INPUT) break
                target.flip()
                val bytes = ByteArray(target.remaining())
                target.get(bytes)
                out.write(bytes)
            }
        } finally {
            (source as DataSource).close()
        }
        assertArrayEquals(file, out.toByteArray())
        assertEquals(listOf(url("/release-b")), reported)
    }
}
