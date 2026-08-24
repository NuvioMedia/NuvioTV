package com.nuvio.tv.core.torrent

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class TorrServerApiTest {

    @Test
    fun reachableEndpointReturnsTrue() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                socket.getOutputStream().use { out ->
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    out.flush()
                }
            }
            server.close()
        }

        val api = TorrServerApi(mockk<TorrServerBinary>(relaxed = true))
        val result = api.isEndpointReachable("http://127.0.0.1:$port")

        assertTrue(result)
    }

    @Test
    fun unreachableEndpointReturnsFalse() = runTest {
        val api = TorrServerApi(mockk<TorrServerBinary>(relaxed = true))

        val result = api.isEndpointReachable("http://127.0.0.1:1")

        assertFalse(result)
    }
}