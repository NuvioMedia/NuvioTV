package com.nuvio.tv.core.network

import android.net.Network
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

private const val TAG = "VpnBypassProxyServer"
private const val CLIENT_SOCKET_TIMEOUT_MS = 15_000
private const val RELAY_BUFFER_SIZE = 16 * 1024

/**
 * A minimal local CONNECT-only HTTP proxy that lets mpv/ffmpeg's playback traffic bypass an
 * active app-scoped VPN for hosts that need it (see PluginSafety.shouldBypassVpnForUrl).
 *
 * ExoPlayer/OkHttp traffic can bind directly to a specific android.net.Network (see
 * PlayerPlaybackNetworking/PluginRuntime), but ffmpeg opens its own native sockets with no
 * Java-level hook to do the same. Instead, mpv is pointed at this loopback proxy
 * (--http-proxy) for bypass-eligible streams: mpv tunnels its HTTPS connection through
 * CONNECT, and this proxy opens the real upstream socket bound to the non-VPN network and
 * blindly relays bytes in both directions.
 */
object VpnBypassProxyServer {
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = -1

    /**
     * Starts the proxy (if not already running) and returns the loopback port it's listening
     * on, or null if it could not be started. [networkProvider] is invoked fresh for every
     * CONNECT tunnel - never cache its result - since a Network object can go stale (Wi-Fi
     * reassociation, DHCP renewal, etc.) and a socket bound to a dead one fails outright.
     */
    @Synchronized
    fun ensureStarted(networkProvider: () -> Network?): Int? {
        serverSocket?.let { if (!it.isClosed) return boundPort }
        return try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            boundPort = server.localPort
            executor.execute { acceptLoop(server, networkProvider) }
            boundPort
        } catch (e: IOException) {
            Log.w(TAG, "Failed to start local VPN bypass proxy", e)
            null
        }
    }

    private fun acceptLoop(server: ServerSocket, networkProvider: () -> Network?) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) return
                continue
            }
            executor.execute { handleClient(client, networkProvider) }
        }
    }

    private fun handleClient(client: Socket, networkProvider: () -> Network?) {
        var upstream: Socket? = null
        try {
            client.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                runCatching { client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) }
                return
            }
            val hostPort = parts[1]
            val host = hostPort.substringBeforeLast(':')
            val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 443

            val network = networkProvider()
            upstream = if (network != null) {
                network.socketFactory.createSocket(host, port)
            } else {
                Socket(host, port)
            }

            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())

            val toUpstream = Thread { relay(client, upstream) }
            val toClient = Thread { relay(upstream, client) }
            toUpstream.start()
            toClient.start()
            toUpstream.join()
            toClient.join()
        } catch (e: IOException) {
            // Expected on client/upstream disconnects - nothing to recover from.
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun relay(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(RELAY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: IOException) {
            // Normal when either side closes the connection.
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
