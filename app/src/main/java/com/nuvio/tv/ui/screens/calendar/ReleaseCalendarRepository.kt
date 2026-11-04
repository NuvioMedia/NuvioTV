package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class ReleaseCalendarEvent(
    val key: String,
    val date: LocalDate,
    val rawReleaseInfo: String,
    val item: LibraryEntry,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val sortTitle: String = title,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    val isEpisode: Boolean get() = key.startsWith(EPISODE_KEY_PREFIX)

    fun isUpcoming(today: LocalDate): Boolean =
        if (isEpisode) {
            isEpisodeReleaseAired(rawReleaseInfo)?.not() ?: date.isAfter(today)
        } else {
            date.isAfter(today)
        }
}

data class ReleaseCalendarCacheState(
    val cacheKey: String? = null,
    val events: List<ReleaseCalendarEvent> = emptyList(),
    val isWarming: Boolean = false,
    val isReady: Boolean = false,
    val loadedMonths: Set<YearMonth> = emptySet(),
    val builtOn: LocalDate? = null,
    val failedSeriesKeys: Set<String> = emptySet(),
)

@Singleton
class ReleaseCalendarRepository @Inject constructor(
    private val metaRepository: MetaRepository,
    private val profileManager: ProfileManager,
) {
    private val retryDelaysMs = longArrayOf(60_000L, 5L * 60_000L, 15L * 60_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ReleaseCalendarCacheState())
    val state: StateFlow<ReleaseCalendarCacheState> = _state.asStateFlow()

    private var lastItems: List<LibraryEntry> = emptyList()
    private var warmJob: Job? = null
    private var warmJobKey: String? = null
    private var monthJob: Job? = null
    private var lastForcedRefreshAtMs = 0L
    private var lastRetryAtMs = 0L
    private var retryJob: Job? = null
    private var retryAttempt = 0

    fun cacheKeyFor(items: List<LibraryEntry>): String {
        val months = releaseCalendarWarmMonths(LocalDate.now()).joinToString(separator = ",")
        val fingerprint = items.joinToString(separator = "|") { item ->
            "${item.type}:${item.id}:${item.releaseInfo.orEmpty()}"
        }
        return "${profileManager.activeProfileId.value}:$months:$fingerprint"
    }

    suspend fun warm(items: List<LibraryEntry>) {
        if (items.isEmpty()) return
        lastItems = items
        requestWarm(items)?.join()
    }

    suspend fun forceRefresh(items: List<LibraryEntry>) {
        if (items.isEmpty()) return
        lastItems = items
        warmJob?.takeIf { it.isActive }?.let { running ->
            running.join()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastForcedRefreshAtMs < FORCE_REFRESH_COOLDOWN_MS) return
        lastForcedRefreshAtMs = now
        resetRetry()
        val cacheKey = cacheKeyFor(items)
        warmJobKey = cacheKey
        val job = scope.launch { runWarm(items, cacheKey, forceFull = true) }
        warmJob = job
        job.join()
    }

    suspend fun retryFailed(items: List<LibraryEntry>) {
        if (items.isEmpty()) return
        lastItems = items
        val now = System.currentTimeMillis()
        if (now - lastRetryAtMs < RETRY_COOLDOWN_MS) return
        lastRetryAtMs = now
        resetRetry()
        requestWarm(items)?.join()
    }

    fun refreshIfStale() {
        val items = lastItems.takeIf { it.isNotEmpty() } ?: return
        requestWarm(items)
    }

    suspend fun ensureMonth(items: List<LibraryEntry>, month: YearMonth) {
        val cacheKey = cacheKeyFor(items)
        val current = _state.value
        if (current.cacheKey != cacheKey || month in current.loadedMonths) return
        if (warmJob?.isActive == true || monthJob?.isActive == true) return
        val job = scope.launch {
            _state.value = _state.value.copy(isWarming = true)
            try {
                val result = buildEvents(items, setOf(month))
                val latest = _state.value
                if (latest.cacheKey == cacheKey) {
                    _state.value = latest.copy(
                        events = (latest.events + result.events).sortedCalendarEvents(),
                        loadedMonths = if (result.failedSeriesKeys.isEmpty()) {
                            latest.loadedMonths + month
                        } else {
                            latest.loadedMonths
                        },
                    )
                }
            } finally {
                if (_state.value.isWarming) _state.value = _state.value.copy(isWarming = false)
            }
        }
        monthJob = job
        job.join()
    }

    private fun isFresh(state: ReleaseCalendarCacheState, cacheKey: String): Boolean =
        state.cacheKey == cacheKey &&
            state.isReady &&
            state.failedSeriesKeys.isEmpty() &&
            state.builtOn == LocalDate.now()

    private fun requestWarm(items: List<LibraryEntry>): Job? {
        val cacheKey = cacheKeyFor(items)
        if (isFresh(_state.value, cacheKey)) return null
        warmJob?.takeIf { job -> job.isActive && warmJobKey == cacheKey }?.let { return it }
        warmJob?.cancel()
        warmJobKey = cacheKey
        return scope.launch { runWarm(items, cacheKey) }.also { warmJob = it }
    }

    private suspend fun runWarm(items: List<LibraryEntry>, cacheKey: String, forceFull: Boolean = false) {
        val today = LocalDate.now()
        val targetMonths = releaseCalendarWarmMonths(today)
        val inMemory = _state.value.takeIf { it.cacheKey == cacheKey }
        val itemKeys = items.map { it.calendarSeriesKey() }.toSet()
        val previousEvents = (inMemory?.events ?: _state.value.events)
            .filter { event -> event.isEpisode && event.item.calendarSeriesKey() in itemKeys }

        _state.value = ReleaseCalendarCacheState(
            cacheKey = cacheKey,
            events = (previousEvents + buildReleaseCalendarFallbackEvents(items)).sortedCalendarEvents(),
            isWarming = true,
            loadedMonths = inMemory?.loadedMonths.orEmpty(),
            builtOn = inMemory?.builtOn,
            failedSeriesKeys = inMemory?.failedSeriesKeys.orEmpty(),
            isReady = inMemory?.isReady == true,
        )

        try {
            val previousBuild = inMemory?.takeIf { it.isReady }
            if (!forceFull && previousBuild != null && isFresh(previousBuild, cacheKey)) {
                _state.value = previousBuild
                resetRetry()
                return
            }

            val sameBuildContext = !forceFull &&
                previousBuild != null &&
                previousBuild.builtOn == today &&
                previousBuild.loadedMonths.containsAll(targetMonths)
            val next = if (sameBuildContext && previousBuild != null) {
                val retried = previousBuild.failedSeriesKeys
                val result = buildEvents(items, targetMonths, onlySeriesKeys = retried)
                val recovered = retried - result.failedSeriesKeys
                previousBuild.copy(
                    events = (previousBuild.events.filterNot { event ->
                        event.isEpisode && event.item.calendarSeriesKey() in recovered
                    } + result.events).withoutSupersededFallbacks(targetMonths),
                    failedSeriesKeys = result.failedSeriesKeys,
                )
            } else {
                val result = buildEvents(items, targetMonths)
                val preserved = previousEvents.filter { event ->
                    event.item.calendarSeriesKey() in result.failedSeriesKeys &&
                        YearMonth.from(event.date) in targetMonths
                }
                ReleaseCalendarCacheState(
                    cacheKey = cacheKey,
                    events = (result.events + preserved).sortedCalendarEvents(),
                    isReady = true,
                    loadedMonths = targetMonths,
                    builtOn = today,
                    failedSeriesKeys = result.failedSeriesKeys,
                )
            }

            if (_state.value.cacheKey != cacheKey) return
            _state.value = next
            if (next.failedSeriesKeys.isEmpty()) resetRetry() else scheduleRetry()
        } finally {
            if (_state.value.cacheKey == cacheKey && _state.value.isWarming) {
                _state.value = _state.value.copy(isWarming = false)
            }
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val delayMs = retryDelaysMs.getOrNull(retryAttempt) ?: return
        retryAttempt += 1
        retryJob = scope.launch {
            delay(delayMs)
            refreshIfStale()
        }
    }

    private fun resetRetry() {
        retryAttempt = 0
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun buildEvents(
        items: List<LibraryEntry>,
        targetMonths: Set<YearMonth>,
        onlySeriesKeys: Set<String>? = null,
    ): ReleaseCalendarBuildResult {
        val fallbackEvents = if (onlySeriesKeys == null) buildReleaseCalendarFallbackEvents(items) else emptyList()
        val episodes = buildEpisodeEvents(items, targetMonths, onlySeriesKeys)
        return ReleaseCalendarBuildResult(
            events = withContext(Dispatchers.Default) {
                (episodes.events + fallbackEvents).withoutSupersededFallbacks(targetMonths)
            },
            failedSeriesKeys = episodes.failedSeriesKeys,
        )
    }

    private suspend fun buildEpisodeEvents(
        items: List<LibraryEntry>,
        targetMonths: Set<YearMonth>,
        onlySeriesKeys: Set<String>?,
    ): ReleaseCalendarBuildResult = coroutineScope {
        val permits = Semaphore(FETCH_CONCURRENCY)
        val results = items
            .filter { it.isCalendarSeries() }
            .filter { item -> onlySeriesKeys == null || item.calendarSeriesKey() in onlySeriesKeys }
            .distinctBy { it.calendarSeriesKey() }
            .map { item ->
                async {
                    permits.withPermit { item to fetchVideos(item) }
                }
            }
            .awaitAll()
        withContext(Dispatchers.Default) {
            val events = mutableListOf<ReleaseCalendarEvent>()
            val failed = mutableSetOf<String>()
            results.forEach { (item, videos) ->
                if (videos == null) {
                    failed += item.calendarSeriesKey()
                    return@forEach
                }
                videos
                    .mapNotNull { video -> video.toCalendarEvent(item) }
                    .filterTo(events) { event -> YearMonth.from(event.date) in targetMonths }
            }
            ReleaseCalendarBuildResult(events = events, failedSeriesKeys = failed)
        }
    }

    private suspend fun fetchVideos(item: LibraryEntry): List<Video>? =
        try {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                metaRepository.getMetaFromAllAddons(type = item.type, id = item.id)
                    .first { it !is NetworkResult.Loading }
            }
            when (result) {
                is NetworkResult.Success -> result.data.videos
                is NetworkResult.Error -> if (
                    result.code == NetworkResult.SOURCE_SUFFICIENT_CODE ||
                    result.code == NetworkResult.META_NOT_FOUND_CODE
                ) {
                    emptyList()
                } else {
                    null
                }
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }

    private class ReleaseCalendarBuildResult(
        val events: List<ReleaseCalendarEvent>,
        val failedSeriesKeys: Set<String>,
    )

    private companion object {
        const val FETCH_CONCURRENCY = 4
        const val FETCH_TIMEOUT_MS = 30_000L
        const val FORCE_REFRESH_COOLDOWN_MS = 30_000L
        const val RETRY_COOLDOWN_MS = 30_000L
    }
}

private const val EPISODE_KEY_PREFIX = "episode:"

internal fun releaseCalendarWarmMonths(today: LocalDate): Set<YearMonth> {
    val current = YearMonth.from(today)
    return linkedSetOf(current.minusMonths(1), current, current.plusMonths(1))
}

internal fun buildReleaseCalendarFallbackEvents(items: List<LibraryEntry>): List<ReleaseCalendarEvent> =
    items
        .asSequence()
        .mapNotNull { item ->
            val raw = item.releaseInfo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = parseExactCalendarDate(raw) ?: return@mapNotNull null
            ReleaseCalendarEvent(
                key = "item:${item.type}:${item.id}:$date",
                date = date,
                rawReleaseInfo = raw,
                item = item,
                title = item.name,
                imageUrl = item.background ?: item.landscapePoster ?: item.poster,
                sortTitle = item.name,
            )
        }
        .toList()
        .sortedCalendarEvents()

internal fun List<ReleaseCalendarEvent>.sortedCalendarEvents(): List<ReleaseCalendarEvent> =
    distinctBy { it.key }
        .sortedWith(compareBy<ReleaseCalendarEvent> { it.date }.thenBy { it.sortTitle.lowercase() })

private fun List<ReleaseCalendarEvent>.withoutSupersededFallbacks(
    targetMonths: Set<YearMonth>,
): List<ReleaseCalendarEvent> {
    val seriesWithEpisodes = filter { it.isEpisode }
        .map { it.item.calendarSeriesKey() }
        .toSet()
    return filterNot { event ->
        !event.isEpisode &&
            YearMonth.from(event.date) in targetMonths &&
            event.item.isCalendarSeries() &&
            event.item.calendarSeriesKey() in seriesWithEpisodes
    }.sortedCalendarEvents()
}

private fun Video.toCalendarEvent(item: LibraryEntry): ReleaseCalendarEvent? {
    val raw = released?.takeIf { it.isNotBlank() } ?: return null
    val date = parseEpisodeReleaseLocalDate(raw) ?: return null
    val seasonNumber = season?.takeIf { it > 0 }
    val episodeNumber = episode?.takeIf { it > 0 }
    val episodeLabel = when {
        seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
        episodeNumber != null -> "E$episodeNumber"
        else -> null
    }
    val subtitle = listOfNotNull(episodeLabel, title.takeIf { it.isNotBlank() })
        .joinToString(" - ")
        .takeIf { it.isNotBlank() }
    return ReleaseCalendarEvent(
        key = "$EPISODE_KEY_PREFIX${item.type}:${item.id}:${season ?: 0}:${episode ?: id}:$date",
        date = date,
        rawReleaseInfo = raw,
        item = item,
        title = item.name,
        subtitle = subtitle,
        imageUrl = thumbnail ?: item.background ?: item.landscapePoster ?: item.poster,
        sortTitle = "${item.name} ${season ?: 0} ${episode ?: 0} $title",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}

private fun parseExactCalendarDate(raw: String): LocalDate? {
    val datePart = raw.trim().substringBefore('T').takeIf { it.length == 10 } ?: return null
    return runCatching { LocalDate.parse(datePart) }.getOrNull()
}

internal fun LibraryEntry.calendarSeriesKey(): String = "${type.lowercase()}:$id"

internal fun LibraryEntry.isCalendarSeries(): Boolean =
    type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("show", ignoreCase = true) ||
        type.equals("tvshow", ignoreCase = true)
