package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CatalogResponseDto(
    @Json(name = "metas") val metas: List<MetaPreviewDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class MetaPreviewDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "name") val name: String,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "posterShape") val posterShape: String? = null,
    @Json(name = "background") val background: String? = null,
    @Json(name = "logo") val logo: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "releaseInfo") val releaseInfo: String? = null,
    @Json(name = "imdbRating") val imdbRating: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "runtime") val runtime: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "landscapePoster") val landscapePoster: String? = null,
    @Json(name = "_rawPosterUrl") val rawPosterUrl: String? = null,
    @Json(name = "director") val director: Any? = null,
    @Json(name = "writer") val writer: Any? = null,
    @Json(name = "writers") val writers: Any? = null,
    @Json(name = "links") val links: List<MetaLinkDto>? = null,
    @Json(name = "trailers") val trailers: List<MetaTrailerDto>? = null,
    @Json(name = "behaviorHints") val behaviorHints: MetaBehaviorHintsDto? = null,
    @Json(name = "trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null
)
