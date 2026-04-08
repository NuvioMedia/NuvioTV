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
import com.nuvio.tv.ui.screens.home.GridItem
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val followLayoutHomeState: HomeUiState? = null
)

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
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
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

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
                val (name, typeLabel) = buildTabLabels(source, catalog?.name)
                FolderTab(label = name, typeLabel = typeLabel, isLoading = true)
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
                posterLabelsEnabled = true
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

            val catalog = addon.catalogs.find { it.id == source.catalogId && it.apiType == source.type }
            val catalogName = catalog?.name ?: source.catalogId

            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
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
}
