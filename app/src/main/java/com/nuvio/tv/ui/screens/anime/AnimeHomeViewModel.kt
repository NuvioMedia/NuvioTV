package com.nuvio.tv.ui.screens.anime

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.AnimeTvdbSettingsDataStore
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TvdbSettings
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.repository.OmdbAwardsRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.NextUpResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AnimeHomeViewModel @Inject constructor(
    internal val animeAddonRepository: AnimeAddonRepository,
    private val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    internal val omdbAwardsRepository: OmdbAwardsRepository,
    @Named("anime_layout") internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("anime_cw_cache") internal val animeCwEnrichmentCache: ContinueWatchingEnrichmentCache,
    @Named("anime_tmdb") internal val animeTmdbSettingsDataStore: TmdbSettingsDataStore,
    @Named("anime_mdblist") internal val animeMdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val animeTvdbSettingsDataStore: AnimeTvdbSettingsDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val tvdbMetadataService: TvdbMetadataService,
    internal val mdbListRepository: MDBListRepository,
    internal val trailerService: TrailerService
) : ViewModel() {

    companion object {
        private const val TAG = "AnimeHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    internal val _uiState = MutableStateFlow(AnimeHomeUiState())

    internal val animeCwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val animeCwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val animeCwNextUpResolutionCache =
        Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val animeCwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val animeDiscoveredOlderNextUpItems =
        Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val animeCwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val animeCwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    internal val animeCwEnrichedInProgressOverlay =
        ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    internal var animeCwPipelineJob: Job? = null
    internal var currentAnimeTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentAnimeMdbListSettings: MDBListSettings = MDBListSettings()
    internal var currentAnimeTvdbSettings: TvdbSettings = TvdbSettings()
    internal var animeHeroEnrichmentJob: Job? = null
    internal var lastAnimeHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    // Rating MDBList richiesto al focus di un item (speculare di
    // HomeViewModel.maybeFetchMdbListRatingForItem): senza questo, le stelle
    // di valutazione non compaiono mai sui poster di Anime.
    internal val animeMdbListRatingFetchedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal var animeMdbListRatingFocusJob: Job? = null
    internal var pendingAnimeMdbListRatingItemId: String? = null
    internal val trailerPreviewLoadingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val trailerPreviewNegativeCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    val uiState: StateFlow<AnimeHomeUiState> = _uiState.asStateFlow()

    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val rows = LinkedHashMap<String, CatalogRow>()
    private val catalogLoadSemaphore = Semaphore(MAX_CONCURRENT_CATALOG_LOADS)
    private val catalogLoadGeneration = AtomicInteger(0)
    private var lastCatalogLoadSignature: String? = null
    private var lastAddons: List<Addon> = emptyList()

    private val layoutOrderKeys = mutableListOf<String>()
    private val layoutDisabledKeys = mutableSetOf<String>()
    private val heroCatalogKeys = mutableListOf<String>()
    private var heroSectionEnabled = true
    private var homeLayout = HomeLayout.MODERN
    private var catalogTypeSuffixEnabled = true
    private var hideUnreleasedContent = false
    private var followAddonsOrder = false
    private var modernLandscapePostersEnabled = false
    private var modernHeroFullScreenBackdropEnabled = false
    private var classicFocusGradientEnabled = false
    private var continueWatchingCardStyle = ContinueWatchingCardStyle.CARD
    private var useEpisodeThumbnailsInCw = true
    private var blurContinueWatchingNextUp = false
    private var posterCardWidthDp = 126
    private var posterCardHeightDp = 189
    private var posterCardCornerRadiusDp = 12
    private var posterLabelsEnabled = true
    private var focusedPosterBackdropExpandEnabled = true
    private var focusedPosterBackdropExpandDelaySeconds = 3
    private var focusedPosterBackdropTrailerEnabled = false
    private var focusedPosterBackdropTrailerMuted = true
    private var focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    private var showFullReleaseDate = true

    init {
        observeLayoutPreferences()
        observeAnimeAddons()
        observeAnimeContinueWatching()
        observeAnimeEnrichmentSettings()
        observeCwCacheCleared()
    }

    // Observe manual cache clear from the anime TMDB settings (Release dates toggle).
    private fun observeCwCacheCleared() {
        viewModelScope.launch {
            var lastSeen = animeCwEnrichmentCache.cacheCleared.value
            animeCwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    clearAllAnimeCwInMemoryCaches()
                }
            }
        }
    }

    private fun clearAllAnimeCwInMemoryCaches() {
        animeCwMetaCache.clear()
        animeCwMetaNegativeCacheTimestamps.clear()
        animeCwNextUpResolutionCache.clear()
        animeCwNextUpNegativeCacheTimestamps.clear()
        animeDiscoveredOlderNextUpItems.clear()
        animeCwLastProcessedNextUpContentIds.clear()
        animeCwEnrichedNextUpOverlay.clear()
        animeCwEnrichedInProgressOverlay.clear()
    }

    private fun observeAnimeEnrichmentSettings() {
        viewModelScope.launch {
            combine(
                animeTmdbSettingsDataStore.settings,
                animeMdbListSettingsDataStore.settings,
                animeTvdbSettingsDataStore.settings
            ) { tmdb, mdb, tvdb ->
                Triple(tmdb, mdb, tvdb)
            }
                .distinctUntilChanged()
                .collectLatest { (tmdb, mdb, tvdb) ->
                    currentAnimeTmdbSettings = tmdb
                    currentAnimeMdbListSettings = mdb
                    currentAnimeTvdbSettings = tvdb
                    // Settings changed — allow hero items to re-enrich with the new selection.
                    lastAnimeHeroEnrichmentSignature = null
                    lastHeroEnrichedItems = emptyList()
                    enrichAnimeHeroItemsIfNeeded(_uiState.value.heroItems)
                }
        }
    }

    private fun enrichAnimeHeroItemsIfNeeded(heroItems: List<MetaPreview>) {
        val tmdbEnabled = currentAnimeTmdbSettings.enabled
        val mdbEnabled = currentAnimeMdbListSettings.enabled &&
            currentAnimeMdbListSettings.apiKey.isNotBlank()
        val tvdbEnabled = currentAnimeTvdbSettings.enabled && currentAnimeTvdbSettings.hasApiKey
        if (heroItems.isEmpty() || (!tmdbEnabled && !mdbEnabled && !tvdbEnabled)) {
            lastAnimeHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
            return
        }
        val signature = animeHeroEnrichmentSignature(heroItems)
        animeHeroEnrichmentJob?.cancel()
        animeHeroEnrichmentJob = viewModelScope.launch {
            if (lastAnimeHeroEnrichmentSignature == signature) {
                // Same items already enriched — restore the cached enriched list,
                // because every publishRows() rewrites heroItems with the raw list.
                val cached = lastHeroEnrichedItems
                if (cached.isNotEmpty()) {
                    _uiState.update { state ->
                        if (state.heroItems == cached) {
                            state
                        } else {
                            state.copy(
                                heroItems = cached,
                                heroItem = cached.firstOrNull() ?: state.heroItem
                            )
                        }
                    }
                }
                return@launch
            }
            val enrichedItems = enrichAnimeHeroItemsBatch(heroItems)
            lastAnimeHeroEnrichmentSignature = signature
            lastHeroEnrichedItems = enrichedItems
            _uiState.update { state ->
                if (state.heroItems == enrichedItems) {
                    state
                } else {
                    state.copy(
                        heroItems = enrichedItems,
                        heroItem = enrichedItems.firstOrNull() ?: state.heroItem
                    )
                }
            }
        }
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            val coreSnapshotFlow = combine(
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.heroCatalogSelections,
                layoutPreferenceDataStore.heroSectionEnabled,
                layoutPreferenceDataStore.selectedLayout
            ) { orderKeys, disabledKeys, heroKeys, heroEnabled, layout ->
                LayoutSnapshot(
                    orderKeys = orderKeys,
                    disabledKeys = disabledKeys.toSet(),
                    heroKeys = heroKeys,
                    heroEnabled = heroEnabled,
                    layout = layout
                )
            }
            val baseSnapshotFlow = combine(
                coreSnapshotFlow,
                layoutPreferenceDataStore.catalogTypeSuffixEnabled,
                layoutPreferenceDataStore.hideUnreleasedContent,
                layoutPreferenceDataStore.followAddonsOrder
            ) { snapshot, typeSuffix, hideUnreleased, followAddons ->
                snapshot.copy(
                    catalogTypeSuffixEnabled = typeSuffix,
                    hideUnreleasedContent = hideUnreleased,
                    followAddonsOrder = followAddons
                )
            }
            val viewSnapshotFlow = combine(
                baseSnapshotFlow,
                layoutPreferenceDataStore.modernLandscapePostersEnabled,
                layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled,
                layoutPreferenceDataStore.classicFocusGradientEnabled,
                layoutPreferenceDataStore.continueWatchingCardStyle
            ) { snapshot, landscape, fullscreenBackdrop, focusGradient, cardStyle ->
                snapshot.copy(
                    modernLandscapePostersEnabled = landscape,
                    modernHeroFullScreenBackdropEnabled = fullscreenBackdrop,
                    classicFocusGradientEnabled = focusGradient,
                    continueWatchingCardStyle = cardStyle
                )
            }
            val focusedPosterSnapshotFlow = combine(
                viewSnapshotFlow,
                layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
                layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled
            ) { snapshot, backdropExpand, backdropExpandDelay, trailerEnabled ->
                snapshot.copy(
                    focusedPosterBackdropExpandEnabled = backdropExpand,
                    focusedPosterBackdropExpandDelaySeconds = backdropExpandDelay,
                    focusedPosterBackdropTrailerEnabled = trailerEnabled
                )
            }
            val focusedPosterMutedFlow = combine(
                focusedPosterSnapshotFlow,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
            ) { snapshot, trailerMuted, playbackTarget ->
                snapshot.copy(
                    focusedPosterBackdropTrailerMuted = trailerMuted,
                    focusedPosterBackdropTrailerPlaybackTarget = playbackTarget
                )
            }
            val cardStyleSnapshotFlow = combine(
                focusedPosterMutedFlow,
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp,
                layoutPreferenceDataStore.posterLabelsEnabled
            ) { snapshot, width, height, cornerRadius, posterLabels ->
                snapshot.copy(
                    posterCardWidthDp = width,
                    posterCardHeightDp = height,
                    posterCardCornerRadiusDp = cornerRadius,
                    posterLabelsEnabled = posterLabels
                )
            }
            combine(
                cardStyleSnapshotFlow,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw,
                layoutPreferenceDataStore.blurContinueWatchingNextUp,
                layoutPreferenceDataStore.showFullReleaseDate
            ) { snapshot, thumbnails, blurNextUp, showFullReleaseDate ->
                snapshot.copy(
                    useEpisodeThumbnailsInCw = thumbnails,
                    blurContinueWatchingNextUp = blurNextUp,
                    showFullReleaseDate = showFullReleaseDate
                )
            }.distinctUntilChanged().collectLatest { snapshot ->
                layoutOrderKeys.clear()
                layoutOrderKeys.addAll(snapshot.orderKeys)
                layoutDisabledKeys.clear()
                layoutDisabledKeys.addAll(snapshot.disabledKeys)
                heroCatalogKeys.clear()
                heroCatalogKeys.addAll(snapshot.heroKeys)
                heroSectionEnabled = snapshot.heroEnabled
                homeLayout = snapshot.layout
                catalogTypeSuffixEnabled = snapshot.catalogTypeSuffixEnabled
                hideUnreleasedContent = snapshot.hideUnreleasedContent
                followAddonsOrder = snapshot.followAddonsOrder
                modernLandscapePostersEnabled = snapshot.modernLandscapePostersEnabled
                modernHeroFullScreenBackdropEnabled = snapshot.modernHeroFullScreenBackdropEnabled
                classicFocusGradientEnabled = snapshot.classicFocusGradientEnabled
                continueWatchingCardStyle = snapshot.continueWatchingCardStyle
                useEpisodeThumbnailsInCw = snapshot.useEpisodeThumbnailsInCw
                blurContinueWatchingNextUp = snapshot.blurContinueWatchingNextUp
                posterCardWidthDp = snapshot.posterCardWidthDp
                posterCardHeightDp = snapshot.posterCardHeightDp
                posterCardCornerRadiusDp = snapshot.posterCardCornerRadiusDp
                posterLabelsEnabled = snapshot.posterLabelsEnabled
                focusedPosterBackdropExpandEnabled = snapshot.focusedPosterBackdropExpandEnabled
                focusedPosterBackdropExpandDelaySeconds = snapshot.focusedPosterBackdropExpandDelaySeconds
                focusedPosterBackdropTrailerEnabled = snapshot.focusedPosterBackdropTrailerEnabled
                focusedPosterBackdropTrailerMuted = snapshot.focusedPosterBackdropTrailerMuted
                focusedPosterBackdropTrailerPlaybackTarget =
                    snapshot.focusedPosterBackdropTrailerPlaybackTarget
                showFullReleaseDate = snapshot.showFullReleaseDate
                publishRows()
            }
        }
    }

    private data class LayoutSnapshot(
        val orderKeys: List<String>,
        val disabledKeys: Set<String>,
        val heroKeys: List<String>,
        val heroEnabled: Boolean,
        val layout: HomeLayout,
        val catalogTypeSuffixEnabled: Boolean = true,
        val hideUnreleasedContent: Boolean = false,
        val followAddonsOrder: Boolean = false,
        val modernLandscapePostersEnabled: Boolean = false,
        val modernHeroFullScreenBackdropEnabled: Boolean = false,
        val classicFocusGradientEnabled: Boolean = false,
        val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
        val useEpisodeThumbnailsInCw: Boolean = true,
        val blurContinueWatchingNextUp: Boolean = false,
        val posterCardWidthDp: Int = 126,
        val posterCardHeightDp: Int = 189,
        val posterCardCornerRadiusDp: Int = 12,
        val posterLabelsEnabled: Boolean = true,
        val focusedPosterBackdropExpandEnabled: Boolean = true,
        val focusedPosterBackdropExpandDelaySeconds: Int = 3,
        val focusedPosterBackdropTrailerEnabled: Boolean = false,
        val focusedPosterBackdropTrailerMuted: Boolean = true,
        val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
            FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
        val showFullReleaseDate: Boolean = true
    )

    private fun observeAnimeAddons() {
        viewModelScope.launch {
            animeAddonRepository.getInstalledAnimeAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    lastAddons = addons
                    val enabled = addons.filter { it.enabled }
                    if (enabled.isEmpty()) {
                        invalidateCatalogLoads()
                        synchronized(rows) { rows.clear() }
                        clearTrailerPreviewState()
                        publishRows()
                        lastCatalogLoadSignature = null
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                installedAddonsCount = addons.size
                            )
                        }
                        return@collectLatest
                    }
                    loadAllCatalogs(enabled)
                }
        }
    }

    /** Stops in-flight loads writing into the rows map and drops hero enrichment. */
    private fun invalidateCatalogLoads() {
        catalogLoadGeneration.incrementAndGet()
        animeHeroEnrichmentJob?.cancel()
        animeHeroEnrichmentJob = null
        lastAnimeHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
    }

    private fun catalogKey(addon: Addon, catalog: CatalogDescriptor): String =
        "${addon.id}_${catalog.apiType}_${catalog.id}"

    private fun clearTrailerPreviewState() {
        trailerPreviewLoadingIds.clear()
        trailerPreviewNegativeCache.clear()
        trailerPreviewUrlsState.clear()
        trailerPreviewAudioUrlsState.clear()
        activeTrailerPreviewItemId = null
        trailerPreviewRequestVersion++
    }

    private fun shouldShowCatalog(catalog: CatalogDescriptor): Boolean {
        val isSearchOnly = catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
        if (isSearchOnly) return false
        return !catalog.hasExplicitShowInHome || catalog.showInHome
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) {
        val signature = buildAnimeCatalogLoadSignature(addons)
        // Same addon set already fully loaded — don't clear the rows just to
        // rebuild them (that flashed the whole tab on every addon emission).
        if (!forceReload &&
            signature == lastCatalogLoadSignature &&
            synchronized(rows) { rows.isNotEmpty() }
        ) {
            // Keep `error` untouched: if the stored rows are all itemless
            // (every load failed), clearing it would swap the Retry branch
            // for the generic empty state.
            _uiState.update {
                it.copy(isLoading = false, installedAddonsCount = addons.size)
            }
            return
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs.filter(::shouldShowCatalog).map { addon to it }
        }
        if (catalogsToLoad.isEmpty()) {
            // Enabled addons but nothing showable (all search-only/hidden):
            // finish the load instead of leaving the spinner up forever.
            invalidateCatalogLoads()
            synchronized(rows) { rows.clear() }
            clearTrailerPreviewState()
            publishRows()
            lastCatalogLoadSignature = signature
            _uiState.update {
                it.copy(isLoading = false, error = null, installedAddonsCount = addons.size)
            }
            return
        }

        val generation = catalogLoadGeneration.incrementAndGet()
        animeHeroEnrichmentJob?.cancel()
        animeHeroEnrichmentJob = null
        lastAnimeHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()

        // On reload keep the currently visible rows on screen while the new
        // ones stream in — same contract as the main home pipeline.
        val isReload = synchronized(rows) { rows.isNotEmpty() }
        if (isReload) {
            _uiState.update { it.copy(error = null, installedAddonsCount = addons.size) }
        } else {
            _uiState.update {
                it.copy(isLoading = true, error = null, installedAddonsCount = addons.size)
            }
            synchronized(rows) { rows.clear() }
        }
        clearTrailerPreviewState()

        synchronized(rows) {
            // Rebuild in manifest order while keeping the loaded content: the old
            // retainAll + putIfAbsent preserved the previous insertion order, so an
            // addon reorder (ordinal signature → full refetch) never visibly
            // reordered the rows until the ViewModel restarted.
            val previous = rows.toMap()
            rows.clear()
            catalogsToLoad.forEach { (addon, catalog) ->
                val key = catalogKey(addon, catalog)
                rows[key] = previous[key] ?: emptyRow(addon, catalog)
            }
        }
        publishRows()

        // Structured concurrency: a new addon emission (collectLatest restart)
        // cancels all in-flight loads, and each load is concurrency-limited by
        // the semaphore instead of running one at a time.
        val failedCount = AtomicInteger(0)
        val firstError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        try {
            coroutineScope {
                catalogsToLoad.forEach { (addon, catalog) ->
                    launch {
                        catalogLoadSemaphore.withPermit {
                            val failure = loadCatalog(addon, catalog, generation)
                            if (failure != null) {
                                failedCount.incrementAndGet()
                                firstError.compareAndSet(null, failure)
                            }
                        }
                    }
                }
            }
            lastCatalogLoadSignature = signature
            val failures = failedCount.get()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (failures > 0 && failures == catalogsToLoad.size) {
                        firstError.get()
                    } else {
                        null
                    }
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Anime catalog bulk load failed", e)
            lastCatalogLoadSignature = null
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun buildAnimeCatalogLoadSignature(addons: List<Addon>): String =
        addons.joinToString(separator = ",") { addon ->
            val catalogs = addon.catalogs.joinToString(separator = ";") { catalog ->
                listOf(
                    catalog.apiType,
                    catalog.id,
                    catalog.name,
                    catalog.showInHome.toString(),
                    catalog.hasExplicitShowInHome.toString(),
                    catalog.pageSize?.toString().orEmpty()
                ).joinToString("|")
            }
            listOf(
                addon.id,
                addon.baseUrl,
                addon.version,
                addon.displayName,
                catalogs
            ).joinToString("|")
        }

    private fun emptyRow(addon: Addon, catalog: CatalogDescriptor): CatalogRow {
        val placeholderItems = (0 until 8).map { i ->
            MetaPreview(
                id = "__placeholder_${addon.id}_${catalog.apiType}_${catalog.id}_$i",
                type = ContentType.fromString(catalog.apiType),
                rawType = catalog.apiType,
                name = " ",
                poster = PLACEHOLDER_IMAGE_URL,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = " ",
                imdbRating = null,
                genres = emptyList()
            )
        }
        return CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.type,
            rawType = catalog.apiType,
            items = placeholderItems,
            isLoading = true,
            hasMore = false,
            supportsSkip = catalog.supportsExtra("skip"),
            skipStep = catalog.skipStep()
        )
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Int): String? {
        val key = catalogKey(addon, catalog)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        Log.d(TAG, "Loading anime catalog addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id}")
        var sawTerminal = false
        var errorMessage: String? = null
        try {
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip
            ).collect { result ->
                // A newer load generation owns the rows map — drop stale results
                // (otherwise catalogs of removed addons reappeared after a reload).
                if (generation != catalogLoadGeneration.get()) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        sawTerminal = true
                        synchronized(rows) { rows[key] = result.data }
                        publishRows()
                    }
                    is NetworkResult.Error -> {
                        sawTerminal = true
                        errorMessage = result.message
                        synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false, items = emptyList()) }
                        publishRows()
                    }
                    NetworkResult.Loading -> { /* handled by row */ }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Anime catalog load failed addonId=${addon.id} catalogId=${catalog.id}", e)
            if (generation == catalogLoadGeneration.get()) {
                synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false, items = emptyList()) }
                publishRows()
            }
            return "${addon.displayName}: ${e.message ?: "load failed"}"
        }
        if (generation != catalogLoadGeneration.get()) return null
        if (!sawTerminal) return "${addon.displayName}: empty response"
        return errorMessage
    }

    fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val currentRow = synchronized(rows) { rows[key] }
        if (currentRow == null || currentRow.isLoading || !currentRow.hasMore) return

        synchronized(rows) { rows[key] = currentRow.copy(isLoading = true) }
        publishRows()

        viewModelScope.launch {
            try {
                val nextSkip = currentRow.nextCatalogSkip()
                catalogRepository.getCatalog(
                    addonBaseUrl = currentRow.addonBaseUrl,
                    addonId = currentRow.addonId,
                    addonName = currentRow.addonName,
                    catalogId = catalogId,
                    catalogName = currentRow.catalogName,
                    type = type,
                    skip = nextSkip,
                    skipStep = currentRow.skipStep,
                    supportsSkip = currentRow.supportsSkip
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            synchronized(rows) {
                                val latest = rows[key]
                                if (latest != null) {
                                    rows[key] = latest.mergeCatalogPage(result.data)
                                }
                            }
                            publishRows()
                        }
                        is NetworkResult.Error -> {
                            synchronized(rows) {
                                val latest = rows[key]
                                if (latest != null) {
                                    rows[key] = latest.copy(isLoading = false)
                                }
                            }
                            publishRows()
                        }
                        NetworkResult.Loading -> { }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Anime catalog load-more failed catalogId=$catalogId", e)
                synchronized(rows) {
                    val latest = rows[key]
                    if (latest != null) {
                        rows[key] = latest.copy(isLoading = false)
                    }
                }
                publishRows()
            }
        }
    }

    /**
     * Ensures the given catalog row is present (used by the See All screen when
     * navigating before the home rows finished loading).
     */
    fun ensureCatalogLoaded(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val existing = synchronized(rows) { rows[key] }
        // Empty rows are failed (or genuinely empty) catalogs — they must be
        // retried, otherwise an error on first load blocked See All forever.
        val firstId = existing?.items?.firstOrNull()?.id
        val hasRealContent = existing != null &&
            existing.items.isNotEmpty() &&
            firstId?.startsWith("__placeholder_") != true
        if (hasRealContent) return

        val addon = lastAddons.firstOrNull { it.id == addonId } ?: return
        val catalog = addon.catalogs.firstOrNull { it.apiType == type && it.id == catalogId } ?: return
        if (!shouldShowCatalog(catalog)) return

        val generation = catalogLoadGeneration.get()
        viewModelScope.launch {
            catalogLoadSemaphore.withPermit {
                val current = synchronized(rows) { rows[key] }
                val currentFirstId = current?.items?.firstOrNull()?.id
                val currentHasRealContent = current != null &&
                    current.items.isNotEmpty() &&
                    currentFirstId?.startsWith("__placeholder_") != true
                if (currentHasRealContent) return@withPermit
                loadCatalog(addon, catalog, generation)
            }
        }
    }

    private fun publishRows() {
        val snapshot = synchronized(rows) { rows.values.toList() }
        val ordered = orderRows(snapshot)
        val today = java.time.LocalDate.now()
        val released = if (hideUnreleasedContent) {
            ordered.map { it.filterReleasedItems(today) }
        } else {
            ordered
        }
        // See All must show the same rows the tab renders (ordered, disabled
        // excluded, unreleased filtered) — same contract as the regular home.
        if (_fullCatalogRows.value != released) {
            _fullCatalogRows.value = released
        }
        val filtered = released.filter { it.items.isNotEmpty() }
        val heroRow = computeHeroRow(visibleRows = filtered, allRows = snapshot, today = today)
        val heroItems = heroRow?.items.orEmpty()
        _uiState.update { state ->
            val updated = state.copy(
                rows = filtered,
                heroEnabled = heroSectionEnabled,
                heroItem = heroRow?.items?.firstOrNull(),
                heroItems = heroItems,
                heroAddonBaseUrl = heroRow?.addonBaseUrl,
                homeLayout = homeLayout,
                catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
                hideUnreleasedContent = hideUnreleasedContent,
                modernLandscapePostersEnabled = modernLandscapePostersEnabled,
                modernHeroFullScreenBackdropEnabled = modernHeroFullScreenBackdropEnabled,
                classicFocusGradientEnabled = classicFocusGradientEnabled,
                continueWatchingCardStyle = continueWatchingCardStyle,
                useEpisodeThumbnailsInCw = useEpisodeThumbnailsInCw,
                blurContinueWatchingNextUp = blurContinueWatchingNextUp,
                posterCardWidthDp = posterCardWidthDp,
                posterCardHeightDp = posterCardHeightDp,
                posterCardCornerRadiusDp = posterCardCornerRadiusDp,
                posterLabelsEnabled = posterLabelsEnabled,
                focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                focusedPosterBackdropTrailerPlaybackTarget = focusedPosterBackdropTrailerPlaybackTarget,
                showFullReleaseDate = showFullReleaseDate
            )
            if (updated == state) state else updated
        }
        // Called AFTER the state update so the cached-enriched restore (same
        // signature) is not overwritten by the raw heroItems written above.
        enrichAnimeHeroItemsIfNeeded(heroItems)
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        when (item) {
            is ContinueWatchingItem.InProgress -> removeAnimeContinueWatchingPipeline(
                contentId = item.progress.contentId,
                season = item.progress.season,
                episode = item.progress.episode,
                isNextUp = false
            )
            is ContinueWatchingItem.NextUp -> removeAnimeContinueWatchingPipeline(
                contentId = item.info.contentId,
                season = item.info.seedSeason,
                episode = item.info.seedEpisode,
                isNextUp = true,
                contentType = item.info.contentType
            )
        }
    }

    private fun orderRows(all: List<CatalogRow>): List<CatalogRow> {
        val enabled = all.filterNot {
            layoutDisabledKeys.contains(homeCatalogKey(it.addonId, it.rawType, it.catalogId))
        }
        // In follow addons order mode, catalogs always stay in manifest order.
        if (followAddonsOrder || layoutOrderKeys.isEmpty()) return enabled
        val ordered = mutableListOf<CatalogRow>()
        val remaining = enabled.toMutableList()
        for (key in layoutOrderKeys) {
            val match = remaining.firstOrNull {
                homeCatalogKey(it.addonId, it.rawType, it.catalogId) == key
            }
            if (match != null) {
                ordered.add(match)
                remaining.remove(match)
            }
        }
        ordered.addAll(remaining)
        return ordered
    }

    private fun computeHeroRow(
        visibleRows: List<CatalogRow>,
        allRows: List<CatalogRow>,
        today: java.time.LocalDate
    ): CatalogRow? {
        if (!heroSectionEnabled) return null
        fun isRealRow(row: CatalogRow): Boolean =
            row.items.isNotEmpty() &&
                row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
        if (heroCatalogKeys.isNotEmpty()) {
            // Selected hero catalogs keep driving the hero even when disabled
            // from the visible rows (main-tab parity) — lookup the full
            // snapshot first instead of silently jumping to another catalog.
            val visibleKeys = visibleRows
                .map { homeCatalogKey(it.addonId, it.rawType, it.catalogId) }
                .toSet()
            val heroOnlyRows = allRows.filter { row ->
                val key = homeCatalogKey(row.addonId, row.rawType, row.catalogId)
                key in heroCatalogKeys && key !in visibleKeys
            }.let { selected ->
                if (hideUnreleasedContent) selected.map { it.filterReleasedItems(today) } else selected
            }
            val candidates = visibleRows + heroOnlyRows
            for (key in heroCatalogKeys) {
                candidates.firstOrNull {
                    homeCatalogKey(it.addonId, it.rawType, it.catalogId) == key
                }?.takeIf(::isRealRow)?.let { return it }
            }
            // Selection matches nothing (catalog uninstalled) — fall through to
            // the generic fallback, same as the main home pipeline.
        }
        if (visibleRows.isEmpty()) return null
        return visibleRows.firstOrNull(::isRealRow)
    }

    /**
     * Speculare di [com.nuvio.tv.ui.screens.home.HomeViewModel]: al focus di un
     * item richiede il rating IMDb da MDBList (con debounce) e lo applica a righe
     * e hero, così le stelle di valutazione compaiono sui poster di Anime.
     */
    private fun maybeFetchAnimeMdbListRatingForItem(item: MetaPreview) {
        val mdbEnabled = currentAnimeMdbListSettings.enabled &&
            currentAnimeMdbListSettings.apiKey.isNotBlank()
        if (!mdbEnabled) return
        if (item.id in animeMdbListRatingFetchedIds) return

        pendingAnimeMdbListRatingItemId = item.id
        animeMdbListRatingFocusJob?.cancel()
        animeMdbListRatingFocusJob = viewModelScope.launch(Dispatchers.IO) {
            delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
            if (pendingAnimeMdbListRatingItemId != item.id) return@launch
            if (item.id in animeMdbListRatingFetchedIds) return@launch
            val rating = runCatching {
                mdbListRepository.getImdbRatingForItem(item.id, item.apiType)
            }.getOrNull()
            if (rating != null) {
                updateAnimeItemImdbRating(item.id, rating.toFloat())
                animeMdbListRatingFetchedIds.add(item.id)
            }
        }
    }

    private fun updateAnimeItemImdbRating(itemId: String, rating: Float) {
        _uiState.update { state ->
            var changed = false
            val updatedRows = state.rows.map { row ->
                val index = row.items.indexOfFirst { it.id == itemId }
                if (index < 0) row
                else {
                    val updated = row.items[index].copy(imdbRating = rating)
                    if (updated == row.items[index]) row
                    else {
                        changed = true
                        row.copy(items = row.items.toMutableList().also { it[index] = updated })
                    }
                }
            }
            val updatedHeroItems = state.heroItems.map { hero ->
                if (hero.id == itemId && hero.imdbRating != rating) {
                    changed = true
                    hero.copy(imdbRating = rating)
                } else {
                    hero
                }
            }
            val updatedHeroItem = state.heroItem?.let { hero ->
                if (hero.id == itemId && hero.imdbRating != rating) {
                    changed = true
                    hero.copy(imdbRating = rating)
                } else {
                    hero
                }
            } ?: state.heroItem
            if (changed) {
                state.copy(
                    rows = updatedRows,
                    heroItems = updatedHeroItems,
                    heroItem = updatedHeroItem
                )
            } else {
                state
            }
        }
    }

    fun onEvent(event: AnimeHomeEvent) {
        when (event) {
            is AnimeHomeEvent.OnLoadMoreCatalog ->
                loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is AnimeHomeEvent.OnItemFocus ->
                maybeFetchAnimeMdbListRatingForItem(event.item)
            AnimeHomeEvent.OnRetry -> {
                viewModelScope.launch {
                    val addons = lastAddons.filter { it.enabled }
                    if (addons.isNotEmpty()) loadAllCatalogs(addons, forceReload = true)
                }
            }
        }
    }
}
