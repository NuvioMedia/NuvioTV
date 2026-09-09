package com.nuvio.tv.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * Tests [ssrfProtected] itself - the single shared function every SSRF-sensitive
 * OkHttpClient in the app (StreamBadgeConfigServer, CollectionManagementViewModel's URL
 * import, and the Coil image loader in NuvioApplication) is built from. Proving this
 * function is correct proves all three call sites are, since none of them add or
 * override its Dns/interceptor/proxy configuration - see each call site's own comment
 * for why it needs this.
 */
class SsrfProtectedOkHttpTest {

    @Test
    fun wiresDnsProxySelectorInterceptorAndRetrySetting() {
        val client = OkHttpClient.Builder().ssrfProtected().build()
        assertTrue(client.dns is SsrfProtectedDns)
        assertTrue(client.proxySelector === NoProxySelector)
        assertTrue(client.networkInterceptors.contains(SsrfNetworkInterceptor))
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun blocksALiteralLoopbackIpEvenThoughARealServerAnswersThere() {
        // The critical regression case found while hardening StreamBadgeConfigServer:
        // OkHttp skips Dns.lookup() entirely when a URL's host is already a literal IP
        // address, so the Dns-based check alone never sees this request. A bare
        // "http://127.0.0.1/..." with nothing listening would "pass" for the wrong
        // reason (a coincidental connection failure, not the check firing) - this uses a
        // real, responding server to prove the block is real.
        assertRequestBlockedDespiteRealServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
            urlHost = InetAddress.getLoopbackAddress().hostAddress!!
        )
    }

    @Test
    fun blocksIPv6LoopbackLiteralEvenThoughARealServerAnswersThere() {
        val loopbackV6 = InetAddress.getByName("::1")
        assertRequestBlockedDespiteRealServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
            urlHost = "[${loopbackV6.hostAddress}]",
            bindAddress = loopbackV6
        )
    }

    @Test
    fun blocksShortenedLoopbackLiteralEvenThoughARealServerAnswersThere() {
        // "127.1" is shorthand for 127.0.0.1 - one of the alternate encodings SSRF
        // filters that only pattern-match "127.0.0.1" as a string commonly miss. This
        // check operates on the parsed InetAddress's bytes, not the URL string, so it's
        // immune to this by construction - confirmed here rather than assumed.
        assertRequestBlockedDespiteRealServer(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
            urlHost = "127.1"
        )
    }

    private fun assertRequestBlockedDespiteRealServer(
        rawResponse: String,
        urlHost: String,
        bindAddress: InetAddress = InetAddress.getLoopbackAddress()
    ) {
        val serverSocket = ServerSocket(0, 1, bindAddress)
        val thread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().write(rawResponse.toByteArray(StandardCharsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Socket closed while waiting for a connection - the test itself fails
                // below if this was actually needed.
            }
        }.apply { isDaemon = true; start() }

        try {
            val client = OkHttpClient.Builder().ssrfProtected().build()
            val request = Request.Builder().url("http://$urlHost:${serverSocket.localPort}/").get().build()

            var threw = false
            try {
                client.newCall(request).execute().close()
            } catch (_: IOException) {
                threw = true
            }
            assertTrue("Expected the request to $urlHost to be blocked, but it succeeded", threw)
        } finally {
            serverSocket.close()
            thread.join(2_000)
        }
    }

    @Test
    fun allowsAResolvedPublicAddressThroughTheSameCheckTheInterceptorUses() {
        // ssrfProtected() has no injection point (by design - every call site gets the
        // exact same, real check), so this can't make a real connection to a genuinely
        // public server from a unit test the way the "blocked" cases above do with a
        // local server. What can be proven directly is that the interceptor and the Dns
        // both gate on the identical isDisallowedForSsrf() check already proven to
        // return false for public addresses (SsrfProtectedDnsTest.allowsPublicIPv4 /
        // allowsPublicIPv6) - there's no separate "is this public" logic in the
        // interceptor that could disagree with the Dns layer.
        val public4 = InetAddress.getByName("93.184.216.34")
        val public6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
        assertFalse(public4.isDisallowedForSsrf())
        assertFalse(public6.isDisallowedForSsrf())
    }
}
