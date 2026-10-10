package com.nuvio.tv.core.usenet

import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class UsenetProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String = "",
    val connections: Int = 20,
    val enabled: Boolean = true,
    /** Equal priorities share article traffic; lower priorities only fill missing articles. */
    val priority: Int = 1
) {
    fun validate() {
        require(name.isNotBlank() && host.isNotBlank()) { "Name and server host are required" }
        require(port in 1..65535 && connections in 1..4096) { "Invalid port or connection limit" }
        require(priority in USENET_PRIORITIES) { "Invalid priority" }
        require(!host.contains(Regex("[\\s/@?#]"))) { "Enter a server host without a URL or port" }
        require(!username.contains(':') && !(username + password).contains(Regex("[\\r\\n\\u0000]"))) {
            "Invalid provider credentials"
        }
        URI(serverUrl()) // Also validates IPv6 and host syntax.
    }

    fun serverUrl(): String {
        val authority = if (host.contains(':')) "[${host.removeSurrounding("[", "]")}]" else host
        val credentials = if (username.isNotEmpty() || password.isNotEmpty()) {
            "${encode(username)}:${encode(password)}@"
        } else ""
        return "${if (tls) "nntps" else "nntp"}://$credentials$authority:$port/$connections?priority=$priority"
    }

    override fun toString() = "UsenetProvider(id=$id, enabled=$enabled)"
}

@Serializable
data class UsenetIndexer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Complete Newznab API endpoint, including /api or a Prowlarr indexer path. */
    val apiUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    /** Equal priorities are searched together; lower priorities only when higher ones find nothing. */
    val priority: Int = 1
) {
    fun validate() {
        val url = apiUrl.toHttpUrlOrNull()
        require(name.isNotBlank() && url != null) { "A name and valid HTTP(S) API URL are required" }
        require(priority in USENET_PRIORITIES) { "Invalid priority" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Use an API URL without user credentials or a fragment"
        }
    }

    override fun toString() = "UsenetIndexer(id=$id, enabled=$enabled)"
}

@Serializable
enum class UsenetSort { QUALITY, LARGEST, SMALLEST, NEWEST, INDEXER }

@Serializable
data class UsenetSourceConfiguration(
    val enabled: Boolean = false,
    val providers: List<UsenetProvider> = emptyList(),
    val indexers: List<UsenetIndexer> = emptyList(),
    val sort: UsenetSort = UsenetSort.QUALITY,
    val minResolution: Int = 0,
    val maxSizeGb: Int = 0,
    val maxAgeDays: Int = 0,
    val maxResults: Int = 50,
    val excludeLowQuality: Boolean = true
) {
    val ready: Boolean get() = enabled && providers.any { it.enabled } && indexers.any { it.enabled }

    /** List order is the hierarchy: by priority, then by the user's order within a priority. */
    fun normalized() = copy(providers = providers.sortedBy { it.priority }, indexers = indexers.sortedBy { it.priority })

    fun validate() {
        require(providers.size <= 64 && indexers.size <= 20) { "Too many providers or indexers" }
        require(providers.map { it.id }.distinct().size == providers.size)
        require(indexers.map { it.id }.distinct().size == indexers.size)
        providers.forEach { it.validate() }
        indexers.forEach { it.validate() }
        require(providers.filter { it.enabled }.sumOf { it.connections } <= 4096) { "Total connections exceed 4096" }
        require(minResolution in listOf(0, 480, 720, 1080, 2160))
        require(maxSizeGb in 0..1000 && maxAgeDays in 0..10000 && maxResults in 1..200)
    }
}

/** 1 is the highest priority. */
val USENET_PRIORITIES = 1..5

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
