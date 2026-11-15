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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "CatalogOrder"
private const val PENDING_SAVED_ORDER_TIMEOUT_MS = 2000L

internal fun catalogOrderKeySample(keys: List<String>, limit: Int = 8): String {
    if (keys.isEmpty()) return "[] n=0"
    val shown = keys.take(limit).joinToString(",")
    val more = if (keys.size > limit) " …+${keys.size - limit}" else ""
    return "[$shown$more] n=${keys.size}"
}

internal fun catalogOrderKeysDiff(pending: List<String>?, disk: List<String>): String {
    if (pending == null) return "pending=null disk=${catalogOrderKeySample(disk)}"
    if (pending == disk) return "equal n=${pending.size}"
    if (pending.size != disk.size) {
        return "size pending=${pending.size} disk=${disk.size} " +
            "pending=${catalogOrderKeySample(pending)} disk=${catalogOrderKeySample(disk)}"
    }
    val index = pending.indices.first { pending[it] != disk[it] }
    return "mismatch@$index pending=${pending[index]} disk=${disk[index]}"
}

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
        Log.d(TAG, "ViewModel init")
        observeCatalogs()
    }

    fun enterMoveMode(key: String) {
        val items = _uiState.value.items
        val item = items.firstOrNull { it.key == key } ?: return
        if (!item.canMove) {
            Log.w(TAG, "enterMoveMode ignored key=$key canMove=false")
            return
        }
        moveBaselineItems = items
        draftOrderKeys = items.map { it.key }
        Log.d(
            TAG,
            "enterMoveMode key=$key index=${items.indexOf(item)} " +
                "baseline=${catalogOrderKeySample(draftOrderKeys.orEmpty())} follow=${_uiState.value.followAddonsOrder}",
        )
        _uiState.update { it.copy(movingKey = key) }
    }

    fun moveDuringDrag(key: String, direction: Int): Int? {
        val draft = draftOrderKeys ?: run {
            Log.w(TAG, "moveDuringDrag no draft key=$key dir=$direction")
            return null
        }
        val fromIndex = draft.indexOf(key)
        val reordered = if (_uiState.value.followAddonsOrder) {
            reorderCollectionInDraft(draft, key, direction) ?: run {
                Log.d(TAG, "moveDuringDrag blocked followAddons key=$key dir=$direction from=$fromIndex")
                return null
            }
        } else {
            reorderInDraft(draft, key, direction) ?: run {
                Log.d(TAG, "moveDuringDrag blocked edge key=$key dir=$direction from=$fromIndex")
                return null
            }
        }
        val toIndex = reordered.indexOf(key)
        draftOrderKeys = reordered
        applyDraftOrder(reordered)
        Log.d(TAG, "moveDuringDrag key=$key dir=$direction $fromIndex->$toIndex")
        return toIndex.takeIf { it >= 0 }
    }

    fun confirmMove() {
        val draft = draftOrderKeys
        val baselineKeys = moveBaselineItems?.map { it.key }
        if (draft == null || draft == baselineKeys) {
            Log.d(
                TAG,
                "confirmMove skip unchanged draftNull=${draft == null} " +
                    "baseline=${catalogOrderKeySample(baselineKeys.orEmpty())}",
            )
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
        Log.d(
            TAG,
            "confirmMove start gen=$generation draft=${catalogOrderKeySample(draft)} " +
                "uiItems=${confirmedItems.size} names=${confirmedItems.take(6).joinToString { it.catalogName }}",
        )
        _uiState.update { it.copy(movingKey = null, items = confirmedItems) }

        viewModelScope.launch {
            var wrote = false
            val writeStarted = SystemClock.elapsedRealtime()
            try {
                withContext(NonCancellable) {
                    persistMutex.withLock {
                        if (generation != persistGeneration) {
                            Log.w(
                                TAG,
                                "confirmMove skip stale write gen=$generation current=$persistGeneration",
                            )
                            return@withLock
                        }
                        layoutPreferenceDataStore.setHomeCatalogOrderKeys(draft)
                        wrote = true
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "confirmMove write failed gen=$generation", t)
            }
            val writeMs = SystemClock.elapsedRealtime() - writeStarted
            Log.d(
                TAG,
                "confirmMove writeDone gen=$generation wrote=$wrote writeMs=$writeMs " +
                    "stillCurrent=${generation == persistGeneration} pending=${pendingSavedOrderKeys != null}",
            )
            if (wrote && generation == persistGeneration) {
                Log.d(TAG, "confirmMove triggerPush gen=$generation")
                withContext(NonCancellable) {
                    homeCatalogSettingsSyncService.triggerPush()
                }
            } else if (generation == persistGeneration) {
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
            val current = _uiState.value
            if (pendingSavedOrderKeys == draft && generation == persistGeneration &&
                current.movingKey == null && current.items == confirmedItems &&
                sessionBaseline != null
            ) {
                Log.w(TAG, "pending order expired, reverting UI to pre-move order")
                pendingSavedOrderKeys = null
                persistedItemsCache = sessionBaseline
                _uiState.update { it.copy(items = sessionBaseline) }
            }
        }
    }

    fun cancelMove() {
        val baseline = moveBaselineItems
        Log.d(
            TAG,
            "cancelMove restoring=${baseline?.size ?: 0} " +
                "keys=${catalogOrderKeySample(baseline?.map { it.key }.orEmpty())}",
        )
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
        for (key in orderKeys) {
            val item = map[key] ?: continue
            if (seen.add(key)) ordered.add(item)
        }
        for (item in source) {
            if (seen.add(item.key)) ordered.add(item)
        }
        return ordered.mapIndexed { index, item ->
            item.withMoveFlags(index, ordered.size, followAddonsOrder)
        }
    }

    private fun CatalogOrderItem.withMoveFlags(
        index: Int,
        size: Int,
        followAddonsOrder: Boolean,
    ): CatalogOrderItem {
        val isCollection = key.startsWith("collection_")
        val canMoveUp: Boolean
        val canMoveDown: Boolean
        if (followAddonsOrder) {
            if (isCollection) {
                canMoveUp = index > 0
                canMoveDown = index < size - 1
            } else {
                canMoveUp = false
                canMoveDown = false
            }
        } else {
            canMoveUp = index > 0
            canMoveDown = index < size - 1
        }
        return copy(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            canMove = canMoveUp || canMoveDown,
        )
    }

    private fun reorderInDraft(
        draft: List<String>,
        key: String,
        direction: Int,
    ): List<String>? {
        val currentIndex = draft.indexOf(key)
        if (currentIndex == -1) return null
        val newIndex = currentIndex + direction
        if (newIndex !in draft.indices) return null
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

        if (newIndex == currentIndex) return null
        return draft.toMutableList().apply {
            removeAt(currentIndex)
            add(newIndex, key)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        Log.d(TAG, "toggleFollowAddonsOrder enabled=$enabled")
        pendingSavedOrderKeys = null
        val current = _uiState.value.items
        val reordered = if (enabled) {
            orderItemsByAddonManifest(current, lastManifestAddonKeys)
        } else {
            current
        }
        val flagged = reordered.mapIndexed { index, item ->
            item.withMoveFlags(index, reordered.size, enabled)
        }
        Log.d(
            TAG,
            "toggleFollowAddonsOrder apply n=${flagged.size} " +
                "names=${flagged.take(6).joinToString { it.catalogName }}",
        )
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
        val byKey = current.associateBy { it.key }
        val addons = manifestKeys.mapNotNull { key -> byKey[key] }
        if (addons.isEmpty()) return current
        val placed = addons.mapTo(HashSet()) { it.key }
        val rest = current.filter { it.key !in placed }
        return addons + rest
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
            val openedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "observeCatalogs subscribe waiting for 6 sources")
            combine(
                combine(
                    addonRepository.getInstalledAddons().onEach { addons ->
                        Log.d(
                            TAG,
                            "source[addons] +${SystemClock.elapsedRealtime() - openedAt}ms " +
                                "n=${addons.size} enabled=${addons.count { it.enabled }}",
                        )
                    },
                    serverCatalog.addons,
                ) { installed, servers ->
                    installed.enabledAddons() + servers
                },
                collectionsDataStore.collections.onEach { collections ->
                    Log.d(
                        TAG,
                        "source[collections] +${SystemClock.elapsedRealtime() - openedAt}ms n=${collections.size}",
                    )
                },
                layoutPreferenceDataStore.homeCatalogOrderKeys.onEach { keys ->
                    Log.d(
                        TAG,
                        "source[diskOrder] +${SystemClock.elapsedRealtime() - openedAt}ms " +
                            catalogOrderKeySample(keys),
                    )
                },
                layoutPreferenceDataStore.disabledHomeCatalogKeys.onEach { keys ->
                    Log.d(
                        TAG,
                        "source[disabled] +${SystemClock.elapsedRealtime() - openedAt}ms n=${keys.size}",
                    )
                },
                layoutPreferenceDataStore.customCatalogTitles.onEach { titles ->
                    Log.d(
                        TAG,
                        "source[titles] +${SystemClock.elapsedRealtime() - openedAt}ms n=${titles.size}",
                    )
                },
                layoutPreferenceDataStore.followAddonsOrder.onEach { follow ->
                    Log.d(
                        TAG,
                        "source[followAddons] +${SystemClock.elapsedRealtime() - openedAt}ms follow=$follow",
                    )
                },
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

                val buildStarted = SystemClock.elapsedRealtime()
                val items = buildOrderedCatalogItems(
                    addons = addons,
                    collections = collections,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons
                )
                Log.d(
                    TAG,
                    "combine buildMs=${SystemClock.elapsedRealtime() - buildStarted} " +
                        "openMs=${SystemClock.elapsedRealtime() - openedAt} " +
                        "items=${items.size} addons=${addons.size} cols=${collections.size} " +
                        "disk=${catalogOrderKeySample(savedOrderKeys)} follow=$followAddons " +
                        "disabled=${disabledKeys.size} titles=${customTitles.size}",
                )
                Triple(items, followAddons, savedOrderKeys)
            }.collectLatest { (orderedItems, followAddons, diskOrderKeys) ->
                var pendingKeys = pendingSavedOrderKeys
                if (pendingKeys != null && diskOrderKeys != pendingKeys &&
                    SystemClock.elapsedRealtime() - pendingSavedOrderKeysAt >
                    PENDING_SAVED_ORDER_TIMEOUT_MS
                ) {
                    Log.w(TAG, "pending order timed out, falling back to disk order")
                    pendingSavedOrderKeys = null
                    pendingKeys = null
                }
                val diskMatchesPending = pendingKeys != null && diskOrderKeys == pendingKeys
                Log.d(
                    TAG,
                    "collect movingKey=${_uiState.value.movingKey} isLoading=${_uiState.value.isLoading} " +
                        "disk=${catalogOrderKeySample(diskOrderKeys)} ${catalogOrderKeysDiff(pendingKeys, diskOrderKeys)} " +
                        "built=${orderedItems.size} names=${orderedItems.take(6).joinToString { it.catalogName }}",
                )
                if (diskMatchesPending) {
                    pendingSavedOrderKeys = null
                    Log.d(TAG, "pending cleared disk caught up")
                }
                val itemsToApply = if (pendingKeys != null && !diskMatchesPending) {
                    Log.w(TAG, "keeping pending UI order until disk matches")
                    itemsInOrder(pendingKeys, orderedItems, followAddons)
                } else {
                    orderedItems
                }
                persistedItemsCache = itemsToApply
                val current = _uiState.value
                if (current.movingKey == null) {
                    val itemsUnchanged = current.items == itemsToApply
                    val followUnchanged = current.followAddonsOrder == followAddons
                    if (itemsUnchanged && followUnchanged && !current.isLoading) {
                        Log.d(TAG, "ui skip identical items follow=$followAddons")
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                items = itemsToApply,
                                followAddonsOrder = followAddons
                            )
                        }
                        Log.d(
                            TAG,
                            "ui applied items=${itemsToApply.size} follow=$followAddons " +
                                "openMs=${SystemClock.elapsedRealtime() - openedAt} " +
                                "itemsUnchanged=$itemsUnchanged",
                        )
                    }
                } else {
                    Log.d(TAG, "ui skip item replace while moving, follow=$followAddons")
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
                typeLabel = "collection"
            )
        }
        val allEntries = defaultEntries + collectionEntries
        val availableMap = allEntries.associateBy { it.key }
        val defaultOrderKeys = allEntries.map { it.key }
        lastManifestAddonKeys = defaultEntries.map { it.key }

        val effectiveOrder: List<String>
        if (followAddonsOrder) {
            // In follow mode, addon catalogs stay in manifest order.
            // Collections are positioned based on their relative position in savedOrderKeys.
            val addonKeys = defaultEntries.map { it.key }
            val collectionKeys = collectionEntries.map { it.key }.toSet()

            val savedValid = savedOrderKeys.filter { it in availableMap }.distinct()

            if (savedValid.isNotEmpty()) {
                // Rebuild order: take saved order but replace addon sequence with manifest order.
                // Strategy: walk through savedValid, output addon keys in manifest order,
                // insert collections at their saved positions relative to addon boundaries.
                val result = mutableListOf<String>()
                var addonPointer = 0 // pointer into addonKeys (manifest order)

                for (savedKey in savedValid) {
                    if (savedKey in collectionKeys) {
                        // Place collection here - but first flush any addon keys up to this point
                        // that haven't been placed yet
                        result.add(savedKey)
                    } else {
                        // It's an addon catalog key in saved order - advance manifest pointer
                        // to include all addon keys up to and including this one
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
                // Append any remaining addon keys not yet placed
                while (addonPointer < addonKeys.size) {
                    val ak = addonKeys[addonPointer]
                    if (ak !in result) {
                        result.add(ak)
                    }
                    addonPointer++
                }
                // Append any collections not in savedValid
                for (ck in collectionKeys) {
                    if (ck !in result) {
                        result.add(ck)
                    }
                }
                // Normalize: ensure collections sit at addon block boundaries, not mid-block.
                // If a collection is between two catalogs of the same addon, push it after that block.
                effectiveOrder = normalizeCollectionPositions(result, availableMap)
            } else {
                // No saved order - addon manifest order + collections at end
                effectiveOrder = addonKeys + collectionKeys.toList()
            }
        } else {
            val savedValid = savedOrderKeys
                .asSequence()
                .filter { it in availableMap }
                .distinct()
                .toList()

            val savedKeySet = savedValid.toSet()
            val missing = defaultOrderKeys.filterNot { it in savedKeySet }
            effectiveOrder = savedValid + missing
        }

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            val isCollection = key.startsWith("collection_")

            val canMoveUp: Boolean
            val canMoveDown: Boolean
            if (followAddonsOrder) {
                if (isCollection) {
                    canMoveUp = index > 0
                    canMoveDown = index < effectiveOrder.lastIndex
                } else {
                    canMoveUp = false
                    canMoveDown = false
                }
            } else {
                canMoveUp = index > 0
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
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canMove = canMoveUp || canMoveDown
            )
        }
    }

    /**
     * Ensures collections are positioned at addon block boundaries.
     * If a collection ended up between two catalogs of the same addon,
     * push it to the end of that addon's block.
     */
    private fun normalizeCollectionPositions(
        order: List<String>,
        availableMap: Map<String, CatalogOrderEntry>
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
                val prevAddonId = findAddonIdBefore(result, i, availableMap)
                val nextAddonId = findAddonIdAfter(result, i, availableMap)
                if (prevAddonId != null && nextAddonId != null && prevAddonId == nextAddonId) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !result[insertPos].startsWith("collection_") &&
                        availableMap[result[insertPos]]?.addonId == prevAddonId
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
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonId
            }
        }
        return null
    }

    private fun findAddonIdAfter(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index + 1 until order.size) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonId
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
    val typeLabel: String
)
