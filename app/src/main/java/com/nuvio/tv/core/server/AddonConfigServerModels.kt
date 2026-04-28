package com.nuvio.tv.core.server

import java.util.UUID

enum class AddonWebConfigMode(
    val allowAddonManagement: Boolean,
    val allowCatalogManagement: Boolean
) {
    FULL(
        allowAddonManagement = true,
        allowCatalogManagement = true
    ),
    COLLECTIONS_ONLY(
        allowAddonManagement = false,
        allowCatalogManagement = false
    )
}

data class AddonInfo(
    val url: String,
    val name: String,
    val description: String?
)

data class CatalogInfo(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val type: String,
    val isDisabled: Boolean
)

data class CollectionInfo(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: String = "TABBED_GRID",
    val showAllTab: Boolean = true,
    val folders: List<FolderInfo>
)

data class FolderInfo(
    val id: String,
    val title: String,
    val coverImageUrl: String?,
    val focusGifUrl: String?,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String?,
    val tileShape: String,
    val hideTitle: Boolean,
    val heroBackdropUrl: String? = null,
    val titleLogoUrl: String? = null,
    val catalogSources: List<CatalogSourceInfo> = emptyList(),
    val sources: List<CollectionSourceInfo> = catalogSources.map { it.toCollectionSourceInfo() }
)

data class CatalogSourceInfo(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

data class CollectionSourceInfo(
    val provider: String = "addon",
    val addonId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    val tmdbSourceType: String? = null,
    val title: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val sortBy: String? = null,
    val filters: TmdbFiltersInfo? = null
)

data class TmdbFiltersInfo(
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

data class TmdbSourceMetadataRequest(
    val sourceType: String,
    val tmdbId: Int
)

data class TmdbSourceMetadataInfo(
    val title: String? = null,
    val coverImageUrl: String? = null
)

data class TmdbSourceSearchRequest(
    val sourceType: String,
    val query: String
)

data class TmdbSourceSearchResultInfo(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val coverImageUrl: String? = null
)

data class PageState(
    val addons: List<AddonInfo>,
    val catalogs: List<CatalogInfo>,
    val collections: List<CollectionInfo> = emptyList(),
    val disabledCollectionKeys: List<String> = emptyList()
)

data class PendingAddonChange(
    val id: String = UUID.randomUUID().toString(),
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val proposedCollectionsJson: String? = null,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    var status: AddonChangeStatus = AddonChangeStatus.PENDING
)

enum class AddonChangeStatus { PENDING, CONFIRMED, REJECTED }

private fun CatalogSourceInfo.toCollectionSourceInfo() = CollectionSourceInfo(
    provider = "addon",
    addonId = addonId,
    type = type,
    catalogId = catalogId,
    genre = genre
)
