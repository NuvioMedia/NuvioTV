package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CatalogRow(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val items: List<MetaPreview>,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100
) {
    val apiType: String
        get() = type.toApiString(rawType)
}
