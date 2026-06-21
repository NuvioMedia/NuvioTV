package com.nuvio.tv.ui.screens.cast

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
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

    init {
        posterOptions.bind(viewModelScope)
        loadPersonDetail()
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

    fun createDynamicCollection(person: PersonDetail) {
        viewModelScope.launch {
            try {
                val folders = mutableListOf<CollectionFolder>()

                val castFolder = if (person.hasCastCredits) {
                    CollectionFolder(
                        id = UUID.randomUUID().toString(),
                        title = context.getString(R.string.pause_cast_label),
                        tileShape = PosterShape.POSTER,
                        sources = listOf(
                            TmdbCollectionSource(
                                sourceType = TmdbCollectionSourceType.PERSON,
                                title = "${person.name} - ${context.getString(R.string.type_movies)}",
                                tmdbId = person.tmdbId,
                                mediaType = TmdbCollectionMediaType.MOVIE
                            ),
                            TmdbCollectionSource(
                                sourceType = TmdbCollectionSourceType.PERSON,
                                title = "${person.name} - ${context.getString(R.string.type_series_plural)}",
                                tmdbId = person.tmdbId,
                                mediaType = TmdbCollectionMediaType.TV
                            )
                        )
                    )
                } else null

                val crewFolders = person.crewJobs.map { job ->
                    CollectionFolder(
                        id = UUID.randomUUID().toString(),
                        title = job,
                        tileShape = PosterShape.POSTER,
                        sources = listOf(
                            TmdbCollectionSource(
                                sourceType = TmdbCollectionSourceType.DIRECTOR,
                                title = "${person.name} - $job ${context.getString(R.string.type_movies)}",
                                tmdbId = person.tmdbId,
                                mediaType = TmdbCollectionMediaType.MOVIE,
                                crewJob = job
                            ),
                            TmdbCollectionSource(
                                sourceType = TmdbCollectionSourceType.DIRECTOR,
                                title = "${person.name} - $job ${context.getString(R.string.type_series_plural)}",
                                tmdbId = person.tmdbId,
                                mediaType = TmdbCollectionMediaType.TV,
                                crewJob = job
                            )
                        )
                    )
                }

                if (castFolder != null) {
                    if (person.knownFor.equals("Acting", ignoreCase = true)) {
                        folders.add(castFolder)
                        folders.addAll(crewFolders)
                    } else {
                        folders.addAll(crewFolders)
                        folders.add(castFolder)
                    }
                } else {
                    folders.addAll(crewFolders)
                }

                if (folders.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cast_detail_collection_created_error, "No credits found for this person"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val collection = Collection(
                    id = UUID.randomUUID().toString(),
                    title = person.name,
                    folders = folders
                )

                collectionsDataStore.addCollection(collection)
                collectionSyncService.triggerPush()

                Toast.makeText(
                    context,
                    context.getString(R.string.cast_detail_collection_created_success, person.name),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.cast_detail_collection_created_error, e.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
