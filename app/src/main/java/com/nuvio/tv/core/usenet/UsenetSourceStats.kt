package com.nuvio.tv.core.usenet

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where [UsenetSourceStats] persists; the app uses private SharedPreferences. */
interface UsenetStatsStorage {
    fun load(): String?
    fun save(value: String)
    /** Removes the saved counts at once; the chosen period and content stay. */
    fun clear()
    fun loadPreference(name: String): String?
    fun savePreference(name: String, value: String)
    /** Changes when the stored counts are cleared from outside, so a loaded copy is dropped. */
    val generation: Int get() = 0
}

class SharedPreferencesUsenetStatsStorage(context: Context) : UsenetStatsStorage, ProfileScopedCredentialStore {
    private val preferences = context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(value: String) { preferences.edit().putString(KEY, value).apply() }
    override fun clear() { preferences.edit().remove(KEY).commit() }
    override fun loadPreference(name: String): String? = preferences.getString(name, null)
    override fun savePreference(name: String, value: String) { preferences.edit().putString(name, value).apply() }
    @Volatile override var generation = 0
        private set

    // Holds no credentials and is shared by profiles; only an account reset clears it.
    override fun removeProfile(profileId: Int) = Unit
    override fun clearAllProfiles() {
        preferences.edit().clear().commit()
        generation += 1
    }

    private companion object { const val KEY = "stats" }
}

/** The days the statistics screen counts. */
enum class StatsPeriod { LAST_7_DAYS, LAST_30_DAYS, ALL_TIME }

/** The searches and playback the statistics screen counts. */
enum class StatsContent { ALL, MOVIES, SHOWS }

/** What an event was for; null where it belongs to neither, such as a capabilities request. */
enum class ContentKind { MOVIE, SHOW }

/** The same rule as a built-in search: every type but a movie is searched as a series. */
internal fun contentKindOf(type: String?): ContentKind? = when {
    type.isNullOrBlank() -> null
    type.equals("movie", ignoreCase = true) -> ContentKind.MOVIE
    else -> ContentKind.SHOW
}

enum class RequestOutcome { ANSWERED, FAILED, LIMITED }

enum class SearchOutcome { ANSWERED, FAILED, PAUSED }

@Serializable
data class IndexerCounts(
    /** Title searches that asked this indexer. */
    val searches: Int = 0,
    /** Searches it answered without a matching release. */
    val emptySearches: Int = 0,
    val failedSearches: Int = 0,
    /** Searches skipped because the indexer was paused by a limit. */
    val pausedSearches: Int = 0,
    /** Matching releases it returned. */
    val releases: Long = 0,
    /** HTTP requests sent to it: searches, further pages, capabilities and key checks. */
    val requests: Int = 0,
    val failedRequests: Int = 0,
    /** Requests refused for a limit: HTTP 429 or a spent quota. */
    val limitedRequests: Int = 0,
    /** Requests it answered, and their summed response time: the average is [responseMs] / [answered]. */
    val answered: Int = 0,
    val responseMs: Long = 0,
    /**
     * NZBs downloaded from it for an open that succeeded. One served from the NZB cache is not a
     * download, and neither is a failed open: a release that failed for good counts as [unavailable].
     */
    val grabs: Int = 0,
    /** Its releases that showed their first frame, and that failed for good. */
    val played: Int = 0,
    val unavailable: Int = 0
) {
    operator fun plus(other: IndexerCounts) = IndexerCounts(
        searches + other.searches, emptySearches + other.emptySearches, failedSearches + other.failedSearches,
        pausedSearches + other.pausedSearches, releases + other.releases, requests + other.requests,
        failedRequests + other.failedRequests, limitedRequests + other.limitedRequests, answered + other.answered,
        responseMs + other.responseMs, grabs + other.grabs, played + other.played, unavailable + other.unavailable)
}

/** Counts split by what they were for; only the parts with events exist. */
@Serializable
internal data class Split<T>(val movie: T? = null, val show: T? = null, val other: T? = null) {
    fun update(kind: ContentKind?, empty: T, change: (T) -> T): Split<T> = when (kind) {
        ContentKind.MOVIE -> copy(movie = change(movie ?: empty))
        ContentKind.SHOW -> copy(show = change(show ?: empty))
        null -> copy(other = change(other ?: empty))
    }

    fun select(content: StatsContent, plus: (T, T) -> T): T? = when (content) {
        StatsContent.ALL -> listOfNotNull(movie, show, other).reduceOrNull(plus)
        StatsContent.MOVIES -> movie
        StatsContent.SHOWS -> show
    }
}

/** One source's counts: per local day for the last [UsenetSourceStats.DAYS] days, and since it was first counted. */
@Serializable
internal data class SourceRecord<T>(
    val since: Long,
    val touchedAt: Long,
    val total: Split<T> = Split(),
    /** By local epoch day. */
    val days: Map<Long, Split<T>> = emptyMap()
)

@Serializable
internal data class StoredUsenetStats(val indexers: Map<String, SourceRecord<IndexerCounts>> = emptyMap())

