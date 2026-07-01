package com.nuvio.tv.ui.screens.tmdb

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject
import android.widget.Toast
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.DYNAMIC_COLLECTION_FALLBACK_COVER_URL
import java.util.UUID

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

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

    fun createNewCollection(name: String, data: TmdbEntityBrowseData, viewMode: FolderViewMode = FolderViewMode.TABBED_GRID) {
        viewModelScope.launch {
            try {
                val backdropUrl = data.header.logo.takeIf { !it.isNullOrBlank() }
                    ?: data.rails
                        .flatMap { it.items }
                        .mapNotNull { it.background ?: it.landscapePoster }
                        .firstOrNull { it.isNotEmpty() }
                    ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL

                val folders = createCollectionFolders(
                    data = data,
                    isAddingToExisting = false,
                    backdropUrl = backdropUrl,
                    tileShape = PosterShape.LANDSCAPE
                )
                if (folders.isEmpty()) {
                    Toast.makeText(context, "No content found to create a collection", Toast.LENGTH_SHORT).show()
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

    fun addToExistingCollection(collectionId: String, data: TmdbEntityBrowseData) {
        viewModelScope.launch {
            try {
                val existing = collectionsDataStore.getCurrentCollections().firstOrNull { it.id == collectionId }
                if (existing == null) {
                    Toast.makeText(context, "Collection not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val backdropUrl = data.header.logo.takeIf { !it.isNullOrBlank() }
                    ?: data.rails
                        .flatMap { it.items }
                        .mapNotNull { it.background ?: it.landscapePoster }
                        .firstOrNull { it.isNotEmpty() }
                    ?: DYNAMIC_COLLECTION_FALLBACK_COVER_URL

                val parentTileShape = existing.folders.firstOrNull()?.tileShape ?: PosterShape.LANDSCAPE

                val newFolders = createCollectionFolders(
                    data = data,
                    isAddingToExisting = true,
                    backdropUrl = backdropUrl,
                    tileShape = parentTileShape
                )
                if (newFolders.isEmpty()) {
                    Toast.makeText(context, "No content found to add", Toast.LENGTH_SHORT).show()
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
        data: TmdbEntityBrowseData,
        isAddingToExisting: Boolean,
        backdropUrl: String?,
        tileShape: PosterShape
    ): List<CollectionFolder> {
        val kind = data.header.kind
        val entityId = data.header.id
        val entityName = data.header.name
        val logoUrl = data.header.logo

        val hasMovies = data.rails.any { it.mediaType == TmdbEntityMediaType.MOVIE }
        val hasTv = data.rails.any { it.mediaType == TmdbEntityMediaType.TV }

        val sourceType = if (kind == TmdbEntityKind.COMPANY) {
            TmdbCollectionSourceType.COMPANY
        } else {
            TmdbCollectionSourceType.NETWORK
        }

        if (isAddingToExisting) {
            val sources = mutableListOf<TmdbCollectionSource>()
            if (hasMovies) {
                sources.add(
                    TmdbCollectionSource(
                        sourceType = sourceType,
                        title = context.getString(R.string.type_movies),
                        tmdbId = entityId,
                        mediaType = TmdbCollectionMediaType.MOVIE
                    )
                )
            }
            if (hasTv) {
                sources.add(
                    TmdbCollectionSource(
                        sourceType = sourceType,
                        title = context.getString(R.string.type_series_plural),
                        tmdbId = entityId,
                        mediaType = TmdbCollectionMediaType.TV
                    )
                )
            }
            if (sources.isEmpty()) return emptyList()

            return listOf(
                CollectionFolder(
                    id = UUID.randomUUID().toString(),
                    title = entityName,
                    tileShape = tileShape,
                    coverImageUrl = logoUrl.takeIf { !it.isNullOrBlank() } ?: backdropUrl,
                    heroBackdropUrl = backdropUrl,
                    sources = sources
                )
            )
        } else {
            val folders = mutableListOf<CollectionFolder>()
            if (hasMovies) {
                folders.add(
                    CollectionFolder(
                        id = UUID.randomUUID().toString(),
                        title = context.getString(R.string.type_movies),
                        tileShape = tileShape,
                        coverImageUrl = logoUrl.takeIf { !it.isNullOrBlank() } ?: backdropUrl,
                        heroBackdropUrl = backdropUrl,
                        sources = listOf(
                            TmdbCollectionSource(
                                sourceType = sourceType,
                                title = context.getString(R.string.type_movies),
                                tmdbId = entityId,
                                mediaType = TmdbCollectionMediaType.MOVIE
                            )
                        )
                    )
                )
            }
            if (hasTv) {
                folders.add(
                    CollectionFolder(
                        id = UUID.randomUUID().toString(),
                        title = context.getString(R.string.type_series_plural),
                        tileShape = tileShape,
                        coverImageUrl = logoUrl.takeIf { !it.isNullOrBlank() } ?: backdropUrl,
                        heroBackdropUrl = backdropUrl,
                        sources = listOf(
                            TmdbCollectionSource(
                                sourceType = sourceType,
                                title = context.getString(R.string.type_series_plural),
                                tmdbId = entityId,
                                mediaType = TmdbCollectionMediaType.TV
                            )
                        )
                    )
                )
            }
            return folders
        }
    }

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    val entityName: String = savedStateHandle.get<String>("entityName").orEmpty().let { raw ->
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
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
                            context.getString(R.string.tmdb_entity_error_load_named, entityName)
                        } else {
                            context.getString(R.string.tmdb_entity_error_load)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: context.getString(R.string.tmdb_entity_error_load)
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
