package com.nuvio.tv.ui.screens.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.data.translation.shouldTranslate
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CalendarHomeViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val metadataTextTranslator: MetadataTextTranslator,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "CalendarHomeVM"
        // Il modello ML Kit può ancora stare scaricandosi al primo uso:
        // pochi tentativi ritardati, poi si riprova al caricamento successivo.
        private const val TRANSLATION_ATTEMPTS = 3
        private const val TRANSLATION_RETRY_DELAY_MS = 1_000L
    }

    private val _uiState = MutableStateFlow(CalendarHomeUiState())
    val uiState: StateFlow<CalendarHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    // Il calendario espone la sinossi grezza del sorgente (spesso inglese) e la
    // UI legge meta.description direttamente: le traduzioni vengono quindi
    // applicate qui, prima di raggruppare le sezioni, come fa Home per gli hero.
    private val translationLock = Any()
    private val translatedDescriptions = mutableMapOf<String, String>()
    private val scheduledDescriptions = mutableSetOf<String>()
    private var rawItems: List<CalendarItem> = emptyList()
    private var targetLanguage: String? = null

    init {
        observeTranslationLanguage()
        loadCalendar()
    }

    /**
     * Lingua di destinazione dalle impostazioni TMDB (stessa sorgente che usa
     * Home/Detail): se cambia, le traduzioni già pubblicate vengono rigenerate.
     */
    private fun observeTranslationLanguage() {
        viewModelScope.launch {
            var previous: String? = null
            tmdbSettingsDataStore.settings.collect { settings ->
                val language = settings.language
                if (previous != null && previous != language) {
                    synchronized(translationLock) {
                        translatedDescriptions.clear()
                        scheduledDescriptions.clear()
                    }
                    if (rawItems.isNotEmpty()) publishSections(rawItems)
                }
                previous = language
                targetLanguage = language
                if (rawItems.isNotEmpty()) scheduleDescriptionTranslations(rawItems)
            }
        }
    }

    private fun loadCalendar() {
        // Cancel any in-flight collect so Retry never races a previous load.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasContent = _uiState.value.sections.isNotEmpty()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    // Keep existing sections visible while refreshing.
                    hasLoaded = hasContent || it.hasLoaded
                )
            }
            try {
                calendarRepository.getCalendarItems().collect { items ->
                    rawItems = items
                    publishSections(items)
                    scheduleDescriptionTranslations(items)
                }
                // Flow completed without emission of usable content.
                _uiState.update { state ->
                    if (state.sections.isEmpty() && state.error == null) {
                        state.copy(isLoading = false, hasLoaded = true)
                    } else {
                        state.copy(isLoading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load calendar", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    /**
     * Raggruppa le sezioni applicando le traduzioni già pronte: la schermata
     * legge `meta.description` direttamente, quindi è qui che il testo inglese
     * va sostituito prima di arrivare alla UI.
     */
    private fun publishSections(items: List<CalendarItem>) {
        val localized = synchronized(translationLock) {
            if (translatedDescriptions.isEmpty()) items
            else items.map { item ->
                val original = item.meta.description ?: return@map item
                val translated = translatedDescriptions[original] ?: return@map item
                if (translated == original) item
                else item.copy(meta = item.meta.copy(description = translated))
            }
        }
        val sections = groupItemsByPeriod(localized)
        _uiState.update {
            it.copy(
                sections = sections,
                isLoading = false,
                error = null,
                hasLoaded = true
            )
        }
    }

    /**
     * Una traduzione per ogni sinossi distinta, in async: la UI mostra prima il
     * testo grezzo e publishSections lo sostituisce appena pronto (nessuno
     * schermo resta bloccato in attesa della rete/mlkit).
     */
    private fun scheduleDescriptionTranslations(items: List<CalendarItem>) {
        val language = targetLanguage ?: return
        val candidates = items
            .mapNotNull { item -> item.meta.description?.takeIf { it.isNotBlank() } }
            .distinct()
            .filter { shouldTranslate(it, language) }
        val pending = synchronized(translationLock) {
            candidates.filter { scheduledDescriptions.add(it) }
        }
        for (description in pending) {
            viewModelScope.launch {
                val translated = translateWithRetries(description, language)
                if (translated != null) {
                    synchronized(translationLock) {
                        translatedDescriptions[description] = translated
                    }
                    publishSections(rawItems)
                } else {
                    // Modello ML Kit non pronto: rilascia la prenotazione così il
                    // prossimo caricamento riprova invece di restare inglese.
                    synchronized(translationLock) {
                        scheduledDescriptions.remove(description)
                    }
                }
            }
        }
    }

    private suspend fun translateWithRetries(description: String, language: String): String? {
        repeat(TRANSLATION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(TRANSLATION_RETRY_DELAY_MS)
            val candidate = runCatching {
                metadataTextTranslator.translateDescription(description, language)
            }.getOrNull()
            if (!candidate.isNullOrBlank() && candidate != description) return candidate
        }
        return null
    }

    private fun groupItemsByPeriod(items: List<CalendarItem>): List<CalendarSection> {
        val today = LocalDate.now()
        val sections = mutableListOf<CalendarSection>()

        // nextOrSame keeps Sunday as the end of "this week" (8-day bug fix).
        val thisWeekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val thisWeekItems = items.filter { item ->
            item.releaseDate != null && !item.releaseDate.isBefore(today) && !item.releaseDate.isAfter(thisWeekEnd)
        }
        if (thisWeekItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = context.getString(R.string.calendar_this_week),
                    dateRange = today..thisWeekEnd,
                    items = thisWeekItems.sortedWith(compareBy({ it.releaseDate }, { it.meta.name.lowercase() }, { it.episodeLabel ?: "" }, { it.meta.id }))
                )
            )
        }

        val nextWeekStart = thisWeekEnd.plusDays(1)
        val nextWeekEnd = nextWeekStart.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        val nextWeekItems = items.filter { item ->
            item.releaseDate != null && !item.releaseDate.isBefore(nextWeekStart) && !item.releaseDate.isAfter(nextWeekEnd)
        }
        if (nextWeekItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = context.getString(R.string.calendar_next_week),
                    dateRange = nextWeekStart..nextWeekEnd,
                    items = nextWeekItems.sortedWith(compareBy({ it.releaseDate }, { it.meta.name.lowercase() }, { it.episodeLabel ?: "" }, { it.meta.id }))
                )
            )
        }

        val laterItems = items.filter { item ->
            item.releaseDate != null && item.releaseDate.isAfter(nextWeekEnd)
        }
        if (laterItems.isNotEmpty()) {
            val groupedByMonth = laterItems.groupBy { item ->
                item.releaseDate!!.withDayOfMonth(1)
            }
            for ((monthStart, monthItems) in groupedByMonth) {
                val monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth())
                val monthLabel = monthStart.format(
                    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                )
                sections.add(
                    CalendarSection(
                        label = monthLabel.replaceFirstChar { it.uppercase(Locale.getDefault()) },
                        dateRange = monthStart..monthEnd,
                        items = monthItems.sortedWith(compareBy({ it.releaseDate }, { it.meta.name.lowercase() }, { it.episodeLabel ?: "" }, { it.meta.id }))
                    )
                )
            }
        }

        return sections
    }

    fun onEvent(event: CalendarHomeEvent) {
        when (event) {
            CalendarHomeEvent.OnRetry -> loadCalendar()
        }
    }
}
