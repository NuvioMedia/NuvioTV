package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val installedAddons: List<Addon> = emptyList(),
    val discoverEnabled: Boolean = true,
    val discoverInitialized: Boolean = false,
    val discoverLoading: Boolean = false,
    val discoverLoadingMore: Boolean = false,
    val discoverCatalogs: List<DiscoverCatalog> = emptyList(),
    val selectedDiscoverType: String = "movie",
    val selectedDiscoverCatalogKey: String? = null,
    val selectedDiscoverGenre: String? = null,
    val discoverResults: List<MetaPreview> = emptyList(),
    val pendingDiscoverResults: List<MetaPreview> = emptyList(),
    val discoverHasMore: Boolean = true,
    val discoverPage: Int = 1,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val suggestions: List<String> = emptyList()
)

@Immutable
data class DiscoverCatalog(
    val key: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val genres: List<String>,
    val supportsSkip: Boolean,
    val skipStep: Int
)
