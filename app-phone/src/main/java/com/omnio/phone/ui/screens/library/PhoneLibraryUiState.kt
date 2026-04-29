package com.omnio.phone.ui.screens.library

import androidx.compose.runtime.Immutable
import com.omnio.phone.R
import com.omnio.tv.domain.model.LibrarySourceMode
import com.omnio.tv.domain.model.MetaPreview

@Immutable
data class PhoneLibraryUiState(
    val isLoading: Boolean = true,
    val totalItemCount: Int = 0,
    val items: List<MetaPreview> = emptyList(),
    val tabs: List<PhoneLibraryTab> = DEFAULT_TABS,
    val selectedTab: PhoneLibraryTabKey = PhoneLibraryTabKey.ALL,
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val availableSortOptions: List<PhoneLibrarySortOption> = PhoneLibrarySortOption.LocalOptions,
    val selectedSortOption: PhoneLibrarySortOption = PhoneLibrarySortOption.ADDED_DESC
) {
    companion object {
        val DEFAULT_TABS: List<PhoneLibraryTab> = listOf(
            PhoneLibraryTab(PhoneLibraryTabKey.ALL, R.string.library_tab_all, 0),
            PhoneLibraryTab(PhoneLibraryTabKey.MOVIES, R.string.library_tab_movies, 0),
            PhoneLibraryTab(PhoneLibraryTabKey.SERIES, R.string.library_tab_series, 0)
        )
    }
}

enum class PhoneLibraryTabKey {
    ALL,
    MOVIES,
    SERIES
}

data class PhoneLibraryTab(
    val key: PhoneLibraryTabKey,
    val labelResId: Int,
    val count: Int
)

enum class PhoneLibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    TRAKT_DEFAULT("default", R.string.library_sort_trakt_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc);

    companion object {
        val TraktOptions = listOf(TRAKT_DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)

        fun fromPersistedKey(key: String?): PhoneLibrarySortOption? =
            entries.firstOrNull { it.key == key }
    }
}
