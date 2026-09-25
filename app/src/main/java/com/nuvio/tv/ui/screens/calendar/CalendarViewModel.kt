package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.CalendarRepository
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarRecommendation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CalendarUiState(
    val loading: Boolean = true,
    val days: List<CalendarDay> = emptyList(),
    val recommendations: List<CalendarRecommendation> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching { repository.load() }
                .onSuccess { _uiState.value = CalendarUiState(false, it.days, it.recommendations) }
                .onFailure { _uiState.value = CalendarUiState(false, error = it.message ?: "Unable to load calendar") }
        }
    }
}
