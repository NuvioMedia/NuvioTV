package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

private const val DUPLICATE_PAGE_ADVANCE_LIMIT = 3

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
    val skipStep: Int = 100,
    val nextSkip: Int = 0,
    val consecutiveDuplicatePages: Int = 0,
    val extraArgs: Map<String, String> = emptyMap()
) {
    val apiType: String
        get() = type.toApiString(rawType)
}

fun CatalogRow.stableKey(): String {
    return catalogRowStableKey(addonId, addonBaseUrl, apiType, catalogId)
}

fun CatalogRow.legacyKey(): String {
    return catalogRowLegacyKey(addonId, apiType, catalogId)
}

fun CatalogRow.stableItemKey(index: Int): String {
    return "${stableKey()}_$index"
}

fun catalogRowStableKey(
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String
): String {
    val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
    val baseUrlKey = "${normalizedBaseUrl.hashCode()}_${normalizedBaseUrl.length}"
    return "${addonId}_${baseUrlKey}_${type}_${catalogId}"
}

fun catalogRowLegacyKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

fun CatalogRow.nextCatalogSkip(): Int {
    val fallback = (currentPage + 1) * skipStep
    return if (nextSkip > 0) nextSkip else fallback
}

fun CatalogRow.mergeCatalogPage(
    page: CatalogRow,
    incomingItems: List<MetaPreview> = page.items
): CatalogRow {
    val existingIds = items.asSequence()
        .map { "${it.apiType}:${it.id}" }
        .toHashSet()
    val newItems = incomingItems.filter { item ->
        "${item.apiType}:${item.id}" !in existingIds
    }
    val mergedItems = items + newItems
    val requestedSkip = nextCatalogSkip()
    val duplicatePageCount = if (newItems.isEmpty() && page.items.isNotEmpty()) {
        consecutiveDuplicatePages + 1
    } else {
        0
    }
    val advancedSkip = if (newItems.isEmpty()) {
        if (page.nextSkip > requestedSkip) page.nextSkip else requestedSkip + page.items.size.coerceAtLeast(1)
    } else {
        page.nextSkip
    }
    val hasMore = when {
        page.items.isEmpty() -> false
        newItems.isNotEmpty() -> page.hasMore
        duplicatePageCount < DUPLICATE_PAGE_ADVANCE_LIMIT -> page.hasMore && advancedSkip > requestedSkip
        else -> false
    }

    return page.copy(
        items = mergedItems,
        hasMore = hasMore,
        currentPage = currentPage + 1,
        nextSkip = advancedSkip,
        consecutiveDuplicatePages = duplicatePageCount
    )
}
