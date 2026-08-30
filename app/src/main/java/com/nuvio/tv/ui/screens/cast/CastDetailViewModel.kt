package com.nuvio.tv.ui.screens.cast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = savedStateHandle.get<String>("personId")?.toIntOrNull() ?: 0
    val personName: String = (savedStateHandle.get<String>("personName") ?: "").let { raw ->
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
    private val preferCrew: Boolean = savedStateHandle.get<Boolean>("preferCrew") ?: false

    private val _uiState = MutableStateFlow<CastDetailUiState>(CastDetailUiState.Loading)
    val uiState: StateFlow<CastDetailUiState> = _uiState.asStateFlow()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val _posterCardCornerRadiusDp = MutableStateFlow(12)
    val posterCardCornerRadiusDp: StateFlow<Int> = _posterCardCornerRadiusDp.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        loadPersonDetail()
        observeWatchedStatus()
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp
                .collect { _posterCardCornerRadiusDp.value = it }
        }
    }

    private fun observeWatchedStatus() {
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
    }

    fun retry() {
        _uiState.value = CastDetailUiState.Loading
        loadPersonDetail()
    }

    private fun loadPersonDetail() {
        viewModelScope.launch {
            try {
                val detail = tmdbMetadataService.fetchPersonDetail(
                    personId = personId,
                    preferCrewCredits = preferCrew,
                    language = tmdbSettingsDataStore.settings.first().language
                )
                if (detail != null) {
                    _uiState.value = CastDetailUiState.Success(detail)
                } else {
                    _uiState.value = CastDetailUiState.Error(
                        context.getString(R.string.cast_error_load_details_for, personName)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = CastDetailUiState.Error(
                    e.message ?: context.getString(R.string.error_unknown)
                )
            }
        }
    }
}
