package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Meta(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val status: String? = null,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String?,
    val director: List<String>,
    val writer: List<String> = emptyList(),
    val cast: List<String>,
    val castMembers: List<MetaCastMember> = emptyList(),
    val videos: List<Video>,
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val ageRating: String? = null,
    val country: String?,
    val awards: String?,
    val language: String?,
    val links: List<MetaLink>,
    val trailerYtIds: List<String> = emptyList(),
    val imdbId: String? = null,
    val slug: String? = null,
    val released: String? = null,
    val landscapePoster: String? = null,
    val rawPosterUrl: String? = null,
    val behaviorHints: MetaBehaviorHints? = null,
    val trailers: List<MetaTrailer> = emptyList(),
    val releaseDates: List<MetaReleaseDateCountry> = emptyList(),
    val hasPoster: Boolean? = null,
    val hasBackground: Boolean? = null,
    val hasLandscapePoster: Boolean? = null,
    val hasLogo: Boolean? = null,
    val hasLinks: Boolean? = null,
    val hasVideos: Boolean? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster
}

@Immutable
data class MetaCastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null
)

@Immutable
data class MetaCompany(
    val name: String,
    val logo: String? = null,
    val tmdbId: Int? = null
)

@Immutable
data class Video(
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val streams: List<Stream> = emptyList(),
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val runtime: Int? = null, // episode runtime in minutes
    val available: Boolean? = null
)

@Immutable
data class MetaLink(
    val name: String,
    val category: String,
    val url: String
)

@Immutable
data class MetaBehaviorHints(
    val defaultVideoId: String? = null,
    val hasScheduledVideos: Boolean? = null
)

@Immutable
data class MetaTrailer(
    val source: String? = null,
    val type: String? = null,
    val name: String? = null,
    val ytId: String? = null,
    val lang: String? = null
)

@Immutable
data class MetaReleaseDateCountry(
    val countryCode: String,
    val releaseDates: List<MetaReleaseDate> = emptyList()
)

@Immutable
data class MetaReleaseDate(
    val certification: String? = null,
    val descriptors: List<String> = emptyList(),
    val languageCode: String? = null,
    val note: String? = null,
    val releaseDate: String? = null,
    val type: Int? = null
)
