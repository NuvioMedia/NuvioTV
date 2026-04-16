package com.omnio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @Json(name = "tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @Json(name = "html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    val name: String,
    @Json(name = "browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @Json(name = "content_type") val contentType: String? = null
)
