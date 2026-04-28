package com.omnio.tv.domain.model


data class CollectionCatalogSource(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

data class CollectionFolder(
    val id: String,
    val title: String,
    val coverImageUrl: String? = null,
    val focusGifUrl: String? = null,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String? = null,
    val tileShape: PosterShape = PosterShape.SQUARE,
    val hideTitle: Boolean = false,
    val catalogSources: List<CollectionCatalogSource> = emptyList()
)

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
