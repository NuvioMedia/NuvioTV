package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AddonManifestDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "version") val version: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "logo") val logo: String? = null,
    @Json(name = "background") val background: String? = null,
    @Json(name = "catalogs") val catalogs: List<CatalogDescriptorDto> = emptyList(),
    @Json(name = "resources") val resources: List<Any> = emptyList(),
    @Json(name = "types") val types: List<String> = emptyList(),
    @Json(name = "idPrefixes") val idPrefixes: List<String> = emptyList(),
    @Json(name = "behaviorHints") val behaviorHints: AddonBehaviorHintsDto? = null,
    @Json(name = "stremioAddonsConfig") val stremioAddonsConfig: StremioAddonsConfigDto? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "configVersion") val configVersion: Long? = null,
    @Json(name = "_timestamp") val timestamp: Long? = null
)

@JsonClass(generateAdapter = true)
data class CatalogDescriptorDto(
    @Json(name = "type") val type: String,
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "extra") val extra: List<Any>? = null,
    @Json(name = "pageSize") val pageSize: Int? = null,
    @Json(name = "showInHome") val showInHome: Boolean? = null,
    @Json(name = "extraSupported") val extraSupported: List<String>? = null,
    @Json(name = "extraRequired") val extraRequired: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class AddonBehaviorHintsDto(
    @Json(name = "configurable") val configurable: Boolean? = null,
    @Json(name = "configurationRequired") val configurationRequired: Boolean? = null,
    @Json(name = "newEpisodeNotifications") val newEpisodeNotifications: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class StremioAddonsConfigDto(
    @Json(name = "issuer") val issuer: String? = null,
    @Json(name = "signature") val signature: String? = null
)
