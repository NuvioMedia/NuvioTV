package com.omnio.phone.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.phone.R
import com.omnio.tv.data.local.LibraryPreferences
import com.omnio.tv.domain.model.LibraryEntry
import com.omnio.tv.domain.model.LibrarySourceMode
import com.omnio.tv.domain.model.MetaPreview
import com.omnio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PhoneLibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val libraryPreferences: LibraryPreferences
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(PhoneLibraryTabKey.ALL)
    private val _uiState = MutableStateFlow(PhoneLibraryUiState())
    val uiState: StateFlow<PhoneLibraryUiState> = _uiState.asStateFlow()

    init {
        observeLibrary()
    }

    fun onSelectTab(key: PhoneLibraryTabKey) {
        _selectedTab.value = key
    }

    fun onSelectSortOption(option: PhoneLibrarySortOption) {
        if (option !in _uiState.value.availableSortOptions) return
        if (option == _uiState.value.selectedSortOption) return
        viewModelScope.launch { libraryPreferences.setSortOption(option.key) }
    }

    private fun observeLibrary() {
        viewModelScope.launch {
            combine(
                libraryRepository.libraryItems,
                libraryRepository.isSyncing,
                libraryRepository.sourceMode,
                libraryPreferences.sortOption,
                _selectedTab
            ) { entries, isSyncing, sourceMode, persistedSortKey, selectedTab ->
                Bundle(entries, isSyncing, sourceMode, persistedSortKey, selectedTab)
            }.collect { bundle ->
                val movieCount = bundle.entries.count { it.matchesType("movie") }
                val seriesCount = bundle.entries.count { it.matchesType("series") }
                val totalCount = bundle.entries.size

                val tabs = listOf(
                    PhoneLibraryTab(PhoneLibraryTabKey.ALL, R.string.library_tab_all, totalCount),
                    PhoneLibraryTab(PhoneLibraryTabKey.MOVIES, R.string.library_tab_movies, movieCount),
                    PhoneLibraryTab(PhoneLibraryTabKey.SERIES, R.string.library_tab_series, seriesCount)
                )

                val filtered = when (bundle.selectedTab) {
                    PhoneLibraryTabKey.ALL -> bundle.entries
                    PhoneLibraryTabKey.MOVIES -> bundle.entries.filter { it.matchesType("movie") }
                    PhoneLibraryTabKey.SERIES -> bundle.entries.filter { it.matchesType("series") }
                }

                val sortOptions = if (bundle.sourceMode == LibrarySourceMode.TRAKT) {
                    PhoneLibrarySortOption.TraktOptions
                } else {
                    PhoneLibrarySortOption.LocalOptions
                }
                val modeDefault = if (bundle.sourceMode == LibrarySourceMode.TRAKT) {
                    PhoneLibrarySortOption.TRAKT_DEFAULT
                } else {
                    PhoneLibrarySortOption.ADDED_DESC
                }
                val persistedSort = PhoneLibrarySortOption.fromPersistedKey(bundle.persistedSortKey)
                val effectiveSort = persistedSort?.takeIf { it in sortOptions } ?: modeDefault

                val sorted = sortEntries(filtered, effectiveSort, bundle.sourceMode)
                val previews: List<MetaPreview> = sorted.map { it.toMetaPreview() }

                _uiState.update {
                    it.copy(
                        isLoading = bundle.isSyncing && bundle.entries.isEmpty(),
                        totalItemCount = totalCount,
                        items = previews,
                        tabs = tabs,
                        selectedTab = bundle.selectedTab,
                        sourceMode = bundle.sourceMode,
                        availableSortOptions = sortOptions,
                        selectedSortOption = effectiveSort
                    )
                }
            }
        }
    }

    private fun sortEntries(
        entries: List<LibraryEntry>,
        option: PhoneLibrarySortOption,
        sourceMode: LibrarySourceMode
    ): List<LibraryEntry> {
        return when (option) {
            PhoneLibrarySortOption.TRAKT_DEFAULT -> if (sourceMode == LibrarySourceMode.TRAKT) {
                entries.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                entries
            }
            PhoneLibrarySortOption.ADDED_DESC -> entries.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            PhoneLibrarySortOption.ADDED_ASC -> entries.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            PhoneLibrarySortOption.TITLE_ASC -> entries.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            PhoneLibrarySortOption.TITLE_DESC -> entries.sortedWith(
                compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
        }
    }

    private fun LibraryEntry.matchesType(target: String): Boolean =
        type.trim().lowercase(Locale.ROOT) == target

    private data class Bundle(
        val entries: List<LibraryEntry>,
        val isSyncing: Boolean,
        val sourceMode: LibrarySourceMode,
        val persistedSortKey: String?,
        val selectedTab: PhoneLibraryTabKey
    )
}
