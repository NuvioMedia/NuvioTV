package com.nuvio.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    private var revealBatchAfterNextDiscoverFetch = false

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
    }

    init {
        viewModelScope.launch {
            layoutPreferenceDataStore.searchDiscoverEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(discoverEnabled = enabled) }
                if (enabled) {
                    loadDiscoverCatalogs()
                } else {
                    discoverJob?.cancel()
                    _uiState.update {
                        it.copy(
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverResults = emptyList(),
                            pendingDiscoverResults = emptyList()
                        )
                    }
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            SearchEvent.Retry -> performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            val submitted = it.submittedQuery.trim()
            it.copy(
                query = query,
                error = null,
                isSearching = false,
                catalogRows = if (trimmedInput == submitted) it.catalogRows else emptyList()
            )
        }

        // Search is explicit on submit only; stop any in-flight requests while editing.
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
    }

    private fun submitSearch() {
        performSearch(_uiState.value.query)
    }

    private fun performSearch(rawQuery: String) {
        val query = rawQuery.trim()

        _uiState.update {
            it.copy(
                submittedQuery = query,
                query = rawQuery
            )
        }

        // Cancel any in-flight work from the previous query.
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        catalogRowsUpdateJob?.cancel()

        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0

        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            if (_uiState.value.discoverEnabled && !_uiState.value.discoverInitialized) {
                viewModelScope.launch {
                    loadDiscoverCatalogs()
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null, catalogRows = emptyList()) }

            val addons = try {
                addonRepository.getInstalledAddons().first()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false, error = e.message ?: "Failed to load addons") }
                return@launch
            }

            _uiState.update { it.copy(installedAddons = addons) }

            val searchTargets = buildSearchTargets(addons)

            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = "No searchable catalogs found in installed addons",
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            val jobs = searchTargets.map { (addon, catalog) ->
                viewModelScope.launch {
                    loadCatalog(addon, catalog, query)
                }
            }
            pendingCatalogResponses = jobs.size
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            viewModelScope.launch {
                try {
                    jobs.joinAll()
                } catch (_: Exception) {
                    // Cancellations are expected when query changes.
                } finally {
                    if (uiState.value.submittedQuery.trim() == query) {
                        _uiState.update { it.copy(isSearching = false) }
                    }
                }
            }
        }
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, query: String) {
        val supportsSkip = catalog.extra.any { it.name == "skip" }
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (uiState.value.submittedQuery.trim() != query) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    catalogsMap[key] = result.data.copy(
                        catalogName = searchCatalogLabel(catalog.apiType)
                    )
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate()
                }
                is NetworkResult.Error -> {
                    if (uiState.value.submittedQuery.trim() != query) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update { it.copy(error = result.message ?: "Search failed") }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val query = uiState.value.query.trim()
        if (query.isBlank()) return

        viewModelScope.launch {
            val addon = uiState.value.installedAddons.find { it.id == addonId } ?: run {
                catalogsMap[key] = currentRow.copy(isLoading = false)
                scheduleCatalogRowsUpdate()
                return@launch
            }

            // Use actual loaded item count for skip, not fixed 100-page size
            val nextSkip = currentRow.items.size
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val mergedItems = currentRow.items + result.data.items
                        catalogsMap[key] = result.data.copy(items = mergedItems)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        scheduleCatalogRowsUpdate()
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun scheduleCatalogRowsUpdate() {
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.mapNotNull { key -> catalogsMap[key] }
            state.copy(
                catalogRows = orderedRows
            )
        }
    }

    private suspend fun loadDiscoverCatalogs() {
        if (!_uiState.value.discoverEnabled) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !catalog.extra.any { it.name == "search" && it.isRequired }
                }
                .map { catalog ->
                    val genres = catalog.extra
                        .firstOrNull { it.name == "genre" }
                        ?.options
                        .orEmpty()
                    DiscoverCatalog(
                        key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = genres,
                        supportsSkip = catalog.extra.any { it.name == "skip" }
                    )
                }
        }

        val availableTypes = discoverCatalogs.map { it.type }.distinct()
        val currentType = _uiState.value.selectedDiscoverType
        val selectedType = if (currentType in availableTypes) currentType else availableTypes.firstOrNull() ?: "movie"
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = discoverCatalogs,
            selectedType = selectedType,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true,
                discoverPage = 1
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = null,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (state.query.trim().isNotEmpty()) return
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverPage = 1,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            val currentPage = if (reset) 1 else state.discoverPage + 1
            val skip = if (currentPage <= 1) 0 else state.discoverResults.size + state.pendingDiscoverResults.size
            val visibleCountBeforeRequest = state.discoverResults.size
            val extraArgs = buildMap<String, String> {
                state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = selectedCatalog.catalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val deduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverPage = if (shouldStopPagination) it.discoverPage else currentPage
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    catalog.extra.any { it.name == "search" }
                }
                .map { catalog -> addon to catalog }
        }

        val requiredSearchTargets = allSearchTargets.filter { (_, catalog) ->
            catalog.extra.any { it.name == "search" && it.isRequired }
        }

        return if (requiredSearchTargets.isNotEmpty()) {
            requiredSearchTargets
        } else {
            allSearchTargets
        }
    }

    private fun searchCatalogLabel(apiType: String): String {
        return when (apiType.lowercase(Locale.ROOT)) {
            "movie" -> "Search Movies"
            "series" -> "Search Series"
            else -> "Search ${apiType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}"
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }
}
