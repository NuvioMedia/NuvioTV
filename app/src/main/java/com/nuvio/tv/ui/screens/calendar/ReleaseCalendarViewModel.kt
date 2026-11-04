package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ReleaseCalendarUiState(
    val today: LocalDate = LocalDate.now(),
    val visibleMonth: YearMonth = YearMonth.from(today),
    val selectedDate: LocalDate = today,
    val events: List<ReleaseCalendarEvent> = emptyList(),
    val eventsByDate: Map<LocalDate, List<ReleaseCalendarEvent>> = emptyMap(),
    val monthEventCount: Int = 0,
    val isLoading: Boolean = true,
    val hasItems: Boolean = false,
    val failedSeriesCount: Int = 0,
) {
    val selectedEvents: List<ReleaseCalendarEvent>
        get() = eventsByDate[selectedDate].orEmpty()
}

private data class ReleaseCalendarSelection(
    val month: YearMonth,
    val date: LocalDate?,
)

private data class ReleaseCalendarItemsSnapshot(
    val items: List<LibraryEntry>,
    val cacheKey: String,
)

@HiltViewModel
class ReleaseCalendarViewModel @Inject constructor(
    private val repository: ReleaseCalendarRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val today = LocalDate.now()
    private val snapshot = MutableStateFlow<ReleaseCalendarItemsSnapshot?>(null)
    private val selection = MutableStateFlow(ReleaseCalendarSelection(YearMonth.from(today), null))

    val uiState: StateFlow<ReleaseCalendarUiState> = combine(
        snapshot,
        repository.state,
        libraryRepository.isSyncing,
        selection,
    ) { snapshot, cache, isSyncing, selection ->
        val items = snapshot?.items.orEmpty()
        val keyMatches = snapshot != null && cache.cacheKey == snapshot.cacheKey
        val events = if (keyMatches) cache.events else buildReleaseCalendarFallbackEvents(items)
        val eventsByDate = events.groupBy { it.date }
        val monthEvents = events.filter { YearMonth.from(it.date) == selection.month }
        val selectedDate = selection.date ?: defaultSelectedDate(monthEvents, selection.month)
        ReleaseCalendarUiState(
            today = today,
            visibleMonth = selection.month,
            selectedDate = selectedDate,
            events = events,
            eventsByDate = eventsByDate,
            monthEventCount = monthEvents.size,
            isLoading = snapshot == null ||
                (items.isEmpty() && isSyncing) ||
                (items.isNotEmpty() && (!keyMatches || cache.isWarming)),
            hasItems = items.isNotEmpty(),
            failedSeriesCount = if (keyMatches) cache.failedSeriesKeys.size else 0,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ReleaseCalendarUiState(today = today))

    init {
        viewModelScope.launch {
            libraryRepository.libraryItems.collectLatest { items ->
                val next = ReleaseCalendarItemsSnapshot(items, repository.cacheKeyFor(items))
                snapshot.value = next
                repository.warm(items)
                val month = selection.value.month
                if (month !in releaseCalendarWarmMonths(LocalDate.now())) {
                    repository.ensureMonth(items, month)
                }
            }
        }
    }

    fun selectDate(date: LocalDate) {
        val current = selection.value
        if (current.date == date && current.month == YearMonth.from(date)) return
        selection.value = ReleaseCalendarSelection(YearMonth.from(date), date)
    }

    fun showPreviousMonth() = showMonth(selection.value.month.minusMonths(1))

    fun showNextMonth() = showMonth(selection.value.month.plusMonths(1))

    fun showToday() {
        selection.value = ReleaseCalendarSelection(YearMonth.from(today), today)
    }

    fun refresh() {
        val items = snapshot.value?.items ?: return
        viewModelScope.launch { repository.forceRefresh(items) }
    }

    fun retryFailed() {
        val items = snapshot.value?.items ?: return
        viewModelScope.launch { repository.retryFailed(items) }
    }

    fun onScreenResumed() {
        repository.refreshIfStale()
    }

    private fun showMonth(month: YearMonth) {
        selection.value = ReleaseCalendarSelection(month, null)
        val items = snapshot.value?.items ?: return
        viewModelScope.launch { repository.ensureMonth(items, month) }
    }

    private fun defaultSelectedDate(monthEvents: List<ReleaseCalendarEvent>, month: YearMonth): LocalDate {
        val isCurrentMonth = YearMonth.from(today) == month
        return monthEvents.firstOrNull { event -> !isCurrentMonth || !event.date.isBefore(today) }?.date
            ?: monthEvents.firstOrNull()?.date
            ?: if (isCurrentMonth) today else month.atDay(1)
    }
}
