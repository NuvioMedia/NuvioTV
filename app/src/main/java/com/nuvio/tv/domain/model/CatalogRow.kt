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
    val extraArgs: Map<String, String> = emptyMap(),
    val pluginSource: PluginSourceRef? = null,
    val pluginNextPageToken: String? = null
) {
    val apiType: String
        get() = rawType.trim().ifBlank { type.toApiString() }
}

fun CatalogRow.stableKey(): String {
    pluginSource?.let { return pluginCatalogRowKey(it, catalogId, apiType) }
    return catalogRowStableKey(addonId, addonBaseUrl, apiType, catalogId)
}

fun CatalogRow.legacyKey(): String {
    if (pluginSource != null) return stableKey()
    return catalogRowLegacyKey(addonId, apiType, catalogId)
}

/**
 * Identity-based item key, so a key follows its item when the list shifts instead of staying
 * pinned to a slot. [occurrence] disambiguates an id appearing twice in the same row.
 */
fun CatalogRow.stableItemKey(item: MetaPreview, occurrence: Int = 0): String {
    val identity = "${stableKey()}_${item.apiType}:${item.id}"
    return if (occurrence == 0) identity else "$identity#$occurrence"
}

/** Item keys for the whole row, aligned with [items], for callers that only have an index. */
fun CatalogRow.stableItemKeys(): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val identity = "${item.apiType}:${item.id}"
        val occurrence = seen.getOrDefault(identity, 0)
        seen[identity] = occurrence + 1
        stableItemKey(item, occurrence)
    }
}

fun catalogRowStableKey(
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String
): String {
    val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
    val baseUrlKey = "${normalizedBaseUrl.hashCode()}_${normalizedBaseUrl.length}"
    return "${addonId}_${baseUrlKey}_${catalogTypeKey(type)}_${catalogId}"
}

fun catalogRowLegacyKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${catalogTypeKey(type)}_${catalogId}"
}

fun CatalogRow.nextCatalogSkip(): Int {
    require(pluginSource == null) { "Plugin catalogs use opaque page tokens, not addon skip offsets" }
    val fallback = (currentPage + 1) * skipStep
    return if (nextSkip > 0) nextSkip else fallback
}

fun CatalogRow.mergeCatalogPage(
    page: CatalogRow,
    incomingItems: List<MetaPreview> = page.items
): CatalogRow {
    require(pluginSource == null && page.pluginSource == null) {
        "Plugin pages must not use addon pagination"
    }
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

/** Merges an opaque-token plugin page without applying addon skip semantics. */
fun CatalogRow.mergePluginCatalogPage(page: CatalogRow): CatalogRow {
    require(pluginSource != null && page.pluginSource == pluginSource) {
        "Plugin pages must come from the same plugin source"
    }
    val existingIds = items.mapTo(mutableSetOf()) { "${it.apiType}:${it.id}" }
    val newItems = page.items.filter { existingIds.add("${it.apiType}:${it.id}") }
    val nextToken = page.pluginNextPageToken
    val tokenAdvanced = nextToken != null && nextToken != pluginNextPageToken
    return page.copy(
        items = items + newItems,
        currentPage = currentPage + 1,
        hasMore = page.hasMore && tokenAdvanced,
        pluginNextPageToken = nextToken
    )
}
