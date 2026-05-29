package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TorboxEnvelopeDto<T>(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "detail") val detail: String? = null
)

@JsonClass(generateAdapter = true)
data class TorboxCreateTorrentDataDto(
    @Json(name = "torrent_id") val torrentId: Int? = null,
    @Json(name = "id") val id: Int? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "auth_id") val authId: String? = null
) {
    fun resolvedTorrentId(): Int? = torrentId ?: id
}

@JsonClass(generateAdapter = true)
data class TorboxTorrentDataDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "files") val files: List<TorboxTorrentFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class TorboxTorrentFileDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "absolute_path") val absolutePath: String? = null,
    @Json(name = "mimetype") val mimeType: String? = null,
    @Json(name = "size") val size: Long? = null
) {
    fun displayName(): String = listOfNotNull(shortName, name?.substringAfterLast('/'), absolutePath?.substringAfterLast('/'))
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

data class TorboxCloudItemDto(
    @Json(name = "id") val id: Any? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "download_state") val downloadState: String? = null,
    @Json(name = "progress") val progress: Double? = null,
    @Json(name = "download_progress") val downloadProgress: Double? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "total_size") val totalSize: Long? = null,
    @Json(name = "files") val files: List<TorboxCloudFileDto>? = null
)

data class TorboxCloudFileDto(
    @Json(name = "id") val id: Any? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "absolute_path") val absolutePath: String? = null,
    @Json(name = "mimetype") val mimeType: String? = null,
    @Json(name = "mime_type") val mimeTypeAlt: String? = null,
    @Json(name = "size") val size: Long? = null
)

@JsonClass(generateAdapter = true)
data class TorboxCheckCachedRequestDto(
    @Json(name = "hashes") val hashes: List<String>
)

@JsonClass(generateAdapter = true)
data class TorboxCachedItemDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "hash") val hash: String? = null
)

@JsonClass(generateAdapter = true)
data class TorboxDeviceAuthorizationDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "friendly_verification_url") val friendlyVerificationUrl: String? = null,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TorboxDeviceTokenRequestDto(
    @Json(name = "device_code") val deviceCode: String
)

@JsonClass(generateAdapter = true)
data class TorboxDeviceTokenDto(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null
)
