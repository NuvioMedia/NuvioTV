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
