package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.data.remote.api.TmdbCollectionSearchResult
import com.nuvio.tv.data.remote.api.TmdbCompanySearchResult
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionEditorUiState(
    val isNew: Boolean = true,
    val collectionId: String = "",
    val title: String = "",
    val backdropImageUrl: String = "",
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val showAllTab: Boolean = true,
    val folders: List<CollectionFolder> = emptyList(),
    val isLoading: Boolean = true,
    val availableCatalogs: List<AvailableCatalog> = emptyList(),
    val editingFolder: CollectionFolder? = null,
    val showFolderEditor: Boolean = false,
    val showCatalogPicker: Boolean = false,
    val showTmdbSourcePicker: Boolean = false,
    val editingTmdbSourceIndex: Int? = null,
    val tmdbBuilderMode: TmdbBuilderMode = TmdbBuilderMode.PRESETS,
    val tmdbInput: String = "",
    val tmdbTitleInput: String = "",
    val tmdbMediaType: TmdbCollectionMediaType = TmdbCollectionMediaType.MOVIE,
    val tmdbMediaBoth: Boolean = false,
    val tmdbSortBy: String = TmdbCollectionSort.POPULAR_DESC.value,
    val tmdbFilters: TmdbCollectionFilters = TmdbCollectionFilters(),
    val tmdbCompanyResults: List<TmdbCompanySearchResult> = emptyList(),
    val tmdbCollectionResults: List<TmdbCollectionSearchResult> = emptyList(),
    val tmdbSearchError: String? = null,
    val genrePickerSourceIndex: Int? = null,
    val showEmojiPicker: Boolean = false
)

data class AvailableCatalog(
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false
)

enum class TmdbBuilderMode {
    PRESETS,
    LIST,
    PRODUCTION,
    NETWORK,
    COLLECTION,
    DISCOVER
}

data class TmdbPresetSource(
    val title: String,
    val source: TmdbCollectionSource
)

