package com.omnio.phone.ui.screens.library

import androidx.compose.runtime.Immutable
import com.omnio.phone.R
import com.omnio.tv.domain.model.MetaPreview

@Immutable
data class PhoneLibraryUiState(
    val isLoading: Boolean = true,
    val totalItemCount: Int = 0,
    val items: List<MetaPreview> = emptyList(),
    val tabs: List<PhoneLibraryTab> = DEFAULT_TABS,
    val selectedTab: PhoneLibraryTabKey = PhoneLibraryTabKey.ALL
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
