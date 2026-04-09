package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.GridItem
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val tabs: List<FolderTab> = emptyList(),
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true,
    val followLayoutHomeState: HomeUiState? = null,
    val movieWatchedStatus: Map<String, Boolean> = emptyMap()
)

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val rawType: String = "",
    val catalogRow: CatalogRow? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isAllTab: Boolean = false
)

data class FolderDetailGridFocusState(
    val verticalScrollIndex: Int = 0,
    val verticalScrollOffset: Int = 0,
    val focusedItemKey: String? = null,
    val hasSavedFocus: Boolean = false
)

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbMetadataService: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    private val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore,
    private val mdbListRepository: com.nuvio.tv.data.repository.MDBListRepository,
    private val mdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    private var movieWatchedJob: Job? = null
    private var seriesWatchedJob: Job? = null
    private var enrichFocusJob: Job? = null
    private val enrichedItemIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()

    private val _rowsFocusState = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState())
    val rowsFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = _rowsFocusState.asStateFlow()

    private val _followLayoutFocusState = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState())
    val followLayoutFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = _followLayoutFocusState.asStateFlow()

    private val _tabFocusStates = MutableStateFlow<Map<Int, FolderDetailGridFocusState>>(emptyMap())
    val tabFocusStates: StateFlow<Map<Int, FolderDetailGridFocusState>> = _tabFocusStates.asStateFlow()

    init {
        loadFolder()
    }

    private val hasAllTab: Boolean
        get() {
            val state = _uiState.value
            val folder = state.folder ?: return false
            return state.tabs.firstOrNull()?.isAllTab == true && folder.catalogSources.size >= 2
        }

    private fun loadFolder() {
        viewModelScope.launch {
            val collections = collectionsDataStore.collections.first()
            val collection = collections.find { it.id == collectionId }
            val folder = collection?.folders?.find { it.id == folderId }

            if (folder == null || folder.catalogSources.isEmpty()) {
                _uiState.update {
                    it.copy(
                        folder = folder,
                        collectionTitle = collection?.title ?: "",
                        viewMode = collection?.viewMode ?: FolderViewMode.TABBED_GRID,
                        isLoading = false
                    )
                }
                return@launch
            }

            val addons = addonRepository.getInstalledAddons().first()
            val homeLayout = layoutPreferenceDataStore.selectedLayout.first()
            val modernLandscapePosters = layoutPreferenceDataStore.modernLandscapePostersEnabled.first()
            val modernFullScreenBackdrop = layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled.first()
            val showAll = (collection?.showAllTab ?: true) && folder.catalogSources.size >= 2

            val sourceTabs = folder.catalogSources.map { source ->
                val addon = addons.find { it.id == source.addonId }
                val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
                    ?: addon?.catalogs?.find { it.id == source.catalogId.substringBefore(",") && it.apiType == source.type }
                    ?: addons.firstNotNullOfOrNull { a -> a.catalogs.find { it.id == source.catalogId && it.apiType == source.type } }
                val (name, typeLabel) = buildTabLabels(source, catalog?.name)
                FolderTab(label = name, typeLabel = typeLabel, rawType = source.type, isLoading = true)
            }

            val tabs = if (showAll) {
                listOf(FolderTab(label = "All", typeLabel = "Combined", isLoading = true, isAllTab = true)) + sourceTabs
            } else {
                sourceTabs
            }

            _uiState.update {
                it.copy(
                    folder = folder,
                    collectionTitle = collection?.title ?: "",
                    viewMode = collection?.viewMode ?: FolderViewMode.TABBED_GRID,
                    homeLayout = homeLayout,
                    modernLandscapePostersEnabled = modernLandscapePosters,
                    modernHeroFullScreenBackdropEnabled = modernFullScreenBackdrop,
                    tabs = tabs,
                    isLoading = false
                )
            }

            // The offset for source tab indices when "All" tab is present
            val tabOffset = if (showAll) 1 else 0

            folder.catalogSources.forEachIndexed { index, source ->
                loadCatalogForTab(index + tabOffset, source)
            }
        }
    }

    private fun rebuildAllTab() {
        val state = _uiState.value
        if (!hasAllTab) return
        val sourceTabs = state.tabs.drop(1) // skip the All tab
        val anyLoading = sourceTabs.any { it.isLoading }
        val loadedRows = sourceTabs.mapNotNull { it.catalogRow }

        if (loadedRows.isEmpty()) return

        // Round-robin interleave items from all loaded catalog rows
        val mergedItems = roundRobinMerge(loadedRows.map { it.items })
        // Use the first loaded row as a template for the merged CatalogRow
        val templateRow = loadedRows.first()
        val mergedRow = templateRow.copy(
            catalogName = "All",
            items = mergedItems
        )

        _uiState.update { s ->
            val tabs = s.tabs.toMutableList()
            tabs[0] = tabs[0].copy(
                catalogRow = mergedRow,
                isLoading = anyLoading
            )
            s.copy(tabs = tabs)
        }
    }

    private fun rebuildFollowLayoutState() {
        val state = _uiState.value
        if (state.viewMode != FolderViewMode.FOLLOW_LAYOUT) return
        val sourceTabs = state.tabs.filter { !it.isAllTab }
        val loadedRows = sourceTabs.mapNotNull { it.catalogRow }
        if (loadedRows.isEmpty()) return

        val homeRows = loadedRows.map { HomeRow.Catalog(it) }
        val gridItems = buildList<GridItem> {
            loadedRows.forEach { row ->
                add(GridItem.SectionDivider(
                    catalogName = row.catalogName,
                    catalogId = row.catalogId,
                    addonBaseUrl = row.addonBaseUrl,
                    addonId = row.addonId,
                    type = row.apiType
                ))
                row.items.forEach { item ->
                    add(GridItem.Content(
                        item = item,
                        addonBaseUrl = row.addonBaseUrl,
                        catalogId = row.catalogId,
                        catalogName = row.catalogName
                    ))
                }
            }
        }

        val anyLoading = sourceTabs.any { it.isLoading }
        _uiState.update { s ->
            s.copy(followLayoutHomeState = HomeUiState(
                catalogRows = loadedRows,
                homeRows = homeRows,
                gridItems = gridItems,
                heroItems = emptyList(),
                heroSectionEnabled = false,
                isLoading = anyLoading,
                homeLayout = s.homeLayout,
                modernLandscapePostersEnabled = s.modernLandscapePostersEnabled,
                modernHeroFullScreenBackdropEnabled = s.modernHeroFullScreenBackdropEnabled,
                catalogAddonNameEnabled = false,
                catalogTypeSuffixEnabled = true,
                posterLabelsEnabled = true,
                movieWatchedStatus = s.movieWatchedStatus
            ))
        }
    }

    private fun roundRobinMerge(lists: List<List<MetaPreview>>): List<MetaPreview> {
        val result = mutableListOf<MetaPreview>()
        val seen = mutableSetOf<String>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxSize) {
            for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                if (seen.add(item.id)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun loadCatalogForTab(tabIndex: Int, source: CollectionCatalogSource) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first()
            val addon = addons.find { it.id == source.addonId }

            if (addon == null) {
                _uiState.update { state ->
                    val tabs = state.tabs.toMutableList()
                    if (tabIndex < tabs.size) {
                        tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = "Addon not found")
                    }
                    state.copy(tabs = tabs)
                }
                return@launch
            }

            var catalog = addon.catalogs.find { it.id == source.catalogId && it.apiType == source.type }
                ?: addon.catalogs.find { it.id == source.catalogId.substringBefore(",") && it.apiType == source.type }
            // If the catalog wasn't found in the declared addon, search all installed addons.
            var effectiveAddon: com.nuvio.tv.domain.model.Addon = addon
            if (catalog == null) {
                for (a in addons) {
                    val match = a.catalogs.find { it.id == source.catalogId && it.apiType == source.type }
                    if (match != null) {
                        effectiveAddon = a
                        catalog = match
                        break
                    }
                }
            }
            val tab = _uiState.value.tabs.getOrNull(tabIndex)
            val catalogName = catalog?.name ?: tab?.label?.takeIf { it != tab?.typeLabel } ?: source.catalogId

            catalogRepository.getCatalog(
                addonBaseUrl = effectiveAddon.baseUrl,
                addonId = effectiveAddon.id,
                addonName = effectiveAddon.displayName,
                catalogId = source.catalogId,
                catalogName = catalogName,
                type = source.type,
                skip = 0
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(
                                    catalogRow = result.data,
                                    isLoading = false
                                )
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                        observeWatchedStatus()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { state ->
                            val tabs = state.tabs.toMutableList()
                            if (tabIndex < tabs.size) {
                                tabs[tabIndex] = tabs[tabIndex].copy(isLoading = false, error = result.message)
                            }
                            state.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun saveTabFocusState(
        tabIndex: Int,
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedItemKey: String?
    ) {
        val nextState = FolderDetailGridFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedItemKey = focusedItemKey,
            hasSavedFocus = true
        )
        _tabFocusStates.update { states ->
            if (states[tabIndex] == nextState) states else states + (tabIndex to nextState)
        }
    }

    fun saveRowsFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>
    ) {
        val nextState = com.nuvio.tv.ui.screens.home.HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
        if (_rowsFocusState.value != nextState) {
            _rowsFocusState.value = nextState
        }
    }

    fun saveFollowLayoutFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>
    ) {
        val nextState = com.nuvio.tv.ui.screens.home.HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
        if (_followLayoutFocusState.value != nextState) {
            _followLayoutFocusState.value = nextState
        }
    }

    fun saveFollowLayoutGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedItemKey: String?
    ) {
        val nextState = com.nuvio.tv.ui.screens.home.HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedItemKey = focusedItemKey,
            hasSavedFocus = true
        )
        if (_followLayoutFocusState.value != nextState) {
            _followLayoutFocusState.value = nextState
        }
    }

    private fun observeWatchedStatus() {
        val allItems = _uiState.value.tabs
            .mapNotNull { it.catalogRow }
            .flatMap { it.items }

        val movieIds = mutableMapOf<String, String>()
        val seriesIds = mutableMapOf<String, String>()
        allItems.forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (item.apiType.equals("movie", ignoreCase = true)) {
                movieIds[key] = item.id
            } else if (item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)) {
                seriesIds[key] = item.id
            }
        }

        movieWatchedJob?.cancel()
        if (movieIds.isNotEmpty()) {
            movieWatchedJob = viewModelScope.launch {
                watchProgressRepository.observeWatchedMovieIds()
                    .collectLatest { watchedIds ->
                        val status = movieIds.mapValues { (_, contentId) -> contentId in watchedIds }
                        _uiState.update { s ->
                            val merged = s.movieWatchedStatus.filterKeys { it !in movieIds } + status
                            if (s.movieWatchedStatus == merged) s else s.copy(movieWatchedStatus = merged)
                        }
                        rebuildFollowLayoutState()
                    }
            }
        }

        seriesWatchedJob?.cancel()
        if (seriesIds.isNotEmpty()) {
            seriesWatchedJob = viewModelScope.launch {
                watchedSeriesStateHolder.fullyWatchedSeriesIds.collectLatest { fullyWatched ->
                    val status = seriesIds.mapValues { (_, contentId) -> contentId in fullyWatched }
                    _uiState.update { s ->
                        val merged = s.movieWatchedStatus.filterKeys { it !in seriesIds } + status
                        if (s.movieWatchedStatus == merged) s else s.copy(movieWatchedStatus = merged)
                    }
                    rebuildFollowLayoutState()
                }
            }
        }
    }

    private fun buildTabLabels(source: CollectionCatalogSource, catalogName: String?): Pair<String, String> {
        val typeLabel = when (source.type.lowercase()) {
            "movie" -> "Movies"
            "series" -> "Series"
            else -> source.type.replaceFirstChar { it.uppercase() }
        }
        val name = if (!catalogName.isNullOrBlank()) {
            catalogName.replaceFirstChar { it.uppercase() }
        } else {
            typeLabel
        }
        return name to typeLabel
    }

    fun onItemFocused(item: MetaPreview) {
        // Clear enriching for previous item immediately.
        if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
            _enrichingItemId.value = null
        }
        if (item.id in enrichedItemIds) return

        // Check if any enrichment source is active — if so, signal enriching immediately
        // so hero content hides until enrichment completes.
        val viewMode = _uiState.value.viewMode
        if (viewMode == FolderViewMode.FOLLOW_LAYOUT && _uiState.value.homeLayout == HomeLayout.MODERN) {
            _enrichingItemId.value = item.id
        }

        enrichFocusJob?.cancel()
        enrichFocusJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(350)
            val tmdbSettings = tmdbSettingsDataStore.settings.first()
            val mdbSettings = mdbListSettingsDataStore.settings.first()
            val homeLayout = _uiState.value.homeLayout
            val tmdbEnabled = tmdbSettings.enabled &&
                (homeLayout != HomeLayout.MODERN || tmdbSettings.modernHomeEnabled)
            val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()
            val externalMetaEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()
            if (!tmdbEnabled && !mdbEnabled && !externalMetaEnabled) {
                if (_enrichingItemId.value == item.id) _enrichingItemId.value = null
                return@launch
            }

            val mdbRating = if (mdbEnabled) {
                runCatching { mdbListRepository.getImdbRatingForItem(item.id, item.apiType) }.getOrNull()
            } else null

            var enrichment: com.nuvio.tv.core.tmdb.TmdbEnrichment? = null
            if (tmdbEnabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                if (tmdbId != null) {
                    enrichment = runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = tmdbSettings.language
                        )
                    }.getOrNull()
                }
            }

            if (enrichment == null && mdbRating == null && !externalMetaEnabled) return@launch
            enrichedItemIds.add(item.id)

            // Apply TMDB + MDB enrichment if available.
            if (enrichment != null || mdbRating != null) {
                val finalEnrichment = enrichment
                val finalMdbRating = mdbRating

                updateItemInTabs(item.id) { merged ->
                    var result = merged
                if (finalEnrichment != null) {
                    if (tmdbSettings.useBasicInfo) {
                        result = result.copy(
                            name = finalEnrichment.localizedTitle ?: result.name,
                            description = finalEnrichment.description ?: result.description,
                            genres = if (finalEnrichment.genres.isNotEmpty()) finalEnrichment.genres else result.genres,
                            imdbRating = finalEnrichment.rating?.toFloat() ?: result.imdbRating
                        )
                    }
                    if (tmdbSettings.useArtwork) {
                        result = result.copy(
                            background = finalEnrichment.backdrop ?: result.background,
                            logo = finalEnrichment.logo ?: result.logo
                        )
                    }
                    if (tmdbSettings.useReleaseDates) {
                        result = result.copy(
                            releaseInfo = finalEnrichment.releaseInfo ?: result.releaseInfo
                        )
                    }
                    if (tmdbSettings.useDetails) {
                        result = result.copy(
                            ageRating = finalEnrichment.ageRating ?: result.ageRating,
                            status = finalEnrichment.status ?: result.status
                        )
                    }
                }
                if (finalMdbRating != null && result.imdbRating == null) {
                    result = result.copy(imdbRating = finalMdbRating.toFloat())
                }
                result
            }
            }

            // External meta addon fallback when TMDB didn't enrich.
            if (enrichment == null && externalMetaEnabled) {
                val metaResult = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }
                if (metaResult is NetworkResult.Success) {
                    val meta = metaResult.data
                    updateItemInTabs(item.id) { merged ->
                        merged.copy(
                            name = meta.name.takeIf { it.isNotBlank() } ?: merged.name,
                            description = meta.description?.takeIf { it.isNotBlank() } ?: merged.description,
                            background = meta.background?.takeIf { it.isNotBlank() } ?: merged.background,
                            logo = meta.logo?.takeIf { it.isNotBlank() } ?: merged.logo,
                            genres = meta.genres.takeIf { it.isNotEmpty() } ?: merged.genres,
                            imdbRating = meta.imdbRating ?: merged.imdbRating,
                            releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: merged.releaseInfo
                        )
                    }
                }
            }

            // Sync enriched tabs into followLayoutHomeState for FOLLOW_LAYOUT mode.
            if (_enrichingItemId.value == item.id) _enrichingItemId.value = null
            rebuildFollowLayoutState()
        }
    }

    private fun updateItemInTabs(itemId: String, transform: (MetaPreview) -> MetaPreview) {
        _uiState.update { state ->
            var changed = false
            val updatedTabs = state.tabs.map { tab ->
                val row = tab.catalogRow ?: return@map tab
                val idx = row.items.indexOfFirst { it.id == itemId }
                if (idx < 0) return@map tab
                val merged = transform(row.items[idx])
                if (merged == row.items[idx]) return@map tab
                changed = true
                val items = row.items.toMutableList()
                items[idx] = merged
                tab.copy(catalogRow = row.copy(items = items))
            }
            if (changed) state.copy(tabs = updatedTabs) else state
        }
    }
}
