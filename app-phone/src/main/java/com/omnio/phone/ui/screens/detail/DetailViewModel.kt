package com.omnio.phone.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.AddonStreams
import com.omnio.tv.domain.model.ContentType
import com.omnio.tv.domain.model.LibraryEntryInput
import com.omnio.tv.domain.model.LibraryListTab
import com.omnio.tv.domain.model.LibrarySourceMode
import com.omnio.tv.domain.model.ListMembershipChanges
import com.omnio.tv.domain.model.Meta
import com.omnio.tv.domain.model.Stream
import com.omnio.tv.domain.model.Video
import com.omnio.tv.domain.repository.LibraryRepository
import com.omnio.tv.domain.repository.MetaRepository
import com.omnio.tv.domain.repository.StreamRepository
import com.omnio.tv.domain.result.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val metaRepository: MetaRepository,
    private val libraryRepository: LibraryRepository,
    private val streamRepository: StreamRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val contentId: String = savedStateHandle["contentId"] ?: ""
    private val contentType: String = savedStateHandle["contentType"] ?: ""

    private val _playbackRequests = MutableSharedFlow<PlaybackRequest>(extraBufferCapacity = 1)
    val playbackRequests: SharedFlow<PlaybackRequest> = _playbackRequests.asSharedFlow()

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var libraryObserveJob: Job? = null
    private var listTabs: List<LibraryListTab> = emptyList()

    init {
        load()
        observeLibraryMembership()
    }

    fun retry() {
        load()
    }

    fun selectSeason(season: Int) {
        val videos = _uiState.value.meta?.videos ?: return
        _uiState.update {
            it.copy(
                selectedSeason = season,
                episodesForSeason = episodesForSeason(videos, season)
            )
        }
    }

    fun toggleLibrary() {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val wasInLibrary = _uiState.value.isInLibrary
            runCatching {
                libraryRepository.toggleDefault(meta.toLibraryEntryInput())
            }.onSuccess {
                val msg = if (wasInLibrary) "Removed from library" else "Added to library"
                _uiState.update { it.copy(userMessage = msg) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(userMessage = error.message ?: "Failed to update library")
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    /**
     * Resolves the first available stream for the meta (or the supplied [video] for series)
     * and emits a [PlaybackRequest] on [playbackRequests]. If no stream resolves, posts a
     * snackbar message instead.
     */
    fun requestPlayback(video: Video? = null) {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.isResolvingPlayback) return

        val target = video ?: pickInitialEpisode(meta)
        _uiState.update { it.copy(isResolvingPlayback = true) }
        viewModelScope.launch {
            val outcome = loadStreamGroups(meta, target)
            val streams = outcome.groups.flatMap { it.streams }
            val playable = streams.firstOrNull { !it.getStreamUrl().isNullOrBlank() }

            if (playable == null) {
                _uiState.update {
                    it.copy(
                        isResolvingPlayback = false,
                        userMessage = outcome.error ?: "No playable streams found for this title."
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isResolvingPlayback = false) }
            emitPlaybackRequest(meta, target, playable)
        }
    }

    /**
     * Opens the bottom-sheet source chooser, loads streams from all addons, and surfaces
     * them grouped by addon. Selecting a row from the sheet emits a [PlaybackRequest].
     */
    fun openSourceSelection(video: Video? = null) {
        val meta = _uiState.value.meta ?: return
        val target = video ?: pickInitialEpisode(meta)
        _uiState.update {
            it.copy(
                streamSelection = StreamSelectionState(
                    targetVideo = target,
                    isLoading = true
                )
            )
        }
        viewModelScope.launch {
            val outcome = loadStreamGroups(meta, target)
            _uiState.update { state ->
                val current = state.streamSelection ?: return@update state
                state.copy(
                    streamSelection = current.copy(
                        isLoading = false,
                        groups = outcome.groups,
                        error = if (outcome.groups.isEmpty()) {
                            outcome.error ?: "No sources found for this title."
                        } else null
                    )
                )
            }
        }
    }

    fun dismissSourceSelection() {
        _uiState.update { it.copy(streamSelection = null) }
    }

    fun setAddonFilter(addonName: String?) {
        _uiState.update { state ->
            val current = state.streamSelection ?: return@update state
            state.copy(streamSelection = current.copy(addonFilter = addonName))
        }
    }

    /**
     * Called when the user taps a stream row in the chooser. Emits a [PlaybackRequest]
     * for that exact stream and dismisses the sheet.
     */
    fun selectStream(stream: Stream) {
        val meta = _uiState.value.meta ?: return
        val selection = _uiState.value.streamSelection ?: return
        if (stream.getStreamUrl().isNullOrBlank()) {
            _uiState.update {
                it.copy(userMessage = "This source has no playable URL.")
            }
            return
        }
        _uiState.update { it.copy(streamSelection = null) }
        emitPlaybackRequest(meta, selection.targetVideo, stream)
    }

    private fun emitPlaybackRequest(meta: Meta, target: Video?, stream: Stream) {
        val isSeries = meta.type == ContentType.SERIES
        val videoId = target?.id ?: meta.id
        val episodeTitle = target?.title
        viewModelScope.launch {
            _playbackRequests.emit(
                PlaybackRequest(
                    stream = stream,
                    title = if (isSeries && episodeTitle != null) "${meta.name} — ${episodeTitle}" else meta.name,
                    contentName = if (isSeries) meta.name else null,
                    contentId = meta.id,
                    contentType = meta.apiType,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    videoId = videoId,
                    season = target?.season,
                    episode = target?.episode,
                    episodeTitle = episodeTitle,
                    year = meta.releaseInfo
                )
            )
        }
    }

    private suspend fun loadStreamGroups(meta: Meta, target: Video?): StreamLoadOutcome {
        val videoId = target?.id ?: meta.id
        val streamsResult = streamRepository
            .getStreamsFromAllAddons(
                type = meta.apiType,
                videoId = videoId,
                season = target?.season,
                episode = target?.episode
            )
            .first { it !is NetworkResult.Loading }

        return when (streamsResult) {
            is NetworkResult.Success -> StreamLoadOutcome(
                groups = streamsResult.data.filter { it.streams.isNotEmpty() }
            )
            is NetworkResult.Error -> StreamLoadOutcome(error = streamsResult.message)
            NetworkResult.Loading -> StreamLoadOutcome()
        }
    }

    private data class StreamLoadOutcome(
        val groups: List<AddonStreams> = emptyList(),
        val error: String? = null
    )

    private fun pickInitialEpisode(meta: Meta): Video? {
        if (meta.type != ContentType.SERIES) return null
        val watchable = meta.watchableEpisodes()
        if (watchable.isEmpty()) return null
        return watchable.minWithOrNull(
            compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE })
        )
    }

    private fun load() {
        if (contentId.isBlank() || contentType.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, error = "Missing content reference.")
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = metaRepository
                .getMetaFromAllAddons(type = contentType, id = contentId)
                .first { it !is NetworkResult.Loading }

            when (result) {
                is NetworkResult.Success -> applyMeta(result.data)
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun applyMeta(meta: Meta) {
        val seasons = meta.videos
            .mapNotNull { it.season }
            .filter { it > 0 }
            .distinct()
            .sorted()
        val selected = seasons.firstOrNull()
        val episodes = if (selected != null) episodesForSeason(meta.videos, selected) else emptyList()
        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                meta = meta,
                seasons = seasons,
                selectedSeason = selected,
                episodesForSeason = episodes
            )
        }
    }

    private fun observeLibraryMembership() {
        libraryObserveJob?.cancel()
        if (contentId.isBlank() || contentType.isBlank()) return
        libraryObserveJob = viewModelScope.launch {
            combine(
                libraryRepository.isInLibrary(itemId = contentId, itemType = contentType),
                libraryRepository.sourceMode,
                libraryRepository.listTabs
            ) { inLibrary, sourceMode, tabs ->
                Triple(inLibrary, sourceMode, tabs)
            }.collect { (inLibrary, sourceMode, tabs) ->
                listTabs = tabs
                _uiState.update { state ->
                    if (state.isInLibrary == inLibrary && state.sourceMode == sourceMode) {
                        state
                    } else {
                        state.copy(isInLibrary = inLibrary, sourceMode = sourceMode)
                    }
                }
            }
        }
    }

    fun openListPicker() {
        if (_uiState.value.sourceMode != LibrarySourceMode.TRAKT) return
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.listPicker?.isLoading == true) return
        _uiState.update { it.copy(listPicker = ListPickerState(isLoading = true)) }
        viewModelScope.launch {
            runCatching {
                libraryRepository.getMembershipSnapshot(meta.toLibraryEntryInput())
            }.onSuccess { snapshot ->
                val tabs = listTabs
                val membership = tabs.associate { tab ->
                    tab.key to (snapshot.listMembership[tab.key] == true)
                }
                _uiState.update {
                    it.copy(
                        listPicker = ListPickerState(
                            isLoading = false,
                            tabs = tabs,
                            membership = membership,
                            originalMembership = membership
                        )
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        listPicker = null,
                        userMessage = error.message ?: "Failed to load lists"
                    )
                }
            }
        }
    }

    fun toggleListMembership(listKey: String) {
        _uiState.update { state ->
            val picker = state.listPicker ?: return@update state
            val current = picker.membership[listKey] == true
            state.copy(
                listPicker = picker.copy(
                    membership = picker.membership.toMutableMap().apply {
                        this[listKey] = !current
                    },
                    error = null
                )
            )
        }
    }

    fun saveListMembership() {
        val state = _uiState.value
        val meta = state.meta ?: return
        val picker = state.listPicker ?: return
        if (picker.isSaving || picker.isLoading) return
        if (!picker.hasChanges) {
            _uiState.update { it.copy(listPicker = null) }
            return
        }

        _uiState.update {
            it.copy(listPicker = picker.copy(isSaving = true, error = null))
        }
        viewModelScope.launch {
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(desiredMembership = picker.membership)
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(listPicker = null, userMessage = "Lists updated")
                }
            }.onFailure { error ->
                _uiState.update { current ->
                    val active = current.listPicker ?: return@update current
                    current.copy(
                        listPicker = active.copy(
                            isSaving = false,
                            error = error.message ?: "Failed to update lists"
                        )
                    )
                }
            }
        }
    }

    fun dismissListPicker() {
        _uiState.update { it.copy(listPicker = null) }
    }

    private fun episodesForSeason(videos: List<Video>, season: Int): List<Video> =
        videos.filter { it.season == season }.sortedBy { it.episode }

    private fun Meta.toLibraryEntryInput(): LibraryEntryInput {
        val year = Regex("(\\d{4})").find(releaseInfo ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            year = year,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres
        )
    }
}
