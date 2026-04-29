package com.omnio.phone.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.phone.R
import com.omnio.tv.domain.model.LibraryEntry
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
    private val libraryRepository: LibraryRepository
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

    private fun observeLibrary() {
        viewModelScope.launch {
            combine(
                libraryRepository.libraryItems,
                libraryRepository.isSyncing,
                _selectedTab
            ) { entries, isSyncing, selectedTab ->
                Triple(entries, isSyncing, selectedTab)
            }.collect { (entries, isSyncing, selectedTab) ->
                val movieCount = entries.count { it.matchesType("movie") }
                val seriesCount = entries.count { it.matchesType("series") }
                val totalCount = entries.size

                val tabs = listOf(
                    PhoneLibraryTab(PhoneLibraryTabKey.ALL, R.string.library_tab_all, totalCount),
                    PhoneLibraryTab(PhoneLibraryTabKey.MOVIES, R.string.library_tab_movies, movieCount),
                    PhoneLibraryTab(PhoneLibraryTabKey.SERIES, R.string.library_tab_series, seriesCount)
                )

                val filtered = when (selectedTab) {
                    PhoneLibraryTabKey.ALL -> entries
                    PhoneLibraryTabKey.MOVIES -> entries.filter { it.matchesType("movie") }
                    PhoneLibraryTabKey.SERIES -> entries.filter { it.matchesType("series") }
                }

                val sorted = filtered.sortedWith(
                    compareByDescending<LibraryEntry> { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )

                val previews: List<MetaPreview> = sorted.map { it.toMetaPreview() }

                _uiState.update {
                    it.copy(
                        isLoading = isSyncing && entries.isEmpty(),
                        totalItemCount = totalCount,
                        items = previews,
                        tabs = tabs,
                        selectedTab = selectedTab
                    )
                }
            }
        }
    }

    private fun LibraryEntry.matchesType(target: String): Boolean =
        type.trim().lowercase(Locale.ROOT) == target
}
