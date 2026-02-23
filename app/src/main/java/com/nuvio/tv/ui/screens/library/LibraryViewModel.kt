package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "Todo")
    }
}

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var messageClearJob: Job? = null

    init {
        observeLibraryData()
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage("Sincronizando biblioteca de Trakt...")
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage("Biblioteca sincronizada")
            }.onFailure { error ->
                setError(error.message ?: "Error al actualizar la biblioteca")
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.sourceMode != LibrarySourceMode.TRAKT) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError("El nombre de la lista es obligatorio")
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("Lista creada")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Lista no válida")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("Lista actualizada")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Error al guardar la lista")
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage("Lista eliminada")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Error al eliminar la lista")
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs
            ) { sourceMode, isSyncing, items, listTabs ->
                DataBundle(sourceMode, isSyncing, items, listTabs)
            }.collectLatest { (sourceMode, isSyncing, items, listTabs) ->
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode == LibrarySourceMode.TRAKT -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val itemsForTypeTabs = if (sourceMode == LibrarySourceMode.TRAKT) {
                        val listKey = nextSelectedList
                        if (listKey.isNullOrBlank()) items else items.filter { it.listKeys.contains(listKey) }
                    } else {
                        items
                    }
                    val typeTabs = buildTypeTabs(itemsForTypeTabs)
                    val nextSelectedType = current.selectedTypeTab
                        ?.takeIf { selected -> typeTabs.any { it.key == selected.key } }
                        ?: LibraryTypeTab.All

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = items,
                        listTabs = listTabs,
                        availableTypeTabs = typeTabs,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        manageSelectedListKey = nextManageSelected,
                        isSyncing = sourceMode == LibrarySourceMode.TRAKT && isSyncing,
                        isLoading = sourceMode == LibrarySourceMode.TRAKT &&
                            isSyncing &&
                            items.isEmpty() &&
                            listTabs.isEmpty()
                    )
                    updated.withVisibleItems()
                }
            }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage("Orden de las listas actualizado")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Error al reordenar las listas")
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun buildTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, LibraryTypeTab>()
        items.forEach { entry ->
            val key = entry.type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = LibraryTypeTab(
                key = key,
                label = prettifyTypeLabel(key)
            )
        }
        return listOf(LibraryTypeTab.All) + byKey.values
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { "Desconocido" }
    }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = allItems.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        val listFiltered = if (sourceMode == LibrarySourceMode.TRAKT) {
            val listKey = selectedListKey ?: ""
            typeFiltered.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            typeFiltered
        }

        return copy(visibleItems = listFiltered)
    }
}