package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
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
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ParallelRangeByteBufferReadTest {

    private val file = ByteArray(600 * 1024) { (it * 31 + 7).toByte() }
    private val server = MockWebServer()

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Uri::class)
    }

    private fun serve(ranges: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (!ranges || range == null) {
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                }
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
        }
        server.start()
    }

    private fun readAll(): ByteArray {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(OkHttpClient()),
            parallelConnections = 2,
            chunkSize = 128L * 1024L,
        )
        val spec = DataSpec.Builder().setUri(uriOf(server.url("/file").toString())).build()
        val out = ByteArrayOutputStream()
        val target = ByteBuffer.allocateDirect(64 * 1024)
        try {
            source.open(spec)
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
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `ranged file reads back byte for byte into direct buffers`() {
        serve(ranges = true)
        assertArrayEquals(file, readAll())
    }

    @Test
    fun `single connection fallback reads back byte for byte into direct buffers`() {
        serve(ranges = false)
        assertArrayEquals(file, readAll())
    }
}
