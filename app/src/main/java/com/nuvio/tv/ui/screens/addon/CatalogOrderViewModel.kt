package com.nuvio.tv.ui.screens.addon

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "CatalogOrder"
private const val PENDING_SAVED_ORDER_TIMEOUT_MS = 2000L

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val serverCatalog: ServerCatalog
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var persistedDisabledKeys: Set<String> = emptySet()
    private var persistedItemsCache: List<CatalogOrderItem> = emptyList()
    private var draftOrderKeys: List<String>? = null
    private var moveBaselineItems: List<CatalogOrderItem>? = null
    private var pendingSavedOrderKeys: List<String>? = null
    private var pendingSavedOrderKeysAt: Long = 0L
    private var lastManifestAddonKeys: List<String> = emptyList()
    private var persistGeneration: Int = 0
    private val persistMutex = Mutex()

    init {
        observeCatalogs()
    }

    fun enterMoveMode(key: String) {
        val items = _uiState.value.items
        val item = items.firstOrNull { it.key == key } ?: return
        if (!item.canMove) return
        moveBaselineItems = items
        draftOrderKeys = items.map { it.key }
        _uiState.update { it.copy(movingKey = key) }
    }

    fun moveDuringDrag(key: String, direction: Int): Int? {
        val draft = draftOrderKeys ?: return null
        val reordered = if (_uiState.value.followAddonsOrder) {
            reorderCollectionInDraft(draft, key, direction) ?: return null
        } else {
            reorderInDraft(draft, key, direction) ?: return null
        }
        val toIndex = reordered.indexOf(key)
        draftOrderKeys = reordered
        applyDraftOrder(reordered)
        return toIndex.takeIf { it >= 0 }
    }

    fun confirmMove() {
        val draft = draftOrderKeys
        val baselineKeys = moveBaselineItems?.map { it.key }
        if (draft == null || draft == baselineKeys) {
            exitMoveMode()
            return
        }
        val confirmedItems = itemsInOrder(
            orderKeys = draft,
            source = _uiState.value.items.ifEmpty { persistedItemsCache },
        )
        persistedItemsCache = confirmedItems
        val generation = ++persistGeneration
        pendingSavedOrderKeys = draft
        pendingSavedOrderKeysAt = SystemClock.elapsedRealtime()
        val sessionBaseline = moveBaselineItems
        draftOrderKeys = null
        moveBaselineItems = null
        _uiState.update { it.copy(movingKey = null, items = confirmedItems) }

        viewModelScope.launch {
            var wrote = false
            try {
                withContext(NonCancellable) {
                    persistMutex.withLock {
                        if (generation != persistGeneration) {
                            return@withLock
                        }
                        layoutPreferenceDataStore.setHomeCatalogOrderKeys(draft)
                        wrote = true
                    }
                    if (wrote && generation == persistGeneration) {
                        homeCatalogSettingsSyncService.triggerPush()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "confirmMove write failed gen=$generation", t)
            }
            if (!wrote && generation == persistGeneration) {
                pendingSavedOrderKeys = null
                val current = _uiState.value
                if (current.movingKey == null && current.items == confirmedItems) {
                    sessionBaseline?.let { baseline ->
                        persistedItemsCache = baseline
                        _uiState.update { it.copy(items = baseline) }
                    }
                }
            }
        }

        viewModelScope.launch {
            delay(PENDING_SAVED_ORDER_TIMEOUT_MS + 500)
            if (pendingSavedOrderKeys == draft && generation == persistGeneration) {
                pendingSavedOrderKeys = null
            }
        }
    }

    fun cancelMove() {
        val baseline = moveBaselineItems
        exitMoveMode()
        if (baseline != null) {
            _uiState.update { it.copy(items = baseline) }
        }
    }

    private fun exitMoveMode() {
        draftOrderKeys = null
        moveBaselineItems = null
        _uiState.update { it.copy(movingKey = null) }
    }

    private fun applyDraftOrder(orderKeys: List<String>) {
        val reordered = itemsInOrder(
            orderKeys = orderKeys,
            source = _uiState.value.items.ifEmpty { persistedItemsCache },
        )
        if (reordered.isEmpty()) return
        _uiState.update { it.copy(items = reordered) }
    }

    private fun itemsInOrder(
        orderKeys: List<String>,
        source: List<CatalogOrderItem>,
        followAddonsOrder: Boolean = _uiState.value.followAddonsOrder,
    ): List<CatalogOrderItem> {
        if (source.isEmpty()) return emptyList()
        val map = source.associateBy { it.key }
        val seen = LinkedHashSet<String>()
        val ordered = ArrayList<CatalogOrderItem>(source.size)
        for (item in source) {
            if (item.isPinnedToTop && seen.add(item.key)) {
                ordered.add(item)
            }
        }
        val firstUnpinnedIndex = ordered.size
        for (key in orderKeys) {
            val item = map[key] ?: continue
            if (seen.add(key)) ordered.add(item)
        }
        for (item in source) {
            if (seen.add(item.key)) ordered.add(item)
        }
        return ordered.mapIndexed { index, item ->
            item.withMoveFlags(index, ordered.size, firstUnpinnedIndex, followAddonsOrder)
        }
    }

    private fun CatalogOrderItem.withMoveFlags(
        index: Int,
        size: Int,
        firstUnpinnedIndex: Int,
        followAddonsOrder: Boolean,
    ): CatalogOrderItem {
        val isCollection = key.startsWith("collection_")
        val canMoveUp: Boolean
        val canMoveDown: Boolean
        if (isPinnedToTop) {
            canMoveUp = false
            canMoveDown = false
        } else if (followAddonsOrder) {
            if (isCollection) {
                canMoveUp = index > firstUnpinnedIndex
                canMoveDown = index < size - 1
            } else {
                canMoveUp = false
                canMoveDown = false
            }
        } else {
            canMoveUp = index > firstUnpinnedIndex
            canMoveDown = index < size - 1
        }
        return copy(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            canMove = canMoveUp || canMoveDown,
        )
    }

    private fun pinnedItemKeys(): Set<String> =
        _uiState.value.items.asSequence()
            .filter { it.isPinnedToTop }
            .map { it.key }
            .toSet()

    private fun reorderInDraft(
        draft: List<String>,
        key: String,
        direction: Int,
    ): List<String>? {
        val pinnedKeys = pinnedItemKeys()
        if (key in pinnedKeys) return null
        val currentIndex = draft.indexOf(key)
        if (currentIndex == -1) return null
        val newIndex = currentIndex + direction
        if (newIndex !in draft.indices) return null
        if (draft[newIndex] in pinnedKeys) return null
        return draft.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }
    }

    private fun reorderCollectionInDraft(
        draft: List<String>,
        key: String,
        direction: Int,
    ): List<String>? {
        if (!key.startsWith("collection_")) return null
        val pinnedKeys = pinnedItemKeys()
        if (key in pinnedKeys) return null
        val items = _uiState.value.items
        val currentIndex = draft.indexOf(key)
        if (currentIndex == -1) return null

        val newIndex: Int
        if (direction < 0) {
            var scanIdx = currentIndex - 1
            while (scanIdx >= 0 && draft[scanIdx].startsWith("collection_")) {
                scanIdx--
            }
            if (scanIdx < 0) return null
            val targetAddonId = items.firstOrNull { it.key == draft[scanIdx] }?.addonId
                ?: return null
            while (scanIdx > 0 && !draft[scanIdx - 1].startsWith("collection_") &&
                items.firstOrNull { it.key == draft[scanIdx - 1] }?.addonId == targetAddonId
            ) {
                scanIdx--
            }
            newIndex = scanIdx
        } else {
            var scanIdx = currentIndex + 1
            while (scanIdx < draft.size && draft[scanIdx].startsWith("collection_")) {
                scanIdx++
            }
            if (scanIdx >= draft.size) return null
            val targetAddonId = items.firstOrNull { it.key == draft[scanIdx] }?.addonId
                ?: return null
            while (scanIdx < draft.lastIndex && !draft[scanIdx + 1].startsWith("collection_") &&
                items.firstOrNull { it.key == draft[scanIdx + 1] }?.addonId == targetAddonId
            ) {
                scanIdx++
            }
            newIndex = scanIdx
        }

        if (newIndex == currentIndex || draft[newIndex] in pinnedKeys) return null
        return draft.toMutableList().apply {
            removeAt(currentIndex)
            add(newIndex, key)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        pendingSavedOrderKeys = null
        val current = _uiState.value.items
        val reordered = if (enabled) {
            orderItemsByAddonManifest(current, lastManifestAddonKeys)
        } else {
            current
        }
        val firstUnpinnedIndex = reordered.count { it.isPinnedToTop }
        val flagged = reordered.mapIndexed { index, item ->
            item.withMoveFlags(index, reordered.size, firstUnpinnedIndex, enabled)
        }
        _uiState.update {
            it.copy(followAddonsOrder = enabled, items = flagged)
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
                homeCatalogSettingsSyncService.triggerPush()
            }
        }
    }

    private fun orderItemsByAddonManifest(
        current: List<CatalogOrderItem>,
        manifestKeys: List<String>,
    ): List<CatalogOrderItem> {
        if (current.isEmpty() || manifestKeys.isEmpty()) return current
        val pinnedItems = current.filter { it.isPinnedToTop }
        val unpinnedItems = current.filterNot { it.isPinnedToTop }
        val byKey = unpinnedItems.associateBy { it.key }
        val addonKeys = manifestKeys.filter { it in byKey }
        if (addonKeys.isEmpty()) return pinnedItems + unpinnedItems
        val collectionKeys = unpinnedItems.asSequence()
            .map { it.key }
            .filter { it.startsWith("collection_") }
            .toSet()
        val orderedKeys = computeFollowAddonsOrderKeys(
            addonKeys = addonKeys,
            collectionKeys = collectionKeys,
            referenceOrderKeys = unpinnedItems.map { it.key },
            addonIdForKey = { key -> byKey[key]?.addonId },
        )
        return pinnedItems + orderedKeys.mapNotNull { byKey[it] }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = persistedDisabledKeys.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        persistedDisabledKeys = updatedDisabled
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistMutex.withLock {
                    layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
                }
                homeCatalogSettingsSyncService.triggerPush()
            }
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                combine(
                    addonRepository.getInstalledAddons(),
                    serverCatalog.addons,
                ) { installed, servers ->
                    installed.enabledAddons() + servers
                },
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean
                persistedDisabledKeys = disabledKeys

                val items = buildOrderedCatalogItems(
                    addons = addons,
                    collections = collections,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons
                )
                Triple(items, followAddons, savedOrderKeys)
            }.collectLatest { (orderedItems, followAddons, diskOrderKeys) ->
                var pendingKeys = pendingSavedOrderKeys
                if (pendingKeys != null && diskOrderKeys != pendingKeys &&
                    SystemClock.elapsedRealtime() - pendingSavedOrderKeysAt >
                    PENDING_SAVED_ORDER_TIMEOUT_MS
                ) {
                    pendingSavedOrderKeys = null
                    pendingKeys = null
                }
                val diskMatchesPending = pendingKeys != null && diskOrderKeys == pendingKeys
                if (diskMatchesPending) {
                    pendingSavedOrderKeys = null
                }
                val itemsToApply = if (pendingKeys != null && !diskMatchesPending) {
                    itemsInOrder(pendingKeys, orderedItems, followAddons)
                } else {
                    orderedItems
                }
                persistedItemsCache = itemsToApply
                val current = _uiState.value
                if (current.movingKey == null) {
                    val itemsUnchanged = current.items == itemsToApply
                    val followUnchanged = current.followAddonsOrder == followAddons
                    if (!itemsUnchanged || !followUnchanged || current.isLoading) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                items = itemsToApply,
                                followAddonsOrder = followAddons
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            followAddonsOrder = followAddons
                        )
                    }
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String> = emptyMap(),
        followAddonsOrder: Boolean = false
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val collectionEntries = collections.map { collection ->
            CatalogOrderEntry(
                key = "collection_${collection.id}",
                disableKey = "collection_${collection.id}",
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                typeLabel = "collection",
                isPinnedToTop = collection.pinToTop
            )
        }
        val pinnedEntries = collectionEntries.filter { it.isPinnedToTop }
        val unpinnedCollectionEntries = collectionEntries.filterNot { it.isPinnedToTop }
        val pinnedKeys = pinnedEntries.map { it.key }
        val pinnedKeySet = pinnedKeys.toSet()
        val allEntries = pinnedEntries + defaultEntries + unpinnedCollectionEntries
        val availableMap = allEntries.associateBy { it.key }
        val unpinnedDefaultOrderKeys = (defaultEntries + unpinnedCollectionEntries).map { it.key }
        val unpinnedKeySet = unpinnedDefaultOrderKeys.toSet()
        lastManifestAddonKeys = defaultEntries.map { it.key }

        val unpinnedEffectiveOrder: List<String>
        if (followAddonsOrder) {
            val addonKeys = defaultEntries.map { it.key }
            val unpinnedCollectionKeys = unpinnedCollectionEntries.map { it.key }.toSet()
            val savedValidUnpinned = savedOrderKeys
                .asSequence()
                .filter { it in unpinnedKeySet }
                .distinct()
                .toList()
            unpinnedEffectiveOrder = computeFollowAddonsOrderKeys(
                addonKeys = addonKeys,
                collectionKeys = unpinnedCollectionKeys,
                referenceOrderKeys = savedValidUnpinned,
                addonIdForKey = { key -> availableMap[key]?.addonId },
            )
        } else {
            val savedValidUnpinned = savedOrderKeys
                .asSequence()
                .filter { it in unpinnedKeySet }
                .distinct()
                .toList()

            val savedKeySet = savedValidUnpinned.toSet()
            val missing = unpinnedDefaultOrderKeys.filterNot { it in savedKeySet }
            unpinnedEffectiveOrder = savedValidUnpinned + missing
        }
        val effectiveOrder = pinnedKeys + unpinnedEffectiveOrder
        val firstUnpinnedIndex = pinnedKeySet.size

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            val isCollection = key.startsWith("collection_")

            val canMoveUp: Boolean
            val canMoveDown: Boolean
            if (entry.isPinnedToTop) {
                canMoveUp = false
                canMoveDown = false
            } else if (followAddonsOrder) {
                if (isCollection) {
                    canMoveUp = index > firstUnpinnedIndex
                    canMoveDown = index < effectiveOrder.lastIndex
                } else {
                    canMoveUp = false
                    canMoveDown = false
                }
            } else {
                canMoveUp = index > firstUnpinnedIndex
                canMoveDown = index < effectiveOrder.lastIndex
            }

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                addonId = entry.addonId,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys),
                isPinnedToTop = entry.isPinnedToTop,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canMove = canMoveUp || canMoveDown
            )
        }
    }

    private fun computeFollowAddonsOrderKeys(
        addonKeys: List<String>,
        collectionKeys: Set<String>,
        referenceOrderKeys: List<String>,
        addonIdForKey: (String) -> String?,
    ): List<String> {
        if (referenceOrderKeys.isEmpty()) {
            return addonKeys + collectionKeys.toList()
        }
        val result = mutableListOf<String>()
        var addonPointer = 0

        for (savedKey in referenceOrderKeys) {
            if (savedKey in collectionKeys) {
                result.add(savedKey)
            } else {
                val targetManifestIdx = addonKeys.indexOf(savedKey)
                if (targetManifestIdx >= 0) {
                    while (addonPointer <= targetManifestIdx) {
                        val ak = addonKeys[addonPointer]
                        if (ak !in result) {
                            result.add(ak)
                        }
                        addonPointer++
                    }
                }
            }
        }
        while (addonPointer < addonKeys.size) {
            val ak = addonKeys[addonPointer]
            if (ak !in result) {
                result.add(ak)
            }
            addonPointer++
        }
        for (ck in collectionKeys) {
            if (ck !in result) {
                result.add(ck)
            }
        }
        return normalizeCollectionPositions(result, addonIdForKey)
    }

    /**
     * Ensures collections are positioned at addon block boundaries.
     * If a collection ended up between two catalogs of the same addon,
     * push it to the end of that addon's block.
     */
    private fun normalizeCollectionPositions(
        order: List<String>,
        addonIdForKey: (String) -> String?
    ): List<String> {
        val result = order.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < result.size) {
                val key = result[i]
                if (!key.startsWith("collection_")) {
                    i++
                    continue
                }
                val prevAddonId = findAddonIdBefore(result, i, addonIdForKey)
                val nextAddonId = findAddonIdAfter(result, i, addonIdForKey)
                if (prevAddonId != null && nextAddonId != null && prevAddonId == nextAddonId) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !result[insertPos].startsWith("collection_") &&
                        addonIdForKey(result[insertPos]) == prevAddonId
                    ) {
                        insertPos++
                    }
                    result.add(insertPos, key)
                    if (insertPos != i) changed = true
                    i++
                } else {
                    i++
                }
            }
        }
        return result
    }

    private fun findAddonIdBefore(
        order: List<String>,
        index: Int,
        addonIdForKey: (String) -> String?
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!order[j].startsWith("collection_")) {
                return addonIdForKey(order[j])
            }
        }
        return null
    }

    private fun findAddonIdAfter(
        order: List<String>,
        index: Int,
        addonIdForKey: (String) -> String?
    ): String? {
        for (j in index + 1 until order.size) {
            if (!order[j].startsWith("collection_")) {
                return addonIdForKey(order[j])
            }
        }
        return null
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
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                addonId = addon.id,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return homeCatalogKey(addonId, type, catalogId)
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false,
    val movingKey: String? = null,
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val addonId: String = "",
    val typeLabel: String,
    val isDisabled: Boolean,
    val isPinnedToTop: Boolean = false,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val canMove: Boolean,
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val legacyDisableKey: String? = null,
    val catalogName: String,
    val addonName: String,
    val addonId: String = "",
    val typeLabel: String,
    val isPinnedToTop: Boolean = false
)
