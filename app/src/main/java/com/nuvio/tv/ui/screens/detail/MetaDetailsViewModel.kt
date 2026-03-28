package com.nuvio.tv.ui.screens.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktCommentsService
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.core.util.isUnreleased
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "MetaDetailsViewModel"

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val imdbEpisodeRatingsRepository: ImdbEpisodeRatingsRepository,
    private val mdbListRepository: MDBListRepository,
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
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private var moreLikeThisJob: Job? = null
    private var collectionJob: Job? = null
    private var episodeRatingsJob: Job? = null
    private var nextToWatchJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsLoadMoreJob: Job? = null

    private var trailerDelayMs = 7000L
    private var trailerAutoplayEnabled = false
    private var trailerHasPlayed = false

    private var isPlayButtonFocused = false
    private var hideUnreleasedContent = false
    private var traktCommentsEnabled = false
    private var traktAuthenticated = false

    /** Content ID used for watch-progress and watched-items lookups.
     *  Starts as the navigation [itemId] (which may be "tmdb:123") and is
     *  updated to [Meta.id] once meta loads (typically an IMDB ID like "tt0396375").
     *  This ensures progress is read from the same key it was written under. */
    private val _effectiveContentId = MutableStateFlow(itemId)

    init {
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
        loadMeta()
    }

    private fun observeHideUnreleasedContent() {
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    hideUnreleasedContent = enabled
                }
        }
    }

    private fun observeMetaViewSettings() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled
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
            val shouldSwitchSeason = nextSeason != null &&
                nextSeason != state.selectedSeason &&
                meta != null &&
                state.seasons.contains(nextSeason)
            if (shouldSwitchSeason && meta != null && nextSeason != null) {
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
            MetaDetailsEvent.OnToggleMovieWatched -> toggleMovieWatched()
            is MetaDetailsEvent.OnToggleEpisodeWatched -> toggleEpisodeWatched(event.video)
            is MetaDetailsEvent.OnMarkSeasonWatched -> markSeasonWatched(event.season)
            is MetaDetailsEvent.OnMarkSeasonUnwatched -> markSeasonUnwatched(event.season)
            is MetaDetailsEvent.OnMarkPreviousEpisodesWatched -> markPreviousEpisodesWatched(event.video)
            MetaDetailsEvent.OnLibraryLongPress -> openListPicker()
            is MetaDetailsEvent.OnPickerMembershipToggled -> togglePickerMembership(event.listKey)
            MetaDetailsEvent.OnPickerSave -> savePickerMembership()
            MetaDetailsEvent.OnPickerDismiss -> dismissListPicker()
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
            libraryRepository.listTabs
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

        viewModelScope.launch {
            libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                .distinctUntilChanged()
                .collectLatest { inLibrary ->
                    _uiState.update { state ->
                        if (state.isInLibrary == inLibrary) state else state.copy(isInLibrary = inLibrary)
                    }
                }
        }

        viewModelScope.launch {
            libraryRepository.isInWatchlist(itemId = itemId, itemType = itemType)
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
                watchProgressRepository.getAllEpisodeProgress(cid)
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
                // Recalculate next to watch when progress changes
                calculateNextToWatch()
            }
        }
    }

    private fun observeWatchedEpisodes() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                watchedItemsPreferences.getWatchedEpisodesForContent(cid)
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
            layoutPreferenceDataStore.blurUnwatchedEpisodes
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
            layoutPreferenceDataStore.showFullReleaseDate
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.showFullReleaseDate == enabled) state else state.copy(showFullReleaseDate = enabled)
                }
            }
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            cancelCommentsRequests()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null,
                    mdbListRatings = null,
                    showMdbListImdb = false,
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
                    selectedComment = null
                )
            }

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)
            // Update effective content ID as early as possible so watch-progress
            // observers use the canonical (usually IMDB) ID, not the navigation ID.
            if (metaLookupId != itemId) {
                _effectiveContentId.value = metaLookupId
            }
            val preferExternal = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()

            if (preferExternal) {
                // 1) Try meta addons first
                metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            applyMetaWithEnrichment(result.data)
                        }
                        is NetworkResult.Error -> {
                            // 2) Fallback: try originating addon if meta addons failed
                            val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                            val preferredMeta: Meta? = preferred?.let { baseUrl ->
                                when (val fallbackResult = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                                    .first { it !is NetworkResult.Loading }) {
                                    is NetworkResult.Success -> fallbackResult.data
                                    else -> null
                                }
                            }

                            if (preferredMeta != null) {
                                applyMetaWithEnrichment(preferredMeta)
                            } else {
                                _uiState.update { it.copy(isLoading = false, error = result.message) }
                            }
                        }
                        NetworkResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                    }
                }
            } else {
                // Original: prefer catalog addon
                val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                val preferredMeta: Meta? = preferred?.let { baseUrl ->
                    when (val result = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                        .first { it !is NetworkResult.Loading }) {
                        is NetworkResult.Success -> result.data
                        else -> null
                    }
                }

                if (preferredMeta != null) {
                    applyMetaWithEnrichment(preferredMeta)
                } else {
                    metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                        when (result) {
                            is NetworkResult.Success -> applyMetaWithEnrichment(result.data)
                            is NetworkResult.Error -> {
                                _uiState.update { it.copy(isLoading = false, error = result.message) }
                            }
                            NetworkResult.Loading -> {
                                _uiState.update { it.copy(isLoading = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val raw = itemId.trim()
        if (!raw.startsWith("tmdb:", ignoreCase = true)) return raw

        val tmdbNumericId = raw
            .substringAfter(':', missingDelimiterValue = "")
            .substringBefore(':')
            .toIntOrNull()
            ?: return raw

        return tmdbService.tmdbToImdb(tmdbNumericId, itemType) ?: raw
    }

    private fun applyMeta(meta: Meta) {
        // Update the effective content ID so watch-progress observers pick up
        // the canonical ID (e.g. IMDB "tt0396375") instead of the navigation ID
        // (which may be "tmdb:13836").  This also covers the reverse: if the user
        // arrived via an IMDB catalog but progress was stored under a tmdb: key,
        // we still try both.
        if (meta.id.isNotBlank() && meta.id != itemId) {
            _effectiveContentId.value = meta.id
        }

        val seasons = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()

        val defaultEpisodeSeason = findPreferredDefaultEpisode(meta)?.season
        // Prefer addon-specified default episode season, otherwise first regular season (> 0), fallback to season 0 (specials)
        val selectedSeason = defaultEpisodeSeason
            ?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.firstOrNull()
            ?: 1
        val episodesForSeason = getEpisodesForSeason(meta.videos, selectedSeason)

        _uiState.update {
            it.copy(
                isLoading = false,
                meta = meta,
                seasons = seasons,
                selectedSeason = selectedSeason,
                episodesForSeason = episodesForSeason,
                error = null,
                shouldShowCommentsSection = traktCommentsEnabled && traktAuthenticated && supportsComments(meta)
            )
        }

        // Calculate next to watch after meta is loaded
        reevaluateSeriesWatchedBadge()
        calculateNextToWatch()

        // Start fetching trailer after meta is loaded
        fetchTrailerUrl()

        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta)
        }
    }

    private suspend fun applyMetaWithEnrichment(meta: Meta) {
        // Fire all independent async jobs immediately — they run in parallel.
        loadMoreLikeThisAsync(meta)
        val enriched = enrichMeta(meta)
        applyMeta(enriched)
        // Episode ratings and MDBList are independent — launch both without waiting.
        loadEpisodeRatingsAsync(enriched)
        viewModelScope.launch { loadMDBListRatings(enriched) }
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
                            commentsError = context.getString(R.string.detail_comments_error),
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
                val settings = tmdbSettingsDataStore.settings.first()
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
                    val settings = tmdbSettingsDataStore.settings.first()
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

            val items = runCatching {
                tmdbMetadataService.fetchMovieCollection(
                    collectionId = collectionId,
                    language = settings.language
                )
            }.getOrElse {
                Log.w(TAG, "Failed to load collection $collectionId: ${it.message}")
                emptyList()
            }

            val filteredItems = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                items.filterNot { it.isUnreleased(today) }
            } else {
                items
            }

            _uiState.update { state ->
                state.copy(collection = filteredItems, collectionName = collectionName)
            }
        }
    }

    private suspend fun loadMDBListRatings(meta: Meta) {
        val ratingsResult = runCatching {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = itemId,
                fallbackItemType = itemType
            )
        }.getOrNull()

        _uiState.update { state ->
            state.copy(
                mdbListRatings = ratingsResult?.ratings,
                showMdbListImdb = ratingsResult?.hasImdbRating == true
            )
        }
    }

    private fun loadEpisodeRatingsAsync(meta: Meta) {
        episodeRatingsJob?.cancel()

        val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV || meta.apiType in listOf("series", "tv")
        if (!isSeries) {
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
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = true,
                    episodeRatingsError = null
                )
            }

            try {
                val tmdbContentType = resolveTmdbContentType(meta)
                if (tmdbContentType !in listOf(ContentType.SERIES, ContentType.TV)) {
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
                val tmdbIdString = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                    ?: tmdbService.ensureTmdbId(itemId, itemType)
                val tmdbId = tmdbIdString?.toIntOrNull()
                val imdbId = extractImdbId(meta.id) ?: extractImdbId(itemId)

                if (tmdbId == null && imdbId == null) {
                    _uiState.update { state ->
                        if (state.meta == null || state.meta.id != meta.id) {
                            state
                        } else {
                            state.copy(
                                episodeImdbRatings = emptyMap(),
                                isEpisodeRatingsLoading = false,
                                episodeRatingsError = context.getString(R.string.ratings_unavailable)
                            )
                        }
                    }
                    return@launch
                }

                val ratings = imdbEpisodeRatingsRepository.getEpisodeRatings(
                    imdbId = imdbId,
                    tmdbId = tmdbId
                )

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = ratings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load episode ratings for ${meta.id}: ${error.message}")
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = emptyMap(),
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = context.getString(R.string.ratings_load_error)
                        )
                    }
                }
            }
        }
    }

    private suspend fun enrichMeta(meta: Meta): Meta {
        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.enabled) return meta

        val tmdbContentType = resolveTmdbContentType(meta)
        val tmdbLookupType = tmdbContentType.toApiString()
        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
            ?: tmdbService.ensureTmdbId(itemId, itemType)
            ?: return meta

        val isSeries = meta.apiType in listOf("series", "tv")
        val needsEpisodes = settings.useEpisodes && isSeries

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
            updated = updated.copy(imdbRating = enrichment.rating?.toFloat() ?: updated.imdbRating)
        }

        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: updated.runtime,
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo,
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                country = enrichment.countries?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language
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

        if (!episodeMap.isNullOrEmpty()) {
            updated = updated.copy(
                videos = meta.videos.map { video ->
                    val key = if (video.season != null && video.episode != null) video.season to video.episode else null
                    val ep = key?.let { episodeMap[it] }
                    video.copy(
                        title = ep?.title ?: video.title,
                        overview = ep?.overview ?: video.overview,
                        released = ep?.airDate ?: video.released,
                        thumbnail = ep?.thumbnail ?: video.thumbnail,
                        runtime = ep?.runtimeMinutes
                    )
                }
            )
        }

        if (enrichment?.collectionId != null) {
            loadCollectionAsync(enrichment.collectionId, enrichment.collectionName, settings)
        }

        return updated
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
        val episodes = _uiState.value.meta?.videos?.let { getEpisodesForSeason(it, season) } ?: emptyList()
        _uiState.update {
            it.copy(
                selectedSeason = season,
                episodesForSeason = episodes
            )
        }
    }

    private fun getEpisodesForSeason(videos: List<Video>, season: Int): List<Video> {
        return videos
            .filter { it.season == season }
            .sortedBy { it.episode }
    }

    private fun reevaluateSeriesWatchedBadge() {
        val contentId = _effectiveContentId.value
        val meta = _uiState.value.meta ?: return
        val isSeries = meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        if (!isSeries) return

        val episodes = meta.videos.filter {
            it.season != null && it.episode != null && (it.season ?: 0) > 0 &&
                (it.available != false || !it.released.isNullOrBlank())
        }
        if (episodes.isEmpty()) return

        val watchedEpisodes = _uiState.value.watchedEpisodes
        val allWatched = episodes.all { video ->
            (video.season!! to video.episode!!) in watchedEpisodes
        }

        val current = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val updated = if (allWatched) current + contentId else current - contentId
        if (updated != current) {
            watchedSeriesStateHolder.update(updated)
        }
    }

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = _uiState.value.episodeProgressMap
        val isSeries = meta.apiType in listOf("series", "tv")
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            if (!isSeries) {
                // For movies, check if there's an in-progress watch
                val progress = watchProgressRepository.getProgress(_effectiveContentId.value).first()
                val nextToWatch = if (progress != null && shouldResumeProgress(progress)) {
                    NextToWatch(
                        watchProgress = progress,
                        isResume = true,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = context.getString(R.string.detail_btn_resume)
                    )
                } else {
                    NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = context.getString(R.string.detail_btn_play)
                    )
                }
                updateNextToWatch(nextToWatch)
                return@launch
            }

            val allEpisodes = meta.videos
                .filter { it.season != null && it.episode != null }
                .filter { it.available != false }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            if (allEpisodes.isEmpty()) {
                updateNextToWatch(
                    NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = context.getString(R.string.detail_btn_play)
                    )
                )
                return@launch
            }

            val nonSpecialEpisodes = allEpisodes.filter { (it.season ?: 0) > 0 }
            val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
            val latestSeriesProgress = progressMap.values
                .sortedWith(
                    compareByDescending<WatchProgress> { it.lastWatched }
                        .thenByDescending { it.season ?: 0 }
                        .thenByDescending { it.episode ?: 0 }
                )
                .firstOrNull()
            val defaultEpisode = findPreferredDefaultEpisode(meta)?.takeIf { preferred ->
                episodePool.any { it.id == preferred.id }
            }

            val nextToWatch = buildNextToWatchFromLatestProgress(
                latestProgress = latestSeriesProgress,
                episodes = episodePool,
                fallbackProgressMap = progressMap,
                metaId = meta.id,
                defaultEpisode = defaultEpisode
            )

            updateNextToWatch(nextToWatch)
        }
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        metaId: String,
        defaultEpisode: Video? = null
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = context.getString(R.string.detail_btn_play)
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
                    displayText = context.getString(R.string.detail_btn_resume_episode, season, episode)
                )
            }

            if (latestProgress.isCompleted() && matchedIndex >= 0) {
                val next = episodes.getOrNull(matchedIndex + 1)
                if (next != null) {
                    return NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = next.id,
                        nextSeason = next.season,
                        nextEpisode = next.episode,
                        displayText = context.getString(R.string.detail_btn_next_episode, next.season, next.episode)
                    )
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
            } else {
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
                    displayText = context.getString(R.string.detail_btn_resume_episode, resumeEpisode.season, resumeEpisode.episode)
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
                        context.getString(R.string.detail_btn_next_episode, s, e)
                    } else {
                        context.getString(R.string.detail_btn_play_episode, s, e)
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
                        context.getString(R.string.detail_btn_play_episode, firstEpisode.season, firstEpisode.episode)
                    } else {
                        context.getString(R.string.detail_btn_play)
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
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val input = meta.toLibraryEntryInput()
            val wasInWatchlist = _uiState.value.isInWatchlist
            val wasInLibrary = _uiState.value.isInLibrary
            runCatching {
                libraryRepository.toggleDefault(input)
                val message = if (_uiState.value.librarySourceMode == LibrarySourceMode.TRAKT) {
                    if (wasInWatchlist) context.getString(R.string.watchlist_removed) else context.getString(R.string.watchlist_added)
                } else {
                    if (wasInLibrary) context.getString(R.string.detail_removed_from_library) else context.getString(R.string.detail_added_to_library)
                }
                showMessage(message)
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: "Failed to update library",
                    isError = true
                )
            }
        }
    }

    private fun openListPicker() {
        if (_uiState.value.librarySourceMode != LibrarySourceMode.TRAKT) return
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
                        pickerError = error.message ?: "Failed to load lists",
                        showListPicker = false
                    )
                }
                showMessage(error.message ?: "Failed to load lists", isError = true)
            }
        }
    }

    private fun togglePickerMembership(listKey: String) {
        val current = _uiState.value.pickerMembership[listKey] == true
        _uiState.update {
            it.copy(
                pickerMembership = it.pickerMembership.toMutableMap().apply {
                    this[listKey] = !current
                },
                pickerError = null
            )
        }
    }

    private fun savePickerMembership() {
        if (_uiState.value.pickerPending) return
        if (_uiState.value.librarySourceMode != LibrarySourceMode.TRAKT) return
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
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        showListPicker = false,
                        pickerError = null
                    )
                }
                showMessage(context.getString(R.string.detail_lists_updated))
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: "Failed to update lists"
                    )
                }
                showMessage(error.message ?: "Failed to update lists", isError = true)
            }
        }
    }

    private fun dismissListPicker() {
        _uiState.update {
            it.copy(
                showListPicker = false,
                pickerPending = false,
                pickerError = null
            )
        }
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
                    showMessage(context.getString(R.string.detail_movie_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta))
                    showMessage(context.getString(R.string.detail_movie_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: "Failed to update watched status",
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
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = resolveFallbackVideoId(), season = season, episode = episode)
                    showMessage(context.getString(R.string.detail_episode_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    showMessage(context.getString(R.string.detail_episode_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: "Failed to update episode watched status",
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
        val episodes = meta.videos.filter { it.season == season && it.episode != null }
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
        viewModelScope.launch {
            val episodes = meta.videos.filter { it.season == season && it.episode != null }
            val unwatched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(context.getString(R.string.detail_all_episodes_watched))
                return@launch
            }

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            var marked = 0
            for (video in unwatched) {
                val key = episodePendingKey(video)
                runCatching {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    marked++
                }.onFailure { error ->
                    Log.w(TAG, "Failed to mark S${video.season}E${video.episode} as watched: ${error.message}")
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage(context.getString(R.string.detail_marked_episodes_watched, marked))
        }
    }

    private fun markSeasonUnwatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val episodes = meta.videos.filter { it.season == season && it.episode != null }
            val watched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
            }
            if (watched.isEmpty()) {
                showMessage(context.getString(R.string.detail_no_watched_episodes))
                return@launch
            }

            val pendingKeys = watched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            var unmarked = 0
            for (video in watched) {
                val key = episodePendingKey(video)
                runCatching {
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = resolveFallbackVideoId(), season = video.season!!, episode = video.episode!!)
                    unmarked++
                }.onFailure { error ->
                    Log.w(TAG, "Failed to unmark S${video.season}E${video.episode}: ${error.message}")
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage(context.getString(R.string.detail_marked_episodes_unwatched, unmarked))
        }
    }

    private fun markPreviousEpisodesWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val targetSeason = video.season ?: return
        val targetEpisode = video.episode ?: return

        viewModelScope.launch {
            val previous = meta.videos.filter { v ->
                v.season == targetSeason && v.episode != null && v.episode < targetEpisode
            }
            val unwatched = previous.filter { v ->
                val s = v.season!!
                val e = v.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(context.getString(R.string.detail_all_previous_watched))
                return@launch
            }

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            var marked = 0
            for (ep in unwatched) {
                val key = episodePendingKey(ep)
                runCatching {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, ep))
                    marked++
                }.onFailure { error ->
                    Log.w(TAG, "Failed to mark S${ep.season}E${ep.episode} as watched: ${error.message}")
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage(context.getString(R.string.detail_marked_previous_watched, marked))
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

            val source = trailerService.getTrailerPlaybackSource(
                title = meta.name,
                year = year,
                tmdbId = tmdbId,
                type = meta.apiType
            ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                    title = meta.name,
                    year = year
                )
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

            if (url != null && isPlayButtonFocused) {
                startIdleTimer()
            }
        }
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()

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
        val state = _uiState.value
        if (state.isTrailerPlaying && !state.showTrailerControls) {
            trailerHasPlayed = true
            setTrailerPlaybackState(isPlaying = false, showControls = false, hideLogo = false)
        }
    }

    private fun handleTrailerButtonClick() {
        val state = _uiState.value
        if (state.trailerUrl.isNullOrBlank()) return
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

    override fun onCleared() {
        super.onCleared()
        idleTimerJob?.cancel()
        trailerFetchJob?.cancel()
        nextToWatchJob?.cancel()
    }
}
