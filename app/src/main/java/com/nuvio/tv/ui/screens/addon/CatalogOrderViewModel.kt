package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        moveCatalog(key, 1)
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys
            ) { addons, savedOrderKeys, disabledKeys ->
                buildOrderedCatalogItems(
                    addons = addons,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys.toSet()
                )
            }.collectLatest { orderedItems ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val availableMap = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }

        // Build a map of catalog key patterns for fuzzy matching
        val keyToAddonAndType = mutableMapOf<String, Pair<String, String>>()
        defaultEntries.forEach { entry ->
            // Key format: ${addonId}_${type}_${catalogId}
            // We need to extract addonId and type, but catalogId can contain underscores
            val firstUnderscore = entry.key.indexOf('_')
            if (firstUnderscore > 0) {
                val addonId = entry.key.substring(0, firstUnderscore)
                val remaining = entry.key.substring(firstUnderscore + 1)
                val secondUnderscore = remaining.indexOf('_')
                if (secondUnderscore > 0) {
                    val type = remaining.substring(0, secondUnderscore)
                    keyToAddonAndType[entry.key] = addonId to type
                }
            }
        }

        // Build final order with fuzzy matching for dynamic catalogs
        val finalOrder = mutableListOf<String>()
        val usedKeys = mutableSetOf<String>()

        savedOrderKeys.forEach { savedKey ->
            if (savedKey in availableMap) {
                // Exact match found
                if (savedKey !in usedKeys) {
                    finalOrder.add(savedKey)
                    usedKeys.add(savedKey)
                }
            } else {
                // Try fuzzy match: find a catalog from same addon+type that hasn't been used
                val firstUnderscore = savedKey.indexOf('_')
                if (firstUnderscore > 0) {
                    val addonId = savedKey.substring(0, firstUnderscore)
                    val remaining = savedKey.substring(firstUnderscore + 1)
                    val secondUnderscore = remaining.indexOf('_')
                    if (secondUnderscore > 0) {
                        val type = remaining.substring(0, secondUnderscore)
                        val matchingKey = defaultOrderKeys.firstOrNull { availableKey ->
                            availableKey !in usedKeys &&
                            keyToAddonAndType[availableKey]?.let { (aid, t) ->
                                aid == addonId && t == type
                            } == true
                        }
                        if (matchingKey != null) {
                            finalOrder.add(matchingKey)
                            usedKeys.add(matchingKey)
                        }
                    }
                }
            }
        }

        // Add any remaining catalogs that weren't matched
        defaultOrderKeys.forEach { key ->
            if (key !in usedKeys) {
                finalOrder.add(key)
            }
        }

        return finalOrder.mapIndexed { index, key ->
            val entry = availableMap[key]!!
            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys,
                canMoveUp = index > 0,
                canMoveDown = index < finalOrder.lastIndex
            )
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = disableKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun disableKey(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): String {
        return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name == "search" && extra.isRequired }
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList()
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
