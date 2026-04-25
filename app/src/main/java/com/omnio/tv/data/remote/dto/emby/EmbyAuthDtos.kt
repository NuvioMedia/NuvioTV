package com.omnio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbyAuthByNameRequestDto(
    @Json(name = "Username") val username: String,
    @Json(name = "Pw") val pw: String
)

@JsonClass(generateAdapter = true)
data class EmbyAuthResponseDto(
    @Json(name = "User") val user: EmbyUserDto,
    @Json(name = "AccessToken") val accessToken: String,
    @Json(name = "ServerId") val serverId: String? = null
)
