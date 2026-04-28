package com.omnio.tv.ui.screens.cast

import com.omnio.tv.domain.model.PersonDetail

sealed interface CastDetailUiState {
    data object Loading : CastDetailUiState
    data class Success(val personDetail: PersonDetail) : CastDetailUiState
    data class Error(val message: String) : CastDetailUiState
}
