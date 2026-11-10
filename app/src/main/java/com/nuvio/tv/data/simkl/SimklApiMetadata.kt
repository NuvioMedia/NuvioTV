package com.nuvio.tv.data.simkl

import com.nuvio.tv.BuildConfig
import java.net.URLEncoder
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

enum class SimklContentType(val mediaType: String) {
    JSON("application/json"),
    FORM("application/x-www-form-urlencoded")
}

fun simklRequestHeaders(
    configuration: SimklApiConfiguration,
    accessToken: String? = null,
    contentType: SimklContentType? = null
): Map<String, String> = buildMap {
    put("User-Agent", "$SIMKL_USER_AGENT_APP_NAME/${configuration.appVersion}")
    put("Accept", "application/json")
    accessToken?.trim()?.takeIf(String::isNotBlank)?.let { token ->
        put("Authorization", "Bearer $token")
    }
    contentType?.let { put("Content-Type", it.mediaType) }
}

/** Encodes [fields] as an `application/x-www-form-urlencoded` body, as AUTH V2 expects. */
fun simklFormBody(fields: Map<String, String>): String = fields.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
}

fun defaultSimklApiConfiguration(): SimklApiConfiguration = SimklApiConfiguration(
    clientId = BuildConfig.SIMKL_CLIENT_ID,
    appName = BuildConfig.SIMKL_APP_NAME.ifBlank { "nuvio" },
    appVersion = BuildConfig.VERSION_NAME.ifBlank { "dev" }
)

private val SIMKL_REQUIRED_QUERY_KEYS = setOf("client_id", "app-name", "app-version")
private const val SIMKL_USER_AGENT_APP_NAME = "NuvioTV"
