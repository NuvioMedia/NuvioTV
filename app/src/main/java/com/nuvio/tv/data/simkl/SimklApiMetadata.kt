package com.nuvio.tv.data.simkl

import com.nuvio.tv.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SimklApiConfiguration(
    val clientId: String,
    val appName: String,
    val appVersion: String,
    val baseUrl: String = "https://api.simkl.com"
)

fun buildSimklApiUrl(
    configuration: SimklApiConfiguration,
    path: String,
    query: Map<String, String> = emptyMap()
): String {
    val normalizedPath = path.trim().let { value -> if (value.startsWith('/')) value else "/$value" }
    val builder = configuration.baseUrl.toHttpUrl().newBuilder().encodedPath(normalizedPath)
    query.filterKeys { it !in SIMKL_REQUIRED_QUERY_KEYS }.forEach { (key, value) ->
        builder.addQueryParameter(key, value)
    }
    builder.addQueryParameter("client_id", configuration.clientId)
    builder.addQueryParameter("app-name", configuration.appName)
    builder.addQueryParameter("app-version", configuration.appVersion)
    return builder.build().toString()
}

fun simklRequestHeaders(
    configuration: SimklApiConfiguration,
    accessToken: String? = null,
    contentTypeJson: Boolean = false
): Map<String, String> = buildMap {
    put("User-Agent", "$SIMKL_USER_AGENT_APP_NAME/${configuration.appVersion}")
    put("Accept", "application/json")
    accessToken?.trim()?.takeIf(String::isNotBlank)?.let { token ->
        put("Authorization", "Bearer $token")
    }
    if (contentTypeJson) put("Content-Type", "application/json")
}

fun defaultSimklApiConfiguration(): SimklApiConfiguration = SimklApiConfiguration(
    clientId = BuildConfig.SIMKL_CLIENT_ID,
    appName = BuildConfig.SIMKL_APP_NAME.ifBlank { "nuvio" },
    appVersion = BuildConfig.VERSION_NAME.ifBlank { "dev" }
)

private val SIMKL_REQUIRED_QUERY_KEYS = setOf("client_id", "app-name", "app-version")
private const val SIMKL_USER_AGENT_APP_NAME = "NuvioTV"
