package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PremiumizeAccountInfoDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "customer_id") val customerId: String? = null,
    @Json(name = "premium_until") val premiumUntil: Long? = null,
    @Json(name = "limit_used") val limitUsed: Double? = null,
    @Json(name = "booster_points") val boosterPoints: Int? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeDirectDownloadDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "content") val content: List<PremiumizeDirectDownloadFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeDirectDownloadFileDto(
    @Json(name = "path") val path: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "link") val link: String? = null
) {
    fun displayName(): String =
        path.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { path.orEmpty() }
}

@JsonClass(generateAdapter = true)
data class PremiumizeCacheCheckDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "response") val response: List<Boolean>? = null,
    @Json(name = "filename") val filename: List<String?>? = null,
    @Json(name = "filesize") val filesize: List<Long?>? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeItemListAllDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "files") val files: List<PremiumizeCloudFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeCloudFileDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "path") val path: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "link") val link: String? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeItemDetailsDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "created_at") val createdAt: Long? = null,
    @Json(name = "folder_id") val folderId: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "link") val link: String? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeDeviceAuthorizationDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_uri") val verificationUri: String? = null,
    @Json(name = "verification_uri_complete") val verificationUriComplete: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)

@JsonClass(generateAdapter = true)
data class PremiumizeDeviceTokenDto(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)
