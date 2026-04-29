package com.omnio.phone.ui.screens.search

import androidx.compose.runtime.Immutable
import com.omnio.tv.domain.model.MetaPreview

@Immutable
data class PhoneSearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val isSearching: Boolean = false,
    val results: List<MetaPreview> = emptyList(),
    val error: String? = null,
    val hasSearchableAddons: Boolean = true
)
