package com.nuvio.tv.core.network

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Turns Nagle's algorithm off. Android leaves it on, so the first request on a new
 * TLS connection waits one round trip for the server to acknowledge the handshake.
 */
object NoDelaySocketFactory : SocketFactory() {
    private val delegate: SocketFactory = getDefault()

    override fun createSocket(): Socket = delegate.createSocket().noDelay()

    override fun createSocket(host: String?, port: Int): Socket =
        delegate.createSocket(host, port).noDelay()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).noDelay()

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        delegate.createSocket(host, port).noDelay()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).noDelay()

    private fun Socket.noDelay(): Socket = apply { runCatching { tcpNoDelay = true } }
}
