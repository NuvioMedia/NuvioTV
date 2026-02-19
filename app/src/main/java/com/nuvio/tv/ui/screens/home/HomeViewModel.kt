package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val trailerService: TrailerService
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
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
    private var addonsCache: List<Addon> = emptyList()
    private var homeCatalogOrderKeys: List<String> = emptyList()
    private var disabledHomeCatalogKeys: Set<String> = emptySet()
    private var currentHeroCatalogKeys: List<String> = emptyList()
    private var catalogUpdateJob: Job? = null
    private var hasRenderedFirstCatalog = false
    private val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    private var pendingCatalogLoads = 0
    private data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    private val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    private val trailerPreviewLoadingIds = mutableSetOf<String>()
    private val trailerPreviewNegativeCache = mutableSetOf<String>()
    private val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    private var activeTrailerPreviewItemId: String? = null
    private var trailerPreviewRequestVersion: Long = 0L
    private var currentTmdbSettings: TmdbSettings = TmdbSettings()
    private var lastHeroEnrichmentSignature: String? = null
    private var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    private val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    private var externalMetaPrefetchEnabled: Boolean = false
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
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
        ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted ->
            FocusedBackdropPrefs(
                expandEnabled = expandEnabled,
                expandDelaySeconds = expandDelaySeconds,
                trailerEnabled = trailerEnabled,
                trailerMuted = trailerMuted
            )
        }

        val modernLayoutPrefsFlow = combine(
            layoutPreferenceDataStore.modernLandscapePostersEnabled,
            layoutPreferenceDataStore.modernNextRowPreviewEnabled
        ) { modernLandscapePostersEnabled, modernNextRowPreviewEnabled ->
            modernLandscapePostersEnabled to modernNextRowPreviewEnabled
        }

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
                modernLandscapePostersEnabled = true,
                modernNextRowPreviewEnabled = false,
                focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
                focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
                focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
                focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
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
                    modernLandscapePostersEnabled = modernPrefs.first,
                    modernNextRowPreviewEnabled = modernPrefs.second
                )
            }.collectLatest { prefs ->
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
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        modernNextRowPreviewEnabled = prefs.modernNextRowPreviewEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp
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
        val trailerMuted: Boolean
    )

    private data class LayoutUiPrefs(
        val layout: HomeLayout,
        val heroCatalogKeys: List<String>,
        val heroSectionEnabled: Boolean,
        val posterLabelsEnabled: Boolean,
        val catalogAddonNameEnabled: Boolean,
        val catalogTypeSuffixEnabled: Boolean,
        val modernLandscapePostersEnabled: Boolean,
        val modernNextRowPreviewEnabled: Boolean,
        val focusedBackdropExpandEnabled: Boolean,
        val focusedBackdropExpandDelaySeconds: Int,
        val focusedBackdropTrailerEnabled: Boolean,
        val focusedBackdropTrailerMuted: Boolean,
        val posterCardWidthDp: Int,
        val posterCardHeightDp: Int,
        val posterCardCornerRadiusDp: Int
    )

    fun requestTrailerPreview(item: MetaPreview) {
        val itemId = item.id
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
                title = item.name,
                year = extractYear(item.releaseInfo),
                tmdbId = null,
                type = item.apiType
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
        if (!externalMetaPrefetchEnabled) return
        if (item.id in prefetchedExternalMetaIds) return
        if (!externalMetaPrefetchInFlightIds.add(item.id)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }

                if (result is NetworkResult.Success) {
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithMeta(item.id, result.data)
                }
            } finally {
                externalMetaPrefetchInFlightIds.remove(item.id)
            }
        }
    }

    private fun updateCatalogItemWithMeta(itemId: String, meta: Meta) {
        _uiState.update { state ->
            var changed = false
            val updatedRows = state.catalogRows.map { row ->
                val itemIndex = row.items.indexOfFirst { it.id == itemId }
                if (itemIndex < 0) {
                    row
                } else {
                    val currentItem = row.items[itemIndex]
                    val mergedItem = currentItem.copy(
                        background = meta.background ?: currentItem.background,
                        logo = meta.logo ?: currentItem.logo,
                        description = meta.description ?: currentItem.description,
                        releaseInfo = meta.releaseInfo ?: currentItem.releaseInfo,
                        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
                        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres
                    )
                    if (mergedItem == currentItem) {
                        row
                    } else {
                        changed = true
                        val mutableItems = row.items.toMutableList()
                        mutableItems[itemIndex] = mergedItem
                        row.copy(items = mutableItems)
                    }
                }
            }
            if (changed) {
                state.copy(catalogRows = updatedRows)
            } else {
                state
            }
        }
    }

    private fun loadHomeCatalogOrderPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
                homeCatalogOrderKeys = keys
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
        }
    }

    private fun loadDisabledHomeCatalogPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
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

    private fun loadContinueWatching() {
        viewModelScope.launch {
            combine(
                watchProgressRepository.allProgress,
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys
            ) { items, daysCap, dismissedNextUp ->
                Triple(items, daysCap, dismissedNextUp)
            }.collectLatest { (items, daysCap, dismissedNextUp) ->
                val windowMs = daysCap.toLong() * 24L * 60L * 60L * 1000L
                val cutoffMs = System.currentTimeMillis() - windowMs
                val recentItems = items
                    .asSequence()
                    .filter { it.lastWatched >= cutoffMs }
                    .sortedByDescending { it.lastWatched }
                    .take(MAX_RECENT_PROGRESS_ITEMS)
                    .toList()

                Log.d("HomeViewModel", "allProgress emitted=${items.size} recentWindow=${recentItems.size}")

                val metaCache = mutableMapOf<String, Meta?>()
                val inProgressOnly = buildList {
                    deduplicateInProgress(
                        recentItems.filter { it.isInProgress() }
                    ).forEach { progress ->
                        add(
                            ContinueWatchingItem.InProgress(
                                progress = progress,
                                episodeDescription = resolveCurrentEpisodeDescription(progress, metaCache),
                                episodeThumbnail = resolveCurrentEpisodeThumbnail(progress, metaCache)
                            )
                        )
                    }
                }

                Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

                // Optimistic immediate render: show in-progress entries instantly.
                _uiState.update { it.copy(continueWatchingItems = inProgressOnly) }

                // Then enrich Next Up in background with bounded concurrency.
                enrichContinueWatchingProgressively(
                    allProgress = recentItems,
                    inProgressItems = inProgressOnly,
                    dismissedNextUp = dismissedNextUp
                )
            }
        }
    }

    private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
        val (series, nonSeries) = items.partition { isSeriesType(it.contentType) }
        val latestPerShow = series
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
        return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
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
        dismissedNextUp: Set<String>
    ) = coroutineScope {
        val inProgressIds = inProgressItems
            .map { it.progress.contentId }
            .filter { it.isNotBlank() }
            .toSet()

        val latestCompletedBySeries = allProgress
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
                    val nextUp = buildNextUpItem(progress, metaCache) ?: return@withPermit
                    mergeMutex.withLock {
                        nextUpByContent[progress.contentId] = nextUp
                        if (nextUpByContent.size - lastEmittedNextUpCount >= 2) {
                            val nextUpItems = nextUpByContent.values.toList()
                            _uiState.update {
                                it.copy(
                                    continueWatchingItems = mergeContinueWatchingItems(
                                        inProgressItems = inProgressItems,
                                        nextUpItems = nextUpItems
                                    )
                                )
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
                    it.copy(
                        continueWatchingItems = mergeContinueWatchingItems(
                            inProgressItems = inProgressItems,
                            nextUpItems = nextUpItems
                        )
                    )
                }
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
        metaCache: MutableMap<String, Meta?>
    ): ContinueWatchingItem.NextUp? {
        val nextEpisode = findNextEpisode(progress, metaCache) ?: return null
        val meta = nextEpisode.first
        val video = nextEpisode.second
        val info = NextUpInfo(
            contentId = progress.contentId,
            contentType = progress.contentType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.background,
            logo = meta.logo,
            videoId = video.id,
            season = video.season ?: return null,
            episode = video.episode ?: return null,
            episodeTitle = video.title,
            episodeDescription = video.overview?.takeIf { it.isNotBlank() },
            thumbnail = video.thumbnail,
            lastWatched = progress.lastWatched
        )
        return ContinueWatchingItem.NextUp(info)
    }

    private suspend fun findNextEpisode(
        progress: WatchProgress,
        metaCache: MutableMap<String, Meta?>
    ): Pair<Meta, Video>? {
        if (!isSeriesType(progress.contentType)) return null

        val meta = resolveMetaForProgress(progress, metaCache) ?: return null

        val episodes = meta.videos
            .filter { it.season != null && it.episode != null && it.season != 0 }
            .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })

        val currentSeason = progress.season ?: return null
        val currentEpisode = progress.episode ?: return null
        val maxEpisodeInSeason = episodes
            .asSequence()
            .filter { it.season == currentSeason }
            .mapNotNull { it.episode }
            .maxOrNull()
            ?: return null

        val isSeasonJump = currentEpisode >= maxEpisodeInSeason
        val targetSeason = if (isSeasonJump) currentSeason + 1 else currentSeason
        val targetEpisode = if (isSeasonJump) 1 else currentEpisode + 1

        val nextEpisode = episodes.firstOrNull {
            it.season == targetSeason && it.episode == targetEpisode
        } ?: return null

        if (!shouldIncludeNextUpEpisode(nextEpisode, isSeasonJump)) return null

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
        isSeasonJump: Boolean
    ): Boolean {
        val releaseDate = parseEpisodeReleaseDate(nextEpisode.released)
            ?: return !isSeasonJump
        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        if (!releaseDate.isAfter(todayUtc)) return true
        if (!isSeasonJump) return true

        val daysUntilRelease = ChronoUnit.DAYS.between(todayUtc, releaseDate)
        return daysUntilRelease <= 7
    }

    private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()

        return runCatching {
            Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate()
        }.getOrNull() ?: runCatching {
            OffsetDateTime.parse(value).toLocalDate()
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
                .distinctUntilChanged { old, new ->
                    old.map { it.id } == new.map { it.id }
                }
                .collectLatest { addons ->
                    addonsCache = addons
                    loadAllCatalogs(addons)
                }
        }
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>) {
        _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
        catalogOrder.clear()
        catalogsMap.clear()
        _fullCatalogRows.value = emptyList()
        truncatedRowCache.clear()
        hasRenderedFirstCatalog = false
        trailerPreviewLoadingIds.clear()
        trailerPreviewNegativeCache.clear()
        trailerPreviewUrlsState.clear()
        activeTrailerPreviewItemId = null
        trailerPreviewRequestVersion = 0L
        prefetchedExternalMetaIds.clear()
        externalMetaPrefetchInFlightIds.clear()
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()

        try {
            if (addons.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
                return
            }

            rebuildCatalogOrder(addons)

            if (catalogOrder.isEmpty()) {
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
            catalogsToLoad.forEach { (addon, catalog) ->
                loadCatalog(addon, catalog)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor) {
        viewModelScope.launch {
            catalogLoadSemaphore.withPermit {
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
                    when (result) {
                        is NetworkResult.Success -> {
                            val key = catalogKey(
                                addonId = addon.id,
                                type = catalog.apiType,
                                catalogId = catalog.id
                            )
                            catalogsMap[key] = result.data
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
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return
        if (key in _loadingCatalogs.value) return

        // Mark loading via lightweight separate flow — avoids full state cascade
        catalogsMap[key] = currentRow.copy(isLoading = true)
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
                        _loadingCatalogs.update { it - key }
                        updateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        _loadingCatalogs.update { it - key }
                        updateCatalogRows()
                    }
                    NetworkResult.Loading -> { }
                }
            }
        }
    }

    private fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            // Render immediately for the first catalog arrival, debounce subsequent updates
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRows()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogLoads > 5 -> 450L
                pendingCatalogLoads > 0 -> 250L
                else -> 100L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() {
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

        // Full (untruncated) rows for CatalogSeeAllScreen
        val fullRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
        _fullCatalogRows.update { rows ->
            if (rows == fullRows) rows else fullRows
        }

        val tmdbSettings = currentTmdbSettings
        val shouldUseEnrichedHeroItems = tmdbSettings.enabled &&
            (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

        val resolvedHeroItems = if (shouldUseEnrichedHeroItems) {
            val enrichmentSignature = heroEnrichmentSignature(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                lastHeroEnrichedItems
            } else {
                val enrichedItems = enrichHeroItems(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                enrichedItems
            }
        } else {
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
            baseHeroItems
        }

        val nextGridItems = if (currentLayout == HomeLayout.GRID) {
            replaceGridHeroItems(baseGridItems, resolvedHeroItems)
        } else {
            baseGridItems
        }

        // Preserve references when values are structurally equal to reduce unnecessary recomposition.
        _uiState.update { state ->
            state.copy(
                catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
                heroItems = if (state.heroItems == resolvedHeroItems) state.heroItems else resolvedHeroItems,
                gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
                isLoading = false
            )
        }
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
        _focusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
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
}
