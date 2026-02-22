package com.nuvio.tv.ui.screens.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MetaDetailsViewModel"

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
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
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()

    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private var moreLikeThisJob: Job? = null
    private var episodeRatingsJob: Job? = null
    private var nextToWatchJob: Job? = null

    private var trailerDelayMs = 7000L
    private var trailerAutoplayEnabled = false

    private var isPlayButtonFocused = false

    init {
        observeMetaViewSettings()
        observeTrailerAutoplaySettings()
        observeLibraryState()
        observeWatchProgress()
        observeWatchedEpisodes()
        observeMovieWatched()
        observeBlurUnwatchedEpisodes()
        loadMeta()
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
            if (state.nextToWatch == nextToWatch) {
                state
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
            watchProgressRepository.getAllEpisodeProgress(itemId)
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
            watchedItemsPreferences.getWatchedEpisodesForContent(itemId)
                .distinctUntilChanged()
                .collectLatest { watchedSet ->
                _uiState.update { state ->
                    if (state.watchedEpisodes == watchedSet) {
                        state
                    } else {
                        state.copy(watchedEpisodes = watchedSet)
                    }
                }
                calculateNextToWatch()
            }
        }
    }

    private fun observeMovieWatched() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            watchProgressRepository.isWatched(itemId)
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

    private fun loadMeta() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null,
                    mdbListRatings = null,
                    showMdbListImdb = false,
                    moreLikeThis = emptyList()
                )
            }

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)
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
        val seasons = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()

        // Prefer first regular season (> 0), fallback to season 0 (specials)
        val selectedSeason = seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
        val episodesForSeason = getEpisodesForSeason(meta.videos, selectedSeason)

        _uiState.update {
            it.copy(
                isLoading = false,
                meta = meta,
                seasons = seasons,
                selectedSeason = selectedSeason,
                episodesForSeason = episodesForSeason,
                error = null
            )
        }
        
        // Calculate next to watch after meta is loaded
        calculateNextToWatch()

        // Start fetching trailer after meta is loaded
        fetchTrailerUrl()
    }

    private suspend fun applyMetaWithEnrichment(meta: Meta) {
        // Start recommendations fetch early so it can run in parallel with enrichment.
        loadMoreLikeThisAsync(meta)
        val enriched = enrichMeta(meta)
        applyMeta(enriched)
        loadEpisodeRatingsAsync(enriched)
        loadMDBListRatings(enriched)
    }

    private fun loadMoreLikeThisAsync(meta: Meta) {
        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            val settings = tmdbSettingsDataStore.settings.first()
            if (!shouldLoadMoreLikeThis(settings)) {
                _uiState.update { it.copy(moreLikeThis = emptyList()) }
                return@launch
            }

            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbLookupType = tmdbContentType.toApiString()
            val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                ?: tmdbService.ensureTmdbId(itemId, itemType)
            if (tmdbId.isNullOrBlank()) {
                _uiState.update { it.copy(moreLikeThis = emptyList()) }
                return@launch
            }

            val recommendations = runCatching {
                tmdbMetadataService.fetchMoreLikeThis(
                    tmdbId = tmdbId,
                    contentType = tmdbContentType,
                    language = settings.language
                )
            }.getOrElse {
                Log.w(TAG, "Failed to load More like this for ${meta.id}: ${it.message}")
                emptyList()
            }

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(moreLikeThis = recommendations)
                } else {
                    state
                }
            }
        }
    }

    private fun shouldLoadMoreLikeThis(settings: TmdbSettings): Boolean {
        return settings.enabled && settings.useMoreLikeThis
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
                                episodeRatingsError = "Ratings are unavailable for this show."
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
                            episodeRatingsError = "Unable to load episode ratings."
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

        val enrichment = tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = tmdbContentType,
            language = settings.language
        )

        var updated = meta

        // Group: Artwork (logo, backdrop)
        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                logo = enrichment.logo ?: updated.logo
            )
        }

        // Group: Basic Info (description, genres, rating)
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

        // Group: Details (runtime, release info, country, language)
        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: updated.runtime,
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                country = enrichment.countries?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language
            )
        }

        // Group: Credits (cast with photos, director, writer)
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

        // Group: Productions
        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        // Group: Networks
        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = enrichment.networks)
        }

        // Group: Episodes (titles, overviews, thumbnails, runtime)
        if (settings.useEpisodes && meta.apiType in listOf("series", "tv")) {
            val seasonNumbers = meta.videos.mapNotNull { it.season }.distinct()
            val episodeMap = tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = seasonNumbers,
                language = settings.language
            )
            if (episodeMap.isNotEmpty()) {
                updated = updated.copy(
                    videos = meta.videos.map { video ->
                        val season = video.season
                        val episode = video.episode
                        val key = if (season != null && episode != null) season to episode else null
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

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = _uiState.value.episodeProgressMap
        val isSeries = meta.apiType in listOf("series", "tv")
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            if (!isSeries) {
                // For movies, check if there's an in-progress watch
                val progress = watchProgressRepository.getProgress(itemId).first()
                val nextToWatch = if (progress != null && shouldResumeProgress(progress)) {
                    NextToWatch(
                        watchProgress = progress,
                        isResume = true,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = "Resume"
                    )
                } else {
                    NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = "Play"
                    )
                }
                updateNextToWatch(nextToWatch)
                return@launch
            }

            val allEpisodes = meta.videos
                .filter { it.season != null && it.episode != null }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            if (allEpisodes.isEmpty()) {
                updateNextToWatch(
                    NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = "Play"
                    )
                )
                return@launch
            }

            val nonSpecialEpisodes = allEpisodes.filter { (it.season ?: 0) > 0 }
            val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
            val latestSeriesProgress = progressMap.values.maxByOrNull { it.lastWatched }

            val nextToWatch = buildNextToWatchFromLatestProgress(
                latestProgress = latestSeriesProgress,
                episodes = episodePool,
                fallbackProgressMap = progressMap,
                metaId = meta.id
            )

            updateNextToWatch(nextToWatch)
        }
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        metaId: String
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = "Play"
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
                    displayText = "Resume S${season}E${episode}"
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
                        displayText = "Next S${next.season}E${next.episode}"
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
                    displayText = "Resume S${resumeEpisode.season}E${resumeEpisode.episode}"
                )
            }
            nextUnwatchedEpisode != null -> {
                val hasWatchedSomething = fallbackProgressMap.isNotEmpty()
                val displayPrefix = if (hasWatchedSomething) "Next" else "Play"
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = nextUnwatchedEpisode.id,
                    nextSeason = nextUnwatchedEpisode.season,
                    nextEpisode = nextUnwatchedEpisode.episode,
                    displayText = "$displayPrefix S${nextUnwatchedEpisode.season}E${nextUnwatchedEpisode.episode}"
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
                        "Play S${firstEpisode.season}E${firstEpisode.episode}"
                    } else {
                        "Play"
                    }
                )
            }
        }
    }

    private fun shouldResumeProgress(progress: WatchProgress): Boolean {
        return progress.progressPercentage >= 0.02f && !progress.isCompleted()
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
                    if (wasInWatchlist) "Removed from watchlist" else "Added to watchlist"
                } else {
                    if (wasInLibrary) "Removed from library" else "Added to library"
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
                showMessage("Lists updated")
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
                    watchProgressRepository.removeFromHistory(itemId)
                    showMessage("Marked as unwatched")
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta))
                    showMessage("Marked as watched")
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
                    watchProgressRepository.removeFromHistory(itemId, season, episode)
                    showMessage("Episode marked as unwatched")
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    showMessage("Episode marked as watched")
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
                showMessage("All episodes already watched")
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

            showMessage("Marked $marked episode${if (marked != 1) "s" else ""} as watched")
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
                showMessage("No watched episodes in this season")
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
                    watchProgressRepository.removeFromHistory(itemId, video.season!!, video.episode!!)
                    unmarked++
                }.onFailure { error ->
                    Log.w(TAG, "Failed to unmark S${video.season}E${video.episode}: ${error.message}")
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage("Marked $unmarked episode${if (unmarked != 1) "s" else ""} as unwatched")
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
                showMessage("All previous episodes already watched")
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

            showMessage("Marked $marked previous episode${if (marked != 1) "s" else ""} as watched")
        }
    }

    private fun buildCompletedMovieProgress(meta: Meta): WatchProgress {
        return WatchProgress(
            contentId = itemId,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.background,
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
            contentId = itemId,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = video.thumbnail ?: meta.background,
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

            val year = meta.releaseInfo?.split("-")?.firstOrNull()

            val tmdbId = try {
                tmdbService.ensureTmdbId(meta.id, meta.apiType)
            } catch (_: Exception) {
                null
            }

            val type = when (meta.type) {
                com.nuvio.tv.domain.model.ContentType.MOVIE -> "movie"
                com.nuvio.tv.domain.model.ContentType.SERIES,
                com.nuvio.tv.domain.model.ContentType.TV -> "tv"
                else -> null
            }

            val url = trailerService.getTrailerUrl(
                title = meta.name,
                year = year,
                tmdbId = tmdbId,
                type = type
            )

            _uiState.update { state ->
                if (state.trailerUrl == url && !state.isTrailerLoading) {
                    state
                } else {
                    state.copy(trailerUrl = url, isTrailerLoading = false)
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
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
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
