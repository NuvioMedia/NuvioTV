package com.nuvio.tv.core.usenet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class UsenetProviderLive(
    val server: Int,
    val articles: Long,
    val bytes: Long,
    val missing: Long,
    val errors: Long,
    val ttfbMs: Double?
)

data class UsenetLiveStats(
    val providers: List<UsenetProviderLive>,
    val filledArticles: Long,
    val failure: String?,
    val servers: List<String> = emptyList()
) {
    val bytes: Long get() = providers.sumOf { it.bytes }
    val missing: Long get() = providers.sumOf { it.missing }
    val errors: Long get() = providers.sumOf { it.errors }
    val ttfbMs: Double? get() = providers.filter { it.ttfbMs != null }.maxByOrNull { it.bytes }?.ttfbMs
    val articles: Long get() = providers.sumOf { it.articles }

    val completion: Double? get() {
        val asked = articles + missing
        if (asked <= 0) return null
        return Math.round(articles * 1000.0 / asked) / 10.0
    }

    fun server(provider: UsenetProviderLive): String? = servers.getOrNull(provider.server)

    val usedProviders: List<UsenetProviderLive> get() = providers.filter { it.articles > 0 }.sortedByDescending { it.bytes }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): UsenetLiveStats? = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0L
            val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { item ->
                val provider = item as? JsonObject ?: return@mapNotNull null
                UsenetProviderLive(
                    server = provider["server"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                    articles = provider.long("articles"),
                    bytes = provider.long("bytes"),
                    missing = provider.long("missing"),
                    errors = provider.long("errors"),
                    ttfbMs = provider["ttfbMs"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
                )
            }
            UsenetLiveStats(
                providers = providers,
                filledArticles = root.long("filledArticles"),
                failure = (root["streamFailure"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }
}
