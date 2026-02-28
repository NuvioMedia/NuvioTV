package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import javax.inject.Inject

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
    private val tvRecommendationManager: TvRecommendationManager
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState

    init {
        observeLayoutPreferences()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeLibraryState()
        observeTmdbSettings()
        loadContinueWatching()
        observeInstalledAddons()
        viewModelScope.launch {
            delay(3000)
            startupGracePeriodActive = false
        }
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

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
                val items = snapshot.items
                val daysCap = snapshot.daysCap
                val dismissedNextUp = snapshot.dismissedNextUp
                val showUnairedNextUp = snapshot.showUnairedNextUp
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

                Log.d("HomeViewModel", "allProgress emitted=${items.size} recentWindow=${recentItems.size}")

                val inProgressOnly = buildList {
                    deduplicateInProgress(
                        recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
                    ).forEach { progress ->
                        add(
                            ContinueWatchingItem.InProgress(
                                progress = progress
                            )
                        )
                    }
                }

                Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

                // Optimistic immediate render: show in-progress entries instantly.
                _uiState.update { state ->
                    if (state.continueWatchingItems == inProgressOnly) {
                        state
                    } else {
                        state.copy(continueWatchingItems = inProgressOnly)
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
                    val nextUp = buildNextUpItem(
                        progress = progress,
                        metaCache = metaCache,
                        showUnairedNextUp = showUnairedNextUp
                    ) ?: return@withPermit
                    mergeMutex.withLock {
                        nextUpByContent[progress.contentId] = nextUp
                        if (nextUpByContent.size - lastEmittedNextUpCount >= 2) {
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
                if (enrichedByProgress.size - lastAppliedCount >= 2) {
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
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() = loadContinueWatchingPipeline()

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use minimal debounce to show content ASAP while still
                // batching near-simultaneous arrivals.
                !hasRenderedFirstCatalog && catalogsMap.isNotEmpty() -> {
                    hasRenderedFirstCatalog = true
                    50L
                }
                pendingCatalogLoads > 8 -> 200L
                pendingCatalogLoads > 3 -> 150L
                pendingCatalogLoads > 0 -> 100L
                else -> 50L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

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

        // Trending channel taking 1 item from first row, 1 item from second row alternatively.
        // Assuming first 2 rows are usually popular movies and popular series.
        val trendingList1 = displayRows.getOrNull(0)?.items.orEmpty()
        val trendingList2 = displayRows.getOrNull(1)?.items.orEmpty()
        
        val mixedTrending = buildList {
            val maxT = maxOf(trendingList1.size, trendingList2.size)
            for (i in 0 until maxT) {
                if (i < trendingList1.size) add(trendingList1[i])
                if (i < trendingList2.size) add(trendingList2[i])
            }
        }.distinctBy { it.id }

        if (mixedTrending.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    tvRecommendationManager.updateTrending(mixedTrending)
                } catch (_: Exception) {
                }
            }
        }

        // Row at index 2 is typically "New Movies", and index 3 is "New Series".
        // We merge them and sort by release info so the absolute newest content
        // always floats to the top of the "New Releases" TV channel.
        val newMovies = displayRows.getOrNull(2)?.items.orEmpty()
        val newSeries = displayRows.getOrNull(3)?.items.orEmpty()

        val newReleases = (newMovies + newSeries)
            .distinctBy { it.id }
            .filter { !it.releaseInfo.isNullOrBlank() }
            .sortedByDescending { it.releaseInfo }
        if (newReleases.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    tvRecommendationManager.updateNewReleases(newReleases)
                } catch (_: Exception) {
                }
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
    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

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
        posterStatusReconcileJob?.cancel()
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}
