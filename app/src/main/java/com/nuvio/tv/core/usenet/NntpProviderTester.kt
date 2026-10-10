package com.nuvio.tv.core.usenet

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

enum class ProviderTestResult { SUCCESS, UNREACHABLE, TLS, AUTH, REFUSED, PRIVATE_NETWORK }

/**
 * Connects and signs in to an NNTP provider the way playback will, so settings
 * can be checked before anything is played. Results are categories only: raw
 * errors could echo credentials.
 */
@Singleton
class NntpProviderTester @Inject constructor() {
    suspend fun test(provider: UsenetProvider, allowPrivateNetwork: Boolean): ProviderTestResult =
        runInterruptible(Dispatchers.IO) {
            val addresses = try { InetAddress.getAllByName(provider.host.removeSurrounding("[", "]")) }
                catch (_: IOException) { return@runInterruptible ProviderTestResult.UNREACHABLE }
            // Mirror the engine's dial policy, or a test could pass where playback is refused.
            if (addresses.any { !allowed(it, allowPrivateNetwork) }) return@runInterruptible ProviderTestResult.PRIVATE_NETWORK
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(addresses.first(), provider.port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val stream = if (provider.tls) secure(socket, provider.host) else socket
                session(stream, provider)
            } catch (_: SSLException) { ProviderTestResult.TLS }
            catch (_: IOException) { ProviderTestResult.UNREACHABLE }
            finally { runCatching { socket.close() } }
        }

    private fun session(socket: Socket, provider: UsenetProvider): ProviderTestResult {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val output = socket.getOutputStream()
        fun code() = input.readLine()?.take(3)?.toIntOrNull() ?: throw IOException("Connection closed")
        // 400 and 502 greetings mean the service is unavailable, often too many connections.
        if (code() !in 200..201) return ProviderTestResult.REFUSED
        if (provider.username.isNotEmpty() || provider.password.isNotEmpty()) {
            output.command("AUTHINFO USER ${provider.username}")
            var reply = code()
            if (reply == 381) {
                output.command("AUTHINFO PASS ${provider.password}")
                reply = code()
            }
            when (reply) {
                281 -> Unit
                // Some providers report too many connections (or a spent block) as 502.
                502 -> return ProviderTestResult.REFUSED
                else -> return ProviderTestResult.AUTH
            }
        }
        runCatching { output.command("QUIT") }
        return ProviderTestResult.SUCCESS
    }

    private fun OutputStream.command(line: String) {
        write("$line\r\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private fun secure(socket: Socket, host: String): Socket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, host, socket.port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.startHandshake()
        return ssl
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        fun allowed(address: InetAddress, allowPrivate: Boolean): Boolean {
            if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return false
            val bytes = address.address
            val private = address.isLoopbackAddress || address.isSiteLocalAddress ||
                (bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 0x40) ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
            return allowPrivate || !private
        }
    }
}
