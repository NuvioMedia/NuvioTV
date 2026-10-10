package com.nuvio.tv.core.usenet

import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NntpProviderTesterTest {
    /** Answers one connection: the greeting, then [replies] for each command prefix. */
    private class FakeNntp(greeting: String, replies: Map<String, String> = emptyMap()) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream()
                        output.write("$greeting\r\n".toByteArray()); output.flush()
                        while (true) {
                            val line = input.readLine() ?: break
                            commands += line
                            val reply = replies.entries.firstOrNull { line.startsWith(it.key) }?.value ?: "205 bye"
                            output.write("$reply\r\n".toByteArray()); output.flush()
                            if (line == "QUIT") break
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun provider(port: Int, username: String = "user", password: String = "pass") =
        UsenetProvider(name = "Test", host = "127.0.0.1", port = port, tls = false, username = username, password = password)

    private val auth = mapOf("AUTHINFO USER" to "381 password required", "AUTHINFO PASS" to "281 ok")

    @Test fun `signs in and quits`() = runBlocking {
        FakeNntp("200 welcome", auth).use { server ->
            assertEquals(ProviderTestResult.SUCCESS, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
            assertEquals(listOf("AUTHINFO USER user", "AUTHINFO PASS pass", "QUIT"), server.commands.toList())
        }
    }

    @Test fun `providers without credentials only need a greeting`() = runBlocking {
        FakeNntp("201 no posting").use { server ->
            assertEquals(ProviderTestResult.SUCCESS,
                NntpProviderTester().test(provider(server.port, "", ""), allowPrivateNetwork = true))
            assertFalse(server.commands.any { it.startsWith("AUTHINFO") })
        }
    }

    @Test fun `rejected password and refused connections are reported separately`() = runBlocking {
        FakeNntp("200 welcome", auth + ("AUTHINFO PASS" to "481 rejected")).use { server ->
            assertEquals(ProviderTestResult.AUTH, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
        }
        FakeNntp("502 too many connections").use { server ->
            assertEquals(ProviderTestResult.REFUSED, NntpProviderTester().test(provider(server.port), allowPrivateNetwork = true))
        }
    }

    @Test fun `local addresses need self hosted servers and closed ports are unreachable`() = runBlocking {
        FakeNntp("200 welcome", auth).use { server ->
            assertEquals(ProviderTestResult.PRIVATE_NETWORK,
                NntpProviderTester().test(provider(server.port), allowPrivateNetwork = false))
            assertTrue("no connection may be made", server.commands.isEmpty())
        }
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(ProviderTestResult.UNREACHABLE, NntpProviderTester().test(provider(closed), allowPrivateNetwork = true))
    }
}
