package com.omnio.phone.ui.screens.home

import com.omnio.tv.domain.model.CatalogRow
import com.omnio.tv.domain.model.MetaPreview

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val rows: List<CatalogRow> = emptyList(),
    val heroItems: List<MetaPreview> = emptyList()
)
