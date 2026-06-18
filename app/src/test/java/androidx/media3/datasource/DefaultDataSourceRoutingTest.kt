package androidx.media3.datasource

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.media3.common.NuvioEngineConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class DefaultDataSourceRoutingTest {

    @Before
    fun setUp() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers {
            val arg = firstArg<CharSequence?>()
            arg == null || arg.isEmpty()
        }

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    @Test
    fun testRoutingToLocalhostZeroCopyWhenEnabled() {
        NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val expectedDataLength = 12345L
        val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: $expectedDataLength\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        var connectionAccepted = false
        val serverThread = thread {
            try {
                val client = serverSocket.accept()
                connectionAccepted = true
                val out = client.getOutputStream()
                out.write(responseHeaders.toByteArray())
                out.flush()
                client.close()
            } catch (e: Exception) {
                // Ignore
            }
        }

        val context = mockk<Context>(relaxed = true)
        val baseDataSource = mockk<DataSource>(relaxed = true)
        every { baseDataSource.open(any()) } returns 9999L

        val mockUri = mockk<Uri>()
        every { mockUri.scheme } returns "http"
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()

        val defaultDataSource = DefaultDataSource(context, baseDataSource)

        try {
            val openedLength = defaultDataSource.open(dataSpec)
            serverThread.join(3000)
            
            // If it routed to LocalhostZeroCopyDataSource, it connected to the socket
            // and parsed Content-Length from headers.
            assertEquals(expectedDataLength, openedLength)
            assertTrue(connectionAccepted)
        } finally {
            defaultDataSource.close()
            serverSocket.close()
        }
    }

    @Test
    fun testRoutingToBaseDataSourceWhenStockMode() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())

        val context = mockk<Context>(relaxed = true)
        val baseDataSource = mockk<DataSource>(relaxed = true)
        val expectedBaseLength = 8888L
        every { baseDataSource.open(any()) } returns expectedBaseLength

        val mockUri = mockk<Uri>()
        every { mockUri.scheme } returns "http"
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns 8080
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()

        val defaultDataSource = DefaultDataSource(context, baseDataSource)

        try {
            val openedLength = defaultDataSource.open(dataSpec)
            // It should route to baseDataSource and return expectedBaseLength
            assertEquals(expectedBaseLength, openedLength)
        } finally {
            defaultDataSource.close()
        }
    }

    @Test
    fun testRoutingToBaseDataSourceForExternalHost() {
        NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode()) // Enabled but host is external

        val context = mockk<Context>(relaxed = true)
        val baseDataSource = mockk<DataSource>(relaxed = true)
        val expectedBaseLength = 7777L
        every { baseDataSource.open(any()) } returns expectedBaseLength

        val mockUri = mockk<Uri>()
        every { mockUri.scheme } returns "http"
        every { mockUri.host } returns "google.com"
        every { mockUri.port } returns 80
        every { mockUri.path } returns "/video.mp4"
        every { mockUri.query } returns null

        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()

        val defaultDataSource = DefaultDataSource(context, baseDataSource)

        try {
            val openedLength = defaultDataSource.open(dataSpec)
            // It should bypass LocalhostZeroCopyDataSource because host is not loopback
            assertEquals(expectedBaseLength, openedLength)
        } finally {
            defaultDataSource.close()
        }
    }
}
