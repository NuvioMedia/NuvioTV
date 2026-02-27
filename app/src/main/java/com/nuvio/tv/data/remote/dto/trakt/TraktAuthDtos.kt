package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktDeviceCodeRequestDto(
    @Json(name = "client_id") val clientId: String
)

@JsonClass(generateAdapter = true)
data class TraktDeviceCodeResponseDto(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int
)

@JsonClass(generateAdapter = true)
data class TraktDeviceTokenRequestDto(
    @Json(name = "code") val code: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

@JsonClass(generateAdapter = true)
data class TraktRefreshTokenRequestDto(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String,
    @Json(name = "redirect_uri") val redirectUri: String,
    @Json(name = "grant_type") val grantType: String = "refresh_token"
)

@JsonClass(generateAdapter = true)
data class TraktRevokeRequestDto(
    @Json(name = "token") val token: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String
)

@JsonClass(generateAdapter = true)
data class TraktTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "created_at") val createdAt: Long
)

@JsonClass(generateAdapter = true)
data class TraktUserSettingsResponseDto(
    @Json(name = "user") val user: TraktUserDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserStatsResponseDto(
    @Json(name = "movies") val movies: TraktUserStatsCategoryDto? = null,
    @Json(name = "shows") val shows: TraktUserStatsCategoryDto? = null,
    @Json(name = "episodes") val episodes: TraktUserStatsCategoryDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserStatsCategoryDto(
    @Json(name = "watched") val watched: Int? = null,
    @Json(name = "minutes") val minutes: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)
