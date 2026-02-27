package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistorySeasonAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryShowAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryMovieAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryEpisodeRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistorySeasonRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryShowRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowSeasonProgressDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktProgressService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore
) {
    companion object {
        private const val TAG = "TraktProgressSvc"
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    data class TraktCachedStats(
        val moviesWatched: Int = 0,
        val showsWatched: Int = 0,
        val episodesWatched: Int = 0,
        val totalWatchedHours: Int = 0
    )

    private data class TimedCache<T>(
        val value: T,
        val updatedAtMs: Long
    )

    private data class EpisodeProgressCacheEntry(
        val progress: Map<Pair<Int, Int>, WatchProgress>,
        val updatedAtMs: Long,
        val activityVersion: Long,
        val hasCompletedSnapshot: Boolean
    )

    private data class EpisodeProgressFetchResult(
        val progress: Map<Pair<Int, Int>, WatchProgress>,
        val hasCompletedSnapshot: Boolean
    )

    private data class OptimisticProgressEntry(
        val progress: WatchProgress,
        val expiresAtMs: Long
    )

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in TraktProgressService scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val episodeVideoIdCache = mutableMapOf<String, String>()
    private val remoteProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
    private val optimisticProgress = MutableStateFlow<Map<String, OptimisticProgressEntry>>(emptyMap())
    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val watchedMoviesState = MutableStateFlow<Set<String>>(emptySet())
    private val episodeProgressState = MutableStateFlow<Map<String, EpisodeProgressCacheEntry>>(emptyMap())
    private val hasLoadedRemoteProgress = MutableStateFlow(false)
    private val cacheMutex = Mutex()
    private val metadataMutex = Mutex()
    private val watchedMoviesMutex = Mutex()
    private val episodeProgressMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val inFlightEpisodeProgressKeys = mutableSetOf<String>()
    private val episodeProgressLastAttemptAtMs = mutableMapOf<String, Long>()
    private var cachedMoviesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null
    private var cachedEpisodesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null
    private var cachedUserStats: TimedCache<TraktCachedStats>? = null
    private var forceRefreshUntilMs: Long = 0L
    private var watchedMoviesUpdatedAtMs: Long = 0L
    private var watchedMoviesLastAttemptAtMs: Long = 0L
    private var hasLoadedWatchedMovies: Boolean = false
    private var watchedMoviesStale: Boolean = true
    @Volatile
    private var lastFastSyncRequestMs: Long = 0L
    @Volatile
    private var lastKnownActivityFingerprint: String? = null
    @Volatile
    private var lastKnownMoviesWatchedAt: String? = null
    @Volatile
    private var lastKnownEpisodeActivityFingerprint: String? = null
    @Volatile
    private var lastManualRefreshSignalMs: Long = 0L
    private val episodeProgressActivityVersion = AtomicLong(0L)

    private val playbackCacheTtlMs = 30_000L
    private val userStatsCacheTtlMs = 60_000L
    private val watchedMoviesCacheTtlMs = 10 * 60_000L
    private val watchedMoviesFetchThrottleMs = 15_000L
    private val episodeProgressCacheTtlMs = 5 * 60_000L
    private val episodeProgressFetchThrottleMs = 15_000L
    private val optimisticTtlMs = 3 * 60_000L
    private val maxRecentEpisodeHistoryEntries = 300
    private val metadataHydrationLimit = 30
    private val fastSyncThrottleMs = 3_000L
    private val manualRefreshSignalThrottleMs = 2_000L
    private val baseRefreshIntervalMs = 60_000L
    private val maxRefreshIntervalMs = 10 * 60_000L
    @Volatile
    private var refreshIntervalMs = baseRefreshIntervalMs
    @Volatile
    private var consecutiveRefreshFailures = 0
    @Volatile
    private var continueWatchingWindowDays: Int = TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP

    init {
        scope.launch {
            traktSettingsDataStore.continueWatchingDaysCap.collectLatest { days ->
                continueWatchingWindowDays = days
            }
        }
        scope.launch {
            refreshEvents().collectLatest {
                val success = try {
                    refreshRemoteSnapshot()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh remote snapshot", e)
                    false
                }
                updateRefreshBackoff(success)
            }
        }
    }

    private fun isAllHistoryWindow(): Boolean {
        return continueWatchingWindowDays == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL
    }

    private fun recentWatchWindowMs(): Long? {
        if (isAllHistoryWindow()) return null
        return continueWatchingWindowDays.toLong() * 24L * 60L * 60L * 1000L
    }

    suspend fun refreshNow() {
        val now = System.currentTimeMillis()
        forceRefreshUntilMs = now + 30_000L
        if (now - lastManualRefreshSignalMs < manualRefreshSignalThrottleMs) {
            trace("refreshNow: suppressed duplicate signal (${now - lastManualRefreshSignalMs}ms since last)")
            return
        }
        lastManualRefreshSignalMs = now
        trace("refreshNow: emitting signal, force window active for 30s")
        refreshSignals.emit(Unit)
    }

    suspend fun getCachedStats(forceRefresh: Boolean = false): TraktCachedStats? {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = cachedUserStats
            if (!forceRefresh && cached != null && now - cached.updatedAtMs <= userStatsCacheTtlMs) {
                return cached.value
            }
        }

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserStats(authorization = authHeader, id = "me")
        } ?: return null

        if (!response.isSuccessful) return null
        val body = response.body() ?: return null

        val totalMinutes = (body.movies?.minutes ?: 0) + (body.episodes?.minutes ?: 0)
        val stats = TraktCachedStats(
            moviesWatched = body.movies?.watched ?: 0,
            showsWatched = body.shows?.watched ?: 0,
            episodesWatched = body.episodes?.watched ?: 0,
            totalWatchedHours = totalMinutes / 60
        )

        cacheMutex.withLock {
            cachedUserStats = TimedCache(value = stats, updatedAtMs = now)
        }
        return stats
    }

    fun applyOptimisticProgress(progress: WatchProgress) {
        val now = System.currentTimeMillis()
        val derivedPercent = when {
            progress.progressPercent != null -> progress.progressPercent
            progress.duration > 0L -> ((progress.position.toFloat() / progress.duration.toFloat()) * 100f)
            else -> null
        }?.coerceIn(0f, 100f)

        val optimistic = progress.copy(
            progressPercent = derivedPercent,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            lastWatched = now
        )

        optimisticProgress.update { current ->
            current.toMutableMap().apply {
                this[progressKey(optimistic)] = OptimisticProgressEntry(
                    progress = optimistic,
                    expiresAtMs = now + optimisticTtlMs
                )
            }
        }
        requestFastSync()
    }

    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        val contentKeyPrefix = contentId.trim()
        optimisticProgress.update { current ->
            current.filterKeys { key ->
                if (season != null && episode != null) {
                    key != "${contentKeyPrefix}_s${season}e${episode}"
                } else {
                    key != contentKeyPrefix && !key.startsWith("${contentKeyPrefix}_s")
                }
            }
        }
        requestFastSync()
    }

    fun clearOptimistic() {
        optimisticProgress.value = emptyMap()
    }

    fun observeAllProgress(): Flow<List<WatchProgress>> {
        return combine(
            remoteProgress,
            optimisticProgress,
            metadataState,
            hasLoadedRemoteProgress
        ) { remote, optimistic, metadata, loaded ->
            val now = System.currentTimeMillis()
            val validOptimistic = optimistic
                .filterValues { it.expiresAtMs > now }
                .mapValues { it.value.progress }

            // Avoid emitting a transient empty state before first remote fetch completes.
            if (!loaded && remote.isEmpty() && validOptimistic.isEmpty()) {
                return@combine null
            }

            val mergedByKey = linkedMapOf<String, WatchProgress>()
            remote.forEach { mergedByKey[progressKey(it)] = it }
            validOptimistic.forEach { (key, value) -> mergedByKey[key] = value }
            mergedByKey.values
                .map { enrichWithMetadata(it, metadata) }
                .sortedByDescending { it.lastWatched }
        }
            .filterNotNull()
            .distinctUntilChanged()
    }

    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val cacheKey = canonicalLookupKey(contentId)
        return episodeProgressState
            .map { state -> state[cacheKey]?.progress ?: emptyMap() }
            .onStart {
                scope.launch {
                    ensureEpisodeProgressSnapshot(contentId = cacheKey, forceRefresh = false)
                }
            }
            .distinctUntilChanged()
    }

    fun observeMovieWatched(contentId: String): Flow<Boolean> {
        val rawKey = contentId.trim()
        val canonicalKey = canonicalLookupKey(rawKey)
        return combine(watchedMoviesState, optimisticProgress) { watchedMovies, optimistic ->
            val optimisticEntry = optimistic[rawKey]?.progress
                ?: optimistic[canonicalKey]?.progress
            when {
                optimisticEntry?.isCompleted() == true -> true
                optimisticEntry?.isInProgress() == true -> false
                else -> watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey)
            }
        }
            .onStart { isMovieWatched(rawKey) }
            .distinctUntilChanged()
    }

    suspend fun markAsWatched(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ) {
        val body = buildHistoryAddRequest(progress, title, year)
            ?: throw IllegalStateException("Insufficient Trakt IDs to mark watched")

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addHistory(authHeader, body)
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful || !hasSuccessfulHistoryAdd(response.body())) {
            throw IllegalStateException("Failed to mark watched on Trakt (${response.code()})")
        }

        if (progress.contentType.equals("movie", ignoreCase = true)) {
            setMovieWatchedInCache(progress.contentId, watched = true)
        } else if (
            progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)
        ) {
            invalidateEpisodeProgressCache(progress.contentId)
        }
        refreshNow()
    }

    suspend fun isMovieWatched(contentId: String): Boolean {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return false
        val canonicalKey = canonicalLookupKey(rawKey)
        val optimistic = optimisticProgress.value[rawKey]?.progress
            ?: optimisticProgress.value[canonicalKey]?.progress
        if (optimistic?.isCompleted() == true) return true

        val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
        return watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey)
    }

    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        Log.d(
            TAG,
            "removeProgress start contentId=$contentId season=$season episode=$episode"
        )
        applyOptimisticRemoval(contentId, season, episode)
        val playbackMovies = getPlayback("movies", force = true)
        val playbackEpisodes = getPlayback("episodes", force = true)

        val target = contentId.trim()
        playbackMovies
            .filter { normalizeContentId(it.movie?.ids) == target }
            .forEach { item ->
                item.id?.let { playbackId ->
                    Log.d(TAG, "removeProgress deleting movie playbackId=$playbackId")
                    traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                        traktApi.deletePlayback(authHeader, playbackId)
                    }
                }
            }

        playbackEpisodes
            .filter { item ->
                val sameContent = normalizeContentId(item.show?.ids) == target
                val sameEpisode = if (season != null && episode != null) {
                    item.episode?.season == season && item.episode.number == episode
                } else {
                    true
                }
                sameContent && sameEpisode
            }
            .forEach { item ->
                item.id?.let { playbackId ->
                    Log.d(
                        TAG,
                        "removeProgress deleting episode playbackId=$playbackId s=${item.episode?.season} e=${item.episode?.number}"
                    )
                    traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                        traktApi.deletePlayback(authHeader, playbackId)
                    }
                }
            }

        Log.d(TAG, "removeProgress refreshNow contentId=$contentId")
        refreshNow()
    }

    suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        applyOptimisticRemoval(contentId, season, episode)

        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        if (!ids.hasAnyId()) {
            refreshNow()
            return
        }

        val likelySeries = season != null && episode != null

        val removeBody = if (likelySeries) {
            TraktHistoryRemoveRequestDto(
                shows = listOf(
                    TraktHistoryShowRemoveDto(
                        ids = ids,
                        seasons = listOf(
                            TraktHistorySeasonRemoveDto(
                                number = season,
                                episodes = listOf(TraktHistoryEpisodeRemoveDto(number = episode))
                            )
                        )
                    )
                )
            )
        } else {
            TraktHistoryRemoveRequestDto(
                movies = listOf(TraktMovieDto(ids = ids))
            )
        }

        traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.removeHistory(authHeader, removeBody)
        }

        if (!likelySeries) {
            setMovieWatchedInCache(
                contentId = normalizeContentId(ids = ids, fallback = contentId.trim()),
                watched = false
            )
        } else {
            invalidateEpisodeProgressCache(contentId)
        }
        refreshNow()
    }

    private fun refreshTicker(): Flow<Unit> = flow {
        while (true) {
            delay(refreshIntervalMs)
            emit(Unit)
        }
    }

    private fun refreshEvents(): Flow<Unit> {
        return merge(refreshTicker(), refreshSignals).onStart { emit(Unit) }
    }

    private fun updateRefreshBackoff(success: Boolean) {
        if (success) {
            if (consecutiveRefreshFailures > 0 || refreshIntervalMs != baseRefreshIntervalMs) {
                Log.d(TAG, "Refresh recovered. Resetting Trakt poll interval to ${baseRefreshIntervalMs}ms")
            }
            consecutiveRefreshFailures = 0
            refreshIntervalMs = baseRefreshIntervalMs
            return
        }

        consecutiveRefreshFailures += 1
        val nextInterval = (baseRefreshIntervalMs shl (consecutiveRefreshFailures - 1))
            .coerceAtMost(maxRefreshIntervalMs)
        if (nextInterval != refreshIntervalMs) {
            Log.w(
                TAG,
                "Refresh failed $consecutiveRefreshFailures time(s). Backing off Trakt poll interval to ${nextInterval}ms"
            )
        }
        refreshIntervalMs = nextInterval
    }

    private suspend fun refreshRemoteSnapshot() {
        val force = System.currentTimeMillis() < forceRefreshUntilMs

        if (!force && !hasActivityChanged()) return

        if (watchedMoviesStale && hasLoadedWatchedMovies) {
            getWatchedMoviesSnapshot(forceRefresh = true)
        }

        val snapshot = fetchAllProgressSnapshot(force = force)
        remoteProgress.value = snapshot
        hasLoadedRemoteProgress.value = true
        reconcileOptimistic(snapshot)
        hydrateMetadata(snapshot)
    }

    private suspend fun hasActivityChanged(): Boolean {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getLastActivities(authHeader)
        } ?: return !hasLoadedRemoteProgress.value
        if (!response.isSuccessful) return !hasLoadedRemoteProgress.value

        val activities = response.body() ?: return true
        val moviesWatchedAt = activities.movies?.watchedAt
        if (moviesWatchedAt != lastKnownMoviesWatchedAt) {
            watchedMoviesStale = true
            lastKnownMoviesWatchedAt = moviesWatchedAt
            trace("last_activities: movies.watched_at changed -> mark watched-movies cache stale")
        }
        val episodeFingerprint = listOfNotNull(
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt
        ).joinToString("|")
        if (episodeFingerprint != lastKnownEpisodeActivityFingerprint) {
            lastKnownEpisodeActivityFingerprint = episodeFingerprint
            val version = episodeProgressActivityVersion.incrementAndGet()
            trace("last_activities: episodes changed -> show-progress cache version=$version")
        }
        val fingerprint = listOfNotNull(
            activities.movies?.pausedAt,
            activities.movies?.watchedAt,
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt
        ).joinToString("|")

        val changed = fingerprint != lastKnownActivityFingerprint
        lastKnownActivityFingerprint = fingerprint
        if (changed) {
            trace("last_activities: fingerprint changed")
        }
        return changed
    }

    private suspend fun invalidateEpisodeProgressCache(contentId: String) {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return
        val canonicalKey = canonicalLookupKey(rawKey)
        val keys = setOf(rawKey, canonicalKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) return

        episodeProgressState.update { current ->
            current.toMutableMap().apply {
                keys.forEach { remove(it) }
            }
        }
        episodeProgressMutex.withLock {
            keys.forEach { key ->
                episodeProgressLastAttemptAtMs.remove(key)
                inFlightEpisodeProgressKeys.remove(key)
            }
        }
        trace("episode-progress cache invalidated: keys=${keys.joinToString()}")
    }

    private suspend fun ensureEpisodeProgressSnapshot(
        contentId: String,
        forceRefresh: Boolean
    ): Map<Pair<Int, Int>, WatchProgress> {
        val cacheKey = canonicalLookupKey(contentId)
        val now = System.currentTimeMillis()

        var cachedEntry: EpisodeProgressCacheEntry? = null
        var shouldFetch = false

        episodeProgressMutex.withLock {
            val existing = episodeProgressState.value[cacheKey]
            cachedEntry = existing
            if (!forceRefresh && isEpisodeProgressCacheFresh(existing, now)) {
                trace("episode-progress cache hit: show=$cacheKey episodes=${existing?.progress?.size ?: 0}")
                return@withLock
            }

            val lastAttempt = episodeProgressLastAttemptAtMs[cacheKey] ?: 0L
            if (!forceRefresh && now - lastAttempt < episodeProgressFetchThrottleMs) {
                trace("episode-progress fetch throttled: show=$cacheKey delta=${now - lastAttempt}ms")
                return@withLock
            }

            if (!inFlightEpisodeProgressKeys.add(cacheKey)) {
                trace("episode-progress fetch already in-flight: show=$cacheKey")
                return@withLock
            }

            episodeProgressLastAttemptAtMs[cacheKey] = now
            shouldFetch = true
        }

        if (!shouldFetch) {
            return cachedEntry?.progress ?: episodeProgressState.value[cacheKey]?.progress.orEmpty()
        }

        return try {
            trace("episode-progress fetch: show=$cacheKey force=$forceRefresh")
            val result = fetchEpisodeProgressSnapshot(contentId = cacheKey)
            val fetchedAt = System.currentTimeMillis()
            val activityVersion = episodeProgressActivityVersion.get()
            episodeProgressState.update { current ->
                current + (
                    cacheKey to EpisodeProgressCacheEntry(
                        progress = result.progress,
                        updatedAtMs = fetchedAt,
                        activityVersion = activityVersion,
                        hasCompletedSnapshot = result.hasCompletedSnapshot
                    )
                )
            }
            trace(
                "episode-progress cache refreshed: show=$cacheKey episodes=${result.progress.size} full=${result.hasCompletedSnapshot}"
            )
            result.progress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch episode progress for show=$cacheKey", e)
            episodeProgressState.value[cacheKey]?.progress ?: cachedEntry?.progress.orEmpty()
        } finally {
            episodeProgressMutex.withLock {
                inFlightEpisodeProgressKeys.remove(cacheKey)
            }
        }
    }

    private fun isEpisodeProgressCacheFresh(
        entry: EpisodeProgressCacheEntry?,
        now: Long
    ): Boolean {
        if (entry == null) return false
        if (!entry.hasCompletedSnapshot) return false
        if (entry.activityVersion != episodeProgressActivityVersion.get()) return false
        return now - entry.updatedAtMs <= episodeProgressCacheTtlMs
    }

    private suspend fun getWatchedMoviesSnapshot(forceRefresh: Boolean): Set<String> {
        val now = System.currentTimeMillis()
        return watchedMoviesMutex.withLock {
            val hasFreshCache = hasLoadedWatchedMovies &&
                !watchedMoviesStale &&
                now - watchedMoviesUpdatedAtMs <= watchedMoviesCacheTtlMs
            if (!forceRefresh && hasFreshCache) {
                trace("watched-movies cache hit: size=${watchedMoviesState.value.size}")
                return@withLock watchedMoviesState.value
            }
            if (!forceRefresh && now - watchedMoviesLastAttemptAtMs < watchedMoviesFetchThrottleMs) {
                trace("watched-movies fetch throttled: ${now - watchedMoviesLastAttemptAtMs}ms since last attempt")
                return@withLock watchedMoviesState.value
            }

            watchedMoviesLastAttemptAtMs = now
            trace("watched-movies fetch: requesting /sync/watched/movies")
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatched(
                    authorization = authHeader,
                    type = "movies"
                )
            } ?: run {
                trace("watched-movies fetch: request returned null (network/auth failure)")
                return@withLock watchedMoviesState.value
            }

            if (!response.isSuccessful) {
                trace("watched-movies fetch: non-success code=${response.code()}")
                return@withLock watchedMoviesState.value
            }

            val watchedMovies = response.body().orEmpty()
                .flatMap { item ->
                    watchedMovieLookupKeys(item.movie?.ids)
                }
                .toSet()

            watchedMoviesState.value = watchedMovies
            watchedMoviesUpdatedAtMs = System.currentTimeMillis()
            hasLoadedWatchedMovies = true
            watchedMoviesStale = false
            trace("watched-movies cache refreshed: size=${watchedMovies.size}")
            watchedMovies
        }
    }

    private suspend fun setMovieWatchedInCache(contentId: String, watched: Boolean) {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return
        val keys = setOf(rawKey, canonicalLookupKey(rawKey)).filter { it.isNotBlank() }
        if (keys.isEmpty()) return
        watchedMoviesMutex.withLock {
            val updated = watchedMoviesState.value.toMutableSet()
            if (watched) {
                updated.addAll(keys)
            } else {
                updated.removeAll(keys)
            }
            watchedMoviesState.value = updated
            watchedMoviesUpdatedAtMs = System.currentTimeMillis()
            hasLoadedWatchedMovies = true
            watchedMoviesStale = false
            trace("watched-movies cache optimistic update: watched=$watched keys=${keys.joinToString()}")
        }
    }

    private fun canonicalLookupKey(contentId: String): String {
        val parsed = parseContentIds(contentId)
        val canonical = normalizeContentId(toTraktIds(parsed))
        return if (canonical.isNotBlank()) canonical else contentId.trim()
    }

    private fun watchedMovieLookupKeys(ids: TraktIdsDto?): List<String> {
        if (ids == null) return emptyList()
        return buildList {
            ids.imdb?.takeIf { it.isNotBlank() }?.let { add(it) }
            ids.tmdb?.let { add("tmdb:$it") }
            ids.trakt?.let { add("trakt:$it") }
            ids.slug?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private suspend fun fetchAllProgressSnapshot(force: Boolean = false): List<WatchProgress> {
        val recentCompletedEpisodes = fetchRecentEpisodeHistorySnapshot()
        val playbackStartAt = recentWatchWindowMs()?.let { windowMs ->
            toTraktUtcDateTime(System.currentTimeMillis() - windowMs)
        }
        val inProgressMovies = getPlayback("movies", force = force, startAt = playbackStartAt).mapNotNull { mapPlaybackMovie(it) }
        val inProgressEpisodes = getPlayback("episodes", force = force, startAt = playbackStartAt).mapNotNull { mapPlaybackEpisode(it) }

        val mergedByKey = linkedMapOf<String, WatchProgress>()

        recentCompletedEpisodes
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        (inProgressMovies + inProgressEpisodes)
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        return mergedByKey.values.sortedByDescending { it.lastWatched }
    }

    private suspend fun fetchRecentEpisodeHistorySnapshot(): List<WatchProgress> {
        val cutoffMs = recentWatchWindowMs()?.let { windowMs ->
            System.currentTimeMillis() - windowMs
        }
        val results = linkedMapOf<String, WatchProgress>()
        var page = 1
        val pageLimit = 100
        val maxPages = if (isAllHistoryWindow()) 20 else 5

        while (page <= maxPages) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getEpisodeHistory(
                    authorization = authHeader,
                    page = page,
                    limit = pageLimit,
                    startAt = cutoffMs?.let(::toTraktUtcDateTime)
                )
            } ?: break

            if (!response.isSuccessful) break
            val items = response.body().orEmpty()
            if (items.isEmpty()) break

            var shouldStop = false
            items.forEach { item ->
                val mapped = mapEpisodeHistoryItem(item) ?: return@forEach
                if (cutoffMs != null && mapped.lastWatched < cutoffMs) {
                    shouldStop = true
                    return@forEach
                }
                results.putIfAbsent(mapped.contentId, mapped)
                if (results.size >= maxRecentEpisodeHistoryEntries) {
                    shouldStop = true
                    return@forEach
                }
            }

            val pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (items.size < pageLimit || shouldStop || (pageCount != null && page >= pageCount)) break
            page += 1
        }

        return results.values.toList()
    }

    private fun mapEpisodeHistoryItem(item: TraktUserEpisodeHistoryItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null

        val lastWatched = parseIsoToMillis(item.watchedAt)
        // Avoid expensive metadata lookups for each history row.
        val videoId = "$contentId:$season:$number"

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private suspend fun fetchEpisodeProgressSnapshot(
        contentId: String
    ): EpisodeProgressFetchResult {
        val pathId = toTraktPathId(contentId)
        val completed = mutableMapOf<Pair<Int, Int>, WatchProgress>()
        var hasCompletedSnapshot = false

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getShowProgressWatched(
                authorization = authHeader,
                id = pathId
            )
        }

        if (response?.isSuccessful == true) {
            hasCompletedSnapshot = true
            val seasons = response.body()?.seasons.orEmpty()
            seasons.forEach { season ->
                mapSeasonProgress(contentId, season).forEach { progress ->
                    val seasonNum = progress.season ?: return@forEach
                    val episodeNum = progress.episode ?: return@forEach
                    completed[seasonNum to episodeNum] = progress
                }
            }
        }

        val inProgress = getPlayback(
            type = "episodes"
        )
            .mapNotNull { mapPlaybackEpisode(it) }
            .filter { it.contentId == contentId }

        inProgress.forEach { progress ->
            val seasonNum = progress.season ?: return@forEach
            val episodeNum = progress.episode ?: return@forEach
            completed[seasonNum to episodeNum] = progress
        }

        return EpisodeProgressFetchResult(
            progress = completed,
            hasCompletedSnapshot = hasCompletedSnapshot
        )
    }

    private suspend fun getPlayback(
        type: String,
        force: Boolean = false,
        startAt: String? = null,
        endAt: String? = null
    ): List<TraktPlaybackItemDto> {
        val now = System.currentTimeMillis()
        if (startAt == null && endAt == null) {
            cacheMutex.withLock {
                val cache = when (type) {
                    "movies" -> cachedMoviesPlayback
                    "episodes" -> cachedEpisodesPlayback
                    else -> null
                }
                if (!force && cache != null && now - cache.updatedAtMs <= playbackCacheTtlMs) {
                    return cache.value
                }
            }
        }

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getPlayback(
                authorization = authHeader,
                type = type,
                startAt = startAt,
                endAt = endAt
            )
        } ?: return emptyList()

        val value = if (response.isSuccessful) response.body().orEmpty() else emptyList()
        if (startAt == null && endAt == null) {
            cacheMutex.withLock {
                val timed = TimedCache(value = value, updatedAtMs = now)
                when (type) {
                    "movies" -> cachedMoviesPlayback = timed
                    "episodes" -> cachedEpisodesPlayback = timed
                }
            }
        }
        return value
    }

    private suspend fun mapPlaybackMovie(item: TraktPlaybackItemDto): WatchProgress? {
        val movie = item.movie ?: return null
        val contentId = normalizeContentId(movie.ids)
        if (contentId.isBlank()) return null

        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = movie.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktMovieId = movie.ids?.trakt
        )
    }

    private suspend fun mapPlaybackEpisode(item: TraktPlaybackItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null
        val videoId = resolveEpisodeVideoId(contentId, season, number)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private fun mapSeasonProgress(
        contentId: String,
        season: TraktShowSeasonProgressDto
    ): List<WatchProgress> {
        val seasonNumber = season.number ?: return emptyList()
        return season.episodes.orEmpty()
            .filter { it.completed == true }
            .mapNotNull { episode ->
                val episodeNumber = episode.number ?: return@mapNotNull null
                WatchProgress(
                    contentId = contentId,
                    contentType = "series",
                    name = contentId,
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$contentId:$seasonNumber:$episodeNumber",
                    season = seasonNumber,
                    episode = episodeNumber,
                    episodeTitle = null,
                    position = 1L,
                    duration = 1L,
                    lastWatched = parseIsoToMillis(episode.lastWatchedAt),
                    progressPercent = 100f,
                    source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
                )
            }
    }

    private suspend fun resolveEpisodeVideoId(
        contentId: String,
        season: Int,
        episode: Int
    ): String {
        val key = "$contentId:$season:$episode"
        episodeVideoIdCache[key]?.let { return it }

        val candidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (candidate in candidates) {
            for (type in listOf("series", "tv")) {
                val result = withTimeoutOrNull(2500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidate)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val videoId = meta.videos.firstOrNull {
                    it.season == season && it.episode == episode
                }?.id

                if (!videoId.isNullOrBlank()) {
                    episodeVideoIdCache[key] = videoId
                    return videoId
                }
            }
        }

        return "$contentId:$season:$episode"
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun hasSuccessfulHistoryAdd(body: TraktHistoryAddResponseDto?): Boolean {
        val added = body?.added ?: return false
        val addedCount = (added.movies ?: 0) +
            (added.episodes ?: 0) +
            (added.shows ?: 0) +
            (added.seasons ?: 0)
        return addedCount > 0
    }

    private fun buildHistoryAddRequest(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ): TraktHistoryAddRequestDto? {
        val ids = resolveHistoryIds(progress)
        if (!ids.hasAnyId()) return null
        val watchedAt = toTraktUtcDateTime(progress.lastWatched)

        val normalizedType = progress.contentType.lowercase()
        val isEpisode = normalizedType in listOf("series", "tv") &&
            progress.season != null && progress.episode != null

        return if (isEpisode) {
            TraktHistoryAddRequestDto(
                shows = listOf(
                    TraktHistoryShowAddDto(
                        title = title,
                        year = year,
                        ids = ids,
                        seasons = listOf(
                            TraktHistorySeasonAddDto(
                                number = progress.season,
                                episodes = listOf(
                                    TraktHistoryEpisodeAddDto(
                                        number = progress.episode,
                                        watchedAt = watchedAt
                                    )
                                )
                            )
                        )
                    )
                )
            )
        } else {
            TraktHistoryAddRequestDto(
                movies = listOf(
                    TraktHistoryMovieAddDto(
                        title = title,
                        year = year,
                        ids = ids,
                        watchedAt = watchedAt
                    )
                )
            )
        }
    }

    private fun resolveHistoryIds(progress: WatchProgress): TraktIdsDto {
        val contentIds = toTraktIds(parseContentIds(progress.contentId))
        if (contentIds.hasAnyId()) return contentIds

        val videoIds = toTraktIds(parseContentIds(progress.videoId))
        if (videoIds.hasAnyId()) return videoIds

        return contentIds
    }

    private fun toTraktUtcDateTime(lastWatchedMs: Long): String {
        val safeMs = if (lastWatchedMs > 0L) lastWatchedMs else System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(safeMs))
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    private fun reconcileOptimistic(remote: List<WatchProgress>) {
        val remoteByKey = remote.associateBy { progressKey(it) }
        val now = System.currentTimeMillis()
        optimisticProgress.update { current ->
            current.filter { (key, entry) ->
                if (entry.expiresAtMs <= now) return@filter false
                val remoteProgress = remoteByKey[key] ?: return@filter true
                val closeEnough = abs(remoteProgress.progressPercentage - entry.progress.progressPercentage) <= 0.03f
                val remoteNewer = remoteProgress.lastWatched >= entry.progress.lastWatched - 1_000L
                !(closeEnough && remoteNewer)
            }
        }
    }

    private fun requestFastSync() {
        val now = System.currentTimeMillis()
        if (now - lastFastSyncRequestMs < fastSyncThrottleMs) return
        lastFastSyncRequestMs = now
        forceRefreshUntilMs = now + 30_000L
        refreshSignals.tryEmit(Unit)
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            scope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.background,
                    logo = meta.logo,
                    episodes = episodes
                )
            }
        }
        return null
    }
}
