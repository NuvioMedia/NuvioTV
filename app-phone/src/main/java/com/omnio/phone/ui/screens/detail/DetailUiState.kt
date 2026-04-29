package com.omnio.phone.ui.screens.detail

import com.omnio.tv.domain.model.Meta
import com.omnio.tv.domain.model.Video

data class DetailUiState(
    val isLoading: Boolean = true,
    val meta: Meta? = null,
    val error: String? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val episodesForSeason: List<Video> = emptyList(),
    val isInLibrary: Boolean = false,
    val userMessage: String? = null
)
