package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.GridItem
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.ModernCarouselRowBuildCache
import com.nuvio.tv.ui.screens.home.ModernHomePresentationInput
import com.nuvio.tv.ui.screens.home.buildModernHomePresentation
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val showFullReleaseDate: Boolean = true,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
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
    val source: CollectionSource? = null,
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
    @ApplicationContext private val appContext: Context,
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
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val trailerService: TrailerService,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
) : ViewModel() {

    private val collectionId: String = savedStateHandle["collectionId"] ?: ""
    private val folderId: String = savedStateHandle["folderId"] ?: ""

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    private var movieWatchedJob: Job? = null
    private var enrichFocusJob: Job? = null
    private val enrichedItemIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    private val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()
    private val _trailerPreviewUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val trailerPreviewUrls: StateFlow<Map<String, String>> = _trailerPreviewUrls.asStateFlow()
    private val _trailerPreviewAudioUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val trailerPreviewAudioUrls: StateFlow<Map<String, String>> = _trailerPreviewAudioUrls.asStateFlow()
    private val trailerPreviewLoadingIds = mutableSetOf<String>()
    private val trailerPreviewNegativeCache = mutableSetOf<String>()
    private val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    private var activeTrailerPreviewItemId: String? = null
    private var trailerPreviewRequestVersion: Long = 0L

    private val _rowsFocusState = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState())
    val rowsFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = _rowsFocusState.asStateFlow()

    private val _followLayoutFocusState = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState())
    val followLayoutFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = _followLayoutFocusState.asStateFlow()

    private val _tabFocusStates = MutableStateFlow<Map<Int, FolderDetailGridFocusState>>(emptyMap())
    val tabFocusStates: StateFlow<Map<Int, FolderDetailGridFocusState>> = _tabFocusStates.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        loadFolder()
        // Observe watched status immediately so badges are ready when catalogs load.
        observeWatchedStatusCombined()
    }

    private fun observeWatchedStatusCombined() {
        movieWatchedJob = viewModelScope.launch {
            combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds,
                _uiState.map { state -> state.tabs.flatMap { it.catalogRow?.items.orEmpty() } }
                    .distinctUntilChanged()
            ) { movieWatchedIds, seriesWatchedIds, allItems ->
                Triple(movieWatchedIds, seriesWatchedIds, allItems)
            }.collectLatest { (movieWatchedIds, seriesWatchedIds, allItems) ->
                val newStatus = mutableMapOf<String, Boolean>()
                allItems.forEach { item ->
                    val key = com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)
                    val isWatched = when (item.apiType) {
                        "movie" -> item.id in movieWatchedIds
                        "series", "tv" -> item.id in seriesWatchedIds
                        else -> false
                    }
                    newStatus[key] = isWatched
                }
                _uiState.update { s ->
                    if (s.movieWatchedStatus == newStatus) s else s.copy(movieWatchedStatus = newStatus)
                }
                rebuildFollowLayoutState()
            }
        }
    }

    private val hasAllTab: Boolean
        get() {
            val state = _uiState.value
            val folder = state.folder ?: return false
            return state.tabs.firstOrNull()?.isAllTab == true && folder.sources.size >= 2
        }

    private fun loadFolder() {
        viewModelScope.launch {
            val collections = collectionsDataStore.collections.first()
            val collection = collections.find { it.id == collectionId }
            val folder = collection?.folders?.find { it.id == folderId }

            if (folder == null || folder.sources.isEmpty()) {
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
            val posterLabelsEnabled = layoutPreferenceDataStore.posterLabelsEnabled.first()
            val catalogAddonNameEnabled = layoutPreferenceDataStore.catalogAddonNameEnabled.first()
            val catalogTypeSuffixEnabled = layoutPreferenceDataStore.catalogTypeSuffixEnabled.first()
            val hideUnreleasedContent = layoutPreferenceDataStore.hideUnreleasedContent.first()
            val showFullReleaseDate = layoutPreferenceDataStore.showFullReleaseDate.first()
            val modernLandscapePosters = layoutPreferenceDataStore.modernLandscapePostersEnabled.first()
            val modernFullScreenBackdrop = layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled.first()
            val focusedPosterBackdropExpandEnabled = layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled.first()
            val focusedPosterBackdropExpandDelaySeconds = layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds.first()
            val focusedPosterBackdropTrailerEnabled = layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled.first()
            val focusedPosterBackdropTrailerMuted = layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted.first()
            val focusedPosterBackdropTrailerPlaybackTarget =
                layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget.first()
            val posterCardWidthDp = layoutPreferenceDataStore.posterCardWidthDp.first()
            val posterCardHeightDp = layoutPreferenceDataStore.posterCardHeightDp.first()
            val posterCardCornerRadiusDp = layoutPreferenceDataStore.posterCardCornerRadiusDp.first()
            val showAll = (collection?.showAllTab ?: true) && folder.sources.size >= 2

            val sourceTabs = folder.sources.map { source ->
                val (name, typeLabel, rawType) = when (source) {
                    is AddonCatalogCollectionSource -> {
                        val addon = addons.find { it.id == source.addonId }
                        val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
                            ?: addon?.catalogs?.find { it.id == source.catalogId.substringBefore(",") && it.apiType == source.type }
                            ?: addons.firstNotNullOfOrNull { a -> a.catalogs.find { it.id == source.catalogId && it.apiType == source.type } }
                        val labels = buildAddonTabLabels(source, catalog?.name)
                        Triple(labels.first, labels.second, source.type)
                    }
                    is TmdbCollectionSource -> Triple(source.title, buildTmdbTypeLabel(source), source.mediaType.value.toCollectionRawType())
                }
                FolderTab(label = name, typeLabel = typeLabel, rawType = rawType, source = source, isLoading = true)
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
                    posterLabelsEnabled = posterLabelsEnabled,
                    catalogAddonNameEnabled = catalogAddonNameEnabled,
                    catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
                    hideUnreleasedContent = hideUnreleasedContent,
                    showFullReleaseDate = showFullReleaseDate,
                    modernLandscapePostersEnabled = modernLandscapePosters,
                    modernHeroFullScreenBackdropEnabled = modernFullScreenBackdrop,
                    focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
                    focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
                    focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled &&
                        AppFeaturePolicy.inAppTrailerPlaybackEnabled,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    focusedPosterBackdropTrailerPlaybackTarget = focusedPosterBackdropTrailerPlaybackTarget,
                    posterCardWidthDp = posterCardWidthDp,
                    posterCardHeightDp = posterCardHeightDp,
                    posterCardCornerRadiusDp = posterCardCornerRadiusDp,
                    tabs = tabs,
                    isLoading = false
                )
            }

            // The offset for source tab indices when "All" tab is present
            val tabOffset = if (showAll) 1 else 0

            folder.sources.forEachIndexed { index, source ->
                loadSourceForTab(index + tabOffset, source)
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
                if (row.hasMore && !row.isLoading) {
                    add(GridItem.SeeAll(
                        catalogId = row.catalogId,
                        addonId = row.addonId,
                        type = row.apiType
                    ))
                }
            }
        }

        val anyLoading = sourceTabs.any { it.isLoading }

        // Build modern presentation so ModernHomeContent has carousel rows to render.
        val modernPresentation = _uiState.value.let { s ->
            if (s.homeLayout == HomeLayout.MODERN) {
                buildModernHomePresentation(
                    input = ModernHomePresentationInput(
                        homeRows = homeRows,
                        catalogRows = loadedRows,
                        continueWatchingItems = emptyList(),
                        useLandscapePosters = s.modernLandscapePostersEnabled,
                        showCatalogTypeSuffix = s.catalogTypeSuffixEnabled,
                        showFullReleaseDate = s.showFullReleaseDate
                    ),
                    cache = modernCarouselRowBuildCache,
                    context = appContext
                )
            } else null
        }

        _uiState.update { s ->
            val homeState = HomeUiState(
                catalogRows = loadedRows,
                homeRows = homeRows,
                gridItems = gridItems,
                heroItems = emptyList(),
                heroSectionEnabled = false,
                isLoading = anyLoading,
                homeLayout = s.homeLayout,
                posterLabelsEnabled = if (s.homeLayout == HomeLayout.MODERN) false else s.posterLabelsEnabled,
                modernLandscapePostersEnabled = s.modernLandscapePostersEnabled,
                modernHeroFullScreenBackdropEnabled = s.modernHeroFullScreenBackdropEnabled,
                catalogAddonNameEnabled = s.catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = s.catalogTypeSuffixEnabled,
                focusedPosterBackdropExpandEnabled = s.focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = s.focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = s.focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = s.focusedPosterBackdropTrailerMuted,
                focusedPosterBackdropTrailerPlaybackTarget = s.focusedPosterBackdropTrailerPlaybackTarget,
                posterCardWidthDp = s.posterCardWidthDp,
                posterCardHeightDp = s.posterCardHeightDp,
                posterCardCornerRadiusDp = s.posterCardCornerRadiusDp,
                hideUnreleasedContent = s.hideUnreleasedContent,
                showFullReleaseDate = s.showFullReleaseDate,
                movieWatchedStatus = s.movieWatchedStatus
            )
            s.copy(followLayoutHomeState = if (modernPresentation != null) {
                homeState.copy(modernHomePresentation = modernPresentation)
            } else {
                homeState
            })
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

    private fun loadSourceForTab(tabIndex: Int, source: CollectionSource) {
        when (source) {
            is AddonCatalogCollectionSource -> loadAddonCatalogForTab(tabIndex, source)
            is TmdbCollectionSource -> loadTmdbSourceForTab(tabIndex, source, page = 1, append = false)
        }
    }

    private fun loadAddonCatalogForTab(tabIndex: Int, source: AddonCatalogCollectionSource) {
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

            val supportsSkip = catalog?.supportsExtra("skip") ?: false
            val skipStep = catalog?.skipStep() ?: 100
            val extraArgs = buildCatalogExtraArgs(source)

            catalogRepository.getCatalog(
                addonBaseUrl = effectiveAddon.baseUrl,
                addonId = effectiveAddon.id,
                addonName = effectiveAddon.displayName,
                catalogId = source.catalogId,
                catalogName = catalogName,
                type = source.type,
                skip = 0,
                skipStep = skipStep,
                extraArgs = extraArgs,
                supportsSkip = supportsSkip
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

    fun loadMoreItems(tabIndex: Int) {
        val state = _uiState.value
        val tab = state.tabs.getOrNull(tabIndex) ?: return

        // All tab: load more from all source tabs that still have more
        if (tab.isAllTab && hasAllTab) {
            val tabOffset = 1
            state.tabs.drop(tabOffset).forEachIndexed { index, sourceTab ->
                val sourceRow = sourceTab.catalogRow ?: return@forEachIndexed
                if (sourceRow.hasMore && !sourceRow.isLoading) {
                    loadMoreItems(index + tabOffset)
                }
            }
            return
        }

        val row = tab.catalogRow ?: return
        if (!row.hasMore || row.isLoading) return

        if (tab.source is TmdbCollectionSource) {
            loadTmdbSourceForTab(tabIndex, tab.source, page = row.currentPage + 1, append = true)
            return
        }

        // Mark the tab's catalogRow as loading
        _uiState.update { s ->
            val tabs = s.tabs.toMutableList()
            if (tabIndex < tabs.size) {
                tabs[tabIndex] = tabs[tabIndex].copy(
                    catalogRow = row.copy(isLoading = true)
                )
            }
            s.copy(tabs = tabs)
        }
        rebuildAllTab()
        rebuildFollowLayoutState()

        viewModelScope.launch {
            val nextSkip = (row.currentPage + 1) * row.skipStep

            catalogRepository.getCatalog(
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                addonName = row.addonName,
                catalogId = row.catalogId,
                catalogName = row.catalogName,
                type = row.apiType,
                skip = nextSkip,
                skipStep = row.skipStep,
                extraArgs = row.extraArgs,
                supportsSkip = row.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { s ->
                            val currentTab = s.tabs.getOrNull(tabIndex)
                            val currentRow = currentTab?.catalogRow ?: return@update s
                            val existingIds = currentRow.items.map { "${it.apiType}:${it.id}" }.toHashSet()
                            val newItems = result.data.items.filter { "${it.apiType}:${it.id}" !in existingIds }
                            val mergedItems = currentRow.items + newItems
                            val hasMore = if (newItems.isEmpty()) false else result.data.hasMore

                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = result.data.copy(
                                    items = mergedItems,
                                    hasMore = hasMore,
                                    isLoading = false
                                )
                            )
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val currentRow = s.tabs.getOrNull(tabIndex)?.catalogRow ?: return@update s
                            val tabs = s.tabs.toMutableList()
                            tabs[tabIndex] = tabs[tabIndex].copy(
                                catalogRow = currentRow.copy(isLoading = false)
                            )
                            s.copy(tabs = tabs)
                        }
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun loadMoreForCatalog(catalogId: String, addonId: String, type: String) {
        val state = _uiState.value
        val tabIndex = state.tabs.indexOfFirst { tab ->
            val row = tab.catalogRow ?: return@indexOfFirst false
            row.catalogId == catalogId && row.addonId == addonId && row.apiType == type
        }
        if (tabIndex >= 0) loadMoreItems(tabIndex)
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

    private fun loadTmdbSourceForTab(tabIndex: Int, source: TmdbCollectionSource, page: Int, append: Boolean) {
        if (append) {
            _uiState.update { s ->
                val tabs = s.tabs.toMutableList()
                val row = tabs.getOrNull(tabIndex)?.catalogRow
                if (row != null) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row.copy(isLoading = true))
                s.copy(tabs = tabs)
            }
            rebuildAllTab()
            rebuildFollowLayoutState()
        }
        viewModelScope.launch {
            tmdbCollectionSourceResolver.resolve(source, page).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val currentRow = tabs.getOrNull(tabIndex)?.catalogRow
                            val row = if (append && currentRow != null) {
                                val existingIds = currentRow.items.map { "${it.apiType}:${it.id}" }.toHashSet()
                                val newItems = result.data.items.filter { "${it.apiType}:${it.id}" !in existingIds }
                                result.data.copy(
                                    items = currentRow.items + newItems,
                                    hasMore = result.data.hasMore && newItems.isNotEmpty(),
                                    isLoading = false
                                )
                            } else {
                                result.data
                            }
                            if (tabIndex < tabs.size) tabs[tabIndex] = tabs[tabIndex].copy(catalogRow = row, isLoading = false)
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    is NetworkResult.Error -> {
                        _uiState.update { s ->
                            val tabs = s.tabs.toMutableList()
                            val current = tabs.getOrNull(tabIndex)
                            if (current != null) {
                                tabs[tabIndex] = current.copy(
                                    isLoading = false,
                                    error = result.message,
                                    catalogRow = current.catalogRow?.copy(isLoading = false)
                                )
                            }
                            s.copy(tabs = tabs)
                        }
                        rebuildAllTab()
                        rebuildFollowLayoutState()
                    }
                    NetworkResult.Loading -> {}
                }
            }
        }
    }

    private fun buildAddonTabLabels(source: AddonCatalogCollectionSource, catalogName: String?): Pair<String, String> {
        val typeLabel = when (source.type.lowercase()) {
            "movie" -> "Movies"
            "series" -> "Series"
            else -> source.type.replaceFirstChar { it.uppercase() }
        }
        val baseName = if (!catalogName.isNullOrBlank()) {
            catalogName.replaceFirstChar { it.uppercase() }
        } else {
            typeLabel
        }
        val name = source.genre?.takeIf { it.isNotBlank() }?.let { "$baseName · $it" } ?: baseName
        return name to typeLabel
    }

    private fun buildTmdbTypeLabel(source: TmdbCollectionSource): String {
        return when (source.sourceType.name) {
            "LIST" -> "TMDB List"
            "COLLECTION" -> "TMDB Movie Collection"
            "COMPANY" -> "Production"
            "NETWORK" -> "Network"
            else -> "TMDB Discover"
        }
    }

    private fun String.toCollectionRawType(): String {
        return if (lowercase() == "tv") "series" else this
    }

    private fun buildCatalogExtraArgs(source: AddonCatalogCollectionSource): Map<String, String> {
        val genre = source.genre?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf("genre" to genre)
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
            val homeLayout = _uiState.value.homeLayout
            val tmdbEnabled = tmdbSettings.enabled &&
                (homeLayout != HomeLayout.MODERN || tmdbSettings.modernHomeEnabled)
            val externalMetaEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()
            if (!tmdbEnabled && !externalMetaEnabled) {
                if (_enrichingItemId.value == item.id) _enrichingItemId.value = null
                return@launch
            }

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

            if (enrichment == null && !externalMetaEnabled) return@launch
            enrichedItemIds.add(item.id)

            // Apply TMDB enrichment if available.
            if (enrichment != null) {
                val finalEnrichment = enrichment

                updateItemInTabs(item.id) { merged ->
                    var result = merged
                if (finalEnrichment != null) {
                    if (tmdbSettings.useBasicInfo) {
                        val isModern = _uiState.value.homeLayout == HomeLayout.MODERN
                        result = result.copy(
                            name = if (isModern) finalEnrichment.localizedTitle ?: result.name else result.name,
                            description = finalEnrichment.description ?: result.description,
                            genres = if (finalEnrichment.genres.isNotEmpty()) finalEnrichment.genres else result.genres
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
                            runtime = finalEnrichment.runtimeMinutes?.toString() ?: result.runtime,
                            ageRating = finalEnrichment.ageRating ?: result.ageRating,
                            status = finalEnrichment.status ?: result.status
                        )
                    }
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
            // Emit enriched preview for Modern expanded poster cards.
            val enrichedItem = _uiState.value.tabs
                .firstNotNullOfOrNull { tab ->
                    tab.catalogRow?.items?.firstOrNull { it.id == item.id }
                }
            if (enrichedItem != null) {
                _enrichedPreviews.update { it + (item.id to enrichedItem) }
            }
            rebuildFollowLayoutState()
        }
    }

    fun requestTrailerPreview(itemId: String, title: String, releaseInfo: String?, apiType: String) {
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
        if (activeTrailerPreviewItemId != itemId) {
            activeTrailerPreviewItemId = itemId
            trailerPreviewRequestVersion++
        }
        if (itemId in trailerPreviewNegativeCache) return
        if (_trailerPreviewUrls.value.containsKey(itemId)) return
        if (!trailerPreviewLoadingIds.add(itemId)) return

        val requestVersion = trailerPreviewRequestVersion
        viewModelScope.launch {
            val tmdbId = runCatching { tmdbService.ensureTmdbId(itemId, apiType) }.getOrNull()
            val trailerSource = trailerService.getTrailerPlaybackSource(
                title = title,
                year = extractYear(releaseInfo),
                tmdbId = tmdbId,
                type = apiType
            )

            val isLatestFocusedItem =
                activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
            if (!isLatestFocusedItem) {
                trailerPreviewLoadingIds.remove(itemId)
                return@launch
            }

            if (trailerSource?.videoUrl.isNullOrBlank()) {
                trailerPreviewNegativeCache.add(itemId)
                _trailerPreviewUrls.update { it - itemId }
                _trailerPreviewAudioUrls.update { it - itemId }
            } else {
                _trailerPreviewUrls.update { it + (itemId to trailerSource.videoUrl) }
                val audioUrl = trailerSource.audioUrl
                if (audioUrl.isNullOrBlank()) {
                    _trailerPreviewAudioUrls.update { it - itemId }
                } else {
                    _trailerPreviewAudioUrls.update { it + (itemId to audioUrl) }
                }
            }

            trailerPreviewLoadingIds.remove(itemId)
        }
    }

    private fun extractYear(releaseInfo: String?): String? {
        if (releaseInfo.isNullOrBlank()) return null
        return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
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
