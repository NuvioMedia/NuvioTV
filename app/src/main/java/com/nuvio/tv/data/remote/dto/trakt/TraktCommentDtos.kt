package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCommentItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "comment") val comment: String? = null,
    @Json(name = "review") val review: Boolean? = null,
    @Json(name = "spoiler") val spoiler: Boolean? = null,
    @Json(name = "user") val user: TraktCommentUserDto? = null,
    @Json(name = "user_stats") val userStats: TraktCommentUserStatsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCommentUserDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktCommentUserStatsDto(
    @Json(name = "rating") val rating: Double? = null
)
