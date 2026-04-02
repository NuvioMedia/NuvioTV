package com.nuvio.tv.ui.screens.tmdb

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    val entityName: String = URLDecoder.decode(
        savedStateHandle.get<String>("entityName").orEmpty(),
        "UTF-8"
    )
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _uiState.value = TmdbEntityBrowseUiState.Loading
        load()
    }

    fun loadMoreRail(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = "${mediaType.value}_${railType.value}"
        val currentSuccess = _uiState.value as? TmdbEntityBrowseUiState.Success ?: return
        val targetRail = currentSuccess.data.rails.firstOrNull {
            it.mediaType == mediaType && it.railType == railType
        } ?: return
        if (!targetRail.hasMore || targetRail.isLoading || !inFlightRailLoads.add(railKey)) return

        _uiState.value = TmdbEntityBrowseUiState.Success(
            currentSuccess.data.withUpdatedRail(mediaType, railType) { it.copy(isLoading = true) }
        )

        viewModelScope.launch {
            try {
                val latestData = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                val latestRail = latestData.rails.firstOrNull {
                    it.mediaType == mediaType && it.railType == railType
                } ?: return@launch
                val language = tmdbSettingsDataStore.settings.first().language
                val nextPage = latestRail.currentPage + 1
                val pageResult = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = language,
                    page = nextPage
                )
                val mergedItems = (latestRail.items + pageResult.items)
                    .distinctBy { it.id }

                _uiState.value = TmdbEntityBrowseUiState.Success(
                    latestData.withUpdatedRail(mediaType, railType) {
                        it.copy(
                            items = mergedItems,
                            currentPage = nextPage,
                            hasMore = pageResult.hasMore,
                            isLoading = false
                        )
                    }
                )
            } catch (_: Exception) {
                val fallback = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    fallback.withUpdatedRail(mediaType, railType) { it.copy(isLoading = false) }
                )
            } finally {
                inFlightRailLoads.remove(railKey)
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val browseData = tmdbMetadataService.fetchEntityBrowse(
                    entityKind = entityKind,
                    entityId = entityId,
                    sourceType = sourceType,
                    fallbackName = entityName,
                    language = language
                )
                _uiState.value = if (browseData != null) {
                    TmdbEntityBrowseUiState.Success(browseData)
                } else {
                    TmdbEntityBrowseUiState.Error(
                        if (entityName.isNotBlank()) {
                            "Could not load $entityName"
                        } else {
                            "Could not load TMDB entity"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: "Could not load TMDB entity"
                )
            }
        }
    }

    private fun TmdbEntityBrowseData.withUpdatedRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (com.nuvio.tv.core.tmdb.TmdbEntityRail) -> com.nuvio.tv.core.tmdb.TmdbEntityRail
    ): TmdbEntityBrowseData {
        return copy(
            rails = rails.map { rail ->
                if (rail.mediaType == mediaType && rail.railType == railType) {
                    transform(rail)
                } else {
                    rail
                }
            }
        )
    }
}
