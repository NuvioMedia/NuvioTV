package com.nuvio.tv.ui.screens.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.perf.FluidModeState
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.AnimeTvdbSettingsDataStore
import com.nuvio.tv.data.local.TvdbSettingsDataStore
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.OmdbAwardsRepository
import com.nuvio.tv.data.repository.TraktCommentsService
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.toggleTrackingMembershipSelection
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.core.util.selectEpisodeReleaseValue
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

private const val TAG = "MetaDetailsViewModel"

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
    private val fluidModeState: FluidModeState,
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    @Named("anime_tmdb") private val animeTmdbSettingsDataStore: TmdbSettingsDataStore,
    @Named("extra_tmdb") private val extraTmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val animeTvdbSettingsDataStore: AnimeTvdbSettingsDataStore,
    private val extraTvdbSettingsDataStore: com.nuvio.tv.data.local.ExtraTvdbSettingsDataStore,
    private val tvdbMetadataService: TvdbMetadataService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val metadataTextTranslator: MetadataTextTranslator,
    private val imdbEpisodeRatingsRepository: ImdbEpisodeRatingsRepository,
    private val mdbListRepository: MDBListRepository,
    private val omdbAwardsRepository: OmdbAwardsRepository,
    private val mdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    @Named("anime_mdblist") private val animeMdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    @Named("extra_mdblist") private val extraMdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktCommentsService: TraktCommentsService,
    private val traktRelatedService: TraktRelatedService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("anime_layout") private val animeLayoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("extra_layout") private val extraLayoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val animeAddonRepository: AnimeAddonRepository,
    private val extraAddonRepository: ExtraAddonRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val itemSourcePreferences: com.nuvio.tv.data.local.ItemSourcePreferences,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val routeAddonBaseUrl: String? =
        savedStateHandle.get<String>("addonBaseUrl")?.takeIf { it.isNotBlank() }
    private val detailOrigin: String =
        savedStateHandle.get<String>("origin")?.takeIf { it.isNotBlank() }.orEmpty()
    private var rememberedSource: String? = null
    private var metaAliasKeys: List<String> = emptyList()

    /**
     * Addon tried first for the meta lookup. Search/Discover/Calendar opens
     * honour the per-item memory (library-style addonBaseUrl) before the card's
     * source, so a title recorded from Home/Anime resolves with the same addon
     * and the same season count everywhere. Every other screen behaves exactly
     * like before: the card's source only, never the memory.
     */
    val preferredAddonBaseUrl: String?
        get() = if (detailOrigin == "search" || detailOrigin == "calendar") {
            rememberedSource ?: routeAddonBaseUrl
        } else {
            routeAddonBaseUrl
        }

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()

    private val localizedContext: Context
        get() {
            val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
                ?: return context
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private var moreLikeThisJob: Job? = null
    private var collectionJob: Job? = null

    val lastFocusedEpisodeIdBySeason = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    private var episodeRatingsJob: Job? = null
    private var nextToWatchJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsLoadMoreJob: Job? = null
    private var pendingDefaultLibraryToggle: LibraryEntryInput? = null

    private var trailerDelayMs = 7000L
    private var trailerAutoplayEnabled = false
    private var trailerHasPlayed = false
    private var suppressSeasonAutoSwitch = false

    private var isPlayButtonFocused = false
    private var hideUnreleasedContent = false
    private var traktCommentsEnabled = false
    private var traktAuthenticated = false
    private var moreLikeThisSourcePreference = com.nuvio.tv.data.local.MoreLikeThisSourcePreference.TRAKT

    /**
     * True when the item originated from an anime addon: anime layout settings
     * then override the global layout settings for this detail page.
     */
    private val animeLayoutActive = MutableStateFlow(false)

    /**
     * True when the item originated from an extra addon: extra layout settings
     * then override the global layout settings for this detail page.
     */
    private val extraLayoutActive = MutableStateFlow(false)

    private val activeLayoutDataStore: LayoutPreferenceDataStore
        get() = when {
            animeLayoutActive.value -> animeLayoutPreferenceDataStore
            extraLayoutActive.value -> extraLayoutPreferenceDataStore
            else -> layoutPreferenceDataStore
        }

    /**
     * Set at the start of every load: gates the performance behaviours (paint-first,
     * parallel layout probes, early spinner) on Fluid Mode, so with it off the detail
     * follows the structural order of the build before the speed-ups.
     */
    @Volatile
    private var fluidPerformance = false

    /** TMDB language/region settings for the tab this detail page belongs to. */
    private val activeTmdbSettingsDataStore: TmdbSettingsDataStore
        get() = when {
            animeLayoutActive.value -> animeTmdbSettingsDataStore
            extraLayoutActive.value -> extraTmdbSettingsDataStore
            else -> tmdbSettingsDataStore
        }

    /** MDBList settings (enabled flag + API key) for the tab this detail page belongs to. */
    private val activeMdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore
        get() = when {
            animeLayoutActive.value -> animeMdbListSettingsDataStore
            extraLayoutActive.value -> extraMdbListSettingsDataStore
            else -> mdbListSettingsDataStore
        }

    private val metaNamespace: String
        get() = when {
            animeLayoutActive.value -> com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ANIME
            extraLayoutActive.value -> com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_EXTRA
            // Same behaviour Anime gets from its own pool: the source addon is
            // always tried first (loadMeta / repository), then every pool races
            // so Home/Search details resolve like Anime instead of dying in HOME.
            else -> com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> layoutFlow(selector: (LayoutPreferenceDataStore) -> Flow<T>): Flow<T> =
        animeLayoutActive.flatMapLatest { animeActive ->
            if (animeActive) {
                selector(animeLayoutPreferenceDataStore)
            } else {
                extraLayoutActive.flatMapLatest { extraActive ->
                    if (extraActive) selector(extraLayoutPreferenceDataStore)
                    else selector(layoutPreferenceDataStore)
                }
            }
        }

    /** Content ID used for watch-progress and watched-items lookups.
     *  Starts as the navigation [itemId] (which may be "tmdb:123") and is
     *  updated to [Meta.id] once meta loads (typically an IMDB ID like "tt0396375").
     *  This ensures progress is read from the same key it was written under. */
    private val _effectiveContentId = MutableStateFlow(itemId)
    private val _optimisticMarks = mutableSetOf<Pair<Int, Int>>()
    private val _optimisticUnmarks = mutableSetOf<Pair<Int, Int>>()

    init {
        posterOptions.bind(viewModelScope)
        observeAnimeLayoutSource()
        observeExtraLayoutSource()
        observeMetaViewSettings()
        observeTrailerAutoplaySettings()
        observeTraktCommentsAvailability()
        observeLibraryState()
        observeWatchProgress()
        observeWatchedEpisodes()
        observeMovieWatched()
        observeBlurUnwatchedEpisodes()
        observeShowFullReleaseDate()
        observeHideUnreleasedContent()
        // Start the OMDb awards lookup before the addon meta round trip so the
        // hero awards line is ready on first composition (shared repository
        // caches and in-flight dedup make the later loadAwards call free).
        prefetchAwards()
        loadMeta()
    }

    private fun prefetchAwards() {
        if (itemId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                omdbAwardsRepository.getAwards(
                    imdbId = null,
                    primaryId = itemId,
                    apiType = itemType,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType
                )
            }.getOrNull()?.let { prefetched ->
                // Never overwrite a value already decided by loadAwards
                // (addon awards win over the OMDb prefetch).
                _uiState.update { state -> state.copy(awards = state.awards ?: prefetched) }
            }
        }
    }

    private fun observeAnimeLayoutSource() {
        viewModelScope.launch {
            resolveAnimeLayoutSource()
                .distinctUntilChanged()
                .collectLatest { active ->
                    animeLayoutActive.value = active
                }
        }
    }

    private fun observeExtraLayoutSource() {
        viewModelScope.launch {
            resolveExtraLayoutSource()
                .distinctUntilChanged()
                .collectLatest { active ->
                    extraLayoutActive.value = active
                }
        }
    }

    /**
     * One-shot provenance check for [preferredAddonBaseUrl].
     * [loadMeta] must await this before reading [metaNamespace] — the async
     * observers above race the first load and make Anime/Extra search hits
     * query the HOME (or ALL) pool instead of their own tab.
     */
    private suspend fun resolveLayoutSource() {
        // Both probes read disk-gated addon flows (up to 8s each) — run them
        // together so a cold start costs one timeout window, not two in sequence.
        // The anime flag still wins over extra, exactly like the serial version.
        val (animeActive, extraActive) = kotlinx.coroutines.coroutineScope {
            val anime = async { readLayoutFlag(resolveAnimeLayoutSource()) }
            val extra = async { readLayoutFlag(resolveExtraLayoutSource()) }
            anime.await() to extra.await()
        }
        animeLayoutActive.value = animeActive
        extraLayoutActive.value = !animeActive && extraActive
    }

    private suspend fun resolveProvenanceFallback(meta: Meta) {
        if (preferredAddonBaseUrl != null) return
        if (animeLayoutActive.value || extraLayoutActive.value) return
        val source = meta.sourceAddonBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (source.isEmpty()) return
        if (readLayoutFlag(resolveAnimeLayoutSource(source))) {
            extraLayoutActive.value = false
            animeLayoutActive.value = true
        } else if (readLayoutFlag(resolveExtraLayoutSource(source))) {
            animeLayoutActive.value = false
            extraLayoutActive.value = true
        }
    }

    /**
     * The underlying addon flows are gated on a disk read, so a bare `.first()` could
     * suspend past the detail load (or propagate a flow failure into the load scope).
     * Cap the wait and degrade to false instead.
     */
    private suspend fun readLayoutFlag(source: Flow<Boolean>): Boolean =
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { source.first() }
        }.getOrNull() ?: false

    private fun resolveAnimeLayoutSource(source: String? = preferredAddonBaseUrl): Flow<Boolean> {
        val normalizedSource = source?.trim()?.trimEnd('/')?.lowercase().orEmpty()
        if (normalizedSource.isEmpty()) return flowOf(false)
        return animeAddonRepository.getInstalledAnimeAddons()
            .map { addons ->
                addons.any { addon ->
                    addon.baseUrl.trim().trimEnd('/').lowercase() == normalizedSource
                }
            }
    }

    private fun resolveExtraLayoutSource(source: String? = preferredAddonBaseUrl): Flow<Boolean> {
        val normalizedSource = source?.trim()?.trimEnd('/')?.lowercase().orEmpty()
        if (normalizedSource.isEmpty()) return flowOf(false)
        return extraAddonRepository.getInstalledExtraAddons()
            .map { addons ->
                addons.any { addon ->
                    addon.baseUrl.trim().trimEnd('/').lowercase() == normalizedSource
                }
            }
    }

    private fun observeHideUnreleasedContent() {
        viewModelScope.launch {
            layoutFlow { it.hideUnreleasedContent }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    hideUnreleasedContent = enabled
                }
        }
    }

    private fun observeMetaViewSettings() {
        viewModelScope.launch {
            layoutFlow { it.detailPageTrailerButtonEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.trailerButtonEnabled == enabled) {
                            state
                        } else {
                            state.copy(trailerButtonEnabled = enabled)
                        }
                    }
                }
        }
    }

    private fun observeTraktCommentsAvailability() {
        viewModelScope.launch {
            traktSettingsDataStore.moreLikeThisSource.collectLatest { source ->
                moreLikeThisSourcePreference = source
            }
        }
        viewModelScope.launch {
            combine(
                traktSettingsDataStore.showMetaComments,
                traktAuthDataStore.isAuthenticated
            ) { enabled, authenticated ->
                enabled to authenticated
            }
                .distinctUntilChanged()
                .collectLatest { (enabled, authenticated) ->
                    traktCommentsEnabled = enabled
                    traktAuthenticated = authenticated

                    val meta = _uiState.value.meta
                    val shouldShow = enabled && authenticated && supportsComments(meta)
                    if (!shouldShow) {
                        cancelCommentsRequests()
                    }

                    _uiState.update { state ->
                        if (shouldShow) {
                            if (state.shouldShowCommentsSection) state else state.copy(
                                shouldShowCommentsSection = true
                            )
                        } else {
                            state.copy(
                                comments = emptyList(),
                                commentsCurrentPage = 0,
                                commentsPageCount = 0,
                                isCommentsLoading = false,
                                isCommentsLoadingMore = false,
                                commentsError = null,
                                shouldShowCommentsSection = false,
                                commentsMode = CommentsMode.TITLE,
                                commentsEpisodeTarget = null,
                                selectedComment = null
                            )
                        }
                    }

                    if (meta != null) {
                        loadMoreLikeThisAsync(meta)
                    }

                    if (shouldShow && meta != null) {
                        loadComments(meta)
                    }
                }
        }
    }

    private fun setTrailerPlaybackState(
        isPlaying: Boolean,
        showControls: Boolean,
        hideLogo: Boolean
    ) {
        _uiState.update { state ->
            if (state.isTrailerPlaying == isPlaying &&
                state.showTrailerControls == showControls &&
                state.hideLogoDuringTrailer == hideLogo
            ) {
                state
            } else {
                state.copy(
                    isTrailerPlaying = isPlaying,
                    showTrailerControls = showControls,
                    hideLogoDuringTrailer = hideLogo
                )
            }
        }
    }

    private fun updateNextToWatch(nextToWatch: NextToWatch) {
        _uiState.update { state ->
            if (state.nextToWatch == nextToWatch) return@update state
            val nextSeason = nextToWatch.nextSeason
            val meta = state.meta
            val shouldSwitchSeason = !suppressSeasonAutoSwitch &&
                nextSeason != null &&
                nextSeason != state.selectedSeason &&
                meta != null &&
                state.seasons.contains(nextSeason)
            if (shouldSwitchSeason) {
                state.copy(
                    nextToWatch = nextToWatch,
                    selectedSeason = nextSeason,
                    episodesForSeason = getEpisodesForSeason(meta.videos, nextSeason)
                )
            } else {
                state.copy(nextToWatch = nextToWatch)
            }
        }
    }

    private fun observeTrailerAutoplaySettings() {
        viewModelScope.launch {
            trailerSettingsDataStore.settings.collectLatest { settings ->
                trailerAutoplayEnabled = settings.enabled
                trailerDelayMs = settings.delaySeconds * 1000L
                if (!settings.enabled) {
                    idleTimerJob?.cancel()
                }
            }
        }
    }

    fun onEvent(event: MetaDetailsEvent) {
        when (event) {
            is MetaDetailsEvent.OnSeasonSelected -> selectSeason(event.season)
            is MetaDetailsEvent.OnEpisodeClick -> { /* Navigate to stream */ }
            is MetaDetailsEvent.OnCommentsModeSelected -> selectCommentsMode(event.mode)
            is MetaDetailsEvent.OnCommentsEpisodeSelected -> selectCommentsEpisode(event.video)
            MetaDetailsEvent.OnPlayClick -> { /* Start playback */ }
            MetaDetailsEvent.OnToggleLibrary -> toggleLibrary()
            MetaDetailsEvent.OnRetry -> loadMeta()
            MetaDetailsEvent.OnRetryComments -> _uiState.value.meta?.let { loadComments(it, forceRefresh = true) }
            MetaDetailsEvent.OnLoadMoreComments -> loadMoreComments()
            is MetaDetailsEvent.OnCommentSelected -> openCommentOverlay(event.review)
            is MetaDetailsEvent.OnAdvanceCommentOverlay -> advanceCommentOverlay(event.direction)
            MetaDetailsEvent.OnDismissCommentOverlay -> dismissCommentOverlay()
            MetaDetailsEvent.OnBackPress -> { /* Handle in screen */ }
            MetaDetailsEvent.OnUserInteraction -> handleUserInteraction()
            MetaDetailsEvent.OnPlayButtonFocused -> handlePlayButtonFocused()
            MetaDetailsEvent.OnTrailerButtonClick -> handleTrailerButtonClick()
            MetaDetailsEvent.OnTrailerEnded -> handleTrailerEnded()
            is MetaDetailsEvent.OnSharedTrailerSelected -> handleSharedTrailerSelected(event.trailer)
            MetaDetailsEvent.OnDismissSharedTrailer -> dismissSharedTrailerOverlay()
            MetaDetailsEvent.OnRetrySharedTrailer -> retrySharedTrailer()
            MetaDetailsEvent.OnToggleMovieWatched -> toggleMovieWatched()
            is MetaDetailsEvent.OnToggleEpisodeWatched -> toggleEpisodeWatched(event.video)
            is MetaDetailsEvent.OnMarkSeasonWatched -> markSeasonWatched(event.season)
            is MetaDetailsEvent.OnMarkSeasonUnwatched -> markSeasonUnwatched(event.season)
            is MetaDetailsEvent.OnMarkPreviousEpisodesWatched -> markPreviousEpisodesWatched(event.video)
            is MetaDetailsEvent.OnMarkPreviousSeasonsWatched -> markPreviousSeasonsWatched(event.season)
            MetaDetailsEvent.OnLibraryLongPress -> openListPicker()
            is MetaDetailsEvent.OnPickerMembershipToggled -> togglePickerMembership(event.listKey)
            MetaDetailsEvent.OnPickerSave -> savePickerMembership()
            MetaDetailsEvent.OnPickerDismiss -> dismissListPicker()
            MetaDetailsEvent.OnRemovalConfirmed -> confirmPickerRemoval()
            MetaDetailsEvent.OnRemovalCancelled -> cancelPickerRemoval()
            MetaDetailsEvent.OnClearMessage -> clearMessage()
            MetaDetailsEvent.OnLifecyclePause -> handleLifecyclePause()
        }
    }

    private fun observeLibraryState() {
        viewModelScope.launch {
            libraryRepository.sourceMode
                .distinctUntilChanged()
                .collectLatest { sourceMode ->
                    _uiState.update { state ->
                        if (state.librarySourceMode == sourceMode) {
                            state
                        } else {
                            state.copy(librarySourceMode = sourceMode)
                        }
                    }
                }
        }

        viewModelScope.launch {
            libraryRepository.membershipListTabs
                .distinctUntilChanged()
                .collectLatest { tabs ->
                _uiState.update { state ->
                    val selectedMembership = state.pickerMembership
                    val filteredMembership = if (selectedMembership.isEmpty()) {
                        selectedMembership
                    } else {
                        tabs.associate { tab -> tab.key to (selectedMembership[tab.key] == true) }
                    }
                    if (state.libraryListTabs == tabs &&
                        state.pickerMembership == filteredMembership
                    ) {
                        state
                    } else {
                        state.copy(
                            libraryListTabs = tabs,
                            pickerMembership = filteredMembership
                        )
                    }
                }
            }
        }

        // Observe library/watchlist on the *same* (id, type) pair that
        // `toggleLibrary` writes via `meta.toLibraryEntryInput()`. Falling back
        // to navigation (itemId, itemType) until meta loads keeps the button
        // responsive but pre-meta (when toggle is unavailable anyway).
        val canonicalKey = _uiState
            .map { state ->
                val id = state.meta?.id?.takeIf { it.isNotBlank() } ?: itemId
                val type = state.meta?.apiType?.takeIf { it.isNotBlank() } ?: itemType
                id to type
            }
            .distinctUntilChanged()

        viewModelScope.launch {
            canonicalKey
                .flatMapLatest { (id, type) -> libraryRepository.isInLibrary(itemId = id, itemType = type) }
                .distinctUntilChanged()
                .collectLatest { inLibrary ->
                    _uiState.update { state ->
                        if (state.isInLibrary == inLibrary) state else state.copy(isInLibrary = inLibrary)
                    }
                }
        }

        viewModelScope.launch {
            canonicalKey
                .flatMapLatest { (id, type) -> libraryRepository.isInWatchlist(itemId = id, itemType = type) }
                .distinctUntilChanged()
                .collectLatest { inWatchlist ->
                    _uiState.update { state ->
                        if (state.isInWatchlist == inWatchlist) state else state.copy(isInWatchlist = inWatchlist)
                    }
                }
        }
    }

    private fun observeWatchProgress() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                if (itemType.equals("other", ignoreCase = true)) {
                    // For "other" type, videos lack season/episode.
                    // Build progress map by matching video IDs to their
                    // position in the meta video list.
                    watchProgressRepository.allProgress.map { allProgress ->
                        val meta = _uiState.value.meta
                        val videos = meta?.videos ?: emptyList()
                        val progressByVideoId = allProgress
                            .filter { it.contentId == cid }
                            .associateBy { it.videoId }
                        val result = mutableMapOf<Pair<Int, Int>, WatchProgress>()
                        videos.forEachIndexed { index, video ->
                            val progress = progressByVideoId[video.id]
                            if (progress != null) {
                                // Use synthetic season=1, episode=index+1 as key
                                result[1 to (index + 1)] = progress
                            }
                        }
                        result as Map<Pair<Int, Int>, WatchProgress>
                    }
                } else {
                    watchProgressRepository.getAllEpisodeProgress(cid)
                }
            }
                .distinctUntilChanged()
                .collectLatest { progressMap ->
                _uiState.update { state ->
                    if (state.episodeProgressMap == progressMap) {
                        state
                    } else {
                        state.copy(episodeProgressMap = progressMap)
                    }
                }
                revalidateLocalWatchedEpisodesAgainstActiveProvider(progressMap)
                // Recalculate next to watch when progress changes
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
    }

    private fun revalidateLocalWatchedEpisodesAgainstActiveProvider(
        providerProgressMap: Map<Pair<Int, Int>, WatchProgress>
    ) {
        if (itemType.equals("other", ignoreCase = true)) return
        if (itemType.equals("movie", ignoreCase = true)) return
        if (providerProgressMap.isEmpty()) return
        val hasCompletedEntries = providerProgressMap.values.any { it.isCompleted() }
        if (!hasCompletedEntries) return

        viewModelScope.launch(Dispatchers.IO) {
            if (!watchProgressRepository.activeProviderOwnsCompletedHistoryProjection()) return@launch

            val contentId = _effectiveContentId.value
            val localWatched = watchedItemsPreferences
                .getWatchedEpisodesForContent(contentId)
                .first()
            if (localWatched.isEmpty()) return@launch

            val staleEpisodes = localWatched.filter { (season, episode) ->
                val providerEntry = providerProgressMap[season to episode]
                providerEntry == null || !providerEntry.isCompleted()
            }

            if (staleEpisodes.isNotEmpty()) {
                Log.d(TAG, "revalidateWatchedEpisodes: pruning ${staleEpisodes.size} stale entries for $contentId")
                watchedItemsPreferences.unmarkAsWatchedBatch(
                    contentId = contentId,
                    episodes = staleEpisodes.toList()
                )
            }
        }
    }

    private fun observeWatchedEpisodes() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                combine(
                    watchedItemsPreferences.getWatchedEpisodesForContent(cid),
                    watchProgressRepository.getAllEpisodeProgress(cid),
                    _uiState.map { it.meta?.videos }.distinctUntilChanged(),
                ) { localWatched, progressMap, videos ->
                    val fromProgress = progressMap.filterValues { it.isCompleted() }.keys
                    val merged = (localWatched + fromProgress).toMutableSet()
                    // Remove optimistic unmarks — episodes the user just batch-unmarked
                    // that may still linger in localWatched/fromProgress briefly.
                    merged -= _optimisticUnmarks
                    if (videos.isNullOrEmpty()) return@combine merged
                    for (video in videos) {
                        val s = video.season ?: continue
                        val e = video.episode ?: continue
                        val key = s to e
                        val watchedByVideoId = watchProgressRepository.isWatchedByVideoId(video.id, e)
                        val isWatched = resolveEpisodeWatchedState(
                            currentlyWatched = key in merged,
                            completedByProgress = key in fromProgress,
                            optimisticallyMarked = key in _optimisticMarks,
                            optimisticallyUnmarked = key in _optimisticUnmarks,
                            watchedByVideoId = watchedByVideoId
                        )
                        if (isWatched) merged += key else merged -= key
                    }
                    merged as Set<Pair<Int, Int>>
                }
            }
                .distinctUntilChanged()
                .collectLatest { watchedSet ->
                _uiState.update { state ->
                    if (state.watchedEpisodes == watchedSet) {
                        state
                    } else {
                        state.copy(watchedEpisodes = watchedSet)
                    }
                }
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
        // Re-calculate next-to-watch when "furthest episode" preference changes
        viewModelScope.launch {
            layoutFlow { it.nextUpFromFurthestEpisode }
                .distinctUntilChanged()
                .collectLatest {
                    calculateNextToWatch()
                }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMovieWatched() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                _uiState.map { it.meta?.imdbId?.takeIf { id -> id != cid && id.isNotBlank() } }
                    .distinctUntilChanged()
                    .flatMapLatest { videoId ->
                        watchProgressRepository.isWatched(cid, videoId = videoId)
                    }
            }
                .distinctUntilChanged()
                .collectLatest { watched ->
                _uiState.update { state ->
                    if (state.isMovieWatched == watched) state else state.copy(isMovieWatched = watched)
                }
            }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutFlow { it.blurUnwatchedEpisodes }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.blurUnwatchedEpisodes == enabled) state else state.copy(blurUnwatchedEpisodes = enabled)
                }
            }
        }
    }

    private fun observeShowFullReleaseDate() {
        viewModelScope.launch {
            layoutFlow { it.showFullReleaseDate }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.showFullReleaseDate == enabled) state else state.copy(showFullReleaseDate = enabled)
                }
            }
        }
    }

    /** Resets the detail state and shows the spinner; classic order calls this last. */
    private fun beginLoadingState() {
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                episodeImdbRatings = emptyMap(),
                isEpisodeRatingsLoading = false,
                episodeRatingsError = null,
                mdbListRatings = null,
                showMdbListImdb = false,
                awards = null,
                tmdbRating = null,
                tvdbRating = null,
                moreLikeThis = emptyList(),
                moreLikeThisSource = null,
                collection = emptyList(),
                collectionName = null,
                comments = emptyList(),
                commentsCurrentPage = 0,
                commentsPageCount = 0,
                isCommentsLoading = false,
                isCommentsLoadingMore = false,
                commentsError = null,
                shouldShowCommentsSection = false,
                commentsMode = CommentsMode.TITLE,
                commentsEpisodeTarget = null,
                selectedComment = null,
                isSharedTrailerOverlayVisible = false,
                isSharedTrailerLoading = false,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null,
                selectedSharedTrailer = null
            )
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            fluidPerformance = fluidModeState.isEnabled()
            // Fluid Mode: flip the spinner before resolveLayoutSource() probes the two
            // disk-gated addon flows (they can suspend for seconds).
            if (fluidPerformance) beginLoadingState()
            resolveLayoutSource()
            cancelCommentsRequests()
            // Classic: the spinner appears only after the layout probes, like the
            // build before the speed-ups.
            if (!fluidPerformance) beginLoadingState()

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)
            // Update effective content ID as early as possible so watch-progress
            // observers use the canonical (usually IMDB) ID, not the navigation ID.
            if (metaLookupId != itemId) {
                _effectiveContentId.value = metaLookupId
            }
            // Extract raw TMDB numeric ID from the original itemId so addons that can't
            // resolve IMDB (e.g. anime/TVDB addons) can still find the content.
            val rawTmdbNumericId = extractRawNumericId(itemId)
            // Resolve the per-item addon memory (library-style) before any lookup
            // so preferredAddonBaseUrl already answers with the remembered source.
            metaAliasKeys = com.nuvio.tv.data.local.ItemSourcePreferences.keysFor(
                itemType, itemId, metaLookupId, rawTmdbNumericId
            )
            rememberedSource = runCatching { itemSourcePreferences.get(metaAliasKeys) }.getOrNull()
            Log.d(
                TAG,
                "remembered source origin=$detailOrigin " +
                    "result=${rememberedSource ?: "miss"} keys=$metaAliasKeys"
            )
            val preferExternal = activeLayoutDataStore.preferExternalMetaAddonDetail.first()

            if (preferExternal) {
                // 1) Try meta addons first
                collectMetaLookup(
                    metaLookupId = metaLookupId,
                    rawTmdbNumericId = rawTmdbNumericId
                )
            } else {
                // Prefer the card's source addon first (same as Anime), trying
                // both the converted lookup id and the raw TMDB id before the pool race.
                val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                val preferredMeta: Meta? = preferred?.let { baseUrl ->
                    val candidateIds = buildList {
                        add(metaLookupId)
                        if (itemId.isNotBlank() && itemId != metaLookupId) {
                            add(itemId)
                        }
                        if (!rawTmdbNumericId.isNullOrBlank() &&
                            rawTmdbNumericId != metaLookupId &&
                            rawTmdbNumericId != itemId
                        ) {
                            add(rawTmdbNumericId)
                        }
                    }
                    candidateIds.firstNotNullOfOrNull { candidateId ->
                        when (
                            val result = metaRepository.getMeta(
                                addonBaseUrl = baseUrl,
                                type = itemType,
                                id = candidateId,
                                namespace = metaNamespace
                            ).first { it !is NetworkResult.Loading }
                        ) {
                            is NetworkResult.Success -> result.data
                            else -> null
                        }
                    }
                }

                if (preferredMeta != null) {
                    applyMetaWithEnrichment(preferredMeta)
                } else {
                    collectMetaLookup(
                        metaLookupId = metaLookupId,
                        rawTmdbNumericId = rawTmdbNumericId
                    )
                }
            }
        }
    }

    private suspend fun collectMetaLookup(
        metaLookupId: String,
        rawTmdbNumericId: String?
    ) {
        val namespaces = buildList {
            add(metaNamespace)
            // Anime/Extra stay on their pool first; on miss race every pool
            // (Home/Search/Library path) before surfacing an error.
            if (metaNamespace != com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL) {
                add(com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL)
            }
        }

        for ((index, namespace) in namespaces.withIndex()) {
            val isLastAttempt = index == namespaces.lastIndex
            var succeeded = false
            metaRepository.getMetaFromAllAddons(
                type = itemType,
                id = metaLookupId,
                sourceAddonBaseUrl = preferredAddonBaseUrl,
                rawId = rawTmdbNumericId,
                originalId = itemId,
                namespace = namespace
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        succeeded = true
                        applyMetaWithEnrichment(result.data)
                    }
                    is NetworkResult.Error -> {
                        // Never apply a synthetic TMDB meta with empty videos
                        // (fake detail). Only surface the error on the last attempt.
                        if (isLastAttempt) {
                            val errorMsg = buildMetaLoadErrorMessage(result.message, metaLookupId)
                            _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                        }
                    }
                    NetworkResult.Loading -> {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                }
            }
            if (succeeded) return
        }
    }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val raw = itemId.trim()
        val isTmdbFormat = raw.startsWith("tmdb:", ignoreCase = true) ||
            raw.startsWith("tmdb_tv_", ignoreCase = true) ||
            raw.startsWith("tmdb_movie_", ignoreCase = true)
        // Calendar fallback IDs (trakt_tv_*) are not addon-compatible — strip to
        // bare numeric so Stremio-style meta routes can still be attempted.
        val isTraktFormat = raw.startsWith("trakt_tv_", ignoreCase = true) ||
            raw.startsWith("trakt_movie_", ignoreCase = true) ||
            raw.startsWith("trakt:", ignoreCase = true)
        if (!isTmdbFormat && !isTraktFormat) return raw

        val numericId = when {
            raw.startsWith("tmdb:", ignoreCase = true) ->
                raw.substringAfter(':', missingDelimiterValue = "").substringBefore(':').toIntOrNull()
            raw.startsWith("trakt:", ignoreCase = true) ->
                raw.substringAfter(':', missingDelimiterValue = "").substringBefore(':').toIntOrNull()
            raw.startsWith("tmdb_tv_", ignoreCase = true) ->
                raw.removePrefix("tmdb_tv_").removePrefix("tmdb_Tv_").toIntOrNull()
            raw.startsWith("tmdb_movie_", ignoreCase = true) ->
                raw.removePrefix("tmdb_movie_").removePrefix("tmdb_Movie_").toIntOrNull()
            raw.startsWith("trakt_tv_", ignoreCase = true) ->
                raw.removePrefix("trakt_tv_").removePrefix("trakt_Tv_").toIntOrNull()
            raw.startsWith("trakt_movie_", ignoreCase = true) ->
                raw.removePrefix("trakt_movie_").removePrefix("trakt_Movie_").toIntOrNull()
            else -> null
        } ?: return raw

        if (isTraktFormat && !isTmdbFormat) {
            // No reliable TMDB↔IMDB bridge for a raw Trakt id; addons get the
            // bare number (best-effort) rather than an unparsable prefix.
            return numericId.toString()
        }

        // Try TMDB → IMDB first (best addon compatibility).
        val imdbId = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            tmdbService.tmdbToImdb(numericId, itemType)
        }?.takeIf { it.isNotBlank() }

        if (imdbId != null) return imdbId

        // Fallback: return bare numeric ID so addons can try their own TMDB lookup.
        return numericId.toString()
    }

    private fun extractRawNumericId(itemId: String): String? {
        val raw = itemId.trim()
        val numericId = when {
            raw.startsWith("tmdb_tv_", ignoreCase = true) ->
                raw.removePrefix("tmdb_tv_").removePrefix("tmdb_Tv_")
            raw.startsWith("tmdb_movie_", ignoreCase = true) ->
                raw.removePrefix("tmdb_movie_").removePrefix("tmdb_Movie_")
            raw.startsWith("tmdb:", ignoreCase = true) ->
                raw.substringAfter(':', missingDelimiterValue = "").substringBefore(':')
            else -> null
        }
        return numericId?.takeIf { it.all { c -> c.isDigit() } }
    }

    private fun buildMetaLoadErrorMessage(originalMessage: String?, lookupId: String): String {
        val base = originalMessage ?: context.getString(R.string.error_meta_not_found)
        val catalogHint = context.getString(R.string.error_meta_catalog_not_found)
        return "$catalogHint\n\n$base\n\nID: $lookupId"
    }

    /**
     * @param startSideEffects when false the trailer/comment kick-off is skipped;
     * used by the enriched re-apply so a paint-first pass already started them.
     */
    private fun applyMeta(meta: Meta, startSideEffects: Boolean = true) {
        // Update the effective content ID so watch-progress observers pick up
        // the canonical ID (e.g. IMDB "tt0396375") instead of the navigation ID
        // (which may be "tmdb:13836").  Don't downgrade from an IMDB ID to a
        // less canonical one (e.g. tmdb:) — Trakt stores progress under IMDB.
        if (meta.id.isNotBlank() && meta.id != itemId) {
            val currentIsImdb = _effectiveContentId.value.startsWith("tt")
            val newIsImdb = meta.id.startsWith("tt")
            if (!currentIsImdb || newIsImdb) {
                _effectiveContentId.value = meta.id
            }
        }

        val seasons = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()
            .ifEmpty {
                // For "other" type content videos lack season/episode numbers.  
                // Treat them as a single virtual season so the episodes UI can display them.
                if (meta.videos.isNotEmpty()) listOf(1) else emptyList()
            }

        val defaultEpisodeSeason = findPreferredDefaultEpisode(meta)?.season
        // Prefer addon-specified default episode season, otherwise first regular season (> 0), fallback to season 0 (specials)
        val selectedSeason = defaultEpisodeSeason
            ?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.firstOrNull()
            ?: 1
        val episodesForSeason = getEpisodesForSeason(meta.videos, selectedSeason)

        _uiState.update {
            // If nextToWatch already set a season (from pre-computed remap), prefer it
            // over the default season selection.
            val effectiveSeason = it.nextToWatch?.nextSeason
                ?.takeIf { s -> s in seasons }
                ?: selectedSeason
            val effectiveEpisodes = if (effectiveSeason != selectedSeason) {
                getEpisodesForSeason(meta.videos, effectiveSeason)
            } else {
                episodesForSeason
            }
            it.copy(
                isLoading = false,
                meta = meta,
                seasons = seasons,
                selectedSeason = effectiveSeason,
                episodesForSeason = effectiveEpisodes,
                error = null,
                commentsEpisodeTarget = null,
                shouldShowCommentsSection = traktCommentsEnabled && traktAuthenticated && supportsComments(meta)
            )
        }

        // Calculate next to watch after meta is loaded
        reevaluateSeriesWatchedBadge()
        calculateNextToWatch()

        if (startSideEffects) {
            // Start fetching trailer after meta is loaded
            fetchTrailerUrl()

            if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
                loadComments(meta)
            }
        }
    }

    /**
     * Persists the winning addon under every id alias of this title.
     *
     * Read-only from Search/Discover/Calendar: their (possibly wrong) card
     * source must never overwrite what Home/Anime recorded. Only a resolution
     * that actually came from the card's own addon counts — a pool-race winner
     * is not a user choice and would pin the title to a random addon.
     */
    private suspend fun rememberResolvedSource(meta: Meta) {
        if (detailOrigin == "search" || detailOrigin == "calendar") return
        val route = routeAddonBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (route.isEmpty()) return
        val source = meta.sourceAddonBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (source.isEmpty()) return
        val sameAddon = source.equals(route, ignoreCase = true) ||
            source.startsWith("$route/", ignoreCase = true) ||
            route.startsWith("$source/", ignoreCase = true)
        if (!sameAddon) {
            Log.d(TAG, "skip remember: race winner '$source' != card '$route'")
            return
        }
        val keys = (metaAliasKeys +
            com.nuvio.tv.data.local.ItemSourcePreferences.keysFor(itemType, itemId, meta.id))
            .distinct()
        if (keys.isEmpty()) return
        runCatching { itemSourcePreferences.remember(keys, source) }
            .onFailure { Log.w(TAG, "remember source failed: ${it.message}") }
    }

    private suspend fun applyMetaWithEnrichment(meta: Meta) {
        resolveProvenanceFallback(meta)
        rememberResolvedSource(meta)
        // Fire all independent async jobs immediately — they run in parallel.
        loadMoreLikeThisAsync(meta)
        // Paint the base meta right away: enrichMeta() below can burn seconds on a
        // stick (TMDB id + main + per-season episode lookups) and the detail
        // renders fine without it. Side effects (trailer, comments) start with
        // this first paint, so the enriched re-apply below skips them.
        applyMeta(meta)
        // La sinossi va tradotta SUBITO, in parallelo con l'enrichment:
        // enrichMeta() può bruciare secondi (id TMDB + main + episodi per
        // stagione + TVDB) e la traduzione partiva solo alla fine, tenendo il
        // dettaglio inglese per tutto quel tempo.
        var earlySynopsis: Pair<String?, String>? = null
        viewModelScope.launch {
            val translated = translateDescriptionEarly(meta)
            if (translated != null) {
                earlySynopsis = meta.description to translated
                publishTranslatedDescription(meta, translated)
            }
        }
        val enriched = enrichMeta(meta)

        // Pre-compute nextToWatch before applyMeta so the PlayButton text is stable
        // from the first composition — prevents focus invalidation from late recomposition.
        val (progressMap, watchedEpisodes) = coroutineScope {
            val progressDeferred = async(Dispatchers.IO) {
                watchProgressRepository.getAllEpisodeProgress(_effectiveContentId.value).first()
            }
            val watchedDeferred = async(Dispatchers.IO) {
                watchedItemsPreferences.getWatchedEpisodesForContent(_effectiveContentId.value).first()
            }
            progressDeferred.await() to watchedDeferred.await()
        }
        val precomputedNextToWatch = computeNextToWatch(enriched, progressMap, watchedEpisodes)
        updateNextToWatch(precomputedNextToWatch)

        if (enriched !== meta) {
            applyMeta(enriched, startSideEffects = false)
            // applyMeta sostituisce meta wholesale: se la sinossi non è
            // cambiata, ripubblica quella già tradotta invece di tornare
            // all'inglese per un frame (o per sempre, se il batch tarda).
            val early = earlySynopsis
            if (early != null && enriched.description == early.first) {
                publishTranslatedDescription(enriched, early.second)
            }
        }
        // Episode ratings and MDBList are independent — launch both without waiting.
        loadEpisodeRatingsAsync(enriched)
        viewModelScope.launch { loadMDBListRatings(enriched) }
        viewModelScope.launch { loadAwards(enriched) }

        // Translate after paint: the detail shows original texts immediately and
        // swaps in translations when ML Kit finishes — a model download on slow
        // sticks can otherwise block the first paint for tens of seconds.
        viewModelScope.launch {
            val language = activeTmdbSettingsDataStore.settings.first().language
            val translated = translateMetaTexts(enriched, language) { description ->
                publishTranslatedDescription(enriched, description)
            }
            if (translated === enriched) return@launch
            _uiState.update { state ->
                val current = state.meta
                if (current?.id != enriched.id) return@update state
                val mergedVideos = if (current.videos.size == translated.videos.size) {
                    current.videos.mapIndexed { index, video ->
                        val incoming = translated.videos[index]
                        if (incoming.season == video.season && incoming.episode == video.episode) {
                            video.copy(overview = incoming.overview)
                        } else {
                            video
                        }
                    }
                } else {
                    current.videos
                }
                val nextDescription = if (current.description == enriched.description) {
                    translated.description ?: current.description
                } else {
                    // Sinossi già sostituita (traduzione anticipata): non tornare
                    // mai al testo grezzo.
                    current.description
                }
                state.copy(
                    meta = current.copy(
                        description = nextDescription,
                        videos = mergedVideos
                    ),
                    episodesForSeason = getEpisodesForSeason(mergedVideos, state.selectedSeason)
                )
            }
        }
    }

    private fun loadComments(meta: Meta, forceRefresh: Boolean = false) {
        if (!traktCommentsEnabled || !traktAuthenticated || !supportsComments(meta)) {
            cancelCommentsRequests()
            _uiState.update { state ->
                state.copy(
                    comments = emptyList(),
                    commentsCurrentPage = 0,
                    commentsPageCount = 0,
                    isCommentsLoading = false,
                    isCommentsLoadingMore = false,
                    commentsError = null,
                    shouldShowCommentsSection = false,
                    commentsMode = CommentsMode.TITLE,
                    commentsEpisodeTarget = null,
                    selectedComment = null
                )
            }
            return
        }

        commentsJob?.cancel()
        commentsLoadMoreJob?.cancel()
        commentsJob = viewModelScope.launch {
            _uiState.update { state ->
                if (state.meta == null || state.meta.id != meta.id) {
                    state
                } else {
                    state.copy(
                        comments = emptyList(),
                        commentsCurrentPage = 0,
                        commentsPageCount = 0,
                        isCommentsLoading = true,
                        isCommentsLoadingMore = false,
                        commentsError = null,
                        shouldShowCommentsSection = true,
                        selectedComment = if (forceRefresh) null else state.selectedComment
                    )
                }
            }

            try {
                val page = traktCommentsService.getCommentsPage(
                    meta = meta,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType,
                    targetEpisode = currentCommentsEpisodeTarget(meta),
                    page = 1,
                    forceRefresh = forceRefresh
                )

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            comments = page.items,
                            commentsCurrentPage = page.currentPage,
                            commentsPageCount = page.pageCount,
                            isCommentsLoading = false,
                            isCommentsLoadingMore = false,
                            commentsError = null,
                            shouldShowCommentsSection = true,
                            selectedComment = state.selectedComment?.let { selected ->
                                page.items.firstOrNull { it.id == selected.id }
                            }
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load Trakt comments for ${meta.id}: ${error.message}")
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            comments = emptyList(),
                            commentsCurrentPage = 0,
                            commentsPageCount = 0,
                            isCommentsLoading = false,
                            isCommentsLoadingMore = false,
                            commentsError = localizedContext.getString(R.string.detail_comments_error),
                            shouldShowCommentsSection = true
                        )
                    }
                }
            }
        }
    }

    private fun supportsComments(meta: Meta?): Boolean {
        if (meta == null) return false
        return when (meta.type) {
            ContentType.MOVIE -> true
            ContentType.SERIES, ContentType.TV -> true
            else -> meta.apiType in listOf("movie", "series", "tv", "show")
        }
    }

    private fun loadMoreComments(selectNextAfterLoad: Boolean = false) {
        val state = _uiState.value
        val meta = state.meta ?: return
        if (!traktCommentsEnabled || !traktAuthenticated || !supportsComments(meta)) return
        if (state.isCommentsLoading || state.isCommentsLoadingMore || state.commentsCurrentPage == 0) return
        if (state.commentsPageCount > 0 && state.commentsCurrentPage >= state.commentsPageCount) return

        val nextPage = state.commentsCurrentPage + 1
        val currentLastCommentId = state.comments.lastOrNull()?.id
        val selectedCommentId = state.selectedComment?.id

        commentsLoadMoreJob?.cancel()
        commentsLoadMoreJob = viewModelScope.launch {
            _uiState.update { current ->
                if (current.meta?.id != meta.id) current else current.copy(isCommentsLoadingMore = true)
            }

            try {
                val page = traktCommentsService.getCommentsPage(
                    meta = meta,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType,
                    targetEpisode = currentCommentsEpisodeTarget(meta),
                    page = nextPage
                )

                _uiState.update { current ->
                    if (current.meta?.id != meta.id) {
                        current
                    } else {
                        val appended = page.items.filterNot { fetched ->
                            current.comments.any { existing -> existing.id == fetched.id }
                        }
                        val updatedComments = current.comments + appended
                        val shouldAdvanceSelection =
                            selectNextAfterLoad &&
                                current.selectedComment?.id == selectedCommentId &&
                                current.selectedComment?.id == currentLastCommentId &&
                                appended.isNotEmpty()

                        current.copy(
                            comments = updatedComments,
                            commentsCurrentPage = maxOf(current.commentsCurrentPage, page.currentPage),
                            commentsPageCount = maxOf(current.commentsPageCount, page.pageCount),
                            isCommentsLoadingMore = false,
                            commentsError = null,
                            selectedComment = if (shouldAdvanceSelection) appended.first() else current.selectedComment
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load more Trakt comments for ${meta.id}: ${error.message}")
                _uiState.update { current ->
                    if (current.meta?.id != meta.id) current else current.copy(isCommentsLoadingMore = false)
                }
            }
        }
    }

    private fun openCommentOverlay(review: TraktCommentReview) {
        _uiState.update { state ->
            state.copy(selectedComment = review)
        }
    }

    private fun advanceCommentOverlay(direction: Int) {
        if (direction == 0) return
        val state = _uiState.value
        val selected = state.selectedComment ?: return
        val selectedIndex = state.comments.indexOfFirst { it.id == selected.id }
        if (selectedIndex < 0) return

        val targetIndex = selectedIndex + direction
        if (targetIndex in state.comments.indices) {
            _uiState.update { current ->
                if (current.selectedComment?.id != selected.id) {
                    current
                } else {
                    current.copy(selectedComment = current.comments.getOrNull(targetIndex) ?: current.selectedComment)
                }
            }
            return
        }

        if (direction > 0) {
            loadMoreComments(selectNextAfterLoad = true)
        }
    }

    private fun dismissCommentOverlay() {
        _uiState.update { state ->
            state.copy(selectedComment = null)
        }
    }

    private fun cancelCommentsRequests() {
        commentsJob?.cancel()
        commentsLoadMoreJob?.cancel()
    }

    private fun loadMoreLikeThisAsync(meta: Meta) {
        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            val source = if (shouldLoadTraktMoreLikeThis(meta)) {
                MoreLikeThisSource.TRAKT
            } else {
                val settings = activeTmdbSettingsDataStore.settings.first()
                if (!shouldLoadMoreLikeThis(settings)) {
                    _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                    return@launch
                }
                MoreLikeThisSource.TMDB
            }

            val rawRecommendations = when (source) {
                MoreLikeThisSource.TRAKT -> {
                    runCatching {
                        traktRelatedService.getRelated(
                            meta = meta,
                            fallbackItemId = itemId,
                            fallbackItemType = itemType
                        )
                    }.getOrElse {
                        Log.w(TAG, "Failed to load Trakt related titles for ${meta.id}: ${it.message}")
                        emptyList()
                    }
                }

                MoreLikeThisSource.TMDB -> {
                    val settings = activeTmdbSettingsDataStore.settings.first()
                    val tmdbContentType = resolveTmdbContentType(meta)
                    val tmdbLookupType = tmdbContentType.toApiString()
                    val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                        ?: tmdbService.ensureTmdbId(itemId, itemType)
                    if (tmdbId.isNullOrBlank()) {
                        _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                        return@launch
                    }

                    runCatching {
                        tmdbMetadataService.fetchMoreLikeThis(
                            tmdbId = tmdbId,
                            contentType = tmdbContentType,
                            language = settings.language
                        )
                    }.getOrElse {
                        Log.w(TAG, "Failed to load More like this for ${meta.id}: ${it.message}")
                        emptyList()
                    }
                }
            }

            val recommendations = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                rawRecommendations.filterNot { it.isUnreleased(today) }
            } else {
                rawRecommendations
            }

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(
                        moreLikeThis = recommendations,
                        moreLikeThisSource = source.takeIf { recommendations.isNotEmpty() }
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun shouldLoadMoreLikeThis(settings: TmdbSettings): Boolean {
        return settings.enabled && settings.useMoreLikeThis
    }

    private fun shouldLoadTraktMoreLikeThis(meta: Meta): Boolean {
        if (!traktAuthenticated) return false
        if (moreLikeThisSourcePreference == com.nuvio.tv.data.local.MoreLikeThisSourcePreference.TMDB) return false
        return when (meta.type) {
            ContentType.MOVIE -> true
            ContentType.SERIES, ContentType.TV -> true
            else -> meta.apiType in listOf("movie", "series", "tv", "show")
        }
    }

    private fun loadCollectionAsync(collectionId: Int, collectionName: String?, settings: TmdbSettings) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            if (!settings.enabled || !settings.useCollections) {
                _uiState.update { it.copy(collection = emptyList(), collectionName = null) }
                return@launch
            }

            val collection = runCatching {
                tmdbMetadataService.fetchMovieCollection(
                    collectionId = collectionId,
                    language = settings.language
                )
            }.getOrElse {
                Log.w(TAG, "Failed to load collection $collectionId: ${it.message}")
                com.nuvio.tv.core.tmdb.TmdbMovieCollection(name = null, items = emptyList())
            }

            val filteredItems = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                collection.items.filterNot { it.isUnreleased(today) }
            } else {
                collection.items
            }

            _uiState.update { state ->
                state.copy(
                    collection = filteredItems,
                    collectionName = collection.name ?: collectionName
                )
            }
        }
    }

    private suspend fun loadMDBListRatings(meta: Meta) {
        val mdbSettings = activeMdbListSettingsDataStore.settings.first()
        val ratingsResult = runCatching {
            mdbListRepository.getRatingsForMetaWithSettings(
                meta = meta,
                fallbackItemId = itemId,
                fallbackItemType = itemType,
                settings = mdbSettings
            )
        }.onFailure { e ->
            Log.w(TAG, "MDBList ratings failed for ${meta.id}: ${e.message}")
        }.getOrNull()

        if (ratingsResult == null) {
            Log.d(TAG, "MDBList ratings: no result for ${meta.id} (disabled, no API key, or resolution failed)")
        } else {
            Log.d(TAG, "MDBList ratings loaded for ${meta.id}: ${ratingsResult.ratings}")
        }

        _uiState.update { state ->
            state.copy(
                mdbListRatings = ratingsResult?.ratings,
                showMdbListImdb = ratingsResult?.hasImdbRating == true
            )
        }
    }

    private suspend fun loadAwards(meta: Meta) {
        val addonAwards = meta.awards?.trim()?.takeIf { it.isNotBlank() }
        val resolved = addonAwards ?: runCatching {
            omdbAwardsRepository.getAwards(
                meta = meta,
                fallbackItemId = itemId,
                fallbackItemType = itemType
            )
        }.onFailure { e ->
            Log.w(TAG, "Awards lookup failed for ${meta.id}: ${e.message}")
        }.getOrNull()

        if (resolved == null) {
            Log.d(TAG, "Awards: no result for ${meta.id} (no OMDb key, no id, or no data)")
        }

        _uiState.update { state ->
            if (state.meta?.id != meta.id) {
                state
            } else {
                // Keep a value already published by prefetchAwards when this
                // resolution comes back empty.
                state.copy(awards = resolved ?: state.awards)
            }
        }
    }

    private fun loadEpisodeRatingsAsync(meta: Meta) {
        episodeRatingsJob?.cancel()

        val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV || meta.apiType in listOf("series", "tv")
        Log.d(TAG, "loadEpisodeRatings: meta.id=${meta.id}, type=${meta.type}, apiType=${meta.apiType}, isSeries=$isSeries")
        if (!isSeries) {
            Log.d(TAG, "loadEpisodeRatings: not a series, skipping")
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null
                )
            }
            return
        }

        episodeRatingsJob = viewModelScope.launch {
            // Per-tab gate: the episode ratings fetch (TMDB vote averages +
            // IMDb fallback) belongs to the TMDB enrichment of the tab that
            // opened this detail page, so honor that store's enabled flag.
            if (!activeTmdbSettingsDataStore.settings.first().enabled) {
                _uiState.update {
                    it.copy(
                        episodeImdbRatings = emptyMap(),
                        isEpisodeRatingsLoading = false,
                        episodeRatingsError = null
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = true,
                    episodeRatingsError = null
                )
            }

            try {
                val tmdbContentType = resolveTmdbContentType(meta)
                Log.d(TAG, "loadEpisodeRatings: tmdbContentType=$tmdbContentType")
                if (tmdbContentType !in listOf(ContentType.SERIES, ContentType.TV)) {
                    Log.d(TAG, "loadEpisodeRatings: content type not series, aborting")
                    _uiState.update {
                        it.copy(
                            episodeImdbRatings = emptyMap(),
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                    return@launch
                }

                val tmdbLookupType = tmdbContentType.toApiString()

                val metaIds = parseContentIds(meta.id)
                val routeIds = parseContentIds(itemId)
                Log.d(TAG, "loadEpisodeRatings: metaIds=$metaIds, routeIds=$routeIds, itemId=$itemId")

                var imdbId = metaIds.imdb ?: routeIds.imdb
                var tmdbId = metaIds.tmdb ?: routeIds.tmdb
                Log.d(TAG, "loadEpisodeRatings: after parseContentIds: imdbId=$imdbId, tmdbId=$tmdbId")

                if (tmdbId == null) {
                    Log.d(TAG, "loadEpisodeRatings: trying ensureTmdbId(meta.id=$meta.id, type=$tmdbLookupType)")
                    val tmdbIdString = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                    Log.d(TAG, "loadEpisodeRatings: ensureTmdbId(meta.id) returned: $tmdbIdString")
                    if (tmdbIdString == null) {
                        Log.d(TAG, "loadEpisodeRatings: trying ensureTmdbId(itemId=$itemId, type=$itemType)")
                        val tmdbIdString2 = tmdbService.ensureTmdbId(itemId, itemType)
                        Log.d(TAG, "loadEpisodeRatings: ensureTmdbId(itemId) returned: $tmdbIdString2")
                        tmdbId = tmdbIdString2?.toIntOrNull()
                    } else {
                        tmdbId = tmdbIdString.toIntOrNull()
                    }
                }

                Log.d(TAG, "loadEpisodeRatings: after ensureTmdbId: imdbId=$imdbId, tmdbId=$tmdbId")

                if (tmdbId != null && imdbId == null) {
                    Log.d(TAG, "loadEpisodeRatings: converting tmdbToImdb for tmdbId=$tmdbId")
                    imdbId = tmdbService.tmdbToImdb(tmdbId, tmdbLookupType)
                    Log.d(TAG, "loadEpisodeRatings: tmdbToImdb returned: $imdbId")
                }

                if (tmdbId == null && imdbId == null) {
                    Log.w(TAG, "loadEpisodeRatings: no TMDB or IMDB ID found for ${meta.id} / $itemId, ratings unavailable")
                    _uiState.update { state ->
                        if (state.meta == null || state.meta.id != meta.id) {
                            state
                        } else {
                            state.copy(
                                episodeImdbRatings = emptyMap(),
                                isEpisodeRatingsLoading = false,
                                episodeRatingsError = null
                            )
                        }
                    }
                    return@launch
                }

                Log.d(TAG, "loadEpisodeRatings: fetching ratings with imdbId=$imdbId, tmdbId=$tmdbId")
                val ratings = imdbEpisodeRatingsRepository.getEpisodeRatings(
                    imdbId = imdbId,
                    tmdbId = tmdbId
                )
                Log.d(TAG, "loadEpisodeRatings: got ${ratings.size} ratings, keys sample=${ratings.keys.take(3)}")

                val effectiveRatings = ratings

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        Log.w(TAG, "loadEpisodeRatings: meta changed during fetch, discarding results")
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = effectiveRatings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load episode ratings for ${meta.id}: ${error.message}", error)
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = emptyMap(),
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = localizedContext.getString(R.string.ratings_load_error)
                        )
                    }
                }
            }
        }
    }

    private suspend fun enrichMeta(meta: Meta): Meta {
        val settings = activeTmdbSettingsDataStore.settings.first()
        if (!settings.enabled) {
            fetchTmdbRatingOnly(meta)
            return enrichSeriesWithTvdb(meta)
        }

        val tmdbContentType = resolveTmdbContentType(meta)
        val tmdbLookupType = tmdbContentType.toApiString()
        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
            ?: tmdbService.ensureTmdbId(itemId, itemType)
        if (tmdbId == null) {
            // TMDB ID resolution failed — TVDB must still run independently.
            return enrichSeriesWithTvdb(meta)
        }

        val isSeries = meta.apiType in listOf("series", "tv")
        val needsEpisodes = (settings.useEpisodes || settings.useReleaseDates) && isSeries

        // Fetch main enrichment and episode enrichment in parallel.
        val (enrichment, episodeMap) = coroutineScope {
            val main = async(Dispatchers.IO) {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = tmdbId,
                    contentType = tmdbContentType,
                    language = settings.language
                )
            }
            val episodes = if (needsEpisodes) {
                async(Dispatchers.IO) {
                    val seasonNumbers = meta.videos.mapNotNull { it.season }.distinct()
                    tmdbMetadataService.fetchEpisodeEnrichment(
                        tmdbId = tmdbId,
                        seasonNumbers = seasonNumbers,
                        language = settings.language
                    )
                }
            } else null
            main.await() to episodes?.await()
        }

        var updated = meta

        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                logo = enrichment.logo ?: updated.logo
            )
        }

        if (enrichment != null && settings.useBasicInfo) {
            updated = updated.copy(
                name = enrichment.localizedTitle ?: updated.name,
                description = enrichment.description ?: updated.description
            )
            if (enrichment.genres.isNotEmpty()) {
                updated = updated.copy(genres = enrichment.genres)
            }
        }

        // Store TMDB rating separately so it can be shown with its own icon on the details screen.
        if (enrichment?.rating != null && settings.useBasicInfo) {
            _uiState.update { it.copy(tmdbRating = enrichment.rating.toFloat()) }
        }

        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: updated.runtime,
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                country = enrichment.countries?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language
            )
        }

        if (enrichment != null && settings.useReleaseDates) {
            updated = updated.copy(
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo
            )
        }

        if (enrichment != null && settings.useCredits) {
            val peopleCredits = buildList {
                addAll(enrichment.directorMembers)
                addAll(enrichment.writerMembers)
                addAll(enrichment.castMembers)
            }
                .filter { it.name.isNotBlank() }
                .distinctBy { it.tmdbId ?: (it.name.lowercase() + "|" + (it.character ?: "")) }

            if (peopleCredits.isNotEmpty()) {
                updated = updated.copy(
                    castMembers = peopleCredits,
                    cast = enrichment.castMembers.takeIf { it.isNotEmpty() }?.map { it.name } ?: updated.cast
                )
            }
            updated = updated.copy(
                director = if (enrichment.director.isNotEmpty()) enrichment.director else updated.director,
                writer = if (enrichment.writer.isNotEmpty()) enrichment.writer else updated.writer
            )
        }

        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = enrichment.networks)
        }

        if (enrichment != null && settings.useTrailers && enrichment.trailers.isNotEmpty()) {
            val mergedTrailers = mergeTrailers(
                existing = updated.trailers,
                incoming = enrichment.trailers
            )
            if (mergedTrailers.isNotEmpty()) {
                updated = updated.copy(
                    trailers = mergedTrailers,
                    trailerYtIds = mergedTrailers.mapNotNull { it.ytId }.distinct()
                )
            }
        }

        if (!episodeMap.isNullOrEmpty()) {
            updated = updated.copy(
                videos = meta.videos.map { video ->
                    val key = if (video.season != null && video.episode != null) video.season to video.episode else null
                    val ep = key?.let { episodeMap[it] }
                    video.copy(
                        title = if (settings.useEpisodes) ep?.title ?: video.title else video.title,
                        overview = if (settings.useEpisodes) ep?.overview ?: video.overview else video.overview,
                        released = selectEpisodeReleaseValue(
                            addonReleased = video.released,
                            tmdbAirDate = ep?.airDate,
                            useTmdbReleaseDates = settings.useReleaseDates
                        ),
                        thumbnail = if (settings.useEpisodes) ep?.thumbnail ?: video.thumbnail else video.thumbnail,
                        runtime = if (settings.useEpisodes) ep?.runtimeMinutes ?: video.runtime else video.runtime
                    )
                }
            )
        }

        if (enrichment?.collectionId != null) {
            loadCollectionAsync(enrichment.collectionId, enrichment.collectionName, settings)
        }

        return enrichSeriesWithTvdb(updated)
    }

    /**
     * Traduce solo la sinossi appena il meta grezzo è dipinto, senza aspettare
     * l'enrichment: è il ritardo percepito "la traduzione arriva in ritardo".
     * Ritorna null quando non c'è nulla da tradurre.
     */
    private suspend fun translateDescriptionEarly(raw: Meta): String? {
        val description = raw.description?.takeIf { it.isNotBlank() } ?: return null
        val language = runCatching {
            activeTmdbSettingsDataStore.settings.first().language
        }.getOrNull() ?: return null
        val translated = runCatching {
            metadataTextTranslator.translateDescription(description, language)
        }.getOrNull()
        return translated?.takeIf { it.isNotBlank() && it != description }
    }

    /**
     * Swaps the synopsis in as soon as the translator has it — the episode
     * overviews of the same batch can take tens of seconds longer and used to
     * hold the description back with them.
     */
    private fun publishTranslatedDescription(enriched: Meta, translatedDescription: String) {
        _uiState.update { state ->
            val current = state.meta
            if (current?.id != enriched.id) return@update state
            // Never clobber text the screen has moved on to since the batch started.
            if (current.description != enriched.description) return@update state
            state.copy(meta = current.copy(description = translatedDescription))
        }
    }

    private suspend fun translateMetaTexts(
        meta: Meta,
        targetLanguage: String,
        onDescriptionReady: (suspend (String) -> Unit)? = null
    ): Meta {
        return try {
            metadataTextTranslator.translateMeta(meta, targetLanguage, onDescriptionReady)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Metadata text translation failed: ${e.message}")
            meta
        }
    }

    private suspend fun fetchTmdbRatingOnly(meta: Meta) {
        try {
            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbLookupType = tmdbContentType.toApiString()
            val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                ?: tmdbService.ensureTmdbId(itemId, itemType)
                ?: return
            val tmdbLanguage = activeTmdbSettingsDataStore.settings.first().language

            val enrichment = withContext(Dispatchers.IO) {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = tmdbId,
                    contentType = tmdbContentType,
                    language = tmdbLanguage
                )
            }

            if (enrichment?.rating != null) {
                Log.d(TAG, "TMDB rating fetched for ${meta.id}: ${enrichment.rating}")
                _uiState.update { it.copy(tmdbRating = enrichment.rating.toFloat()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch TMDB rating for ${meta.id}: ${e.message}")
        }
    }

    private suspend fun enrichSeriesWithTvdb(meta: Meta): Meta {
        if (meta.apiType !in listOf("series", "tv")) return meta

        val tvdbSettings = resolveTvdbEnrichmentSettings()
        if (!tvdbSettings.enabled || !tvdbSettings.hasApiKey) return meta

        val tvdbResult = withContext(Dispatchers.IO) {
            tvdbMetadataService.enrichSeries(
                meta = meta,
                fallbackItemId = itemId,
                settings = tvdbSettings
            )
        }
        var updated = tvdbResult.meta
        if (tvdbResult.rating != null) {
            _uiState.update { it.copy(tvdbRating = tvdbResult.rating) }
        }
        if (tvdbResult.trailers.isNotEmpty()) {
            val mergedTrailers = mergeTrailers(
                existing = updated.trailers,
                incoming = tvdbResult.trailers
            )
            if (mergedTrailers.isNotEmpty()) {
                updated = updated.copy(
                    trailers = mergedTrailers,
                    trailerYtIds = mergedTrailers.mapNotNull { it.ytId }.distinct()
                )
            }
        }
        return updated
    }

    private suspend fun resolveTvdbEnrichmentSettings(): com.nuvio.tv.domain.model.TvdbSettings = when {
        animeLayoutActive.value -> animeTvdbSettingsDataStore.settings.first()
        extraLayoutActive.value -> extraTvdbSettingsDataStore.settings.first()
        else -> tvdbSettingsDataStore.settings.first()
    }

    private fun resolveTmdbContentType(meta: Meta): ContentType {
        val fromRoute = parseApiTypeToContentType(itemType)
        if (fromRoute != null) return fromRoute

        val fromMetaApi = parseApiTypeToContentType(meta.apiType)
        if (fromMetaApi != null) return fromMetaApi

        return when (meta.type) {
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            ContentType.MOVIE -> ContentType.MOVIE
            else -> ContentType.MOVIE
        }
    }

    private fun parseApiTypeToContentType(apiType: String?): ContentType? {
        val normalized = apiType?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "movie", "film" -> ContentType.MOVIE
            "series", "tv", "show", "tvshow" -> ContentType.SERIES
            else -> null
        }
    }

    private fun selectSeason(season: Int) {
        val meta = _uiState.value.meta ?: return
        val episodes = getEpisodesForSeason(meta.videos, season)
        _uiState.update {
            it.copy(
                selectedSeason = season,
                episodesForSeason = episodes
            )
        }
    }

    private fun getEpisodesForSeason(videos: List<Video>, season: Int): List<Video> {
        val filtered = videos.filter { it.season == season }
        if (filtered.isNotEmpty()) return filtered.sortedBy { it.episode }
        // Fallback: if no videos match the season (e.g. "other" type with
        // null seasons), return all videos with synthetic season/episode
        // numbers so the episode UI can track watched state.
        if (videos.isNotEmpty() && videos.all { it.season == null }) {
            return videos.mapIndexed { index, video ->
                video.copy(season = 1, episode = index + 1)
            }
        }
        return emptyList()
    }

    private fun selectCommentsMode(mode: CommentsMode) {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.commentsMode == mode) return
        val nextTarget = resolveCommentsEpisodeTarget(
            meta = meta,
            preferredSeason = _uiState.value.selectedSeason,
            existingTarget = _uiState.value.commentsEpisodeTarget
        )
        _uiState.update {
            it.copy(
                commentsMode = mode,
                commentsEpisodeTarget = nextTarget,
                selectedComment = null
            )
        }
        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta, forceRefresh = true)
        }
    }

    private fun selectCommentsEpisode(video: Video) {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.commentsEpisodeTarget?.id == video.id && _uiState.value.commentsMode == CommentsMode.EPISODE) {
            return
        }
        _uiState.update {
            it.copy(
                commentsMode = CommentsMode.EPISODE,
                commentsEpisodeTarget = video,
                selectedComment = null
            )
        }
        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta, forceRefresh = true)
        }
    }

    private fun currentCommentsEpisodeTarget(meta: Meta): Video? {
        val state = _uiState.value
        if (state.commentsMode != CommentsMode.EPISODE) return null
        return resolveCommentsEpisodeTarget(
            meta = meta,
            preferredSeason = state.selectedSeason,
            existingTarget = state.commentsEpisodeTarget
        )
    }

    private fun resolveCommentsEpisodeTarget(
        meta: Meta,
        preferredSeason: Int,
        existingTarget: Video?
    ): Video? {
        val allEpisodes = meta.videos.filter { it.season != null && it.episode != null }
        if (allEpisodes.isEmpty()) return null

        existingTarget
            ?.takeIf { target ->
                allEpisodes.any { episode ->
                    episode.season == target.season && episode.episode == target.episode
                }
            }
            ?.let { return it }

        val nextToWatch = _uiState.value.nextToWatch
        nextToWatch?.nextVideoId
            ?.let { nextVideoId ->
                allEpisodes.firstOrNull { episode -> episode.id == nextVideoId }
            }
            ?.let { return it }

        nextToWatch
            ?.takeIf { it.nextSeason != null && it.nextEpisode != null }
            ?.let { target ->
                allEpisodes.firstOrNull { episode ->
                    episode.season == target.nextSeason && episode.episode == target.nextEpisode
                }
            }
            ?.let { return it }

        return allEpisodes.firstOrNull { it.season == preferredSeason }
            ?: allEpisodes.firstOrNull { (it.season ?: 0) > 0 }
            ?: allEpisodes.first()
    }

    private fun reevaluateSeriesWatchedBadge() {
        val contentId = _effectiveContentId.value
        val meta = _uiState.value.meta ?: return
        val isSeries = meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        if (!isSeries) return

        val episodes = meta.watchableEpisodes()
        if (episodes.isEmpty()) return

        val watchedEpisodes = _uiState.value.watchedEpisodes
        val progressMap = _uiState.value.episodeProgressMap

        val allWatched = episodes.all { video ->
            val key = video.season!! to video.episode!!
            key in watchedEpisodes || progressMap[key]?.isCompleted() == true
        }

        val current = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val allIds = buildSet {
            add(contentId)
            meta.id.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
            itemId.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
        }
        val updated = if (allWatched) current + allIds else current - allIds
        if (updated != current) {
            watchedSeriesStateHolder.updateWithValidation(updated, allIds)
        }
    }

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = _uiState.value.episodeProgressMap
        val watchedEpisodes = _uiState.value.watchedEpisodes
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            val nextToWatch = computeNextToWatch(meta, progressMap, watchedEpisodes)
            updateNextToWatch(nextToWatch)
        }
    }

    private suspend fun computeNextToWatch(
        meta: Meta,
        progressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet()
    ): NextToWatch {
        val isSeries = meta.apiType in listOf("series", "tv")

        if (!isSeries) {
            val progress = watchProgressRepository.getProgress(_effectiveContentId.value).first()
            return if (progress != null && shouldResumeProgress(progress)) {
                NextToWatch(
                    watchProgress = progress,
                    isResume = true,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_resume)
                )
            } else {
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_play)
                )
            }
        }

        val allEpisodes = meta.videos
            .filter { it.season != null && it.episode != null }
            .filter { it.available != false }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (allEpisodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = meta.id,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        val nonSpecialEpisodes = allEpisodes.filter { (it.season ?: 0) > 0 }
        val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
        val useFurthestEpisode = activeLayoutDataStore.nextUpFromFurthestEpisode.first()
        val latestSeriesProgress = if (useFurthestEpisode) {
            // When using furthest episode mode, consider both progressMap entries
            // AND watchedEpisodes (batch marks) to find the furthest watched episode.
            val furthestFromProgress = progressMap.values
                .filter { it.isCompleted() || shouldResumeProgress(it) }
                .maxWithOrNull(
                    compareBy<WatchProgress> { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            val furthestFromWatched = watchedEpisodes
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                ?.let { (s, e) ->
                    // Only use watchedEpisodes entry if it's further than progressMap
                    val progressFurthest = furthestFromProgress?.let { (it.season ?: 0) to (it.episode ?: 0) }
                    if (progressFurthest == null || s > progressFurthest.first || (s == progressFurthest.first && e > progressFurthest.second)) {
                        episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                            WatchProgress(
                                contentId = _effectiveContentId.value,
                                contentType = "series",
                                name = "",
                                poster = null, backdrop = null, logo = null,
                                videoId = video.id,
                                season = video.season,
                                episode = video.episode,
                                episodeTitle = video.title,
                                position = 1L, duration = 1L,
                                lastWatched = System.currentTimeMillis(),
                                progressPercent = 100f
                            )
                        }
                    } else null
                }
            furthestFromWatched ?: furthestFromProgress
        } else {
            progressMap.values
                .sortedWith(
                    compareByDescending<WatchProgress> { it.lastWatched }
                        .thenByDescending { it.season ?: 0 }
                        .thenByDescending { it.episode ?: 0 }
                )
                .firstOrNull()
        }
        val effectiveLatestProgress = latestSeriesProgress ?: run {
            if (watchedEpisodes.isEmpty()) null
            else {
                val watchedWithTimestamps = watchedItemsPreferences
                    .getWatchedEpisodesWithTimestamps(_effectiveContentId.value)
                    .first()
                val highest = watchedWithTimestamps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Pair<Int, Int>, Long>> { it.value }
                        .thenBy { it.key.first }
                        .thenBy { it.key.second })
                highest?.let { (key, watchedAt) ->
                    val (s, e) = key
                    episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                        WatchProgress(
                            contentId = _effectiveContentId.value,
                            contentType = "series",
                            name = "",
                            poster = null, backdrop = null, logo = null,
                            videoId = video.id,
                            season = video.season,
                            episode = video.episode,
                            episodeTitle = video.title,
                            position = 1L, duration = 1L,
                            lastWatched = watchedAt,
                            progressPercent = 100f
                        )
                    }
                }
            }
        }
        val defaultEpisode = findPreferredDefaultEpisode(meta)?.takeIf { preferred ->
            episodePool.any { it.id == preferred.id }
        }

        return buildNextToWatchFromLatestProgress(
            latestProgress = effectiveLatestProgress,
            episodes = episodePool,
            fallbackProgressMap = progressMap,
            watchedEpisodes = watchedEpisodes,
            metaId = meta.id,
            defaultEpisode = defaultEpisode,
            isRewatchMode = !useFurthestEpisode
        )
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
        metaId: String,
        defaultEpisode: Video? = null,
        isRewatchMode: Boolean = false
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        if (latestProgress?.season != null && latestProgress.episode != null) {
            val season = latestProgress.season
            val episode = latestProgress.episode
            val matchedIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }

            if (shouldResumeProgress(latestProgress)) {
                val matchedEpisode = if (matchedIndex >= 0) episodes[matchedIndex] else null
                return NextToWatch(
                    watchProgress = latestProgress,
                    isResume = true,
                    nextVideoId = matchedEpisode?.id ?: latestProgress.videoId,
                    nextSeason = season,
                    nextEpisode = episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, season, episode)
                )
            }

            if (latestProgress.isCompleted() && matchedIndex >= 0) {
                if (isRewatchMode) {
                    // In rewatch mode, simply take the next episode regardless of watched state
                    val next = episodes.getOrNull(matchedIndex + 1)
                    if (next != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = next.id,
                            nextSeason = next.season,
                            nextEpisode = next.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, next.season, next.episode)
                        )
                    }
                } else {
                    // Normal mode: skip already watched episodes
                    val nextUnwatched = episodes.subList(matchedIndex + 1, episodes.size)
                        .firstOrNull { candidate ->
                            val s = candidate.season ?: return@firstOrNull true
                            val e = candidate.episode ?: return@firstOrNull true
                            val progress = fallbackProgressMap[s to e]
                            val isWatched = progress?.isCompleted() == true || (s to e) in watchedEpisodes
                            !isWatched
                        }
                    if (nextUnwatched != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = nextUnwatched.id,
                            nextSeason = nextUnwatched.season,
                            nextEpisode = nextUnwatched.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, nextUnwatched.season, nextUnwatched.episode)
                        )
                    }
                }
            }
        }

        var resumeEpisode: Video? = null
        var resumeProgress: WatchProgress? = null
        var nextUnwatchedEpisode: Video? = null

        for (episode in episodes) {
            val season = episode.season ?: continue
            val ep = episode.episode ?: continue
            val progress = fallbackProgressMap[season to ep]

            if (progress != null) {
                if (shouldResumeProgress(progress)) {
                    resumeEpisode = episode
                    resumeProgress = progress
                    break
                } else if (progress.isCompleted()) {
                    continue
                }
            }
            // Check watchedEpisodes — covers both batch marks and episodes
            // that haven't propagated to episodeProgressMap yet.
            if (progress == null && (season to ep) in watchedEpisodes) {
                continue
            }
            if (progress == null) {
                if (nextUnwatchedEpisode == null) {
                    nextUnwatchedEpisode = episode
                }
                if (resumeEpisode == null) {
                    break
                }
            }
        }

        return when {
            resumeEpisode != null && resumeProgress != null -> {
                NextToWatch(
                    watchProgress = resumeProgress,
                    isResume = true,
                    nextVideoId = resumeEpisode.id,
                    nextSeason = resumeEpisode.season,
                    nextEpisode = resumeEpisode.episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, resumeEpisode.season, resumeEpisode.episode)
                )
            }
            nextUnwatchedEpisode != null -> {
                val hasWatchedSomething = fallbackProgressMap.isNotEmpty()
                val preferredEpisode = if (hasWatchedSomething) nextUnwatchedEpisode else (defaultEpisode ?: nextUnwatchedEpisode)
                val s = preferredEpisode.season
                val e = preferredEpisode.episode
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = preferredEpisode.id,
                    nextSeason = s,
                    nextEpisode = e,
                    displayText = if (hasWatchedSomething) {
                        localizedContext.getString(R.string.detail_btn_next_episode, s, e)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play_episode, s, e)
                    }
                )
            }
            else -> {
                val firstEpisode = episodes.firstOrNull()
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = firstEpisode?.id ?: metaId,
                    nextSeason = firstEpisode?.season,
                    nextEpisode = firstEpisode?.episode,
                    displayText = if (firstEpisode != null) {
                        localizedContext.getString(R.string.detail_btn_play_episode, firstEpisode.season, firstEpisode.episode)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play)
                    }
                )
            }
        }
    }

    private fun findPreferredDefaultEpisode(meta: Meta): Video? {
        val defaultVideoId = meta.behaviorHints?.defaultVideoId ?: return null
        return meta.videos.firstOrNull { it.id == defaultVideoId && it.available != false }
    }

    private fun shouldResumeProgress(progress: WatchProgress): Boolean {
        if (progress.isCompleted()) return false
        if (progress.progressPercentage >= 0.02f) return true

        val hasStartedPlayback = progress.position > 0L ||
            progress.progressPercent?.let { it > 0f } == true
        return hasStartedPlayback &&
            progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
            progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
    }

    private fun toggleLibrary() {
        if (
            _uiState.value.defaultLibraryTogglePending ||
            _uiState.value.removalConfirmations.isNotEmpty()
        ) {
            return
        }
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val input = meta.toLibraryEntryInput()
            val wasInWatchlist = _uiState.value.isInWatchlist
            val wasInLibrary = _uiState.value.isInLibrary
            _uiState.update { it.copy(defaultLibraryTogglePending = true) }
            runCatching {
                libraryRepository.toggleDefault(input)
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    pendingDefaultLibraryToggle = input
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                    return@onSuccess
                }
                pendingDefaultLibraryToggle = null
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                val message = if (wasInLibrary || wasInWatchlist) {
                    localizedContext.getString(R.string.detail_removed_from_library)
                } else {
                    localizedContext.getString(R.string.detail_added_to_library)
                }
                showMessage(message)
            }.onFailure { error ->
                pendingDefaultLibraryToggle = null
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_library_failed),
                    isError = true
                )
            }
        }
    }

    private fun openListPicker() {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                val snapshot = libraryRepository.getMembershipSnapshot(meta.toLibraryEntryInput())
                _uiState.update {
                    it.copy(
                        showListPicker = true,
                        pickerMembership = snapshot.listMembership,
                        pickerPending = false,
                        pickerError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed),
                        showListPicker = false
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed), isError = true)
            }
        }
    }

    private fun togglePickerMembership(listKey: String) {
        _uiState.update { current ->
            val updatedMembership = toggleTrackingMembershipSelection(
                tabs = current.libraryListTabs,
                membership = current.pickerMembership,
                listKey = listKey,
                contentType = current.meta?.apiType
            ) ?: return@update current
            current.copy(
                pickerMembership = updatedMembership,
                pickerError = null
            )
        }
    }

    private fun savePickerMembership() {
        if (_uiState.value.pickerPending) return
        val meta = _uiState.value.meta ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(
                        desiredMembership = _uiState.value.pickerMembership
                    )
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    pendingDefaultLibraryToggle = null
                    _uiState.update {
                        it.copy(
                            pickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    completePickerSave()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed)
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed), isError = true)
            }
        }
    }

    private fun dismissListPicker() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                showListPicker = false,
                pickerPending = false,
                pickerError = null,
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
    }

    private fun confirmPickerRemoval() {
        if (_uiState.value.pickerPending || _uiState.value.defaultLibraryTogglePending) return
        val meta = _uiState.value.meta ?: return
        val confirmations = _uiState.value.removalConfirmations
        if (confirmations.isEmpty()) return
        val defaultToggle = pendingDefaultLibraryToggle
        if (defaultToggle != null) {
            confirmDefaultLibraryRemoval(defaultToggle, confirmations)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true) }
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(_uiState.value.pickerMembership),
                    confirmedRemovalProviders = confirmations.mapTo(linkedSetOf(), TrackingMembershipRemovalConfirmation::providerId)
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    _uiState.update {
                        it.copy(
                            pickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    completePickerSave()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        removalConfirmations = emptyList(),
                        pickerError = error.message
                            ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed)
                    )
                }
            }
        }
    }

    private fun confirmDefaultLibraryRemoval(
        input: LibraryEntryInput,
        confirmations: List<TrackingMembershipRemovalConfirmation>
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(defaultLibraryTogglePending = true) }
            runCatching {
                libraryRepository.toggleDefault(
                    item = input,
                    confirmedRemovalProviders = confirmations.mapTo(
                        linkedSetOf(),
                        TrackingMembershipRemovalConfirmation::providerId
                    )
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    pendingDefaultLibraryToggle = null
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = emptyList()
                        )
                    }
                    showMessage(localizedContext.getString(R.string.detail_removed_from_library))
                }
            }.onFailure { error ->
                pendingDefaultLibraryToggle = null
                _uiState.update {
                    it.copy(
                        defaultLibraryTogglePending = false,
                        removalConfirmations = emptyList()
                    )
                }
                showMessage(
                    message = error.message
                        ?: context.getString(com.nuvio.tv.R.string.detail_error_update_library_failed),
                    isError = true
                )
            }
        }
    }

    private fun cancelPickerRemoval() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
    }

    private fun completePickerSave() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                pickerPending = false,
                showListPicker = false,
                pickerError = null,
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
        showMessage(localizedContext.getString(R.string.detail_lists_updated))
    }

    private fun toggleMovieWatched() {
        val meta = _uiState.value.meta ?: return
        if (meta.apiType != "movie") return
        if (_uiState.value.isMovieWatchedPending) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMovieWatchedPending = true) }
            runCatching {
                if (_uiState.value.isMovieWatched) {
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = resolveFallbackVideoId())
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta))
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_watched_failed),
                    isError = true
                )
            }
            _uiState.update { it.copy(isMovieWatchedPending = false) }
        }
    }

    private fun toggleEpisodeWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val season = video.season ?: return
        val episode = video.episode ?: return
        val pendingKey = episodePendingKey(video)
        if (_uiState.value.episodeWatchedPendingKeys.contains(pendingKey)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKey)
            }

            val isWatched = _uiState.value.episodeProgressMap[season to episode]?.isCompleted() == true
                || _uiState.value.watchedEpisodes.contains(season to episode)
            runCatching {
                if (isWatched) {
                    _optimisticMarks -= season to episode
                    _optimisticUnmarks += season to episode
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = video.id, season = season, episode = episode)
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_unwatched))
                } else {
                    _optimisticUnmarks -= season to episode
                    _optimisticMarks += season to episode
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_episode_watched_failed),
                    isError = true
                )
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKey)
            }
        }
    }

    fun isSeasonFullyWatched(season: Int): Boolean {
        val state = _uiState.value
        val meta = state.meta ?: return false
        // For "other" type, use episodesForSeason which has synthetic season/episode.
        // For regular series, use meta.videos to support checking any season.
        val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
            state.episodesForSeason.filter { it.season == season && it.episode != null }
        } else {
            meta.videos.filter { it.season == season && it.episode != null }
        }
        if (episodes.isEmpty()) return false
        return episodes.all { video ->
            val s = video.season ?: return@all false
            val e = video.episode ?: return@all false
            state.episodeProgressMap[s to e]?.isCompleted() == true
                || state.watchedEpisodes.contains(s to e)
        }
    }

    private fun markSeasonWatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season == season && it.episode != null }
            } else {
                meta.videos.filter { it.season == season && it.episode != null }
            }
            val unwatched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_episodes_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark season $season as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_watched, unwatched.size))
        }
    }

    private fun markSeasonUnwatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season == season && it.episode != null }
            } else {
                meta.videos.filter { it.season == season && it.episode != null }
            }
            val watched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
            }
            if (watched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_no_watched_episodes))
                return@launch
            }

            val optimisticKeys = watched.map { it.season!! to it.episode!! }.toSet()
            _optimisticMarks -= optimisticKeys
            _optimisticUnmarks += optimisticKeys

            val pendingKeys = watched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val episodeTriples = watched.map { Triple(it.season!!, it.episode!!, it.id) }
                watchProgressRepository.removeFromHistoryBatch(
                    contentId = _effectiveContentId.value,
                    videoId = resolveFallbackVideoId(),
                    episodes = episodeTriples
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch unmark season $season: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_unwatched, watched.size))
        }
    }

    private fun markPreviousEpisodesWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val targetSeason = video.season ?: return
        val targetEpisode = video.episode ?: return

        viewModelScope.launch {
            val previous = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { v ->
                    v.season == targetSeason && v.episode != null && v.episode < targetEpisode
                }
            } else {
                meta.videos.filter { v ->
                    v.season == targetSeason && v.episode != null && v.episode < targetEpisode
                }
            }
            val unwatched = previous.filter { v ->
                val s = v.season!!
                val e = v.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_previous_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark previous episodes as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_previous_watched, unwatched.size))
        }
    }

    private fun markPreviousSeasonsWatched(targetSeason: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season != null && it.season < targetSeason && it.season > 0 && it.episode != null }
            } else {
                meta.videos.filter { it.season != null && it.season < targetSeason && it.season > 0 && it.episode != null }
            }
            val unwatched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_previous_seasons_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark previous seasons as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_watched, unwatched.size))
        }
    }

    private fun resolveFallbackVideoId(): String? {
        val meta = _uiState.value.meta ?: return null
        return meta.imdbId?.takeIf { it != itemId && it.isNotBlank() }
    }

    private fun buildCompletedMovieProgress(meta: Meta): WatchProgress {
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.backdropUrl,
            logo = meta.logo,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun buildCompletedEpisodeProgress(meta: Meta, video: Video): WatchProgress {
        val runtimeMs = video.runtime?.toLong()?.times(60_000L) ?: 1L
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = video.thumbnail ?: meta.backdropUrl,
            logo = meta.logo,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            position = runtimeMs,
            duration = runtimeMs,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun episodePendingKey(video: Video): String {
        return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { state ->
            if (state.userMessage == message && state.userMessageIsError == isError) {
                state
            } else {
                state.copy(
                    userMessage = message,
                    userMessageIsError = isError
                )
            }
        }
    }

    private fun clearMessage() {
        _uiState.update { state ->
            if (state.userMessage == null && !state.userMessageIsError) {
                state
            } else {
                state.copy(userMessage = null, userMessageIsError = false)
            }
        }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val normalized = rawId.trim()
        return if (normalized.startsWith("tt", ignoreCase = true)) {
            normalized.substringBefore(':')
        } else {
            null
        }
    }

    private fun Meta.toLibraryEntryInput(): LibraryEntryInput {
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
            addonBaseUrl = preferredAddonBaseUrl
        )
    }

    fun getNextEpisodeInfo(): String? {
        val nextToWatch = _uiState.value.nextToWatch
        return nextToWatch?.displayText
    }

    // --- Trailer ---

    private fun fetchTrailerUrl() {
        val meta = _uiState.value.meta ?: return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            _uiState.update { state ->
                if (state.isTrailerLoading) state else state.copy(isTrailerLoading = true)
            }

            val year = meta.releaseInfo?.let { info ->
                if (info.isBlank()) null
                else Regex("""\b(19|20)\d{2}\b""").find(info)?.value
            }

            val tmdbId = try {
                tmdbService.ensureTmdbId(meta.id, meta.apiType)
            } catch (_: Exception) {
                null
            }

            val tmdbSettingsOverride = runCatching { activeTmdbSettingsDataStore.settings.first() }.getOrNull()

            val source = if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                trailerService.getTrailerPlaybackSource(
                    title = meta.name,
                    year = year,
                    tmdbId = tmdbId,
                    type = meta.apiType,
                    tmdbSettingsOverride = tmdbSettingsOverride
                ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                    trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                        youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                        title = meta.name,
                        year = year
                    )
                }
            } else {
                val externalUrl = if (AppFeaturePolicy.externalTrailerPlaybackEnabled) {
                    trailerService.getExternalTrailerUrl(
                        tmdbId = tmdbId,
                        type = meta.apiType,
                        tmdbSettingsOverride = tmdbSettingsOverride
                    ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                        "https://www.youtube.com/watch?v=$ytId"
                    }
                } else {
                    null
                }
                externalUrl?.let { com.nuvio.tv.data.trailer.TrailerPlaybackSource(videoUrl = it) }
            }
            val url = source?.videoUrl
            val audioUrl = source?.audioUrl

            _uiState.update { state ->
                if (state.trailerUrl == url &&
                    state.trailerAudioUrl == audioUrl &&
                    !state.isTrailerLoading
                ) {
                    state
                } else {
                    state.copy(
                        trailerUrl = url,
                        trailerAudioUrl = audioUrl,
                        isTrailerLoading = false
                    )
                }
            }

            if (url != null && isPlayButtonFocused && AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                startIdleTimer()
            }
        }
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return

        val state = _uiState.value
        if (state.trailerUrl == null || state.isTrailerPlaying) return
        if (!trailerAutoplayEnabled) return
        if (trailerHasPlayed) return
        if (!isPlayButtonFocused) return

        idleTimerJob = viewModelScope.launch {
            delay(trailerDelayMs)
            setTrailerPlaybackState(
                isPlaying = true,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handlePlayButtonFocused() {
        if (isPlayButtonFocused) return
        isPlayButtonFocused = true
        startIdleTimer()
    }

    private fun handleUserInteraction() {
        val state = _uiState.value
        val shouldStopAutoTrailer = state.isTrailerPlaying && !state.showTrailerControls
        val hasActiveIdleTimer = idleTimerJob?.isActive == true
        if (!isPlayButtonFocused && !hasActiveIdleTimer && !shouldStopAutoTrailer) {
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false

        if (shouldStopAutoTrailer) {
            trailerHasPlayed = true
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handleLifecyclePause() {
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        dismissSharedTrailerOverlay()
        val state = _uiState.value
        if (state.isTrailerPlaying && !state.showTrailerControls) {
            trailerHasPlayed = true
            setTrailerPlaybackState(isPlaying = false, showControls = false, hideLogo = false)
        }
    }

    private fun handleTrailerButtonClick() {
        val state = _uiState.value
        if (state.trailerUrl.isNullOrBlank()) return
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            openExternalTrailer(state.trailerUrl)
            return
        }
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = true,
            showControls = true,
            hideLogo = true
        )
    }

    private fun handleTrailerEnded() {
        trailerHasPlayed = true
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = false,
            showControls = false,
            hideLogo = false
        )
    }

    private fun handleSharedTrailerSelected(trailer: MetaTrailer) {
        val ytId = trailer.ytId?.trim().orEmpty()
        if (ytId.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    isSharedTrailerOverlayVisible = true,
                    isSharedTrailerLoading = false,
                    sharedTrailerUrl = null,
                    sharedTrailerAudioUrl = null,
                    sharedTrailerErrorMessage = localizedContext.getString(R.string.detail_trailer_error),
                    selectedSharedTrailer = trailer
                )
            }
            return
        }

        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled && AppFeaturePolicy.externalTrailerPlaybackEnabled) {
            openExternalTrailer("https://www.youtube.com/watch?v=$ytId")
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        if (_uiState.value.isTrailerPlaying) {
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }

        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = true,
                isSharedTrailerLoading = true,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null,
                selectedSharedTrailer = trailer
            )
        }

        viewModelScope.launch {
            val meta = _uiState.value.meta
            val year = meta?.releaseInfo?.let { info ->
                if (info.isBlank()) null else Regex("""\b(19|20)\d{2}\b""").find(info)?.value
            }
            val source = trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                title = meta?.name,
                year = year
            )

            _uiState.update { state ->
                if (state.selectedSharedTrailer?.ytId != trailer.ytId) {
                    state
                } else {
                    state.copy(
                        isSharedTrailerOverlayVisible = true,
                        isSharedTrailerLoading = false,
                        sharedTrailerUrl = source?.videoUrl,
                        sharedTrailerAudioUrl = source?.audioUrl,
                        sharedTrailerErrorMessage = if (source == null) {
                            localizedContext.getString(R.string.detail_trailer_error)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private fun openExternalTrailer(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun retrySharedTrailer() {
        _uiState.value.selectedSharedTrailer?.let(::handleSharedTrailerSelected)
    }

    private fun dismissSharedTrailerOverlay() {
        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = false,
                isSharedTrailerLoading = false,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null
            )
        }
    }

    private fun mergeTrailers(existing: List<MetaTrailer>, incoming: List<MetaTrailer>): List<MetaTrailer> {
        if (existing.isEmpty()) return incoming.distinctBy { it.ytId ?: it.name ?: it.type ?: "" }
        if (incoming.isEmpty()) return existing

        val merged = LinkedHashMap<String, MetaTrailer>()

        fun keyOf(trailer: MetaTrailer): String {
            val yt = trailer.ytId?.trim().orEmpty()
            if (yt.isNotBlank()) return "yt:$yt"
            val fallback = listOf(trailer.name, trailer.type, trailer.lang)
                .joinToString("|") { it?.trim()?.lowercase().orEmpty() }
            return "meta:$fallback"
        }

        existing.forEach { trailer -> merged.putIfAbsent(keyOf(trailer), trailer) }
        incoming.forEach { trailer -> merged.putIfAbsent(keyOf(trailer), trailer) }
        return merged.values.toList()
    }

    override fun onCleared() {
        super.onCleared()
        idleTimerJob?.cancel()
        trailerFetchJob?.cancel()
        nextToWatchJob?.cancel()
    }
}
