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
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.DYNAMIC_COLLECTION_FALLBACK_COVER_URL
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

    private val _showCollectionDialog = MutableStateFlow(false)
    val showCollectionDialog: StateFlow<Boolean> = _showCollectionDialog.asStateFlow()

    private val _existingCollections = MutableStateFlow<List<Collection>>(emptyList())
    val existingCollections: StateFlow<List<Collection>> = _existingCollections.asStateFlow()

    fun onCollectionClick() {
        viewModelScope.launch {
            try {
                val list = collectionsDataStore.getCurrentCollections()
                _existingCollections.value = list
                _showCollectionDialog.value = true
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading collections: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun dismissCollectionDialog() {
        _showCollectionDialog.value = false
    }

    fun createNewCollection(name: String, person: PersonDetail, viewMode: FolderViewMode = FolderViewMode.TABBED_GRID) {
        viewModelScope.launch {
            try {
                val backdropUrl = (person.movieCredits + person.tvCredits)
                    .mapNotNull { it.background ?: it.landscapePoster }
                    .firstOrNull { it.isNotEmpty() }
                    ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL

                val folders = createCollectionFolders(
                    person = person,
                    isAddingToExisting = false,
                    backdropUrl = backdropUrl,
                    tileShape = PosterShape.POSTER
                )
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
                    title = name,
                    backdropImageUrl = backdropUrl,
                    viewMode = viewMode,
                    folders = folders
                )

                collectionsDataStore.addCollection(collection)
                collectionSyncService.triggerPush()

                _showCollectionDialog.value = false

                Toast.makeText(
                    context,
                    context.getString(R.string.cast_detail_collection_created_success, name),
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

    fun addToExistingCollection(collectionId: String, person: PersonDetail) {
        viewModelScope.launch {
            try {
                val existing = collectionsDataStore.getCurrentCollections().firstOrNull { it.id == collectionId }
                if (existing == null) {
                    Toast.makeText(context, "Collection not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val backdropUrl = (person.movieCredits + person.tvCredits)
                    .mapNotNull { it.background ?: it.landscapePoster }
                    .firstOrNull { it.isNotEmpty() }
                    ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL

                val parentTileShape = existing.folders.firstOrNull()?.tileShape ?: PosterShape.POSTER

                val newFolders = createCollectionFolders(
                    person = person,
                    isAddingToExisting = true,
                    backdropUrl = backdropUrl,
                    tileShape = parentTileShape
                )
                if (newFolders.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cast_detail_collection_created_error, "No credits found for this person"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val updatedFolders = existing.folders.toMutableList()
                for (newFolder in newFolders) {
                    val existingIndex = updatedFolders.indexOfFirst { it.title.equals(newFolder.title, ignoreCase = true) }
                    if (existingIndex >= 0) {
                        val existingFolder = updatedFolders[existingIndex]
                        val mergedSources = (existingFolder.sources + newFolder.sources).distinct()
                        updatedFolders[existingIndex] = existingFolder.copy(sources = mergedSources)
                    } else {
                        updatedFolders.add(newFolder)
                    }
                }

                val updatedCollection = existing.copy(folders = updatedFolders)
                collectionsDataStore.updateCollection(updatedCollection)
                collectionSyncService.triggerPush()

                _showCollectionDialog.value = false

                Toast.makeText(
                    context,
                    context.getString(R.string.cast_detail_collection_added_success, existing.title),
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

    private fun createCollectionFolders(
        person: PersonDetail,
        isAddingToExisting: Boolean,
        backdropUrl: String?,
        tileShape: PosterShape
    ): List<CollectionFolder> {
        if (isAddingToExisting) {
            val sources = mutableListOf<TmdbCollectionSource>()

            val castSources = if (person.hasCastCredits) {
                listOf(
                    TmdbCollectionSource(
                        sourceType = TmdbCollectionSourceType.PERSON,
                        title = context.getString(R.string.pause_cast_label),
                        tmdbId = person.tmdbId,
                        mediaType = TmdbCollectionMediaType.MOVIE
                    ),
                    TmdbCollectionSource(
                        sourceType = TmdbCollectionSourceType.PERSON,
                        title = context.getString(R.string.pause_cast_label),
                        tmdbId = person.tmdbId,
                        mediaType = TmdbCollectionMediaType.TV
                    )
                )
            } else emptyList()

            val crewSources = person.crewJobs.flatMap { job ->
                val list = mutableListOf<TmdbCollectionSource>()
                if (job.hasMovies) {
                    list.add(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.DIRECTOR,
                            title = job.jobName,
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.MOVIE,
                            crewJob = job.jobName
                        )
                    )
                }
                if (job.hasTv) {
                    list.add(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.DIRECTOR,
                            title = job.jobName,
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.TV,
                            crewJob = job.jobName
                        )
                    )
                }
                list
            }

            if (person.knownFor.equals("Acting", ignoreCase = true)) {
                sources.addAll(castSources)
                sources.addAll(crewSources)
            } else {
                sources.addAll(crewSources)
                sources.addAll(castSources)
            }

            if (sources.isEmpty()) return emptyList()

            return listOf(
                CollectionFolder(
                    id = UUID.randomUUID().toString(),
                    title = person.name,
                    tileShape = tileShape,
                    coverImageUrl = if (tileShape == PosterShape.LANDSCAPE) {
                        backdropUrl ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL
                    } else {
                        person.profilePhoto
                    },
                    heroBackdropUrl = backdropUrl,
                    sources = sources
                )
            )
        } else {
            val folders = mutableListOf<CollectionFolder>()

            val castFolder = if (person.hasCastCredits) {
                CollectionFolder(
                    id = UUID.randomUUID().toString(),
                    title = context.getString(R.string.pause_cast_label),
                    tileShape = tileShape,
                    coverImageUrl = if (tileShape == PosterShape.LANDSCAPE) {
                        backdropUrl ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL
                    } else {
                        person.profilePhoto
                    },
                    heroBackdropUrl = backdropUrl,
                    sources = listOf(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.PERSON,
                            title = context.getString(R.string.type_movies),
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.MOVIE
                        ),
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.PERSON,
                            title = context.getString(R.string.type_series_plural),
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.TV
                        )
                    )
                )
            } else null

            val crewFolders = person.crewJobs.mapNotNull { job ->
                val sources = mutableListOf<TmdbCollectionSource>()
                if (job.hasMovies) {
                    sources.add(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.DIRECTOR,
                            title = context.getString(R.string.type_movies),
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.MOVIE,
                            crewJob = job.jobName
                        )
                    )
                }
                if (job.hasTv) {
                    sources.add(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.DIRECTOR,
                            title = context.getString(R.string.type_series_plural),
                            tmdbId = person.tmdbId,
                            mediaType = TmdbCollectionMediaType.TV,
                            crewJob = job.jobName
                        )
                    )
                }
                if (sources.isEmpty()) return@mapNotNull null

                CollectionFolder(
                    id = UUID.randomUUID().toString(),
                    title = job.jobName,
                    tileShape = tileShape,
                    coverImageUrl = if (tileShape == PosterShape.LANDSCAPE) {
                        backdropUrl ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL
                    } else {
                        person.profilePhoto
                    },
                    heroBackdropUrl = backdropUrl,
                    sources = sources
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
            return folders
        }
    }
}
