package com.omnio.tv.data.remote.dto.aiometadata

import com.omnio.tv.domain.model.AioConfigInnerDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Upstream (cedya77/aiometadata) config payload shapes.
 *
 * Every save/update/load call expects a nested body keyed on `config` and a
 * user-chosen `password`; the inner `config` object is what the UI edits. We
 * pin down only the fields the Android client writes directly and carry the
 * rest (catalogs, settings) as untyped maps so upstream can add knobs without
 * forcing a DTO bump.
 *
 * [AioConfigInnerDto] is defined in the domain layer; on the wire it uses a
 * custom Moshi adapter ([AioConfigInnerDtoJsonAdapter]) so the `settings`
 * catch-all serializes as flat root fields — the shape the upstream web
 * `/configure` UI expects.
 */

@JsonClass(generateAdapter = true)
data class AioConfigSaveRequestDto(
    @Json(name = "config") val config: AioConfigInnerDto,
    @Json(name = "password") val password: String,
    @Json(name = "addonPassword") val addonPassword: String? = null,
)

@JsonClass(generateAdapter = true)
data class AioConfigUpdateRequestDto(
    @Json(name = "config") val config: AioConfigInnerDto,
    @Json(name = "password") val password: String,
    @Json(name = "addonPassword") val addonPassword: String? = null,
)

@JsonClass(generateAdapter = true)
data class AioConfigLoadRequestDto(
    @Json(name = "password") val password: String,
    @Json(name = "addonPassword") val addonPassword: String? = null,
)

@JsonClass(generateAdapter = true)
data class AioConfigSaveResponseDto(
    @Json(name = "userUUID") val userUUID: String,
    @Json(name = "installUrl") val installUrl: String? = null,
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "message") val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class AioConfigLoadResponseDto(
    @Json(name = "userUUID") val userUUID: String,
    @Json(name = "config") val config: AioConfigInnerDto,
    @Json(name = "success") val success: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class AioTrustedResponseDto(
    @Json(name = "trusted") val trusted: Boolean,
    @Json(name = "requiresAddonPassword") val requiresAddonPassword: Boolean = false,
)
