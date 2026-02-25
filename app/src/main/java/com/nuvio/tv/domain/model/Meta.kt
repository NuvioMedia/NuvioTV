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
    val links: List<MetaLink>
) {
    val apiType: String
        get() = type.toApiString(rawType)
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
    val logo: String? = null
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
    val runtime: Int? = null // episode runtime in minutes
)

@Immutable
data class MetaLink(
    val name: String,
    val category: String,
    val url: String
)
