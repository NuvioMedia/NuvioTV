package com.omnio.tv.data.remote.dto.aiometadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Upstream (cedya77/aiometadata) config payload shapes.
 *
 * The full config object upstream stores is large and still evolving, so most
 * fields are carried as untyped maps; we only pin down what the Android UI
 * reads or writes directly. Treat [extras] as a passthrough bag — read it from
 * upstream, hand it back unchanged on updates.
 */

@JsonClass(generateAdapter = true)
data class AioConfigRequestDto(
    @Json(name = "providers") val providers: Map<String, Boolean> = emptyMap(),
    @Json(name = "providerKeys") val providerKeys: Map<String, String> = emptyMap(),
    @Json(name = "catalogs") val catalogs: List<Map<String, Any?>> = emptyList(),
    @Json(name = "settings") val settings: Map<String, Any?> = emptyMap(),
    @Json(name = "password") val password: String? = null,
)

@JsonClass(generateAdapter = true)
data class AioConfigResponseDto(
    @Json(name = "uuid") val uuid: String,
    @Json(name = "manifestUrl") val manifestUrl: String? = null,
    @Json(name = "providers") val providers: Map<String, Boolean> = emptyMap(),
    @Json(name = "providerKeys") val providerKeys: Map<String, String> = emptyMap(),
    @Json(name = "catalogs") val catalogs: List<Map<String, Any?>> = emptyList(),
    @Json(name = "settings") val settings: Map<String, Any?> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class AioTrustedResponseDto(
    @Json(name = "trusted") val trusted: Boolean,
)
