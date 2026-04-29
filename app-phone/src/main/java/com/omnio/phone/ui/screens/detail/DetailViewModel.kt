package com.omnio.phone.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.ContentType
import com.omnio.tv.domain.model.LibraryEntryInput
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
        val isSeries = meta.type == ContentType.SERIES
        val videoId = target?.id ?: meta.id
        val season = target?.season
        val episode = target?.episode
        val episodeTitle = target?.title

        _uiState.update { it.copy(isResolvingPlayback = true) }
        viewModelScope.launch {
            val streamsResult = streamRepository
                .getStreamsFromAllAddons(
                    type = meta.apiType,
                    videoId = videoId,
                    season = season,
                    episode = episode
                )
                .first { it !is NetworkResult.Loading }

            val streams: List<Stream> = when (streamsResult) {
                is NetworkResult.Success -> streamsResult.data.flatMap { it.streams }
                is NetworkResult.Error -> emptyList()
                NetworkResult.Loading -> emptyList()
            }
            val playable = streams.firstOrNull { !it.getStreamUrl().isNullOrBlank() }

            if (playable == null) {
                val errorMsg = (streamsResult as? NetworkResult.Error)?.message
                _uiState.update {
                    it.copy(
                        isResolvingPlayback = false,
                        userMessage = errorMsg ?: "No playable streams found for this title."
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isResolvingPlayback = false) }
            _playbackRequests.emit(
                PlaybackRequest(
                    stream = playable,
                    title = if (isSeries && episodeTitle != null) "${meta.name} — ${episodeTitle}" else meta.name,
                    contentName = if (isSeries) meta.name else null,
                    contentId = meta.id,
                    contentType = meta.apiType,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    episodeTitle = episodeTitle,
                    year = meta.releaseInfo
                )
            )
        }
    }

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
            libraryRepository.isInLibrary(itemId = contentId, itemType = contentType)
                .collect { inLibrary ->
                    _uiState.update {
                        if (it.isInLibrary == inLibrary) it else it.copy(isInLibrary = inLibrary)
                    }
                }
        }
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
