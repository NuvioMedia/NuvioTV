package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CollectionCatalogSource(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

@Immutable
sealed interface CollectionSource

@Immutable
data class AddonCatalogCollectionSource(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
) : CollectionSource

@Immutable
data class TmdbCollectionSource(
    val sourceType: TmdbCollectionSourceType,
    val title: String,
    val tmdbId: Int? = null,
    val mediaType: TmdbCollectionMediaType = TmdbCollectionMediaType.MOVIE,
    val sortBy: String = TmdbCollectionSort.POPULAR_DESC.value,
    val filters: TmdbCollectionFilters = TmdbCollectionFilters()
) : CollectionSource

enum class TmdbCollectionSourceType {
    LIST,
    COLLECTION,
    COMPANY,
    NETWORK,
    DISCOVER
}

enum class TmdbCollectionMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv")
}

enum class TmdbCollectionSort(val value: String) {
    ORIGINAL("original"),
    POPULAR_DESC("popularity.desc"),
    VOTE_AVERAGE_DESC("vote_average.desc"),
    RELEASE_DATE_DESC("primary_release_date.desc"),
    FIRST_AIR_DATE_DESC("first_air_date.desc")
}

@Immutable
data class TmdbCollectionFilters(
    val withGenres: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null,
    val voteAverageGte: Double? = null,
    val voteAverageLte: Double? = null,
    val voteCountGte: Int? = null,
    val withOriginalLanguage: String? = null,
    val withOriginCountry: String? = null,
    val withKeywords: String? = null,
    val withCompanies: String? = null,
    val withNetworks: String? = null,
    val year: Int? = null
)

@Immutable
data class CollectionFolder(
    val id: String,
    val title: String,
    val coverImageUrl: String? = null,
    val focusGifUrl: String? = null,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String? = null,
    val tileShape: PosterShape = PosterShape.SQUARE,
    val hideTitle: Boolean = false,
    val sources: List<CollectionSource> = emptyList(),
    val heroBackdropUrl: String? = null,
    val titleLogoUrl: String? = null
) {
    val catalogSources: List<CollectionCatalogSource>
        get() = sources.mapNotNull { source ->
            (source as? AddonCatalogCollectionSource)?.let {
                CollectionCatalogSource(
                    addonId = it.addonId,
                    type = it.type,
                    catalogId = it.catalogId,
                    genre = it.genre
                )
            }
        }
}

@Immutable
data class Collection(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val showAllTab: Boolean = true,
    val folders: List<CollectionFolder> = emptyList()
)
