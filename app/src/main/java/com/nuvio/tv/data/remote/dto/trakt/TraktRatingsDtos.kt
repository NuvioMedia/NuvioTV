package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRatingItemDto(
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingsAddRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingMovieRequestItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingMovieRequestItemDto(
    @Json(name = "rating") val rating: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto
)

@JsonClass(generateAdapter = true)
data class TraktRatingsAddResponseDto(
    @Json(name = "added") val added: TraktListMutationCountDto? = null,
    @Json(name = "not_found") val notFound: TraktListMutationNotFoundDto? = null
)