/**
 * How each indexer did: searches, requests, NZB downloads, and how its releases played. Kept per
 * day for a month and in total, each split by movies and shows, so settings can show the last week,
 * the last month or everything. Sources are keyed by hashes ([UsenetIndexer.stateKey]): nothing
 * names an indexer or holds a key, and an edited indexer starts fresh.
 *
 * A search sends several requests to every indexer within a second, so counts are saved at most
 * every [SAVE_INTERVAL_MS]; [flush] saves the rest when the app leaves the foreground.
 */
class UsenetSourceStats internal constructor(
    private val storage: UsenetStatsStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Runs saves in order, off the calling thread. */
    private val background: (Runnable) -> Unit = WRITER::execute
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0)
    /** Increments on every change, so settings can show counts that searches update. */
    val revision: StateFlow<Int> = changes.asStateFlow()
    private val periodState = MutableStateFlow(StatsPeriod.LAST_30_DAYS)
    private val contentState = MutableStateFlow(StatsContent.ALL)
    /** The chosen period; read from storage on first use. */
    val period: StateFlow<StatsPeriod> = periodState.asStateFlow()
    val content: StateFlow<StatsContent> = contentState.asStateFlow()

    private var loaded: MutableMap<String, SourceRecord<IndexerCounts>>? = null
    private var loadedGeneration = 0
    private var dirty = false
    private var savedAt = Long.MIN_VALUE / 2

    /**
     * Which indexer listed a release, and what for. Memory only: release URLs carry API keys.
     * [Attribution.outcomeAt] keeps a replayed first frame from counting twice.
     */
    private class Attribution(val indexer: String, val kind: ContentKind?, var outcomeAt: Long = Long.MIN_VALUE / 2)
    private val attributions = object : LinkedHashMap<String, Attribution>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Attribution>) = size > MAX_ATTRIBUTIONS
    }

    /** Loaded on first use, and again after an account reset cleared the storage. */
    private val indexers: MutableMap<String, SourceRecord<IndexerCounts>>
        get() {
            loaded?.takeIf { loadedGeneration == storage.generation }?.let { return it }
            loadedGeneration = storage.generation
            dirty = false
            periodState.value = preference(PERIOD, StatsPeriod.LAST_30_DAYS)
            contentState.value = preference(CONTENT, StatsContent.ALL)
            return runCatching { json.decodeFromString<StoredUsenetStats>(storage.load() ?: "{}") }
                .getOrDefault(StoredUsenetStats()).indexers.toMutableMap().also { loaded = it }
        }

    private inline fun <reified E : Enum<E>> preference(name: String, default: E): E =
        storage.loadPreference(name)?.let { value -> enumValues<E>().firstOrNull { it.name == value } } ?: default

    /** Loads the counts and the chosen filters; settings call it off the main thread before reading. */
    @Synchronized
    fun load() { indexers }

    @Synchronized
    fun indexerRequest(key: String, kind: ContentKind?, outcome: RequestOutcome, elapsedMs: Long = 0) = count(key, kind) {
        when (outcome) {
            RequestOutcome.ANSWERED -> it.copy(requests = it.requests + 1, answered = it.answered + 1,
                responseMs = it.responseMs + elapsedMs.coerceAtLeast(0))
            RequestOutcome.FAILED -> it.copy(requests = it.requests + 1, failedRequests = it.failedRequests + 1)
            RequestOutcome.LIMITED -> it.copy(requests = it.requests + 1, limitedRequests = it.limitedRequests + 1)
        }
    }

    @Synchronized
    fun indexerSearch(key: String, kind: ContentKind, outcome: SearchOutcome, releases: Int = 0) = count(key, kind) {
        when (outcome) {
            SearchOutcome.ANSWERED -> it.copy(searches = it.searches + 1, releases = it.releases + releases.coerceAtLeast(0),
                emptySearches = it.emptySearches + if (releases <= 0) 1 else 0)
            SearchOutcome.FAILED -> it.copy(searches = it.searches + 1, failedSearches = it.failedSearches + 1)
            SearchOutcome.PAUSED -> it.copy(searches = it.searches + 1, pausedSearches = it.pausedSearches + 1)
        }
    }

    /** Remembers that [indexerKey] listed these releases, so their downloads and playback count for it. */
    @Synchronized
    fun remember(nzbUrls: Collection<String>, indexerKey: String, kind: ContentKind?) {
        nzbUrls.forEach { url ->
            if (url.isBlank()) return@forEach
            val known = attributions[url]
            // A release listed again keeps when its outcome last counted.
            attributions[url] = Attribution(indexerKey, kind, known?.outcomeAt ?: Long.MIN_VALUE / 2)
        }
    }

    /** An NZB was downloaded from the release's indexer. Ignored for a release no search listed. */
    @Synchronized
    fun grabbed(nzbUrl: String?) {
        val attribution = nzbUrl?.let { attributions[it] } ?: return
        count(attribution.indexer, attribution.kind) { it.copy(grabs = it.grabs + 1) }
    }

    /** The release showed its first frame. Counted once per release every [OUTCOME_INTERVAL_MS]. */
    @Synchronized
    fun played(nzbUrl: String?) = outcome(nzbUrl) { it.copy(played = it.played + 1) }

    /** The release failed for good. Counted once per release every [OUTCOME_INTERVAL_MS]. */
    @Synchronized
    fun unavailable(nzbUrl: String?) = outcome(nzbUrl) { it.copy(unavailable = it.unavailable + 1) }

    private fun outcome(nzbUrl: String?, change: (IndexerCounts) -> IndexerCounts) {
        val attribution = nzbUrl?.let { attributions[it] } ?: return
        // Player restarts and decoder switches show the first frame again.
        if (now() - attribution.outcomeAt < OUTCOME_INTERVAL_MS) return
        attribution.outcomeAt = now()
        count(attribution.indexer, attribution.kind, change)
    }

    @Synchronized
    fun indexer(key: String, period: StatsPeriod, content: StatsContent): IndexerCounts? {
        val record = indexers[key] ?: return null
        if (period == StatsPeriod.ALL_TIME) return record.total.select(content, IndexerCounts::plus)
        val first = today() - (if (period == StatsPeriod.LAST_7_DAYS) 7 else DAYS) + 1
        return record.days.filterKeys { it >= first }.values
            .mapNotNull { it.select(content, IndexerCounts::plus) }.reduceOrNull(IndexerCounts::plus)
    }

    @Synchronized
    fun indexerToday(key: String, content: StatsContent): IndexerCounts? =
        indexers[key]?.days?.get(today())?.select(content, IndexerCounts::plus)

    /** When the indexer was first counted, or since the last reset. */
    @Synchronized
    fun indexerSince(key: String): Long? = indexers[key]?.since

    @Synchronized
    fun setPeriod(period: StatsPeriod) {
        indexers
        if (periodState.value == period) return
        periodState.value = period
        storage.savePreference(PERIOD, period.name)
    }

    @Synchronized
    fun setContent(content: StatsContent) {
        indexers
        if (contentState.value == content) return
        contentState.value = content
        storage.savePreference(CONTENT, content.name)
    }

    /** Forgets every count at once; the chosen period and content stay. */
    @Synchronized
    fun reset() {
        indexers.clear()
        dirty = false
        // After any save already queued, so an older copy cannot come back.
        background(Runnable { storage.clear() })
        changes.value += 1
    }

    /** Saves counts that were held back. */
    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    private fun count(key: String, kind: ContentKind?, change: (IndexerCounts) -> IndexerCounts) {
        val at = now()
        val today = today()
        val records = indexers
        val old = records[key] ?: SourceRecord(since = at, touchedAt = at)
        val days = old.days.toMutableMap()
        days[today] = (days[today] ?: Split()).update(kind, IndexerCounts(), change)
        records[key] = old.copy(touchedAt = at, total = old.total.update(kind, IndexerCounts(), change), days = days)
        changes.value += 1
        dirty = true
        if (at - savedAt >= SAVE_INTERVAL_MS) save()
    }

    private fun save() {
        val records = indexers
        val at = now()
        val first = today() - DAYS + 1
        // Deleted or edited indexers stop being counted; forget them and bound the size.
        records.entries.removeAll { at - it.value.touchedAt > RETENTION_MS }
        while (records.size > MAX_SOURCES) records.remove(records.minBy { it.value.touchedAt }.key)
        records.replaceAll { _, record ->
            if (record.days.keys.all { it >= first }) record else record.copy(days = record.days.filterKeys { it >= first })
        }
        val snapshot = StoredUsenetStats(records.toMap())
        dirty = false
        savedAt = at
        background(Runnable { storage.save(json.encodeToString(snapshot)) })
    }

    private fun today(): Long = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()

    companion object {
        /** How many days are kept per day. */
        const val DAYS = 30
        internal const val SAVE_INTERVAL_MS = 5_000L
        internal const val OUTCOME_INTERVAL_MS = 12L * 3600 * 1000
        internal const val RETENTION_MS = 180L * 24 * 3600 * 1000
        internal const val MAX_SOURCES = 32
        private const val MAX_ATTRIBUTIONS = 4000
        private const val PERIOD = "period"
        private const val CONTENT = "content"
        private val WRITER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "usenet-stats").apply { isDaemon = true }
        }

        @Volatile private var storage: SharedPreferencesUsenetStatsStorage? = null
        @Volatile private var instance: UsenetSourceStats? = null

        fun storage(context: Context): SharedPreferencesUsenetStatsStorage = storage ?: synchronized(this) {
            storage ?: SharedPreferencesUsenetStatsStorage(context.applicationContext).also { storage = it }
        }

        fun get(context: Context): UsenetSourceStats = instance ?: synchronized(this) {
            instance ?: UsenetSourceStats(storage(context)).also { instance = it }
        }
    }
}
