package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Job

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                "${addon.id}|${addon.baseUrl}|${catalog.apiType}|${catalog.id}|${catalog.name}|${catalog.showInHome}|${catalog.hasExplicitShowInHome}"
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

private fun HomeViewModel.reindexCatalogRow(
    key: String,
    previousRow: CatalogRow?,
    updatedRow: CatalogRow?
) {
    previousRow?.items?.forEach { item ->
        val keys = catalogItemKeyIndex[item.id] ?: return@forEach
        keys.remove(key)
        if (keys.isEmpty()) {
            catalogItemKeyIndex.remove(item.id)
        }
    }

    updatedRow?.items?.forEach { item ->
        catalogItemKeyIndex.getOrPut(item.id) { LinkedHashSet() }.add(key)
    }
}

internal fun HomeViewModel.hasAnyCatalogRows(): Boolean = synchronized(catalogStateLock) {
    catalogsMap.isNotEmpty()
}

internal fun HomeViewModel.isCatalogOrderEmpty(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isEmpty()
}

internal fun HomeViewModel.hasCatalogOrderEntries(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isNotEmpty()
}

internal fun HomeViewModel.readCatalogRow(key: String): CatalogRow? = synchronized(catalogStateLock) {
    catalogsMap[key]
}

internal fun HomeViewModel.replaceCatalogRow(key: String, row: CatalogRow) {
    synchronized(catalogStateLock) {
        val previousRow = catalogsMap.put(key, row)
        reindexCatalogRow(key, previousRow, row)
    }
}

internal inline fun HomeViewModel.updateCatalogRow(
    key: String,
    transform: (CatalogRow) -> CatalogRow
): CatalogRow? {
    return synchronized(catalogStateLock) {
        val currentRow = catalogsMap[key] ?: return@synchronized null
        val updatedRow = transform(currentRow)
        if (updatedRow != currentRow) {
            catalogsMap[key] = updatedRow
            reindexCatalogRow(key, currentRow, updatedRow)
        }
        updatedRow
    }
}

internal fun HomeViewModel.clearCatalogData() {
    synchronized(catalogStateLock) {
        catalogsMap.clear()
        catalogItemKeyIndex.clear()
        truncatedRowCache.clear()
        pendingLazyCatalogs.clear()
        placeholderDescriptors.clear()
    }
    lazyLoadRequestedKeys.clear()
}

internal fun HomeViewModel.snapshotCatalogKeys(): Set<String> = synchronized(catalogStateLock) {
    catalogsMap.keys.toSet()
}

internal fun HomeViewModel.snapshotCatalogState(): Pair<List<String>, Map<String, CatalogRow>> = synchronized(catalogStateLock) {
    catalogOrder.toList() to catalogsMap.toMap()
}

internal fun HomeViewModel.findCatalogItemById(itemId: String): MetaPreview? = synchronized(catalogStateLock) {
    val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
    rowKeys.firstNotNullOfOrNull { key ->
        catalogsMap[key]?.items?.firstOrNull { it.id == itemId }
    }
}

internal inline fun HomeViewModel.updateIndexedCatalogItem(
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
): Boolean {
    return synchronized(catalogStateLock) {
        val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
        var changed = false

        rowKeys.forEach { key ->
            val row = catalogsMap[key] ?: return@forEach
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) return@forEach

            val updatedItem = transform(row.items[itemIndex])
            if (updatedItem == row.items[itemIndex]) return@forEach

            val mutableItems = row.items.toMutableList()
            mutableItems[itemIndex] = updatedItem
            catalogsMap[key] = row.copy(items = mutableItems)
            truncatedRowCache.remove(key)
            changed = true
        }

        changed
    }
}

internal fun HomeViewModel.getTruncatedRowCacheEntry(key: String): HomeViewModel.TruncatedRowCacheEntry? = synchronized(catalogStateLock) {
    truncatedRowCache[key]
}

internal fun HomeViewModel.putTruncatedRowCacheEntry(key: String, entry: HomeViewModel.TruncatedRowCacheEntry) {
    synchronized(catalogStateLock) {
        truncatedRowCache[key] = entry
    }
}

internal fun HomeViewModel.removeTruncatedRowCacheEntry(key: String) {
    synchronized(catalogStateLock) {
        truncatedRowCache.remove(key)
    }
}

internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    val defaultOrder = buildDefaultCatalogOrder(addons)
    val collectionKeys = collectionsCache.map { "collection_${it.id}" }
    val allAvailable = (defaultOrder + collectionKeys).toSet()

    val savedValid = homeCatalogOrderKeys
        .asSequence()
        .filter { it in allAvailable }
        .distinct()
        .toList()

    val savedSet = savedValid.toSet()
    val unsavedCatalogs = defaultOrder.filterNot { it in savedSet }
    val unsavedCollections = collectionKeys.filterNot { it in savedSet }
    val mergedOrder = savedValid + unsavedCatalogs + unsavedCollections

    synchronized(catalogStateLock) {
        catalogOrder.clear()
        catalogOrder.addAll(mergedOrder)
    }
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    addons.forEach { addon ->
        addon.catalogs
            .filterNot {
                !it.shouldShowOnHome() || isCatalogDisabled(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    type = it.apiType,
                    catalogId = it.id,
                    catalogName = it.name
                )
            }
            .forEach { catalog ->
                val key = catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in orderedKeys) {
                    orderedKeys.add(key)
                }
            }
    }
    return orderedKeys
}

internal fun HomeViewModel.isCatalogDisabled(
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
        return true
    }
    // Backward compatibility with previously stored keys.
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
}

internal fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}

internal fun CatalogDescriptor.shouldShowOnHome(): Boolean {
    if (isSearchOnlyCatalog()) return false
    return !hasExplicitShowInHome || showInHome
}

internal fun MetaPreview.hasHeroArtwork(): Boolean {
    return !background.isNullOrBlank()
}

internal fun HomeViewModel.extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
}
