package com.omnio.phone.ui.screens.seeall

import com.omnio.tv.domain.model.MetaPreview

data class PhoneSeeAllUiState(
    val catalogName: String = "",
    val addonName: String = "",
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100,
    val error: String? = null
)
