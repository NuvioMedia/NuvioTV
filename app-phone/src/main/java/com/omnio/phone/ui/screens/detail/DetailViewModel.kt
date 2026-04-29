package com.omnio.phone.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.LibraryEntryInput
import com.omnio.tv.domain.model.Meta
import com.omnio.tv.domain.model.Video
import com.omnio.tv.domain.repository.LibraryRepository
import com.omnio.tv.domain.repository.MetaRepository
import com.omnio.tv.domain.result.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val metaRepository: MetaRepository,
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val contentId: String = savedStateHandle["contentId"] ?: ""
    private val contentType: String = savedStateHandle["contentType"] ?: ""

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
