package com.nuvio.tv.ui.screens.hub

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.isCatalogDisabledIn
import com.nuvio.tv.ui.screens.home.isFeaturedHomeCatalog
import com.nuvio.tv.ui.screens.home.isSearchOnlyCatalog
import com.nuvio.tv.ui.screens.home.isSeriesType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch

/**
 * Powers the Movies and TV hubs: every enabled addon's movie-type catalog (or
 * series/tv-type) is fetched in parallel and merged into one deduped grid, with
 * a Source chip row to slice back to a single catalog.
 */
@HiltViewModel
class HubViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "HubViewModel"
        private const val MAX_SOURCE_LOAD_CONCURRENCY = 3
    }

    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    private val catalogLoadSemaphore = Semaphore(MAX_SOURCE_LOAD_CONCURRENCY)

    private var currentKind: HubKind = HubKind.MOVIES
    private var initialized = false
    private var selectedSourceKey: String? = null
    private var disabledHomeCatalogKeys: Set<String> = emptySet()

    /** All hub sources in addon/manifest order. */
    private val sourcesByKey = linkedMapOf<String, HubSource>()
    /** Per-source fully-paged rows; sourceRows[key].items is the single-source view. */
    private val sourceRows = linkedMapOf<String, CatalogRow>()
    /** Global deduped items for the "All" view. */
    private val mergedItems = linkedMapOf<String, MetaPreview>()
    private val seenItemKeys = mutableSetOf<String>()
    /** Sources that returned a page with no new globally-unique items while in "All" mode. */
    private val sourcesExhaustedForAll = mutableSetOf<String>()
    private var nextLoadMoreSourceIndex = 0

    private var initialLoadJob: Job? = null
    private var loadMoreJob: Job? = null
    private val sourceLoadJobs = mutableListOf<Job>()

    fun initialize(kind: HubKind) {
        if (initialized && kind == currentKind) return
        currentKind = kind
        initialized = true
        startInitialLoad()
    }

    fun refresh() {
        clearSourceData()
        startInitialLoad()
    }

    fun selectSource(key: String?) {
        selectedSourceKey = key
        publishState()
    }

    fun loadMore() {
        Log.w(
            TAG,
            "Hub ${currentKind} loadMore selected=$selectedSourceKey " +
                "hasMoreAny=${sourceRows.entries.any { it.value.hasMore && it.key !in sourcesExhaustedForAll }}"
        )
        if (loadMoreJob?.isActive == true) return
        val source = if (selectedSourceKey == null) {
            rotateLoadMoreTarget()
        } else {
            if (sourceRows[selectedSourceKey]?.hasMore == true) sourcesByKey[selectedSourceKey] else null
        } ?: run {
            publishState()
            return
        }
        loadMoreJob = viewModelScope.launch {
            loadNextPageForSource(source)
            loadMoreJob = null
        }
    }

    private fun startInitialLoad() {
        initialLoadJob?.cancel()
        sourceLoadJobs.forEach { it.cancel() }
        sourceLoadJobs.clear()
        clearSourceData()

        initialLoadJob = viewModelScope.launch {
            val addons = runCatching {
                addonRepository.getInstalledAddons().first { it.isNotEmpty() }
            }.getOrDefault(emptyList()).enabledAddons()
            disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toSet()

            val sources = buildHubSources(addons)
            if (sources.isEmpty()) {
                _uiState.update {
                    it.copy(kind = currentKind, sources = emptyList(), items = emptyList(), isLoading = false, hasMore = false, error = null)
                }
                return@launch
            }

            sources.forEach { source ->
                sourcesByKey[source.key] = source
                sourceRows[source.key] = CatalogRow(
                    addonId = source.addonId,
                    addonName = source.addonName,
                    addonBaseUrl = source.addonBaseUrl,
                    catalogId = source.catalogId,
                    catalogName = source.catalogName,
                    type = ContentType.UNKNOWN,
                    rawType = source.type,
                    items = emptyList(),
                    supportsSkip = source.supportsSkip,
                    skipStep = source.skipStep
                )
            }
            _uiState.update { it.copy(kind = currentKind, sources = sources, isLoading = true, error = null) }

            sources.forEach { source ->
                sourceLoadJobs += viewModelScope.launch { fetchFirstPage(source) }
            }
            sourceLoadJobs.joinAll()
            _uiState.update { it.copy(isLoading = false) }
            val perSourceSummary = sourceRows.entries.joinToString { (k, r) -> "$k=${r.items.size}/m${r.hasMore}" }
            val hasMoreAny = sourceRows.entries.any { it.value.hasMore && it.key !in sourcesExhaustedForAll }
            Log.w(
                TAG,
                "Hub ${currentKind} loaded sources=${sources.size} merged=${mergedItems.size} " +
                    "perSource=$perSourceSummary hasMoreAny=$hasMoreAny"
            )
        }
    }

    /** Movie-type catalogs (or series/tv for the TV hub) that aren't Home-featured or user-disabled. */
    private fun buildHubSources(addons: List<Addon>): List<HubSource> {
        val result = mutableListOf<HubSource>()
        addons.forEach { addon ->
            addon.catalogs.forEach { catalog ->
                val matchesKind = when (currentKind) {
                    HubKind.MOVIES -> catalog.apiType.equals("movie", ignoreCase = true)
                    HubKind.TV -> isSeriesType(catalog.apiType)
                }
                if (!matchesKind) return@forEach
                if (catalog.isSearchOnlyCatalog()) return@forEach
                // Featured/curated catalogs stay on Home, not the hubs.
                if (catalog.isFeaturedHomeCatalog()) return@forEach
                if (isCatalogDisabledIn(
                        disabledHomeCatalogKeys,
                        addon.baseUrl,
                        addon.id,
                        catalog.apiType,
                        catalog.id,
                        catalog.name
                    )
                ) {
                    return@forEach
                }
                val source = HubSource(
                    key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    sourceLabel = hubSourceLabel(catalog.name),
                    type = catalog.apiType,
                    supportsSkip = catalog.supportsExtra("skip"),
                    skipStep = catalog.skipStep()
                )
                // Field-test hook: captures the live catalog split (Home vs hubs).
                Log.w(TAG, "Hub ${currentKind} source ${source.addonName}|${source.type}|${source.catalogId}|${source.catalogName}|skip=${source.supportsSkip}")
                result += source
            }
        }
        return result
    }

    private suspend fun fetchFirstPage(source: HubSource) {
        catalogLoadSemaphore.withPermit {
            catalogRepository.getCatalog(
                addonBaseUrl = source.addonBaseUrl,
                addonId = source.addonId,
                addonName = source.addonName,
                catalogId = source.catalogId,
                catalogName = source.catalogName,
                type = source.type,
                skip = 0,
                skipStep = source.skipStep,
                supportsSkip = source.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        mergeSourcePage(source, result.data)
                        Log.w(TAG, "Hub ${currentKind} first page ${source.key} items=${result.data.items.size}")
                    }
                    is NetworkResult.Error -> Log.w(
                        TAG,
                        "Hub ${currentKind} failed ${source.key} code=${result.code} msg=${result.message}"
                    )
                    NetworkResult.Loading -> { /* grid shows loading state */ }
                }
            }
        }
    }

    private fun mergeSourcePage(source: HubSource, page: CatalogRow) {
        val existing = sourceRows[source.key] ?: return
        sourceRows[source.key] = existing.mergeCatalogPage(page)
        page.items.forEach { item ->
            val key = hubDedupKey(item)
            if (seenItemKeys.add(key)) {
                mergedItems[key] = item
            }
        }
        publishState()
    }

    private suspend fun loadNextPageForSource(source: HubSource) {
        val row = sourceRows[source.key] ?: return
        if (!row.hasMore) {
            Log.w(TAG, "Hub ${currentKind} loadMore skip ${source.key} (no hasMore)")
            return
        }
        val skip = row.nextCatalogSkip()
        Log.w(TAG, "Hub ${currentKind} loadMore paging ${source.key} skip=$skip")
        val result = catalogRepository.getCatalog(
            addonBaseUrl = source.addonBaseUrl,
            addonId = source.addonId,
            addonName = source.addonName,
            catalogId = source.catalogId,
            catalogName = source.catalogName,
            type = source.type,
            skip = skip,
            skipStep = source.skipStep,
            supportsSkip = source.supportsSkip
        ).first()
        when (result) {
            is NetworkResult.Success -> {
                val existing = sourceRows[source.key] ?: return
                val mergedRow = existing.mergeCatalogPage(result.data)
                val newGlobalBefore = seenItemKeys.size
                result.data.items.forEach { item ->
                    val key = hubDedupKey(item)
                    if (seenItemKeys.add(key)) {
                        mergedItems[key] = item
                    }
                }
                // In "All" mode, a page with zero new globally-unique items adds
                // nothing to the merged grid — don't page this source again.
                val exhaustedForAll = selectedSourceKey == null &&
                    result.data.items.isNotEmpty() &&
                    seenItemKeys.size == newGlobalBefore
                sourceRows[source.key] = if (exhaustedForAll) {
                    mergedRow.copy(hasMore = false)
                } else {
                    mergedRow
                }
                if (exhaustedForAll) sourcesExhaustedForAll.add(source.key)
                publishState()
                Log.w(
                    TAG,
                    "Hub ${currentKind} loadMore result ${source.key} skip=$skip new=${result.data.items.size} " +
                        "hasMore=${mergedRow.hasMore} exhaustedForAll=$exhaustedForAll mergedNow=${mergedItems.size}"
                )
            }
            is NetworkResult.Error -> Log.w(
                TAG,
                "Hub ${currentKind} load-more failed ${source.key} code=${result.code} msg=${result.message}"
            )
            NetworkResult.Loading -> {}
        }
    }

    private fun rotateLoadMoreTarget(): HubSource? {
        val keys = sourcesByKey.keys.toList()
        if (keys.isEmpty()) return null
        var attempts = keys.size
        while (attempts > 0) {
            val key = keys[nextLoadMoreSourceIndex % keys.size]
            nextLoadMoreSourceIndex = (nextLoadMoreSourceIndex + 1) % keys.size
            attempts--
            if (key in sourcesExhaustedForAll) continue
            if (sourceRows[key]?.hasMore == true) {
                return sourcesByKey[key]
            }
        }
        return null
    }

    private fun publishState() {
        _uiState.update { state ->
            val items = if (selectedSourceKey == null) {
                mergedItems.values.toList()
            } else {
                // Dedup by the same key the grid uses, so a source returning two
                // items with one IMDb id can't produce a duplicate LazyGrid key.
                sourceRows[selectedSourceKey]?.items.orEmpty().let { rowItems ->
                    linkedMapOf<String, MetaPreview>().apply {
                        rowItems.forEach { item -> putIfAbsent(hubDedupKey(item), item) }
                    }.values.toList()
                }
            }
            state.copy(
                kind = currentKind,
                items = items,
                selectedSourceKey = selectedSourceKey,
                hasMore = sourceRows.entries.any { it.value.hasMore && it.key !in sourcesExhaustedForAll },
                isLoadingMore = loadMoreJob?.isActive == true
            )
        }
    }

    private fun clearSourceData() {
        sourcesByKey.clear()
        sourceRows.clear()
        mergedItems.clear()
        seenItemKeys.clear()
        sourcesExhaustedForAll.clear()
        nextLoadMoreSourceIndex = 0
        loadMoreJob?.cancel()
    }
}
