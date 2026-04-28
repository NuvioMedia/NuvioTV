package com.omnio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbySystemInfoDto(
    @Json(name = "ServerName") val serverName: String? = null,
    @Json(name = "Version") val version: String? = null,
    @Json(name = "Id") val id: String? = null
)
