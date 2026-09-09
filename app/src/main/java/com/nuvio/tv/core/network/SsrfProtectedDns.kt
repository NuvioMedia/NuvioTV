package com.nuvio.tv.core.network

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Wraps another [Dns], rejecting any resolved address that is loopback, link-local,
 * private (RFC 1918 / IPv6 ULA), a wildcard ("any local"), or multicast.
 *
 * Intended for HTTP clients that fetch URLs chosen by semi-trusted third-party code (the
 * plugin/scraper JS sandbox) which has no legitimate reason to reach the device's own
 * local network or itself. OkHttp calls [Dns.lookup] for every connection it makes -
 * including each hop of an HTTP redirect and literal IP addresses in the URL (OkHttp
 * still routes those through the configured Dns) - so wrapping resolution here covers
 * the initial request, every redirect target, and DNS-rebinding attempts uniformly,
 * without needing a separate check at every call site.
 *
 * Deliberately NOT used for the app's general-purpose OkHttp clients that reach LAN
 * addresses on purpose (IPTV/local server discovery, the local TorrServer/mpv playback
 * client, the Zidoo remote-control client) - those talk to a fixed, non-user-controlled
 * local endpoint, not a URL chosen by external input.
 *
 * On its own this only covers hostname-based resolution. OkHttp skips [Dns.lookup]
 * entirely when a URL's host is already a literal IP address, so callers that fetch a
 * URL chosen by external input should use [ssrfProtected] instead of applying this Dns
 * alone - it adds [SsrfNetworkInterceptor] as the backstop for that gap.
 */
class SsrfProtectedDns(private val delegate: Dns = IPv4FirstDns()) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val safe = addresses.filterNot { it.isDisallowedForSsrf() }
        if (safe.isEmpty()) {
            throw UnknownHostException(
                "Refusing to resolve '$hostname' - only loopback/private/link-local addresses returned"
            )
        }
        return safe
    }
}

// internal (not private) so callers that need to check an *already-resolved* address -
// e.g. an OkHttp network interceptor verifying the actual connected address, which is
// necessary because OkHttp skips Dns.lookup() entirely when a URL's host is already a
// literal IP address - can reuse this exact check instead of re-implementing it.
internal fun InetAddress.isDisallowedForSsrf(): Boolean {
    return isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isAnyLocalAddress ||
        isMulticastAddress ||
        isIPv6UniqueLocalAddress()
}

/**
 * fc00::/7 (IPv6 unique local addresses, RFC 4193) - NOT covered by
 * [InetAddress.isSiteLocalAddress], which for IPv6 only recognizes the older, deprecated
 * fec0::/10 range.
 */
private fun InetAddress.isIPv6UniqueLocalAddress(): Boolean {
    if (this !is Inet6Address) return false
    val firstByte = address[0].toInt() and 0xFF
    return firstByte == 0xFC || firstByte == 0xFD
}
