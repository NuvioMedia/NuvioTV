package com.nuvio.tv.core.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

private class FakeDns(private val addressesByHost: Map<String, List<String>>) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val literals = addressesByHost[hostname] ?: throw UnknownHostException(hostname)
        return literals.map { InetAddress.getByName(it) }
    }
}

class SsrfProtectedDnsTest {

    private fun dnsFor(host: String, vararg literals: String) =
        SsrfProtectedDns(FakeDns(mapOf(host to literals.toList())))

    // Public addresses - the "legitimate addon" regression matrix (FASE 7)

    @Test
    fun allowsPublicIPv4() {
        val dns = dnsFor("api.example.com", "93.184.216.34")
        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), dns.lookup("api.example.com"))
    }

    @Test
    fun allowsPublicIPv4RegardlessOfScheme() {
        // SsrfProtectedDns operates purely on the resolved address, not the URL scheme,
        // so HTTP and HTTPS addons are both allowed to reach the same public host.
        val dns = dnsFor("addon.example.org", "1.2.3.4")
        assertEquals(listOf(InetAddress.getByName("1.2.3.4")), dns.lookup("addon.example.org"))
    }

    @Test
    fun allowsPublicIPv6() {
        val dns = dnsFor("v6.example.com", "2606:2800:220:1:248:1893:25c8:1946")
        assertEquals(1, dns.lookup("v6.example.com").size)
    }

    // Blocked ranges

    @Test
    fun blocksLocalhostHostname() {
        val dns = dnsFor("localhost", "127.0.0.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("localhost") }
    }

    @Test
    fun blocksLoopbackIPv4() {
        val dns = dnsFor("evil.example.com", "127.0.0.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksPrivate192Range() {
        val dns = dnsFor("evil.example.com", "192.168.1.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksPrivate10Range() {
        val dns = dnsFor("evil.example.com", "10.0.0.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksPrivate172Range() {
        val lower = dnsFor("evil.example.com", "172.16.0.1")
        val upper = dnsFor("evil.example.com", "172.31.255.255")
        assertThrows(UnknownHostException::class.java) { lower.lookup("evil.example.com") }
        assertThrows(UnknownHostException::class.java) { upper.lookup("evil.example.com") }
    }

    @Test
    fun allows172RangeJustOutsidePrivateBlock() {
        // 172.15.x.x and 172.32.x.x are public - confirms the private-range check doesn't
        // over-block adjacent public ranges.
        val below = dnsFor("addon.example.com", "172.15.255.255")
        val above = dnsFor("addon.example.com", "172.32.0.1")
        assertEquals(1, below.lookup("addon.example.com").size)
        assertEquals(1, above.lookup("addon.example.com").size)
    }

    @Test
    fun blocksIPv6Loopback() {
        val dns = dnsFor("evil.example.com", "::1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksIPv6UniqueLocal() {
        val fc = dnsFor("evil.example.com", "fc00::1")
        val fd = dnsFor("evil.example.com", "fd12:3456:789a::1")
        assertThrows(UnknownHostException::class.java) { fc.lookup("evil.example.com") }
        assertThrows(UnknownHostException::class.java) { fd.lookup("evil.example.com") }
    }

    @Test
    fun blocksLinkLocal() {
        val v4 = dnsFor("evil.example.com", "169.254.1.1")
        val v6 = dnsFor("evil.example.com", "fe80::1")
        assertThrows(UnknownHostException::class.java) { v4.lookup("evil.example.com") }
        assertThrows(UnknownHostException::class.java) { v6.lookup("evil.example.com") }
    }

    @Test
    fun blocksWildcardAddress() {
        val dns = dnsFor("evil.example.com", "0.0.0.0")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksMulticast() {
        val dns = dnsFor("evil.example.com", "224.0.0.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    // DNS-rebinding shaped cases: a hostname that resolves to a mix of public and
    // private addresses. OkHttp tries resolved addresses in order and calls
    // Dns.lookup() again on every redirect hop, so filtering (rather than
    // all-or-nothing rejection) here still leaves no way to reach the private address
    // through this hostname, while a legitimate multi-A-record public host still works.

    @Test
    fun filtersMixedPublicAndPrivateToPublicOnly() {
        val dns = dnsFor("mixed.example.com", "203.0.113.5", "10.0.0.1")
        assertEquals(listOf(InetAddress.getByName("203.0.113.5")), dns.lookup("mixed.example.com"))
    }

    @Test
    fun rejectsWhenAllResolvedAddressesArePrivate() {
        val dns = dnsFor("rebind.example.com", "10.0.0.1", "192.168.0.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("rebind.example.com") }
    }

    // Redirect-chain simulation: OkHttp calls Dns.lookup() independently for every hop
    // (see class doc on SsrfProtectedDns), so a single stateless instance must enforce
    // the same check on each call regardless of call order or prior results.

    @Test
    fun enforcesCheckIndependentlyAcrossSimulatedRedirectHops() {
        val dns = SsrfProtectedDns(
            FakeDns(
                mapOf(
                    "public-start.example.com" to listOf("203.0.113.10"),
                    "private-redirect-target.example.com" to listOf("10.0.0.5"),
                    "public-redirect-target.example.com" to listOf("203.0.113.20")
                )
            )
        )
        // Hop 1: public host - allowed.
        assertEquals(1, dns.lookup("public-start.example.com").size)
        // Hop 2 (simulated redirect to a private host): still blocked on this same
        // Dns instance, proving there's no "already validated this request" bypass.
        assertThrows(UnknownHostException::class.java) {
            dns.lookup("private-redirect-target.example.com")
        }
        // Hop 2 (simulated redirect to another public host): still allowed.
        assertEquals(1, dns.lookup("public-redirect-target.example.com").size)
    }

    // Exact redirect matrix requested for the CollectionManagementViewModel/Coil audit:
    // a public origin redirecting to each disallowed destination class must still be
    // blocked on the redirect hop, and a public->public redirect must still work. OkHttp
    // calls Dns.lookup() again for the Location header's host on every hop (same
    // mechanism already proven above), so this is the same check, just spelled out
    // per-destination for traceability against that request.

    @Test
    fun redirectPublicToPublicIsAllowed() {
        val dns = SsrfProtectedDns(
            FakeDns(mapOf("public-a.example.com" to listOf("203.0.113.1"), "public-b.example.com" to listOf("203.0.113.2")))
        )
        assertEquals(1, dns.lookup("public-a.example.com").size)
        assertEquals(1, dns.lookup("public-b.example.com").size)
    }

    @Test
    fun redirectPublicToLocalhostHostnameIsBlocked() {
        val dns = SsrfProtectedDns(
            FakeDns(mapOf("public-a.example.com" to listOf("203.0.113.1"), "localhost" to listOf("127.0.0.1")))
        )
        assertEquals(1, dns.lookup("public-a.example.com").size)
        assertThrows(UnknownHostException::class.java) { dns.lookup("localhost") }
    }

    @Test
    fun redirectPublicToLoopbackIpIsBlocked() {
        val dns = SsrfProtectedDns(
            FakeDns(mapOf("public-a.example.com" to listOf("203.0.113.1"), "redirect-target.example.com" to listOf("127.0.0.1")))
        )
        assertEquals(1, dns.lookup("public-a.example.com").size)
        assertThrows(UnknownHostException::class.java) { dns.lookup("redirect-target.example.com") }
    }

    @Test
    fun redirectPublicToPrivateIpIsBlocked() {
        val dns = SsrfProtectedDns(
            FakeDns(mapOf("public-a.example.com" to listOf("203.0.113.1"), "redirect-target.example.com" to listOf("192.168.50.1")))
        )
        assertEquals(1, dns.lookup("public-a.example.com").size)
        assertThrows(UnknownHostException::class.java) { dns.lookup("redirect-target.example.com") }
    }

    // Alternate IPv4 literal encodings some naive SSRF filters miss (decimal-integer,
    // octal, hex, and shortened "127.1" forms of 127.0.0.1). isDisallowedForSsrf() checks
    // byte-level InetAddress properties, not the original string, so this only proves
    // anything if InetAddress.getByName() actually parses these to the loopback address
    // in the first place - which is exactly what these confirm empirically rather than
    // assume.

    @Test
    fun blocksShortenedLoopbackForm() {
        val dns = dnsFor("evil.example.com", "127.1")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }

    @Test
    fun blocksDecimalIntegerLoopbackForm() {
        // 2130706433 == 127.0.0.1 as a big-endian 32-bit integer.
        val parsed = runCatching { InetAddress.getByName("2130706433") }.getOrNull()
        if (parsed == null) {
            // This JVM's InetAddress doesn't parse decimal-integer literals as an IP
            // address at all (it falls through to a real DNS lookup for the literal
            // string, which just fails to resolve) - nothing for isDisallowedForSsrf()
            // to check, and no route through the parser exists for this form either.
            return
        }
        val dns = dnsFor("evil.example.com", "2130706433")
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.example.com") }
    }
}
