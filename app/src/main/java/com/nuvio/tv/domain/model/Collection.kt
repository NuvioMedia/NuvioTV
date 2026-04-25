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
data class CollectionFolder(
    val id: String,
    val title: String,
    val coverImageUrl: String? = null,
    val focusGifUrl: String? = null,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String? = null,
    val tileShape: PosterShape = PosterShape.SQUARE,
    val hideTitle: Boolean = false,
    val catalogSources: List<CollectionCatalogSource> = emptyList(),
    val heroBackdropUrl: String? = null,
    val titleLogoUrl: String? = null
)

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
