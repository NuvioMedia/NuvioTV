package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection

internal data class LocalHomeCatalogSettingsState(
    val orderKeys: List<String> = emptyList(),
    val disabledKeys: Set<String> = emptySet(),
    val customTitles: Map<String, String> = emptyMap()
)

private data class HomeCatalogSyncEntry(
    val key: String,
    val addonId: String = "",
    val addonBaseUrl: String = "",
    val type: String = "",
    val catalogId: String = "",
    val catalogName: String = "",
    val isCollection: Boolean = false,
    val collectionId: String = ""
)

internal fun homeCatalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun homeCollectionKey(collectionId: String): String {
    return "collection_${collectionId}"
}

internal fun homeLegacyDisabledCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
}

internal fun hasLegacyHomeCatalogDisabledKeyFormat(key: String): Boolean {
    return key.contains("://")
}

internal fun isHomeCatalogDisabled(
    disabledKeys: Set<String>,
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    return homeCatalogKey(addonId, type, catalogId) in disabledKeys ||
        homeLegacyDisabledCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledKeys
}

internal fun buildHomeCatalogSyncPayload(
    addons: List<Addon>,
    collections: List<Collection>,
    localState: LocalHomeCatalogSettingsState
): SyncHomeCatalogPayload {
    val catalogEntries = buildCatalogEntries(addons)
    val collectionEntries = collections.map { collection ->
        HomeCatalogSyncEntry(
            key = homeCollectionKey(collection.id),
            isCollection = true,
            collectionId = collection.id
        )
    }
    val entryByKey = (catalogEntries + collectionEntries).associateBy { it.key }
    val catalogKeys = catalogEntries.map { it.key }
    val collectionKeys = collectionEntries.map { it.key }

    val savedValid = localState.orderKeys
        .asSequence()
        .filter { it in entryByKey }
        .distinct()
        .toList()
    val savedSet = savedValid.toSet()
    val mergedOrder = savedValid +
        catalogKeys.filterNot { it in savedSet } +
        collectionKeys.filterNot { it in savedSet }

    val items = mergedOrder.mapIndexedNotNull { index, key ->
        val entry = entryByKey[key] ?: return@mapIndexedNotNull null
        if (entry.isCollection) {
            SyncCatalogItem(
                addonId = "",
                type = "",
                catalogId = "",
                enabled = key !in localState.disabledKeys,
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = true,
                collectionId = entry.collectionId
            )
        } else {
            SyncCatalogItem(
                addonId = entry.addonId,
                type = entry.type,
                catalogId = entry.catalogId,
                enabled = !isHomeCatalogDisabled(
                    disabledKeys = localState.disabledKeys,
                    addonId = entry.addonId,
                    addonBaseUrl = entry.addonBaseUrl,
                    type = entry.type,
                    catalogId = entry.catalogId,
                    catalogName = entry.catalogName
                ),
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = false,
                collectionId = ""
            )
        }
    }

    return SyncHomeCatalogPayload(items = items)
}

private fun buildCatalogEntries(addons: List<Addon>): List<HomeCatalogSyncEntry> {
    val entries = mutableListOf<HomeCatalogSyncEntry>()
    val seenKeys = mutableSetOf<String>()

    addons.forEach { addon ->
        addon.catalogs
            .filter { it.shouldShowOnHomeForSync() }
            .forEach { catalog ->
                val key = homeCatalogKey(addon.id, catalog.apiType, catalog.id)
                if (seenKeys.add(key)) {
                    entries.add(
                        HomeCatalogSyncEntry(
                            key = key,
                            addonId = addon.id,
                            addonBaseUrl = addon.baseUrl,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name
                        )
                    )
                }
            }
    }

    return entries
}

private fun CatalogDescriptor.shouldShowOnHomeForSync(): Boolean {
    if (extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }) {
        return false
    }
    return !hasExplicitShowInHome || showInHome
}