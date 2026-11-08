package com.nuvio.tv.core.network

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Asks for a fixed receive buffer on playback sockets only when the box grants more than its own tuning ceiling
 * (the last tcp_rmem value). Asking turns the kernel's tuning off for that socket.
 */
internal object ReceiveWindow {
    private const val TAG = "ReceiveWindow"
    const val REQUEST_BYTES = 8 * 1024 * 1024
    // Ceiling unreadable: ask only when the whole request was granted (the kernel doubles it).
    const val UNKNOWN_CEILING_MIN_GRANT = 2 * REQUEST_BYTES

    /** Bytes to ask for on each playback socket, or 0 to leave the kernel's tuning alone. */
    val bytes: Int by lazy { decide(::grantFor, ::readTuningCeiling) }

    internal fun decide(grant: (Int) -> Int, ceiling: () -> Int?): Int {
        val granted = runCatching { grant(REQUEST_BYTES) }.getOrDefault(0)
        val tuningMax = ceiling()
        val ask = shouldAsk(granted, tuningMax)
        runCatching {
            Log.i(TAG, "RECEIVE_WINDOW: requestedKb=${REQUEST_BYTES / 1024} grantedKb=${granted / 1024} " +
                "tuningMaxKb=${tuningMax?.let { it / 1024 } ?: "-"} used=$ask")
        }
        return if (ask) REQUEST_BYTES else 0
    }

    fun shouldAsk(grantedBytes: Int, tuningCeilingBytes: Int?): Boolean =
        if (tuningCeilingBytes != null) grantedBytes > tuningCeilingBytes else grantedBytes >= UNKNOWN_CEILING_MIN_GRANT

    private fun grantFor(bytes: Int): Int = Socket().use { it.receiveBufferSize = bytes; it.receiveBufferSize }

    private fun readTuningCeiling(): Int? = runCatching {
        File("/proc/sys/net/ipv4/tcp_rmem").readText().trim().split(Regex("\\s+")).last().toInt()
    }.getOrNull()
}

/** [NoDelaySocketFactory] plus the playback receive buffer from [ReceiveWindow]. */
object PlaybackSocketFactory : SocketFactory() {
    private val delegate: SocketFactory = NoDelaySocketFactory

    // OkHttp connects the unconnected socket itself, so the buffer is set before the handshake.
    override fun createSocket(): Socket = delegate.createSocket().window()

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)

    private fun Socket.window(): Socket = apply {
        val bytes = ReceiveWindow.bytes
        if (bytes > 0) runCatching { receiveBufferSize = bytes }
    }
}
