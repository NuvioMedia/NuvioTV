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
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "tagline") val tagline: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "rating") val rating: Double? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "certification") val certification: String? = null,
    @Json(name = "languages") val languages: List<String>? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "images") val images: TraktImagesDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "tagline") val tagline: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "network") val network: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "rating") val rating: Double? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "certification") val certification: String? = null,
    @Json(name = "languages") val languages: List<String>? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "images") val images: TraktImagesDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktImagesDto(
    @Json(name = "fanart") val fanart: List<String>? = null,
    @Json(name = "poster") val poster: List<String>? = null,
    @Json(name = "logo") val logo: List<String>? = null,
    @Json(name = "clearart") val clearart: List<String>? = null,
    @Json(name = "banner") val banner: List<String>? = null,
    @Json(name = "thumb") val thumb: List<String>? = null
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
