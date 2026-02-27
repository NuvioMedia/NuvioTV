package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.device.DeviceCapabilities
import com.nuvio.tv.core.device.DeviceTier
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import java.util.Locale

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val libraryRepository: LibraryRepository,
    private val metaRepository: MetaRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val trailerService: TrailerService,
    private val deviceCapabilities: DeviceCapabilities
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val CATALOG_WAVE_DELAY_MS = 120L
        private const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    private val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()
    private val activeCatalogLoadJobs = mutableMapOf<String, Job>()
    private var addonsCache: List<Addon> = emptyList()
    private var homeCatalogOrderKeys: List<String> = emptyList()
    private var disabledHomeCatalogKeys: Set<String> = emptySet()
    private var currentHeroCatalogKeys: List<String> = emptyList()
    private var activeCatalogLoadSessionId: Long = 0L
    private var catalogUpdateJob: Job? = null
    private var hasDispatchedFirstCatalogBatch = false
    @Volatile
    private var hasCatalogContentDrawn = false
    private var reconcilePosterObserversJob: Job? = null
    private var hasBootstrappedPosterObservers = false
    private val catalogLoadSemaphore = Semaphore(
        when (deviceCapabilities.tier) {
            DeviceTier.LOW -> 2
            DeviceTier.MEDIUM -> 3
            DeviceTier.HIGH -> 4
        }
    )
    private var pendingCatalogLoads = 0
    private data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    private val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    private var catalogVersion = 0L
    private var lastProcessedCatalogVersion = -1L
    private val trailerPreviewLoadingIds = mutableSetOf<String>()
    private val trailerPreviewNegativeCache = mutableSetOf<String>()
    private val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    private var activeTrailerPreviewItemId: String? = null
    private var trailerPreviewRequestVersion: Long = 0L
    private var currentTmdbSettings: TmdbSettings = TmdbSettings()
    private var heroEnrichmentJob: Job? = null
    private var lastHeroEnrichmentSignature: String? = null
    private var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    private val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    private var externalMetaPrefetchJob: Job? = null
    private var pendingExternalMetaPrefetchItemId: String? = null
    private val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    private val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    private val pendingLibraryStatusUpdates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val pendingWatchedStatusUpdates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var posterStatusFlushJob: Job? = null
    @Volatile
    private var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    private var startupGracePeriodActive: Boolean = true
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState

    init {
        observeLayoutPreferences()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeTmdbSettings()
        loadContinueWatching()
        observeInstalledAddons()
        viewModelScope.launch {
            delay(3000)
            startupGracePeriodActive = false
        }
    }

    private fun observeLayoutPreferences() {
        val coreLayoutPrefsFlow = combine(
            combine(
                layoutPreferenceDataStore.selectedLayout,
                layoutPreferenceDataStore.heroCatalogSelections,
                layoutPreferenceDataStore.heroSectionEnabled,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled
            ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
                CoreLayoutPrefs(
                    layout = layout,
                    heroCatalogKeys = heroCatalogKeys,
                    heroSectionEnabled = heroSectionEnabled,
                    posterLabelsEnabled = posterLabelsEnabled,
                    catalogAddonNameEnabled = catalogAddonNameEnabled,
                    catalogTypeSuffixEnabled = true
                )
            },
            layoutPreferenceDataStore.catalogTypeSuffixEnabled
        ) { corePrefs, catalogTypeSuffixEnabled ->
            corePrefs.copy(catalogTypeSuffixEnabled = catalogTypeSuffixEnabled)
        }

        val focusedBackdropPrefsFlow = combine(
            layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
            layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
        ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted, trailerPlaybackTarget ->
            FocusedBackdropPrefs(
                expandEnabled = expandEnabled,
                expandDelaySeconds = expandDelaySeconds,
                trailerEnabled = trailerEnabled,
                trailerMuted = trailerMuted,
                trailerPlaybackTarget = trailerPlaybackTarget
            )
        }

        val modernLayoutPrefsFlow = layoutPreferenceDataStore.modernLandscapePostersEnabled

        val baseLayoutUiPrefsFlow = combine(
            coreLayoutPrefsFlow,
            focusedBackdropPrefsFlow,
            layoutPreferenceDataStore.posterCardWidthDp,
            layoutPreferenceDataStore.posterCardHeightDp,
            layoutPreferenceDataStore.posterCardCornerRadiusDp
        ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
            LayoutUiPrefs(
                layout = corePrefs.layout,
                heroCatalogKeys = corePrefs.heroCatalogKeys,
                heroSectionEnabled = corePrefs.heroSectionEnabled,
                posterLabelsEnabled = corePrefs.posterLabelsEnabled,
                catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
                modernLandscapePostersEnabled = false,
                focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
                focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
                focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
                focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
                focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
                posterCardWidthDp = posterCardWidthDp,
                posterCardHeightDp = posterCardHeightDp,
                posterCardCornerRadiusDp = posterCardCornerRadiusDp
            )
        }

        viewModelScope.launch {
            combine(
                baseLayoutUiPrefsFlow,
                modernLayoutPrefsFlow
            ) { basePrefs, modernPrefs ->
                basePrefs.copy(
                    modernLandscapePostersEnabled = modernPrefs
                )
            }
                .distinctUntilChanged()
                .debounce(100)
                .collectLatest { prefs ->
                val effectivePosterLabelsEnabled = if (prefs.layout == HomeLayout.MODERN) {
                    false
                } else {
                    prefs.posterLabelsEnabled
                }
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    currentHeroCatalogKeys != prefs.heroCatalogKeys ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                val isLowTier = deviceCapabilities.tier == DeviceTier.LOW
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        focusedPosterBackdropExpandEnabled = if (isLowTier) false else prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = if (isLowTier) false else prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        focusedPosterBackdropTrailerPlaybackTarget = prefs.focusedBackdropTrailerPlaybackTarget,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp,
                        keyRepeatThrottleMs = deviceCapabilities.keyRepeatThrottleMs,
                        nestedPrefetchItemCount = deviceCapabilities.nestedPrefetchItemCount
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    scheduleUpdateCatalogRows()
                }
            }
        }
    }

    private fun observeExternalMetaPrefetchPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.preferExternalMetaAddonDetail
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    externalMetaPrefetchEnabled = enabled
                    if (!enabled) {
                        externalMetaPrefetchJob?.cancel()
                        pendingExternalMetaPrefetchItemId = null
                        externalMetaPrefetchInFlightIds.clear()
                    }
                }
        }
    }

    private data class CoreLayoutPrefs(
        val layout: HomeLayout,
        val heroCatalogKeys: List<String>,
        val heroSectionEnabled: Boolean,
        val posterLabelsEnabled: Boolean,
        val catalogAddonNameEnabled: Boolean,
        val catalogTypeSuffixEnabled: Boolean
    )

    private data class FocusedBackdropPrefs(
        val expandEnabled: Boolean,
        val expandDelaySeconds: Int,
        val trailerEnabled: Boolean,
        val trailerMuted: Boolean,
        val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
    )

    private data class LayoutUiPrefs(
        val layout: HomeLayout,
        val heroCatalogKeys: List<String>,
        val heroSectionEnabled: Boolean,
        val posterLabelsEnabled: Boolean,
        val catalogAddonNameEnabled: Boolean,
        val catalogTypeSuffixEnabled: Boolean,
        val modernLandscapePostersEnabled: Boolean,
        val focusedBackdropExpandEnabled: Boolean,
        val focusedBackdropExpandDelaySeconds: Int,
        val focusedBackdropTrailerEnabled: Boolean,
        val focusedBackdropTrailerMuted: Boolean,
        val focusedBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
        val posterCardWidthDp: Int,
        val posterCardHeightDp: Int,
        val posterCardCornerRadiusDp: Int
    )

    fun requestTrailerPreview(item: MetaPreview) {
        requestTrailerPreview(
            itemId = item.id,
            title = item.name,
            releaseInfo = item.releaseInfo,
            apiType = item.apiType
        )
    }

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) {
        if (startupGracePeriodActive) return
        if (activeTrailerPreviewItemId != itemId) {
            activeTrailerPreviewItemId = itemId
            trailerPreviewRequestVersion++
        }

        if (trailerPreviewNegativeCache.contains(itemId)) return
        if (trailerPreviewUrlsState.containsKey(itemId)) return
        if (!trailerPreviewLoadingIds.add(itemId)) return

        val requestVersion = trailerPreviewRequestVersion

        viewModelScope.launch {
            val trailerUrl = trailerService.getTrailerUrl(
                title = title,
                year = extractYear(releaseInfo),
                tmdbId = null,
                type = apiType
            )

            val isLatestFocusedItem =
                activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
            if (!isLatestFocusedItem) {
                trailerPreviewLoadingIds.remove(itemId)
                return@launch
            }

            if (trailerUrl.isNullOrBlank()) {
                trailerPreviewNegativeCache.add(itemId)
            } else {
                if (trailerPreviewUrlsState[itemId] != trailerUrl) {
                    trailerPreviewUrlsState[itemId] = trailerUrl
                }
            }

            trailerPreviewLoadingIds.remove(itemId)
        }
    }

    fun onItemFocus(item: MetaPreview) {
        if (startupGracePeriodActive) return
        if (!externalMetaPrefetchEnabled) return
        if (deviceCapabilities.tier == DeviceTier.LOW) return
        if (item.id in prefetchedExternalMetaIds) return
        if (pendingExternalMetaPrefetchItemId == item.id) return

        pendingExternalMetaPrefetchItemId = item.id
        externalMetaPrefetchJob?.cancel()
        externalMetaPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
            if (pendingExternalMetaPrefetchItemId != item.id) return@launch
            if (!externalMetaPrefetchEnabled) return@launch
            if (item.id in prefetchedExternalMetaIds) return@launch
            if (!externalMetaPrefetchInFlightIds.add(item.id)) return@launch
            try {
                val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }

                if (result is NetworkResult.Success) {
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithMeta(item.id, result.data)
                }
            } finally {
                externalMetaPrefetchInFlightIds.remove(item.id)
                if (pendingExternalMetaPrefetchItemId == item.id) {
                    pendingExternalMetaPrefetchItemId = null
                }
            }
        }
    }

    private fun updateCatalogItemWithMeta(itemId: String, meta: Meta) {
        fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
            background = meta.background ?: currentItem.background,
            logo = meta.logo ?: currentItem.logo,
            description = meta.description ?: currentItem.description,
            releaseInfo = meta.releaseInfo ?: currentItem.releaseInfo,
            imdbRating = meta.imdbRating ?: currentItem.imdbRating,
            genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres
        )

        // Update the source-of-truth catalogsMap so re-renders don't revert the enrichment
        catalogsMap.forEach { (key, row) ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex >= 0) {
                val merged = mergeItem(row.items[itemIndex])
                if (merged != row.items[itemIndex]) {
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = merged
                    catalogsMap[key] = row.copy(items = mutableItems)
                    catalogVersion++
                    truncatedRowCache.remove(key)
                }
            }
        }

        _uiState.update { state ->
            var changed = false
            val updatedRows = state.catalogRows.map { row ->
                val itemIndex = row.items.indexOfFirst { it.id == itemId }
                if (itemIndex < 0) {
                    row
                } else {
                    val mergedItem = mergeItem(row.items[itemIndex])
                    if (mergedItem == row.items[itemIndex]) {
                        row
                    } else {
                        changed = true
                        val mutableItems = row.items.toMutableList()
                        mutableItems[itemIndex] = mergedItem
                        row.copy(items = mutableItems)
                    }
                }
            }
            if (changed) state.copy(catalogRows = updatedRows) else state
        }
    }

    private fun loadHomeCatalogOrderPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeCatalogOrderKeys
                .distinctUntilChanged()
                .collectLatest { keys ->
                homeCatalogOrderKeys = keys
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
        }
    }

    private fun loadDisabledHomeCatalogPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.disabledHomeCatalogKeys
                .distinctUntilChanged()
                .collectLatest { keys ->
                disabledHomeCatalogKeys = keys.toSet()
                rebuildCatalogOrder(addonsCache)
                if (addonsCache.isNotEmpty()) {
                    loadAllCatalogs(addonsCache)
                } else {
                    scheduleUpdateCatalogRows()
                }
            }
        }
    }

    private fun observeTmdbSettings() {
        viewModelScope.launch {
            tmdbSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentTmdbSettings = settings
                    scheduleUpdateCatalogRows()
                }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache) }
        }
    }

    fun togglePosterLibrary(item: MetaPreview, addonBaseUrl: String?) {
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        if (statusKey in _uiState.value.posterLibraryPending) return

        _uiState.update { state ->
            state.copy(posterLibraryPending = state.posterLibraryPending + statusKey)
        }

        viewModelScope.launch {
            runCatching {
                libraryRepository.toggleDefault(item.toLibraryEntryInput(addonBaseUrl))
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle poster library for ${item.id}: ${error.message}")
            }
            _uiState.update { state ->
                state.copy(posterLibraryPending = state.posterLibraryPending - statusKey)
            }
        }
    }

    fun togglePosterMovieWatched(item: MetaPreview) {
        if (!item.apiType.equals("movie", ignoreCase = true)) return
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        if (statusKey in _uiState.value.movieWatchedPending) return

        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending + statusKey)
        }

        viewModelScope.launch {
            val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true
            runCatching {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(item.id)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle poster watched status for ${item.id}: ${error.message}")
            }
            _uiState.update { state ->
                state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
            }
        }
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            combine(
                watchProgressRepository.allProgress,
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                traktSettingsDataStore.showUnairedNextUp
            ) { items, daysCap, dismissedNextUp, showUnairedNextUp ->
                ContinueWatchingSettingsSnapshot(
                    items = items,
                    daysCap = daysCap,
                    dismissedNextUp = dismissedNextUp,
                    showUnairedNextUp = showUnairedNextUp
                )
            }.collectLatest { snapshot ->
                val preparedState = withContext(Dispatchers.Default) {
                    val items = snapshot.items
                    val daysCap = snapshot.daysCap
                    val cutoffMs = if (daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
                        null
                    } else {
                        val windowMs = daysCap.toLong() * 24L * 60L * 60L * 1000L
                        System.currentTimeMillis() - windowMs
                    }
                    val recentItems = items
                        .asSequence()
                        .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                        .sortedByDescending { it.lastWatched }
                        .take(MAX_RECENT_PROGRESS_ITEMS)
                        .toList()

                    val inProgressOnly = deduplicateInProgress(
                        recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
                    ).map { progress ->
                        ContinueWatchingItem.InProgress(progress = progress)
                    }

                    ContinueWatchingPreparedState(
                        allProgressCount = items.size,
                        recentItems = recentItems,
                        inProgressOnly = inProgressOnly
                    )
                }
                val recentItems = preparedState.recentItems
                val inProgressOnly = preparedState.inProgressOnly
                val dismissedNextUp = snapshot.dismissedNextUp
                val showUnairedNextUp = snapshot.showUnairedNextUp
                Log.d("HomeViewModel", "allProgress emitted=${preparedState.allProgressCount} recentWindow=${recentItems.size}")

                Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

                // Optimistic immediate render: show in-progress entries instantly.
                _uiState.update { state ->
                    if (state.continueWatchingItems == inProgressOnly) {
                        state
                    } else {
                        state.copy(continueWatchingItems = inProgressOnly)
                    }
                }

                // Wait for first catalog to render before enriching, so enrichment
                // state updates don't compete with the initial catalog paint.
                if (!hasCatalogContentDrawn) {
                    withTimeoutOrNull(5000L) {
                        while (!hasCatalogContentDrawn) { delay(100) }
                    }
                }

                // Then enrich Next Up and item details in background.
                enrichContinueWatchingProgressively(
                    allProgress = recentItems,
                    inProgressItems = inProgressOnly,
                    dismissedNextUp = dismissedNextUp,
                    showUnairedNextUp = showUnairedNextUp
                )
                enrichInProgressEpisodeDetailsProgressively(inProgressOnly)
            }
        }
    }

    private data class ContinueWatchingSettingsSnapshot(
        val items: List<WatchProgress>,
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val showUnairedNextUp: Boolean
    )

    private data class ContinueWatchingPreparedState(
        val allProgressCount: Int,
        val recentItems: List<WatchProgress>,
        val inProgressOnly: List<ContinueWatchingItem.InProgress>
    )

    private data class NextUpArtworkFallback(
        val thumbnail: String?,
        val backdrop: String?,
        val poster: String?,
        val airDate: String?
    )

    private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
        val (series, nonSeries) = items.partition { isSeriesType(it.contentType) }
        val latestPerShow = series
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
        return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
    }

    private fun shouldTreatAsInProgressForContinueWatching(progress: WatchProgress): Boolean {
        if (progress.isInProgress()) return true
        if (progress.isCompleted()) return false

        // Rewatch edge case: a started replay can be below the default 2% "in progress"
        // threshold, but should still suppress Next Up and appear as resume.
        val hasStartedPlayback = progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
        return hasStartedPlayback &&
            progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
            progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
    }

    private suspend fun resolveCurrentEpisodeDescription(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>
    ): String? {
        if (!isSeriesType(progress.contentType)) return null
        val meta = resolveMetaForProgress(progress, metaCache) ?: return null
        val video = resolveVideoForProgress(progress, meta) ?: return null
        return video.overview?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveCurrentEpisodeThumbnail(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>
    ): String? {
        if (!isSeriesType(progress.contentType)) return null
        val meta = resolveMetaForProgress(progress, metaCache) ?: return null
        val video = resolveVideoForProgress(progress, meta) ?: return null
        return video.thumbnail?.takeIf { it.isNotBlank() }
    }

    private fun resolveVideoForProgress(progress: WatchProgress, meta: Meta): Video? {
        if (!isSeriesType(progress.contentType)) return null
        val videos = meta.videos.filter { it.season != null && it.episode != null && it.season != 0 }
        if (videos.isEmpty()) return null

        progress.videoId.takeIf { it.isNotBlank() }?.let { videoId ->
            videos.firstOrNull { it.id == videoId }?.let { return it }
        }

        val season = progress.season
        val episode = progress.episode
        if (season != null && episode != null) {
            videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
        }

        return null
    }

    private suspend fun enrichContinueWatchingProgressively(
        allProgress: List<WatchProgress>,
        inProgressItems: List<ContinueWatchingItem.InProgress>,
        dismissedNextUp: Set<String>,
        showUnairedNextUp: Boolean
    ) = coroutineScope {
        val latestCompletedBySeries = withContext(Dispatchers.Default) {
            val inProgressIds = inProgressItems
                .map { it.progress.contentId }
                .filter { it.isNotBlank() }
                .toSet()
            allProgress
                .filter { progress ->
                    isSeriesType(progress.contentType) &&
                        progress.season != null &&
                        progress.episode != null &&
                        progress.season != 0 &&
                        progress.isCompleted() &&
                        progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK
                }
                .groupBy { it.contentId }
                .mapNotNull { (_, items) ->
                    items.maxWithOrNull(
                        compareBy<WatchProgress>(
                            { it.lastWatched },
                            { it.season ?: -1 },
                            { it.episode ?: -1 }
                        )
                    )
                }
                .filter { it.contentId !in inProgressIds }
                .filter { progress -> nextUpDismissKey(progress.contentId) !in dismissedNextUp }
                .sortedByDescending { it.lastWatched }
                .take(MAX_NEXT_UP_LOOKUPS)
        }

        if (latestCompletedBySeries.isEmpty()) {
            return@coroutineScope
        }

        val lookupSemaphore = Semaphore(MAX_NEXT_UP_CONCURRENCY)
        val mergeMutex = Mutex()
        val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
        val metaCache = mutableMapOf<String, Meta?>()
        var lastEmittedNextUpCount = 0

        val jobs = latestCompletedBySeries.map { progress ->
            launch(Dispatchers.IO) {
                lookupSemaphore.withPermit {
                    val nextUp = buildNextUpItem(
                        progress = progress,
                        metaCache = metaCache,
                        showUnairedNextUp = showUnairedNextUp
                    ) ?: return@withPermit
                    mergeMutex.withLock {
                        nextUpByContent[progress.contentId] = nextUp
                        if (nextUpByContent.size - lastEmittedNextUpCount >= 4) {
                            val nextUpItems = nextUpByContent.values.toList()
                            _uiState.update {
                                val mergedItems = mergeContinueWatchingItems(
                                    inProgressItems = inProgressItems,
                                    nextUpItems = nextUpItems
                                )
                                if (it.continueWatchingItems == mergedItems) {
                                    it
                                } else {
                                    it.copy(continueWatchingItems = mergedItems)
                                }
                            }
                            lastEmittedNextUpCount = nextUpByContent.size
                        }
                    }
                }
            }
        }
        jobs.joinAll()

        mergeMutex.withLock {
            if (nextUpByContent.size != lastEmittedNextUpCount) {
                val nextUpItems = nextUpByContent.values.toList()
                _uiState.update {
                    val mergedItems = mergeContinueWatchingItems(
                        inProgressItems = inProgressItems,
                        nextUpItems = nextUpItems
                    )
                    if (it.continueWatchingItems == mergedItems) {
                        it
                    } else {
                        it.copy(continueWatchingItems = mergedItems)
                    }
                }
            }
        }
    }

    private suspend fun enrichInProgressEpisodeDetailsProgressively(
        inProgressItems: List<ContinueWatchingItem.InProgress>
    ) = coroutineScope {
        if (inProgressItems.isEmpty()) return@coroutineScope

        val seriesItems = inProgressItems.filter { isSeriesType(it.progress.contentType) }
        if (seriesItems.isEmpty()) return@coroutineScope

        val metaCache = mutableMapOf<String, Meta?>()
        val enrichedByProgress = linkedMapOf<WatchProgress, ContinueWatchingItem.InProgress>()
        var lastAppliedCount = 0

        for (item in seriesItems) {
            val description = resolveCurrentEpisodeDescription(item.progress, metaCache)
            val thumbnail = resolveCurrentEpisodeThumbnail(item.progress, metaCache)
            val enrichedItem = item.copy(
                episodeDescription = description,
                episodeThumbnail = thumbnail
            )

            if (enrichedItem != item) {
                enrichedByProgress[item.progress] = enrichedItem
                if (enrichedByProgress.size - lastAppliedCount >= 4) {
                    applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
                    lastAppliedCount = enrichedByProgress.size
                }
            }
        }

        if (enrichedByProgress.isNotEmpty() && enrichedByProgress.size != lastAppliedCount) {
            applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
        }
    }

    private fun applyInProgressEpisodeDetailEnrichment(
        replacements: Map<WatchProgress, ContinueWatchingItem.InProgress>
    ) {
        if (replacements.isEmpty()) return

        _uiState.update { state ->
            var changed = false
            val updatedItems = state.continueWatchingItems.map { item ->
                if (item is ContinueWatchingItem.InProgress) {
                    val replacement = replacements[item.progress]
                    if (replacement != null && replacement != item) {
                        changed = true
                        replacement
                    } else {
                        item
                    }
                } else {
                    item
                }
            }

            if (changed) {
                state.copy(continueWatchingItems = updatedItems)
            } else {
                state
            }
        }
    }

    private fun mergeContinueWatchingItems(
        inProgressItems: List<ContinueWatchingItem.InProgress>,
        nextUpItems: List<ContinueWatchingItem.NextUp>
    ): List<ContinueWatchingItem> {
        val inProgressSeriesIds = inProgressItems
            .asSequence()
            .map { it.progress }
            .filter { isSeriesType(it.contentType) }
            .map { it.contentId }
            .filter { it.isNotBlank() }
            .toSet()

        val filteredNextUpItems = nextUpItems.filter { item ->
            item.info.contentId !in inProgressSeriesIds
        }

        val combined = mutableListOf<Pair<Long, ContinueWatchingItem>>()
        inProgressItems.forEach { combined.add(it.progress.lastWatched to it) }
        filteredNextUpItems.forEach { combined.add(it.info.lastWatched to it) }

        return combined
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private suspend fun buildNextUpItem(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>,
        showUnairedNextUp: Boolean
    ): ContinueWatchingItem.NextUp? {
        val nextEpisode = findNextEpisode(
            progress = progress,
            metaCache = metaCache,
            showUnairedNextUp = showUnairedNextUp
        ) ?: return null
        val meta = nextEpisode.first
        val video = nextEpisode.second
        val nextSeason = requireNotNull(video.season)
        val nextEpisodeNumber = requireNotNull(video.episode)

        val isNextEpisodeAlreadyWatched = runCatching {
            watchProgressRepository.isWatched(
                contentId = progress.contentId,
                season = nextSeason,
                episode = nextEpisodeNumber
            ).first()
        }.getOrDefault(false)
        if (isNextEpisodeAlreadyWatched) return null

        val existingPoster = meta.poster.normalizeImageUrl()
        val existingBackdrop = meta.background.normalizeImageUrl()
        val existingLogo = meta.logo.normalizeImageUrl()
        val existingThumbnail = video.thumbnail.normalizeImageUrl()
        val artworkFallback = if (
            existingThumbnail == null ||
            existingBackdrop == null ||
            existingPoster == null
        ) {
            resolveNextUpArtworkFallback(
                progress = progress,
                meta = meta,
                season = nextSeason,
                episode = nextEpisodeNumber
            )
        } else {
            null
        }
        val released = video.released?.trim()?.takeIf { it.isNotEmpty() }
            ?: artworkFallback?.airDate
        val releaseDate = parseEpisodeReleaseDate(released)
        val todayLocal = LocalDate.now(ZoneId.systemDefault())
        val hasAired = releaseDate?.let { !it.isAfter(todayLocal) } ?: true
        val info = NextUpInfo(
            contentId = progress.contentId,
            contentType = progress.contentType,
            name = meta.name,
            poster = existingPoster ?: artworkFallback?.poster,
            backdrop = existingBackdrop ?: artworkFallback?.backdrop,
            logo = existingLogo,
            videoId = video.id,
            season = nextSeason,
            episode = nextEpisodeNumber,
            episodeTitle = video.title,
            episodeDescription = video.overview?.takeIf { it.isNotBlank() },
            thumbnail = existingThumbnail ?: artworkFallback?.thumbnail,
            released = released,
            hasAired = hasAired,
            airDateLabel = if (hasAired) {
                null
            } else {
                formatEpisodeAirDateLabel(releaseDate)
            },
            lastWatched = progress.lastWatched
        )
        return ContinueWatchingItem.NextUp(info)
    }

    private suspend fun findNextEpisode(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>,
        showUnairedNextUp: Boolean
    ): Pair<Meta, Video>? {
        if (!isSeriesType(progress.contentType)) return null

        val meta = resolveMetaForProgress(progress, metaCache) ?: return null

        val episodes = meta.videos
            .filter { it.season != null && it.episode != null && it.season != 0 }
            .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })

        val currentSeason = progress.season ?: return null
        val currentEpisode = progress.episode ?: return null
        val maxEpisodeInSeason = episodes.asSequence()
            .filter { it.season == currentSeason }
            .mapNotNull { it.episode }
            .maxOrNull()
            ?: return null

        val targetSeason = if (currentEpisode >= maxEpisodeInSeason) currentSeason + 1 else currentSeason
        val targetEpisode = if (currentEpisode >= maxEpisodeInSeason) 1 else currentEpisode + 1

        val nextEpisode = episodes.firstOrNull {
            it.season == targetSeason && it.episode == targetEpisode
        } ?: return null

        if (!shouldIncludeNextUpEpisode(nextEpisode, showUnairedNextUp)) return null

        return meta to nextEpisode
    }

    private suspend fun resolveMetaForProgress(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>
    ): Meta? {
        val cacheKey = "${progress.contentType}:${progress.contentId}"
        synchronized(metaCache) {
            if (metaCache.containsKey(cacheKey)) {
                return metaCache[cacheKey]
            }
        }

        val idCandidates = buildList {
            add(progress.contentId)
            if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
            if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
        }.distinct()

        val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
        val resolved = run {
            var meta: Meta? = null
            for (type in typeCandidates) {
                for (candidateId in idCandidates) {
                    val result = withTimeoutOrNull(2500) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    } ?: continue
                    meta = (result as? NetworkResult.Success)?.data
                    if (meta != null) break
                }
                if (meta != null) break
            }
            meta
        }

        synchronized(metaCache) {
            metaCache[cacheKey] = resolved
        }
        return resolved
    }

    private fun isSeriesType(type: String?): Boolean {
        return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
    }

    private fun shouldIncludeNextUpEpisode(
        nextEpisode: Video,
        showUnairedNextUp: Boolean
    ): Boolean {
        if (showUnairedNextUp) return true
        val releaseDate = parseEpisodeReleaseDate(nextEpisode.released)
            ?: return true
        val todayLocal = LocalDate.now(ZoneId.systemDefault())
        return !releaseDate.isAfter(todayLocal)
    }

    private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        val zone = ZoneId.systemDefault()

        return runCatching {
            Instant.parse(value).atZone(zone).toLocalDate()
        }.getOrNull() ?: runCatching {
            OffsetDateTime.parse(value).toInstant().atZone(zone).toLocalDate()
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse(value).toLocalDate()
        }.getOrNull() ?: runCatching {
            LocalDate.parse(value)
        }.getOrNull() ?: runCatching {
            val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
                ?: return@runCatching null
            LocalDate.parse(datePortion)
        }.getOrNull()
    }

    private suspend fun resolveNextUpArtworkFallback(
        progress: WatchProgress,
        meta: Meta,
        season: Int,
        episode: Int
    ): NextUpArtworkFallback? {
        val tmdbId = resolveTmdbIdForNextUp(progress, meta) ?: return null
        val language = currentTmdbSettings.language

        val episodeMeta = runCatching {
            tmdbMetadataService
                .fetchEpisodeEnrichment(
                    tmdbId = tmdbId,
                    seasonNumbers = listOf(season),
                    language = language
                )[season to episode]
        }.getOrNull()

        val showMeta = runCatching {
            tmdbMetadataService.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = ContentType.SERIES,
                language = language
            )
        }.getOrNull()

        val fallback = NextUpArtworkFallback(
            thumbnail = episodeMeta?.thumbnail.normalizeImageUrl(),
            backdrop = showMeta?.backdrop.normalizeImageUrl(),
            poster = showMeta?.poster.normalizeImageUrl(),
            airDate = episodeMeta?.airDate?.trim()?.takeIf { it.isNotEmpty() }
        )

        return if (
            fallback.thumbnail == null &&
            fallback.backdrop == null &&
            fallback.poster == null &&
            fallback.airDate == null
        ) {
            null
        } else {
            fallback
        }
    }

    private suspend fun resolveTmdbIdForNextUp(
        progress: WatchProgress,
        meta: Meta
    ): String? {
        val candidates = buildList {
            add(progress.contentId)
            add(meta.id)
            add(progress.videoId)
            if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
            if (meta.id.startsWith("trakt:")) add(meta.id.substringAfter(':'))
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            tmdbService.ensureTmdbId(candidate, progress.contentType)?.let { return it }
        }
        return null
    }

    private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
        val todayLocal = LocalDate.now(ZoneId.systemDefault())
        val formatter = if (releaseDate.year == todayLocal.year) {
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        } else {
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        }
        return releaseDate.format(formatter)
    }

    private fun String?.normalizeImageUrl(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun nextUpDismissKey(contentId: String): String {
        return contentId.trim()
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) {
        if (isNextUp) {
            val dismissKey = nextUpDismissKey(contentId)
            _uiState.update { state ->
                state.copy(
                    continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                        when (item) {
                            is ContinueWatchingItem.NextUp ->
                                nextUpDismissKey(item.info.contentId) == dismissKey
                            is ContinueWatchingItem.InProgress -> false
                        }
                    }
                )
            }
            viewModelScope.launch {
                traktSettingsDataStore.addDismissedNextUpKey(dismissKey)
            }
            return
        }
        viewModelScope.launch {
            val targetSeason = if (isNextUp) season else null
            val targetEpisode = if (isNextUp) episode else null
            Log.d(
                TAG,
                "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp targetSeason=$targetSeason targetEpisode=$targetEpisode"
            )
            watchProgressRepository.removeProgress(
                contentId = contentId,
                season = targetSeason,
                episode = targetEpisode
            )
        }
    }

    private fun observeInstalledAddons() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged()
                .debounce(200)
                .collectLatest { addons ->
                    addonsCache = addons
                    loadAllCatalogs(addons)
                }
        }
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>) {
        val catalogLoadSessionId = startCatalogLoadSession()
        _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
        catalogOrder.clear()
        catalogsMap.clear()
        catalogVersion++
        lastProcessedCatalogVersion = -1L
        reconcilePosterObserversJob?.cancel()
        hasBootstrappedPosterObservers = false
        hasDispatchedFirstCatalogBatch = false
        hasCatalogContentDrawn = false
        reconcilePosterStatusObservers(emptyList())
        _fullCatalogRows.value = emptyList()
        truncatedRowCache.clear()
        trailerPreviewLoadingIds.clear()
        trailerPreviewNegativeCache.clear()
        trailerPreviewUrlsState.clear()
        activeTrailerPreviewItemId = null
        trailerPreviewRequestVersion = 0L
        prefetchedExternalMetaIds.clear()
        externalMetaPrefetchInFlightIds.clear()
        externalMetaPrefetchJob?.cancel()
        pendingExternalMetaPrefetchItemId = null
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()

        try {
            if (addons.isEmpty()) {
                if (catalogLoadSessionId != activeCatalogLoadSessionId) return
                _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
                return
            }

            rebuildCatalogOrder(addons)

            if (catalogOrder.isEmpty()) {
                if (catalogLoadSessionId != activeCatalogLoadSessionId) return
                _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
                return
            }

            // Load catalogs
            val catalogsToLoad = addons.flatMap { addon ->
                addon.catalogs
                    .filterNot {
                        it.isSearchOnlyCatalog() || isCatalogDisabled(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            type = it.apiType,
                            catalogId = it.id,
                            catalogName = it.name
                        )
                    }
                    .map { catalog -> addon to catalog }
            }
            pendingCatalogLoads = catalogsToLoad.size

            // Stagger catalog dispatching in waves so the UI thread gets breathing
            // room between batches to render + start Coil image loads.
            val firstWaveSize = if (deviceCapabilities.tier == DeviceTier.LOW) 1 else 2
            val subsequentWaveSize = if (deviceCapabilities.tier == DeviceTier.LOW) 2 else 3

            val firstWave = catalogsToLoad.take(firstWaveSize)
            val remaining = catalogsToLoad.drop(firstWaveSize)

            firstWave.forEach { (addon, catalog) -> loadCatalog(addon, catalog, catalogLoadSessionId) }

            val waveDelayMs = if (deviceCapabilities.tier == DeviceTier.LOW) {
                CATALOG_WAVE_DELAY_MS * 2  // Extra breathing room on constrained devices
            } else {
                CATALOG_WAVE_DELAY_MS
            }
            remaining.chunked(subsequentWaveSize).forEach { wave ->
                delay(waveDelayMs)
                if (catalogLoadSessionId != activeCatalogLoadSessionId) return
                wave.forEach { (addon, catalog) -> loadCatalog(addon, catalog, catalogLoadSessionId) }
            }
        } catch (e: Exception) {
            if (catalogLoadSessionId != activeCatalogLoadSessionId) return
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun startCatalogLoadSession(): Long {
        activeCatalogLoadSessionId += 1
        activeCatalogLoadJobs.values.forEach { it.cancel() }
        activeCatalogLoadJobs.clear()
        _loadingCatalogs.value = emptySet()
        pendingCatalogLoads = 0
        return activeCatalogLoadSessionId
    }

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, sessionId: Long) {
        val key = catalogKey(
            addonId = addon.id,
            type = catalog.apiType,
            catalogId = catalog.id
        )
        activeCatalogLoadJobs.remove(key)?.cancel()
        val job = viewModelScope.launch {
            catalogLoadSemaphore.withPermit {
                if (sessionId != activeCatalogLoadSessionId) return@withPermit
                val supportsSkip = catalog.extra.any { it.name == "skip" }
                Log.d(
                    TAG,
                    "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip"
                )
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    skip = 0,
                    supportsSkip = supportsSkip
                ).collect { result ->
                    if (sessionId != activeCatalogLoadSessionId) return@collect
                    when (result) {
                        is NetworkResult.Success -> {
                            catalogsMap[key] = result.data
                            catalogVersion++
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            Log.d(
                                TAG,
                                "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                            )
                            scheduleUpdateCatalogRows()
                        }
                        is NetworkResult.Error -> {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            Log.w(
                                TAG,
                                "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                            )
                            scheduleUpdateCatalogRows()
                        }
                        NetworkResult.Loading -> { /* Handled by individual row */ }
                    }
                }
            }
        }
        activeCatalogLoadJobs[key] = job
        job.invokeOnCompletion {
            if (activeCatalogLoadJobs[key] == job) {
                activeCatalogLoadJobs.remove(key)
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return
        if (key in _loadingCatalogs.value) return

        // Mark loading via lightweight separate flow — avoids full state cascade
        catalogsMap[key] = currentRow.copy(isLoading = true)
        catalogVersion++
        _loadingCatalogs.update { it + key }

        viewModelScope.launch {
            val addon = addonsCache.find { it.id == addonId } ?: return@launch

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
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val mergedItems = currentRow.items + result.data.items
                        catalogsMap[key] = result.data.copy(items = mergedItems)
                        catalogVersion++
                        _loadingCatalogs.update { it - key }
                        scheduleUpdateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        catalogVersion++
                        _loadingCatalogs.update { it - key }
                        scheduleUpdateCatalogRows()
                    }
                    NetworkResult.Loading -> { }
                }
            }
        }
    }

    private fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use minimal debounce to show content ASAP while still
                // batching near-simultaneous arrivals.
                !hasDispatchedFirstCatalogBatch && catalogsMap.isNotEmpty() -> {
                    hasDispatchedFirstCatalogBatch = true
                    50L
                }
                pendingCatalogLoads > 8 -> 350L
                pendingCatalogLoads > 3 -> 250L
                pendingCatalogLoads > 0 -> 150L
                else -> 50L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() {
        // Skip expensive recomputation when catalog data hasn't changed.
        val currentVersion = catalogVersion
        if (currentVersion == lastProcessedCatalogVersion && !_uiState.value.isLoading) return
        lastProcessedCatalogVersion = currentVersion

        // Snapshot mutable state before entering background context
        val orderedKeys = catalogOrder.toList()
        val catalogSnapshot = catalogsMap.toMap()
        val heroCatalogKeys = currentHeroCatalogKeys
        val currentLayout = _uiState.value.homeLayout
        val currentGridItems = _uiState.value.gridItems
        val heroSectionEnabled = _uiState.value.heroSectionEnabled

        val (displayRows, baseHeroItems, baseGridItems) = withContext(Dispatchers.Default) {
            val orderedRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
            val selectedHeroCatalogSet = heroCatalogKeys.toSet()
            val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
                orderedRows.filter { row ->
                    val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                    key in selectedHeroCatalogSet
                }
            } else {
                emptyList()
            }
            val heroItemsFromSelectedCatalogs = selectedHeroRows
                .asSequence()
                .flatMap { row -> row.items.asSequence() }
                .filter { item -> item.hasHeroArtwork() }
                .take(7)
                .toList()
            val fallbackHeroItemsFromSelectedCatalogs = selectedHeroRows
                .asSequence()
                .flatMap { row -> row.items.asSequence() }
                .take(7)
                .toList()

            val fallbackHeroItemsWithArtwork = orderedRows
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filter { it.hasHeroArtwork() }
                .take(7)
                .toList()

            val computedHeroItems = when {
                heroItemsFromSelectedCatalogs.isNotEmpty() -> heroItemsFromSelectedCatalogs
                fallbackHeroItemsFromSelectedCatalogs.isNotEmpty() -> fallbackHeroItemsFromSelectedCatalogs
                fallbackHeroItemsWithArtwork.isNotEmpty() -> fallbackHeroItemsWithArtwork
                else -> emptyList()
            }

            val computedDisplayRows = orderedRows.map { row ->
                val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN && row.supportsSkip
                if (row.items.size > 25 && !shouldKeepFullRowInModern) {
                    val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                    val cachedEntry = truncatedRowCache[key]
                    if (cachedEntry != null && cachedEntry.sourceRow === row) {
                        cachedEntry.truncatedRow
                    } else {
                        val truncatedRow = row.copy(items = row.items.take(25))
                        truncatedRowCache[key] = TruncatedRowCacheEntry(
                            sourceRow = row,
                            truncatedRow = truncatedRow
                        )
                        truncatedRow
                    }
                } else {
                    val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                    truncatedRowCache.remove(key)
                    row
                }
            }

            val computedGridItems = if (currentLayout == HomeLayout.GRID) {
                buildList {
                    if (heroSectionEnabled && computedHeroItems.isNotEmpty()) {
                        add(GridItem.Hero(computedHeroItems))
                    }
                    computedDisplayRows.filter { it.items.isNotEmpty() }.forEach { row ->
                        add(
                            GridItem.SectionDivider(
                                catalogName = row.catalogName,
                                catalogId = row.catalogId,
                                addonBaseUrl = row.addonBaseUrl,
                                addonId = row.addonId,
                                type = row.apiType
                            )
                        )
                        val hasEnoughForSeeAll = row.items.size >= 15
                        val displayItems = if (hasEnoughForSeeAll) row.items.take(14) else row.items.take(15)
                        displayItems.forEach { item ->
                            add(
                                GridItem.Content(
                                    item = item,
                                    addonBaseUrl = row.addonBaseUrl,
                                    catalogId = row.catalogId,
                                    catalogName = row.catalogName
                                )
                            )
                        }
                        if (hasEnoughForSeeAll) {
                            add(
                                GridItem.SeeAll(
                                    catalogId = row.catalogId,
                                    addonId = row.addonId,
                                    type = row.apiType
                                )
                            )
                        }
                    }
                }
            } else {
                currentGridItems
            }

            Triple(computedDisplayRows, computedHeroItems, computedGridItems)
        }

        // Full (untruncated) rows for CatalogSeeAllScreen — only needed when
        // user navigates to SeeAll, so defer during the initial bulk load.
        if (pendingCatalogLoads <= 0) {
            val fullRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
            _fullCatalogRows.update { rows ->
                if (rows == fullRows) rows else fullRows
            }
        }

        // Emit un-enriched hero items immediately so the UI renders without waiting for TMDB
        val nextGridItems = if (currentLayout == HomeLayout.GRID) {
            replaceGridHeroItems(baseGridItems, baseHeroItems)
        } else {
            baseGridItems
        }

        // Preserve references when values are structurally equal to reduce unnecessary recomposition.
        _uiState.update { state ->
            state.copy(
                catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
                heroItems = if (state.heroItems == baseHeroItems) state.heroItems else baseHeroItems,
                gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
                isLoading = false
            )
        }

        // Launch hero enrichment in the background — updates heroItems when done.
        // Defer during the initial bulk load to avoid 28 TMDB API calls competing
        // with catalog network requests and triggering extra recompositions.
        val tmdbSettings = currentTmdbSettings
        val shouldUseEnrichedHeroItems = tmdbSettings.enabled &&
            (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

        if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty() && pendingCatalogLoads <= 0) {
            heroEnrichmentJob?.cancel()
            heroEnrichmentJob = viewModelScope.launch {
                val enrichmentSignature = heroEnrichmentSignature(baseHeroItems, tmdbSettings)
                if (lastHeroEnrichmentSignature == enrichmentSignature) {
                    // Already enriched with same signature — apply cached result
                    val cached = lastHeroEnrichedItems
                    _uiState.update { state ->
                        state.copy(
                            heroItems = if (state.heroItems == cached) state.heroItems else cached,
                            gridItems = if (currentLayout == HomeLayout.GRID) {
                                val enrichedGrid = replaceGridHeroItems(state.gridItems, cached)
                                if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                            } else state.gridItems
                        )
                    }
                } else {
                    val enrichedItems = enrichHeroItems(baseHeroItems, tmdbSettings)
                    lastHeroEnrichmentSignature = enrichmentSignature
                    lastHeroEnrichedItems = enrichedItems
                    _uiState.update { state ->
                        state.copy(
                            heroItems = if (state.heroItems == enrichedItems) state.heroItems else enrichedItems,
                            gridItems = if (currentLayout == HomeLayout.GRID) {
                                val enrichedGrid = replaceGridHeroItems(state.gridItems, enrichedItems)
                                if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                            } else state.gridItems
                        )
                    }
                }
            }
        } else {
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
        }

        // Defer poster-status observers during the initial bulk load to avoid
        // launching hundreds of coroutines that compete with catalog loading + rendering.
        if (pendingCatalogLoads <= 0) {
            scheduleReconcilePosterStatusObservers(displayRows)
        }
    }

    private fun scheduleReconcilePosterStatusObservers(rows: List<CatalogRow>) {
        reconcilePosterObserversJob?.cancel()
        val rowsSnapshot = rows.toList()
        reconcilePosterObserversJob = viewModelScope.launch {
            if (!hasBootstrappedPosterObservers) {
                withTimeoutOrNull(4000L) {
                    while (!hasCatalogContentDrawn) {
                        delay(50)
                    }
                }
            }
            delay(if (hasBootstrappedPosterObservers) 120L else 450L)
            reconcilePosterStatusObservers(rowsSnapshot)
            hasBootstrappedPosterObservers = true
        }
    }

    fun onCatalogContentRendered() {
        hasCatalogContentDrawn = true
    }

    private fun schedulePosterStatusFlush() {
        if (posterStatusFlushJob?.isActive == true) return
        posterStatusFlushJob = viewModelScope.launch {
            delay(200)
            flushPosterStatusUpdates()
        }
    }

    private fun flushPosterStatusUpdates() {
        val libraryBatch = HashMap(pendingLibraryStatusUpdates)
        val watchedBatch = HashMap(pendingWatchedStatusUpdates)
        if (libraryBatch.isEmpty() && watchedBatch.isEmpty()) return
        pendingLibraryStatusUpdates.keys.removeAll(libraryBatch.keys)
        pendingWatchedStatusUpdates.keys.removeAll(watchedBatch.keys)

        _uiState.update { state ->
            val newLibrary = if (libraryBatch.isEmpty()) {
                state.posterLibraryMembership
            } else {
                state.posterLibraryMembership + libraryBatch
            }
            val newWatched = if (watchedBatch.isEmpty()) {
                state.movieWatchedStatus
            } else {
                state.movieWatchedStatus + watchedBatch
            }
            if (newLibrary == state.posterLibraryMembership && newWatched == state.movieWatchedStatus) {
                state
            } else {
                state.copy(
                    posterLibraryMembership = newLibrary,
                    movieWatchedStatus = newWatched
                )
            }
        }
    }

    private suspend fun reconcilePosterStatusObservers(rows: List<CatalogRow>) {
        val desiredItemsByKey = linkedMapOf<String, Pair<String, String>>()
        rows.asSequence()
            .flatMap { row -> row.items.asSequence() }
            .forEach { item ->
                val key = homeItemStatusKey(item.id, item.apiType)
                if (key !in desiredItemsByKey) {
                    desiredItemsByKey[key] = item.id to item.apiType
                }
            }
        val desiredKeys = desiredItemsByKey.keys
        val desiredMovieKeys = desiredItemsByKey
            .filterValues { (_, itemType) -> itemType.equals("movie", ignoreCase = true) }
            .keys

        posterLibraryObserverJobs.keys
            .filterNot { it in desiredKeys }
            .forEach { staleKey ->
                posterLibraryObserverJobs.remove(staleKey)?.cancel()
            }
        movieWatchedObserverJobs.keys
            .filterNot { it in desiredMovieKeys }
            .forEach { staleKey ->
                movieWatchedObserverJobs.remove(staleKey)?.cancel()
            }

        // Launch observers in chunks to avoid a burst of 300+ coroutines
        // all emitting initial values simultaneously.
        var launchedInChunk = 0
        desiredItemsByKey.forEach { (statusKey, itemRef) ->
            val itemId = itemRef.first
            val itemType = itemRef.second
            var launchedAny = false

            if (statusKey !in posterLibraryObserverJobs) {
                posterLibraryObserverJobs[statusKey] = viewModelScope.launch {
                    libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                        .distinctUntilChanged()
                        .collectLatest { isInLibrary ->
                            pendingLibraryStatusUpdates[statusKey] = isInLibrary
                            schedulePosterStatusFlush()
                        }
                }
                launchedAny = true
            }

            if (itemType.equals("movie", ignoreCase = true)) {
                if (statusKey !in movieWatchedObserverJobs) {
                    movieWatchedObserverJobs[statusKey] = viewModelScope.launch {
                        watchProgressRepository.isWatched(contentId = itemId)
                            .distinctUntilChanged()
                            .collectLatest { watched ->
                                pendingWatchedStatusUpdates[statusKey] = watched
                                schedulePosterStatusFlush()
                            }
                    }
                    launchedAny = true
                }
            }

            if (launchedAny) {
                launchedInChunk++
                if (launchedInChunk >= 30) {
                    launchedInChunk = 0
                    // Yield to let pending coroutines emit their first values
                    // and batch into a single flush before launching the next chunk.
                    kotlinx.coroutines.yield()
                }
            }
        }

        // Flush any buffered updates before trimming stale entries.
        posterStatusFlushJob?.cancel()
        flushPosterStatusUpdates()

        // Remove stale entries from pending buffers.
        pendingLibraryStatusUpdates.keys.retainAll(desiredKeys)
        pendingWatchedStatusUpdates.keys.retainAll(desiredMovieKeys)

        _uiState.update { state ->
            val trimmedLibraryMembership =
                state.posterLibraryMembership.filterKeys { it in desiredKeys }
            val trimmedMovieWatchedStatus =
                state.movieWatchedStatus.filterKeys { it in desiredMovieKeys }
            val trimmedLibraryPending =
                state.posterLibraryPending.filterTo(linkedSetOf()) { it in desiredKeys }
            val trimmedMovieWatchedPending =
                state.movieWatchedPending.filterTo(linkedSetOf()) { it in desiredMovieKeys }

            if (
                trimmedLibraryMembership == state.posterLibraryMembership &&
                trimmedMovieWatchedStatus == state.movieWatchedStatus &&
                trimmedLibraryPending == state.posterLibraryPending &&
                trimmedMovieWatchedPending == state.movieWatchedPending
            ) {
                state
            } else {
                state.copy(
                    posterLibraryMembership = trimmedLibraryMembership,
                    movieWatchedStatus = trimmedMovieWatchedStatus,
                    posterLibraryPending = trimmedLibraryPending,
                    movieWatchedPending = trimmedMovieWatchedPending
                )
            }
        }
    }

    private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
        return WatchProgress(
            contentId = item.id,
            contentType = item.apiType,
            name = item.name,
            poster = item.poster,
            backdrop = item.background,
            logo = item.logo,
            videoId = item.id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
        val year = Regex("(\\d{4})").find(releaseInfo ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val parsedIds = parseContentIds(id)
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            year = year,
            traktId = parsedIds.trakt,
            imdbId = parsedIds.imdb,
            tmdbId = parsedIds.tmdb,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl
        )
    }

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> {
        if (items.isEmpty()) return items

        // Enrich all hero items in parallel
        return coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async item
                        val enrichment = tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = settings.language
                        ) ?: return@async item

                        var enriched = item

                        if (settings.useArtwork) {
                            enriched = enriched.copy(
                                background = enrichment.backdrop ?: enriched.background,
                                logo = enrichment.logo ?: enriched.logo,
                                poster = enrichment.poster ?: enriched.poster
                            )
                        }

                        if (settings.useBasicInfo) {
                            enriched = enriched.copy(
                                name = enrichment.localizedTitle ?: enriched.name,
                                description = enrichment.description ?: enriched.description,
                                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                                imdbRating = enrichment.rating?.toFloat() ?: enriched.imdbRating
                            )
                        }

                        if (settings.useDetails) {
                            enriched = enriched.copy(
                                releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
                            )
                        }

                        enriched
                    } catch (e: Exception) {
                        Log.w(TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                        item
                    }
                }
            }.awaitAll()
        }
    }

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> {
        if (gridItems.isEmpty()) return gridItems
        return gridItems.map { item ->
            if (item is GridItem.Hero) {
                item.copy(items = heroItems)
            } else {
                item
            }
        }
    }

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String {
        val itemSignature = items.joinToString(separator = "|") { item ->
            "${item.id}:${item.apiType}:${item.name}:${item.background}:${item.logo}:${item.poster}"
        }
        return buildString {
            append(settings.enabled)
            append(':')
            append(settings.language)
            append(':')
            append(settings.useArtwork)
            append(':')
            append(settings.useBasicInfo)
            append(':')
            append(settings.useDetails)
            append("::")
            append(itemSignature)
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun rebuildCatalogOrder(addons: List<Addon>) {
        val defaultOrder = buildDefaultCatalogOrder(addons)
        val availableSet = defaultOrder.toSet()

        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in availableSet }
            .distinct()
            .toList()

        val savedSet = savedValid.toSet()
        val mergedOrder = savedValid + defaultOrder.filterNot { it in savedSet }

        catalogOrder.clear()
        catalogOrder.addAll(mergedOrder)
    }

    private fun buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
        val orderedKeys = mutableListOf<String>()
        addons.forEach { addon ->
            addon.catalogs
                .filterNot {
                    it.isSearchOnlyCatalog() || isCatalogDisabled(
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

    private fun isCatalogDisabled(
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

    private fun disableCatalogKey(
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

    private fun MetaPreview.hasHeroArtwork(): Boolean {
        return !background.isNullOrBlank()
    }

    private fun extractYear(releaseInfo: String?): String? {
        if (releaseInfo.isNullOrBlank()) return null
        return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
    }

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>
    ) {
        val nextState = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    /**
     * Saves the grid layout focus and scroll state.
     */
    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }

    override fun onCleared() {
        posterStatusFlushJob?.cancel()
        reconcilePosterObserversJob?.cancel()
        activeCatalogLoadJobs.values.forEach { it.cancel() }
        activeCatalogLoadJobs.clear()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        pendingLibraryStatusUpdates.clear()
        pendingWatchedStatusUpdates.clear()
        super.onCleared()
    }
}
