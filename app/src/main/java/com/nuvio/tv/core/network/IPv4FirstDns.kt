package com.nuvio.tv.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Custom DNS that reorders resolved addresses to place IPv4 (Inet4Address)
 * before IPv6 (Inet6Address). This avoids 60s timeout delays on networks
 * with broken IPv6 routing (issue #651).
 */
class IPv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4Addresses.isNotEmpty()) {
            ipv4Addresses
        } else {
            addresses
        }
    }
}
