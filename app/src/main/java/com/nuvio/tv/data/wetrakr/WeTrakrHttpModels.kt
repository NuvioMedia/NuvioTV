package com.nuvio.tv.data.wetrakr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class WeTrakrConfiguration(
    val clientId: String,
    val appVersion: String,
    val baseUrl: String = "https://api.wetrakr.com"
)

enum class WeTrakrHttpMethod { GET, POST, DELETE }

class WeTrakrHttpRequest(
    val method: WeTrakrHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
    val accessToken: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val limitKey: String = "public",
    val retrySafe: Boolean = method == WeTrakrHttpMethod.GET,
    val returnsRateLimit: Boolean = false
) {
    override fun toString(): String = "WeTrakrHttpRequest(method=$method)"
}

class WeTrakrHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun errorCode(): String? = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject
        when (val error = root?.get("error")) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["code"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z_]{1,64}")) }

    override fun toString(): String = "WeTrakrHttpResponse(status=$status)"
}

class WeTrakrApiException(
    val status: Int? = null,
    val code: String? = null,
    val retryAtEpochMs: Long? = null
) : Exception("WeTrakr request failed${status?.let { " (HTTP $it)" }.orEmpty()}")

class WeTrakrDecodingException : Exception("WeTrakr returned an incomplete response")

fun interface WeTrakrHttpEngine {
    suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse
}
