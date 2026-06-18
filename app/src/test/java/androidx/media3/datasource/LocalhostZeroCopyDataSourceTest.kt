package androidx.media3.datasource

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class LocalhostZeroCopyDataSourceTest {

    @Test
    fun testOpenAndReadZeroCopy() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val expectedData = "Hello, zero-copy socket loopback data pipeline!"
        val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${expectedData.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                // Read request headers
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                
                // Write response
                val out = client.getOutputStream()
                out.write(responseHeaders.toByteArray())
                out.write(expectedData.toByteArray())
                out.flush()
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Mock android.net.Uri
        val mockUri = mockk<Uri>()
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSource = LocalhostZeroCopyDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()
        
        try {
            val bytesRemaining = dataSource.open(dataSpec)
            assertEquals(expectedData.length.toLong(), bytesRemaining)
            assertTrue(dataSource.supportsByteBufferRead())

            val buffer = ByteBuffer.allocateDirect(100)
            val bytesRead = dataSource.read(buffer, expectedData.length)
            assertEquals(expectedData.length, bytesRead)
            
            buffer.flip()
            val bytes = ByteArray(bytesRead)
            buffer.get(bytes)
            val actualData = String(bytes)
            assertEquals(expectedData, actualData)
        } finally {
            dataSource.close()
            serverSocket.close()
        }
    }
}
