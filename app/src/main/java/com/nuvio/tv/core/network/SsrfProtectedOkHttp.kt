package com.nuvio.tv.core.network

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Applies the app's full SSRF hardening to an [OkHttpClient.Builder] in one call:
 * [SsrfProtectedDns] for hostname resolution, plus [SsrfNetworkInterceptor] as a
 * backstop for the cases Dns-based filtering alone misses (see its doc), a direct
 * [NoProxySelector] (this check has no visibility into whatever a system/network proxy
 * might do), and connection-failure retry disabled (nothing here should be retried past
 * what the check itself allows).
 *
 * Every call site that fetches a URL chosen by something other than this app's own code
 * (an addon/plugin, a pasted import URL, image/metadata URLs from a catalog or addon)
 * must go through this - see SsrfProtectedDns's doc for what's deliberately excluded.
 */
fun OkHttpClient.Builder.ssrfProtected(): OkHttpClient.Builder = this
    .dns(SsrfProtectedDns())
    .proxySelector(NoProxySelector)
    .retryOnConnectionFailure(false)
    .addNetworkInterceptor(SsrfNetworkInterceptor)

/**
 * The actual backstop for SSRF protection: runs after the TCP connection is established,
 * once per real network exchange (which includes every redirect hop when
 * `followRedirects` is on), and checks the address OkHttp actually connected to.
 *
 * This is necessary, not just defense-in-depth, because OkHttp skips [okhttp3.Dns.lookup]
 * entirely when a URL's host is already a literal IP address - confirmed empirically (see
 * StreamBadgeConfigServerTest) - so a literal-IP sourceUrl, or a redirect Location header
 * that's itself a literal IP, would otherwise never be checked at all. Because this reads
 * the already-connected socket's address rather than parsing the URL string, it's also
 * immune by construction to alternate IPv4 literal encodings (decimal-integer, octal,
 * hex, shortened forms like "127.1") - whatever string OkHttp or the platform resolved to
 * get a socket, [java.net.InetAddress]'s own byte-level checks apply to the real address,
 * not to how it was spelled.
 */
object SsrfNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val connectedAddress = chain.connection()?.socket()?.inetAddress
        if (connectedAddress != null && connectedAddress.isDisallowedForSsrf()) {
            throw UnknownHostException(
                "Refusing to connect to '${connectedAddress.hostAddress}' - only " +
                    "loopback/private/link-local addresses were reachable"
            )
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Routes SSRF-protected requests directly, never through a system/network proxy.
 * Besides being unnecessary for these fetches, letting the platform's default
 * ProxySelector decide routing would add an unrelated, uncontrolled side channel this
 * check has no visibility into (and, on at least one dev machine, made requests to
 * private-looking addresses noticeably slower without changing the outcome).
 */
object NoProxySelector : ProxySelector() {
    private val direct = listOf(Proxy.NO_PROXY)
    override fun select(uri: URI?): List<Proxy> = direct
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
}
