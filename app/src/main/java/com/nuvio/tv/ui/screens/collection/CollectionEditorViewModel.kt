package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
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

@HiltViewModel
class CollectionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionsDataStore: CollectionsDataStore,
    private val addonRepository: AddonRepository
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
            tileShape = PosterShape.POSTER
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
            val source = CollectionCatalogSource(
                addonId = catalog.addonId,
                type = catalog.type,
                catalogId = catalog.catalogId,
                genre = defaultGenre
            )
            if (folder.catalogSources.any { it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId }) {
                return@update state
            }
            state.copy(
                editingFolder = folder.copy(catalogSources = folder.catalogSources + source),
                genrePickerSourceIndex = null
            )
        }
    }

    fun removeCatalogSource(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val sources = folder.catalogSources.toMutableList()
            if (index in sources.indices) sources.removeAt(index)
            state.copy(
                editingFolder = folder.copy(catalogSources = sources),
                genrePickerSourceIndex = state.genrePickerSourceIndex?.takeIf { it != index }
                    ?.let { if (it > index) it - 1 else it }
            )
        }
    }

    fun moveCatalogSourceUp(index: Int) {
        if (index <= 0) return
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val sources = folder.catalogSources.toMutableList()
            val item = sources.removeAt(index)
            sources.add(index - 1, item)
            state.copy(editingFolder = folder.copy(catalogSources = sources))
        }
    }

    fun moveCatalogSourceDown(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            if (index >= folder.catalogSources.size - 1) return@update state
            val sources = folder.catalogSources.toMutableList()
            val item = sources.removeAt(index)
            sources.add(index + 1, item)
            state.copy(editingFolder = folder.copy(catalogSources = sources))
        }
    }

    fun toggleCatalogSource(catalog: AvailableCatalog) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val existing = folder.catalogSources.indexOfFirst {
                it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
            }
            val newSources = if (existing >= 0) {
                folder.catalogSources.toMutableList().also { it.removeAt(existing) }
            } else {
                val defaultGenre = resolveGenreSelection(catalog, requestedGenre = null)
                folder.catalogSources + CollectionCatalogSource(
                    addonId = catalog.addonId,
                    type = catalog.type,
                    catalogId = catalog.catalogId,
                    genre = defaultGenre
                )
            }
            state.copy(
                editingFolder = folder.copy(catalogSources = newSources),
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

    fun showGenrePicker(index: Int) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            if (index !in folder.catalogSources.indices) return@update state
            state.copy(showCatalogPicker = false, genrePickerSourceIndex = index)
        }
    }

    fun hideGenrePicker() {
        _uiState.update { it.copy(genrePickerSourceIndex = null) }
    }

    fun updateCatalogSourceGenre(index: Int, genre: String?) {
        _uiState.update { state ->
            val folder = state.editingFolder ?: return@update state
            val source = folder.catalogSources.getOrNull(index) ?: return@update state
            val catalog = state.availableCatalogs.find {
                it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
            } ?: return@update state
            val normalizedGenre = resolveGenreSelection(catalog, genre)
            val updatedSources = folder.catalogSources.toMutableList()
            updatedSources[index] = source.copy(genre = normalizedGenre)
            state.copy(editingFolder = folder.copy(catalogSources = updatedSources))
        }
    }

    fun saveFolderEdit() {
        val rawFolder = _uiState.value.editingFolder ?: return
        if (rawFolder.catalogSources.isEmpty()) return
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
