package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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
        val All = LibraryTypeTab(key = ALL_KEY, label = "All")
    }
}

enum class LibrarySortOption(
    val key: String,
    val label: String
) {
    DEFAULT("default", "Trakt Order"),
    TITLE_ASC("title_asc", "Title A-Z"),
    RECENTLY_WATCHED("recently_watched", "Recently Watched");

    companion object {
        val TraktOptions = entries
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
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val lastWatchedByContent: Map<String, Long> = emptyMap(),
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
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository
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

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val updated = current.copy(selectedSortOption = option)
            updated.withVisibleItems()
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage("Syncing Trakt library...")
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage("Library synced")
            }.onFailure { error ->
                setError(error.message ?: "Failed to refresh library")
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
            setError("List name is required")
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
                        setTransientMessage("List created")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Invalid list")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List updated")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to save list")
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
                setTransientMessage("List deleted")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to delete list")
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
                libraryRepository.listTabs,
                watchProgressRepository.allProgress.onStart { emit(emptyList()) }
            ) { sourceMode, isSyncing, items, listTabs, allProgress ->
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs,
                    lastWatchedByContent = buildLastWatchedIndex(allProgress)
                )
            }.collectLatest { (sourceMode, isSyncing, items, listTabs, lastWatchedByContent) ->
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
                    val sortOptions = if (sourceMode == LibrarySourceMode.TRAKT) {
                        LibrarySortOption.TraktOptions
                    } else {
                        emptyList()
                    }
                    val nextSelectedSort = current.selectedSortOption
                        .takeIf { it in sortOptions }
                        ?: LibrarySortOption.DEFAULT

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = items,
                        listTabs = listTabs,
                        availableTypeTabs = typeTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        lastWatchedByContent = lastWatchedByContent,
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
        val listTabs: List<LibraryListTab>,
        val lastWatchedByContent: Map<String, Long>
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
                setTransientMessage("List order updated")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to reorder lists")
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
            .ifBlank { "Unknown" }
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

        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> listFiltered
            LibrarySortOption.TITLE_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.RECENTLY_WATCHED -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { entry -> resolveLastWatched(entry) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
        }

        return copy(visibleItems = sorted)
    }

    private fun buildLastWatchedIndex(progressItems: List<WatchProgress>): Map<String, Long> {
        val byKey = mutableMapOf<String, Long>()
        progressItems.forEach { progress ->
            val normalizedType = normalizeContentType(progress.contentType)
            contentIdCandidates(progress.contentId).forEach { idKey ->
                val composite = "$normalizedType:$idKey"
                val current = byKey[composite] ?: 0L
                if (progress.lastWatched > current) {
                    byKey[composite] = progress.lastWatched
                }
            }
        }
        return byKey
    }

    private fun LibraryUiState.resolveLastWatched(entry: LibraryEntry): Long {
        val normalizedType = normalizeContentType(entry.type)
        return contentIdCandidates(entry.id)
            .map { idKey -> "$normalizedType:$idKey" }
            .mapNotNull { key -> lastWatchedByContent[key] }
            .maxOrNull() ?: 0L
    }

    private fun normalizeContentType(type: String): String {
        return when (type.trim().lowercase(Locale.ROOT)) {
            "series", "show", "tv" -> "series"
            "movie" -> "movie"
            else -> type.trim().lowercase(Locale.ROOT)
        }
    }

    private fun contentIdCandidates(contentId: String): Set<String> {
        val raw = contentId.trim()
        if (raw.isBlank()) return emptySet()

        val parsed = parseContentIds(raw)
        return buildSet {
            add(raw.lowercase(Locale.ROOT))
            parsed.imdb
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
            parsed.tmdb?.let { add("tmdb:$it") }
            parsed.trakt?.let { add("trakt:$it") }
        }
    }
}
