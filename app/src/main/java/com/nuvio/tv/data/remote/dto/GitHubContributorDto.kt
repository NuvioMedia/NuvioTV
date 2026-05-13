package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubContributorDto(
    val login: String? = null,
    @param:Json(name = "avatar_url") val avatarUrl: String? = null,
    @param:Json(name = "html_url") val htmlUrl: String? = null,
    val contributions: Int? = null,
    val type: String? = null
)

@JsonClass(generateAdapter = true)
data class UniqueContributionsResponseDto(
    val contributors: List<UniqueContributorDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class UniqueContributorDto(
    val name: String? = null,
    val avatar: String? = null,
    val profile: String? = null,
    val mobile: Int? = null,
    val tv: Int? = null,
    val web: Int? = null,
    val total: Int? = null
)
