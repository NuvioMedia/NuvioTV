package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCommentDto(
    @Json(name = "id") val id: Long,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "comment") val comment: String? = null,
    @Json(name = "spoiler") val spoiler: Boolean? = null,
    @Json(name = "review") val review: Boolean? = null,
    @Json(name = "likes") val likes: Int? = null,
    @Json(name = "user_stats") val userStats: TraktCommentUserStatsDto? = null,
    @Json(name = "user") val user: TraktCommentUserDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCommentUserStatsDto(
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "play_count") val playCount: Int? = null,
    @Json(name = "completed_count") val completedCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktCommentUserDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "private") val isPrivate: Boolean? = null,
    @Json(name = "vip") val vip: Boolean? = null,
    @Json(name = "vip_ep") val vipEp: Boolean? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktSearchResultDto(
    @Json(name = "type") val type: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)
