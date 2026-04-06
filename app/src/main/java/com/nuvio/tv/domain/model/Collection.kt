package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CollectionCatalogSource(
    val addonId: String,
    val type: String,
    val catalogId: String
)

@Immutable
data class CollectionFolder(
    val id: String,
    val title: String,
    val coverImageUrl: String? = null,
    val coverEmoji: String? = null,
    val tileShape: PosterShape = PosterShape.SQUARE,
    val hideTitle: Boolean = false,
    val catalogSources: List<CollectionCatalogSource> = emptyList()
)

@Immutable
data class Collection(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val showAllTab: Boolean = true,
    val folders: List<CollectionFolder> = emptyList()
)
