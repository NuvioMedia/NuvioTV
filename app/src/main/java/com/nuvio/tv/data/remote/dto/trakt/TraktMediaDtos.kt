package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktIdsDto(
    @Json(name = "trakt") val trakt: Int? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktEpisodeDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)
