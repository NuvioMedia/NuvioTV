package com.nuvio.tv.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class CachedDns(private val delegate: Dns) : Dns {
    private val cache = ConcurrentHashMap<String, CachedRecord>()

    private data class CachedRecord(
        val ips: List<InetAddress>,
        val expiresAt: Long
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = cache[hostname]
        if (cached != null && cached.expiresAt > now) {
            return cached.ips
        }

        // Prevent multiple threads from resolving the same hostname concurrently
        synchronized(this) {
            val syncCached = cache[hostname]
            if (syncCached != null && syncCached.expiresAt > now) {
                return syncCached.ips
            }
            
            val ips = delegate.lookup(hostname)
            // Cache DNS lookup for 10 minutes to significantly speed up API requests
            cache[hostname] = CachedRecord(ips, System.currentTimeMillis() + 10 * 60 * 1000L) 
            return ips
        }
    }
}

fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>) = dns(
    CachedDns(
        DnsOverHttps
            .Builder()
            .client(build())
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(
                ips.map { InetAddress.getByName(it) }
            )
            .build()
    )
)

fun OkHttpClient.Builder.addGoogleDns() = addGenericDns(
    "https://dns.google/dns-query",
    listOf("8.8.4.4", "8.8.8.8")
)

fun OkHttpClient.Builder.addCloudFlareDns() = addGenericDns(
    "https://cloudflare-dns.com/dns-query",
    listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
)

fun OkHttpClient.Builder.addAdGuardDns() = addGenericDns(
    "https://dns.adguard.com/dns-query",
    listOf("94.140.14.140", "94.140.14.141")
)

fun OkHttpClient.Builder.addDNSWatchDns() = addGenericDns(
    "https://resolver2.dns.watch/dns-query",
    listOf("84.200.69.80", "84.200.70.40")
)

fun OkHttpClient.Builder.addQuad9Dns() = addGenericDns(
    "https://dns.quad9.net/dns-query",
    listOf("9.9.9.9", "149.112.112.112")
)

fun OkHttpClient.Builder.addDnsSbDns() = addGenericDns(
    "https://doh.dns.sb/dns-query",
    listOf("185.222.222.222", "45.11.45.11")
)

fun OkHttpClient.Builder.addCanadianShieldDns() = addGenericDns(
    "https://private.canadianshield.cira.ca/dns-query",
    listOf("149.112.121.10", "149.112.122.10")
)
