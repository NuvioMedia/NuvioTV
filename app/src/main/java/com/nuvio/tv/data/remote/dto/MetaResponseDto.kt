package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetaResponseDto(
    @Json(name = "meta") val meta: MetaDto? = null
)

@JsonClass(generateAdapter = true)
data class MetaDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "name") val name: String,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "posterShape") val posterShape: String? = null,
    @Json(name = "background") val background: String? = null,
    @Json(name = "logo") val logo: String? = null,
    @Json(name = "landscapePoster") val landscapePoster: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "releaseInfo") val releaseInfo: String? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "imdbRating") val imdbRating: String? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "runtime") val runtime: String? = null,
    // Stremio addons are inconsistent here (string vs list). Keep it tolerant.
    @Json(name = "director") val director: Any? = null,
    // Addons are inconsistent: may be `writer` (string/list) or `writers`.
    @Json(name = "writer") val writer: Any? = null,
    @Json(name = "writers") val writers: Any? = null,
    @Json(name = "cast") val cast: Any? = null,
    @Json(name = "videos") val videos: List<VideoDto>? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "awards") val awards: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "links") val links: List<MetaLinkDto>? = null,
    @Json(name = "trailers") val trailers: List<MetaTrailerDto>? = null,
    @Json(name = "behaviorHints") val behaviorHints: MetaBehaviorHintsDto? = null,
    @Json(name = "trailerStreams") val trailerStreams: List<TrailerStreamDto>? = null,
    @Json(name = "app_extras") val appExtras: AppExtrasDto? = null,
    @Json(name = "_rawPosterUrl") val rawPosterUrl: String? = null,
    @Json(name = "_hasPoster") val hasPoster: Boolean? = null,
    @Json(name = "_hasBackground") val hasBackground: Boolean? = null,
    @Json(name = "_hasLandscapePoster") val hasLandscapePoster: Boolean? = null,
    @Json(name = "_hasLogo") val hasLogo: Boolean? = null,
    @Json(name = "_hasLinks") val hasLinks: Boolean? = null,
    @Json(name = "_hasVideos") val hasVideos: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TrailerStreamDto(
    @Json(name = "ytId") val ytId: String? = null
)

@JsonClass(generateAdapter = true)
data class AppExtrasDto(
    @Json(name = "cast") val cast: List<AppExtrasCastMemberDto>? = null,
    @Json(name = "directors") val directors: List<AppExtrasCastMemberDto>? = null,
    @Json(name = "writers") val writers: List<AppExtrasCastMemberDto>? = null,
    @Json(name = "releaseDates") val releaseDates: MetaReleaseDatesEnvelopeDto? = null,
    @Json(name = "certification") val certification: String? = null
)

@JsonClass(generateAdapter = true)
data class AppExtrasCastMemberDto(
    @Json(name = "name") val name: String,
    @Json(name = "character") val character: String? = null,
    @Json(name = "photo") val photo: String? = null,
    @Json(name = "tmdbId") val tmdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class VideoDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "thumbnail") val thumbnail: String? = null,
    @Json(name = "streams") val streams: List<StreamDto>? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "runtime") val runtime: String? = null,
    @Json(name = "available") val available: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class MetaLinkDto(
    @Json(name = "name") val name: String,
    @Json(name = "category") val category: String,
    @Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class MetaTrailerDto(
    @Json(name = "source") val source: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "ytId") val ytId: String? = null,
    @Json(name = "lang") val lang: String? = null
)

@JsonClass(generateAdapter = true)
data class MetaBehaviorHintsDto(
    @Json(name = "defaultVideoId") val defaultVideoId: String? = null,
    @Json(name = "hasScheduledVideos") val hasScheduledVideos: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class MetaReleaseDatesEnvelopeDto(
    @Json(name = "results") val results: List<MetaReleaseDateCountryDto>? = null
)

@JsonClass(generateAdapter = true)
data class MetaReleaseDateCountryDto(
    @Json(name = "iso_3166_1") val iso31661: String,
    @Json(name = "release_dates") val releaseDates: List<MetaReleaseDateDto>? = null
)

@JsonClass(generateAdapter = true)
data class MetaReleaseDateDto(
    @Json(name = "certification") val certification: String? = null,
    @Json(name = "descriptors") val descriptors: List<String>? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "note") val note: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "type") val type: Int? = null
)