@HiltViewModel
class CollectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver
) : ViewModel() {

    private val collectionIdArg: String = savedStateHandle["collectionId"] ?: ""

    private val _uiState = MutableStateFlow(CollectionEditorUiState())
    val uiState: StateFlow<CollectionEditorUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons().first()
            val availableCatalogs = addons.flatMap { addon ->
                addon.catalogs
                    .filter { catalog ->
                        catalog.extra.none { extra -> extra.isRequired && !extra.name.equals("genre", ignoreCase = true) }
                    }
                    .map { catalog ->
                        val genreExtra = catalog.extra.firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        AvailableCatalog(
                            addonId = addon.id,
                            addonName = addon.displayName,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            genreOptions = genreExtra?.options.orEmpty(),
                            genreRequired = genreExtra?.isRequired == true
                        )
                    }
            }

            if (collectionIdArg.isNotBlank()) {
                val collections = collectionsDataStore.collections.first()
                val existing = collections.find { it.id == collectionIdArg }
                if (existing != null) {
                    _uiState.update {
                        it.copy(
                            isNew = false,
                            collectionId = existing.id,
                            title = existing.title,
                            backdropImageUrl = existing.backdropImageUrl ?: "",
                            pinToTop = existing.pinToTop,
                            focusGlowEnabled = existing.focusGlowEnabled,
                            viewMode = existing.viewMode,
                            showAllTab = existing.showAllTab,
                            folders = existing.folders,
                            availableCatalogs = availableCatalogs,
                            isLoading = false
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    isNew = true,
                    collectionId = collectionsDataStore.generateId(),
                    availableCatalogs = availableCatalogs,
                    isLoading = false
                )
            }
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setBackdropImageUrl(url: String) {
        _uiState.update { it.copy(backdropImageUrl = url) }
    }

    fun setPinToTop(pinToTop: Boolean) {
        _uiState.update { it.copy(pinToTop = pinToTop) }
    }

    fun setFocusGlowEnabled(enabled: Boolean) {
        _uiState.update { it.copy(focusGlowEnabled = enabled) }
    }

    fun addFolder() {
        val newFolder = CollectionFolder(
            id = collectionsDataStore.generateId(),
            title = "",
            tileShape = PosterShape.POSTER,
            sources = emptyList()
        )
        _uiState.update {
            it.copy(editingFolder = newFolder, showFolderEditor = true)
        }
    }

    fun editFolder(folderId: String) {
        val folder = _uiState.value.folders.find { it.id == folderId } ?: return
        _uiState.update { it.copy(editingFolder = folder, showFolderEditor = true) }
    }

    fun removeFolder(folderId: String) {
        _uiState.update { state ->
            state.copy(folders = state.folders.filter { it.id != folderId })
        }
    }

    fun moveFolderUp(index: Int) {
        if (index <= 0) return
        _uiState.update { state ->
            val folders = state.folders.toMutableList()
            val item = folders.removeAt(index)
            folders.add(index - 1, item)
            state.copy(folders = folders)
        }
    }

    fun moveFolderDown(index: Int) {
        val folders = _uiState.value.folders
        if (index >= folders.size - 1) return
        _uiState.update { state ->
            val mutableFolders = state.folders.toMutableList()
            val item = mutableFolders.removeAt(index)
            mutableFolders.add(index + 1, item)
            state.copy(folders = mutableFolders)
        }
    }

    fun updateFolderTitle(title: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(title = title))
        }
    }

    fun updateFolderCoverImage(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = url,
                coverEmoji = null
            ))
        }
    }

    fun updateFolderFocusGifUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(focusGifUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderFocusGifEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(focusGifEnabled = enabled))
        }
    }

    fun updateFolderCoverEmoji(emoji: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverEmoji = emoji,
                coverImageUrl = null
            ))
        }
    }

    fun switchToImageMode() {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = state.editingFolder.coverImageUrl ?: "",
                coverEmoji = null
            ))
        }
    }

    fun clearFolderCover() {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(
                coverImageUrl = null,
                coverEmoji = null
            ))
        }
    }

    fun showEmojiPicker() {
        _uiState.update { it.copy(showEmojiPicker = true) }
    }

    fun hideEmojiPicker() {
        _uiState.update { it.copy(showEmojiPicker = false) }
    }

    fun updateFolderTileShape(shape: PosterShape) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(tileShape = shape))
        }
    }

    fun updateFolderHideTitle(hide: Boolean) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(hideTitle = hide))
        }
    }

    fun updateFolderHeroBackdropUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(heroBackdropUrl = url.ifBlank { null }))
        }
    }

    fun updateFolderTitleLogoUrl(url: String) {
        _uiState.update { state ->
            state.copy(editingFolder = state.editingFolder?.copy(titleLogoUrl = url.ifBlank { null }))
        }
    }

    fun setViewMode(viewMode: FolderViewMode) {
        _uiState.update { it.copy(viewMode = viewMode) }
    }

    fun setShowAllTab(show: Boolean) {
        _uiState.update { it.copy(showAllTab = show) }
    }

    fun addCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val defaultGenre = resolveGenreSelection(catalog, requestedGenre = null)
            val source = AddonCatalogCollectionSource(
                addonId = catalog.addonId,
                type = catalog.type,
                catalogId = catalog.catalogId,
                genre = defaultGenre
            )
            if (folder.sources.any { it is AddonCatalogCollectionSource && it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId }) {
                return@update state
            }
            state.copy(
                editingFolder = folder.copy(sources = folder.sources + source),
                genrePickerSourceIndex = null
            )
        }
    }

    fun removeCatalogSource(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val sources = folder.sources.toMutableList()
            if (index in sources.indices) sources.removeAt(index)
            state.copy(
                editingFolder = folder.copy(sources = sources),
                genrePickerSourceIndex = state.genrePickerSourceIndex?.takeIf { it != index }
                    ?.let { if (it > index) it - 1 else it }
            )
        }
    }

    fun moveCatalogSourceUp(index: Int) {
        if (index <= 0) return
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val sources = folder.sources.toMutableList()
            val item = sources.removeAt(index)
            sources.add(index - 1, item)
            state.copy(editingFolder = folder.copy(sources = sources))
        }
    }

    fun moveCatalogSourceDown(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            if (index >= folder.sources.size - 1) return@update state
            val sources = folder.sources.toMutableList()
            val item = sources.removeAt(index)
            sources.add(index + 1, item)
            state.copy(editingFolder = folder.copy(sources = sources))
        }
    }

    fun toggleCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val existing = folder.sources.indexOfFirst {
                it is AddonCatalogCollectionSource && it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
            }
            val newSources = if (existing >= 0) {
                folder.sources.toMutableList().also { it.removeAt(existing) }
            } else {
                val defaultGenre = resolveGenreSelection(catalog, requestedGenre = null)
                folder.sources + AddonCatalogCollectionSource(
                    addonId = catalog.addonId,
                    type = catalog.type,
                    catalogId = catalog.catalogId,
                    genre = defaultGenre
                )
            }
            state.copy(
                editingFolder = folder.copy(sources = newSources),
                genrePickerSourceIndex = state.genrePickerSourceIndex?.takeIf { it != existing }
            )
        }
    }

    fun showCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = true, genrePickerSourceIndex = null) }
    }

    fun hideCatalogPicker() {
        _uiState.update { it.copy(showCatalogPicker = false) }
    }

    fun showTmdbSourcePicker() {
        _uiState.update {
            it.copy(
                showTmdbSourcePicker = true,
                editingTmdbSourceIndex = null,
                genrePickerSourceIndex = null,
                tmdbBuilderMode = TmdbBuilderMode.PRESETS,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbMediaType = TmdbCollectionMediaType.MOVIE,
                tmdbMediaBoth = false,
                tmdbSortBy = TmdbCollectionSort.POPULAR_DESC.value,
                tmdbFilters = TmdbCollectionFilters(),
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun hideTmdbSourcePicker() {
        _uiState.update { it.copy(showTmdbSourcePicker = false, editingTmdbSourceIndex = null, tmdbSearchError = null) }
    }

    fun editTmdbSource(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val source = folder.sources.getOrNull(index) as? TmdbCollectionSource ?: return@update state
            state.copy(
                showTmdbSourcePicker = true,
                editingTmdbSourceIndex = index,
                genrePickerSourceIndex = null,
                tmdbBuilderMode = source.sourceType.toBuilderMode(),
                tmdbInput = source.tmdbId?.toString().orEmpty(),
                tmdbTitleInput = source.title,
                tmdbMediaType = source.mediaType,
                tmdbMediaBoth = false,
                tmdbSortBy = source.sortBy,
                tmdbFilters = source.filters,
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun setTmdbBuilderMode(mode: TmdbBuilderMode) {
        _uiState.update {
            val mediaType = when (mode) {
                TmdbBuilderMode.NETWORK -> TmdbCollectionMediaType.TV
                else -> TmdbCollectionMediaType.MOVIE
            }
            val sortBy = when (mode) {
                TmdbBuilderMode.LIST,
                TmdbBuilderMode.COLLECTION -> TmdbCollectionSort.ORIGINAL.value
                else -> TmdbCollectionSort.POPULAR_DESC.value
            }
            it.copy(
                tmdbBuilderMode = mode,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbMediaType = mediaType,
                tmdbMediaBoth = false,
                tmdbSortBy = sortBy,
                tmdbCompanyResults = emptyList(),
                tmdbCollectionResults = emptyList(),
                tmdbSearchError = null
            )
        }
    }

    fun setTmdbInput(value: String) {
        _uiState.update { it.copy(tmdbInput = value) }
    }

    fun setTmdbTitleInput(value: String) {
        _uiState.update { it.copy(tmdbTitleInput = value) }
    }

    fun setTmdbMediaType(mediaType: TmdbCollectionMediaType) {
        _uiState.update { it.copy(tmdbMediaType = mediaType, tmdbMediaBoth = false) }
    }

    fun setTmdbMediaBoth(enabled: Boolean) {
        _uiState.update { it.copy(tmdbMediaBoth = enabled, tmdbMediaType = TmdbCollectionMediaType.MOVIE) }
    }

    fun setTmdbSortBy(sortBy: String) {
        _uiState.update { it.copy(tmdbSortBy = sortBy) }
    }

    fun setTmdbFilters(filters: TmdbCollectionFilters) {
        _uiState.update { it.copy(tmdbFilters = filters) }
    }

    fun searchTmdbCompanies() {
        val query = _uiState.value.tmdbInput.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            val results = runCatching { tmdbCollectionSourceResolver.searchCompanies(query) }
            _uiState.update {
                it.copy(
                    tmdbCompanyResults = results.getOrDefault(emptyList()),
                    tmdbSearchError = results.exceptionOrNull()?.message
                )
            }
        }
    }

    fun searchTmdbCollections() {
        val query = _uiState.value.tmdbInput.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            val results = runCatching { tmdbCollectionSourceResolver.searchCollections(query) }
            _uiState.update {
                it.copy(
                    tmdbCollectionResults = results.getOrDefault(emptyList()),
                    tmdbSearchError = results.exceptionOrNull()?.message
                )
            }
        }
    }

    fun addTmdbSource(source: TmdbCollectionSource) {
        if (source.tmdbId != null && source.sourceType in coverMetadataSourceTypes) {
            viewModelScope.launch {
                val metadata = runCatching { importMetadataFor(source.sourceType, source.tmdbId) }
                val resolved = metadata.getOrNull()
                addTmdbSourceToFolder(
                    source = if (source.title.isBlank()) source.copy(title = resolved?.title.orEmpty()) else source,
                    coverImageUrl = resolved?.coverImageUrl
                )
            }
            return
        }
        addTmdbSourceToFolder(source)
    }

    fun addTmdbSources(sources: List<TmdbCollectionSource>) {
        val metadataSource = sources.firstOrNull { it.tmdbId != null && it.sourceType in coverMetadataSourceTypes }
        if (metadataSource != null) {
            viewModelScope.launch {
                val metadata = runCatching { importMetadataFor(metadataSource.sourceType, metadataSource.tmdbId!!) }
                val resolved = metadata.getOrNull()
                addTmdbSourcesToFolder(sources, resolved?.coverImageUrl)
            }
            return
        }
        addTmdbSourcesToFolder(sources)
    }

    private fun addTmdbSourceToFolder(source: TmdbCollectionSource, coverImageUrl: String? = null) {
        addTmdbSourcesToFolder(listOf(source), coverImageUrl)
    }

    private fun addTmdbSourcesToFolder(sources: List<TmdbCollectionSource>, coverImageUrl: String? = null) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val editingIndex = state.editingTmdbSourceIndex
            val newSources = sources.filterIndexed { sourceIndex, source ->
                folder.sources.noneIndexed { existingIndex, existing ->
                    existing == source && (editingIndex == null || existingIndex != editingIndex || sourceIndex > 0)
                }
            }
            if (newSources.isEmpty()) return@update state
            val shouldApplyCover = newSources.any { it.sourceType in coverMetadataSourceTypes } &&
                !coverImageUrl.isNullOrBlank() &&
                folder.coverImageUrl.isNullOrBlank()
            val updatedSources = if (
                editingIndex != null &&
                editingIndex in folder.sources.indices &&
                folder.sources[editingIndex] is TmdbCollectionSource
            ) {
                folder.sources.toMutableList().also {
                    it.removeAt(editingIndex)
                    it.addAll(editingIndex, newSources)
                }
            } else {
                folder.sources + newSources
            }
            val updatedFolder = if (shouldApplyCover) {
                folder.copy(
                    sources = updatedSources,
                    coverImageUrl = coverImageUrl,
                    coverEmoji = null
                )
            } else {
                folder.copy(sources = updatedSources)
            }
            state.copy(
                editingFolder = updatedFolder,
                showTmdbSourcePicker = false,
                editingTmdbSourceIndex = null,
                tmdbInput = "",
                tmdbTitleInput = "",
                tmdbSearchError = null
            )
        }
    }

    fun addTmdbSourceFromInput() {
        val state = _uiState.value
        val mode = state.tmdbBuilderMode
        val id = tmdbCollectionSourceResolver.parseTmdbId(state.tmdbInput)
        val title = state.tmdbTitleInput.ifBlank {
            when (mode) {
                TmdbBuilderMode.LIST -> "TMDB List ${id ?: ""}".trim()
                TmdbBuilderMode.NETWORK -> "TMDB Network ${id ?: ""}".trim()
                TmdbBuilderMode.COLLECTION -> "TMDB Collection ${id ?: ""}".trim()
                TmdbBuilderMode.PRODUCTION -> "TMDB Production ${id ?: ""}".trim()
                else -> "TMDB Discover"
            }
        }
        val sourceType = when (mode) {
            TmdbBuilderMode.LIST -> TmdbCollectionSourceType.LIST
            TmdbBuilderMode.NETWORK -> TmdbCollectionSourceType.NETWORK
            TmdbBuilderMode.COLLECTION -> TmdbCollectionSourceType.COLLECTION
            TmdbBuilderMode.PRODUCTION -> TmdbCollectionSourceType.COMPANY
            else -> TmdbCollectionSourceType.DISCOVER
        }
        if (sourceType != TmdbCollectionSourceType.DISCOVER && id == null) {
            _uiState.update { it.copy(tmdbSearchError = "Enter a valid TMDB ID or URL") }
            return
        }
        val mediaType = when (sourceType) {
            TmdbCollectionSourceType.NETWORK -> TmdbCollectionMediaType.TV
            TmdbCollectionSourceType.COLLECTION,
            TmdbCollectionSourceType.LIST -> TmdbCollectionMediaType.MOVIE
            TmdbCollectionSourceType.COMPANY,
            TmdbCollectionSourceType.DISCOVER -> state.tmdbMediaType
        }
        val mediaTypes = selectedMediaTypes(state, sourceType)
        if (sourceType == TmdbCollectionSourceType.LIST || sourceType == TmdbCollectionSourceType.COLLECTION) {
            viewModelScope.launch {
                val metadata = runCatching {
                    if (sourceType == TmdbCollectionSourceType.LIST) {
                        tmdbCollectionSourceResolver.listImportMetadata(id!!)
                    } else {
                        tmdbCollectionSourceResolver.collectionImportMetadata(id!!)
                    }
                }
                val resolved = metadata.getOrNull()
                if (metadata.isFailure) {
                    _uiState.update { it.copy(tmdbSearchError = metadata.exceptionOrNull()?.message ?: "Could not load TMDB source") }
                    return@launch
                }
                addTmdbSourceToFolder(
                    source = TmdbCollectionSource(
                        sourceType = sourceType,
                        title = state.tmdbTitleInput.ifBlank { resolved?.title ?: title },
                        tmdbId = id,
                        mediaType = mediaType,
                        sortBy = state.tmdbSortBy,
                        filters = state.tmdbFilters
                    ),
                    coverImageUrl = resolved?.coverImageUrl
                )
            }
            return
        }
        if (mediaTypes.size > 1) {
            addTmdbSourcesToFolder(
                mediaTypes.map { type ->
                    TmdbCollectionSource(
                        sourceType = sourceType,
                        title = titleForMedia(title, type, addSuffix = true),
                        tmdbId = id,
                        mediaType = type,
                        sortBy = state.tmdbSortBy,
                        filters = state.tmdbFilters
                    )
                }
            )
        } else {
            addTmdbSourceToFolder(
                TmdbCollectionSource(
                    sourceType = sourceType,
                    title = title,
                    tmdbId = id,
                    mediaType = mediaType,
                    sortBy = state.tmdbSortBy,
                    filters = state.tmdbFilters
                )
            )
        }
    }

    fun addDiscoverSource() {
        val state = _uiState.value
        val baseTitle = state.tmdbTitleInput.ifBlank { "TMDB Discover" }
        val mediaTypes = selectedMediaTypes(state, TmdbCollectionSourceType.DISCOVER)
        addTmdbSourcesToFolder(
            mediaTypes.map { mediaType ->
                TmdbCollectionSource(
                    sourceType = TmdbCollectionSourceType.DISCOVER,
                    title = titleForMedia(baseTitle, mediaType, addSuffix = mediaTypes.size > 1),
                    mediaType = mediaType,
                    sortBy = state.tmdbSortBy,
                    filters = state.tmdbFilters
                )
            }
        )
    }

    private fun selectedMediaTypes(
        state: CollectionEditorUiState,
        sourceType: TmdbCollectionSourceType
    ): List<TmdbCollectionMediaType> {
        return when (sourceType) {
            TmdbCollectionSourceType.COMPANY,
            TmdbCollectionSourceType.DISCOVER -> if (state.tmdbMediaBoth) {
                listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
            } else {
                listOf(state.tmdbMediaType)
            }
            TmdbCollectionSourceType.NETWORK -> listOf(TmdbCollectionMediaType.TV)
            TmdbCollectionSourceType.COLLECTION,
            TmdbCollectionSourceType.LIST -> listOf(TmdbCollectionMediaType.MOVIE)
        }
    }

    private fun titleForMedia(title: String, mediaType: TmdbCollectionMediaType, addSuffix: Boolean): String {
        if (!addSuffix) return title
        val suffix = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> "Movies"
            TmdbCollectionMediaType.TV -> "Series"
        }
        return "$title $suffix"
    }

    private fun TmdbCollectionSourceType.toBuilderMode(): TmdbBuilderMode {
        return when (this) {
            TmdbCollectionSourceType.LIST -> TmdbBuilderMode.LIST
            TmdbCollectionSourceType.COLLECTION -> TmdbBuilderMode.COLLECTION
            TmdbCollectionSourceType.COMPANY -> TmdbBuilderMode.PRODUCTION
            TmdbCollectionSourceType.NETWORK -> TmdbBuilderMode.NETWORK
            TmdbCollectionSourceType.DISCOVER -> TmdbBuilderMode.DISCOVER
        }
    }

    private inline fun List<CollectionSource>.noneIndexed(predicate: (Int, CollectionSource) -> Boolean): Boolean {
        forEachIndexed { index, source ->
            if (predicate(index, source)) return false
        }
        return true
    }

    private suspend fun importMetadataFor(sourceType: TmdbCollectionSourceType, id: Int) = when (sourceType) {
        TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.collectionImportMetadata(id)
        TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.companyImportMetadata(id)
        TmdbCollectionSourceType.NETWORK -> tmdbCollectionSourceResolver.networkImportMetadata(id)
        TmdbCollectionSourceType.LIST -> tmdbCollectionSourceResolver.listImportMetadata(id)
        TmdbCollectionSourceType.DISCOVER -> null
    }

    private val coverMetadataSourceTypes = setOf(
        TmdbCollectionSourceType.COLLECTION,
        TmdbCollectionSourceType.COMPANY,
        TmdbCollectionSourceType.NETWORK
    )

    fun tmdbPresets(): List<TmdbPresetSource> = listOf(
        TmdbPresetSource("Marvel Studios", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Marvel Studios", 420, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Walt Disney Pictures", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Walt Disney Pictures", 2, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Pixar", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Pixar", 3, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Lucasfilm", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Lucasfilm", 1, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Warner Bros.", TmdbCollectionSource(TmdbCollectionSourceType.COMPANY, "Warner Bros.", 174, TmdbCollectionMediaType.MOVIE)),
        TmdbPresetSource("Netflix", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Netflix", 213, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("HBO", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "HBO", 49, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Disney+", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Disney+", 2739, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Prime Video", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Prime Video", 1024, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Hulu", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Hulu", 453, TmdbCollectionMediaType.TV)),
        TmdbPresetSource("Apple TV+", TmdbCollectionSource(TmdbCollectionSourceType.NETWORK, "Apple TV+", 2552, TmdbCollectionMediaType.TV))
    )

    fun showGenrePicker(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            if (index !in folder.sources.indices || folder.sources[index] !is AddonCatalogCollectionSource) return@update state
            state.copy(showCatalogPicker = false, genrePickerSourceIndex = index)
        }
    }

    fun hideGenrePicker() {
        _uiState.update { it.copy(genrePickerSourceIndex = null) }
    }

    fun updateCatalogSourceGenre(index: Int, genre: String?) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val source = folder.sources.getOrNull(index) as? AddonCatalogCollectionSource ?: return@update state
            val catalog = state.availableCatalogs.find {
                it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
            } ?: return@update state
            val normalizedGenre = resolveGenreSelection(catalog, genre)
            val updatedSources = folder.sources.toMutableList()
            updatedSources[index] = source.copy(genre = normalizedGenre)
            state.copy(editingFolder = folder.copy(sources = updatedSources))
        }
    }

    fun saveFolderEdit() {
        val rawFolder = _uiState.value.editingFolder ?: return
        if (rawFolder.sources.isEmpty()) return
        val cleanedFolder = rawFolder.copy(
            title = rawFolder.title.ifBlank { "Untitled" },
            coverImageUrl = rawFolder.coverImageUrl?.ifBlank { null },
            heroBackdropUrl = rawFolder.heroBackdropUrl?.ifBlank { null },
            titleLogoUrl = rawFolder.titleLogoUrl?.ifBlank { null }
        )
        val editingFolder = cleanedFolder
        _uiState.update { state ->
            val existingIndex = state.folders.indexOfFirst { it.id == editingFolder.id }
            val newFolders = if (existingIndex >= 0) {
                state.folders.toMutableList().also { it[existingIndex] = editingFolder }
            } else {
                state.folders + editingFolder
            }
            state.copy(
                folders = newFolders,
                showFolderEditor = false,
                editingFolder = null,
                showCatalogPicker = false,
                showTmdbSourcePicker = false,
                genrePickerSourceIndex = null,
                showEmojiPicker = false
            )
        }
    }

    fun cancelFolderEdit() {
        _uiState.update {
            it.copy(
                showFolderEditor = false,
                editingFolder = null,
                showCatalogPicker = false,
                showTmdbSourcePicker = false,
                genrePickerSourceIndex = null,
                showEmojiPicker = false
            )
        }
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.folders.isEmpty()) return
        viewModelScope.launch {
            val collection = Collection(
                id = state.collectionId,
                title = state.title.ifBlank { "Untitled Collection" },
                backdropImageUrl = state.backdropImageUrl.ifBlank { null },
                pinToTop = state.pinToTop,
                focusGlowEnabled = state.focusGlowEnabled,
                viewMode = state.viewMode,
                showAllTab = state.showAllTab,
                folders = state.folders
            )

            if (state.isNew) {
                collectionsDataStore.addCollection(collection)
            } else {
                collectionsDataStore.updateCollection(collection)
            }
            onComplete()
        }
    }

    private fun resolveGenreSelection(catalog: AvailableCatalog, requestedGenre: String?): String? {
        return when {
            catalog.genreOptions.isEmpty() -> null
            requestedGenre != null && catalog.genreOptions.contains(requestedGenre) -> requestedGenre
            catalog.genreRequired -> catalog.genreOptions.firstOrNull()
            else -> null
        }
    }
}
