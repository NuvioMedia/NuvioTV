package com.nuvio.tv.data.repository

import android.os.SystemClock
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
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import java.io.IOException
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
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktEpisodeMappingService: TraktEpisodeMappingService
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
        val airedEpisodes: List<Pair<Int, Int>>,
        val updatedAtMs: Long,
        val activityVersion: Long,
        val hasCompletedSnapshot: Boolean
    )

    private data class EpisodeProgressFetchResult(
        val progress: Map<Pair<Int, Int>, WatchProgress>,
        val airedEpisodes: List<Pair<Int, Int>>,
        val hasCompletedSnapshot: Boolean
    )

    private data class EpisodeHistoryAddAttempt(
        val response: Response<TraktHistoryAddResponseDto>,
        val remappedEpisode: EpisodeMappingEntry? = null
    )

    private data class WatchedShowSeedsSnapshot(
        val seeds: List<WatchProgress>,
        val updatedAtMs: Long,
        val hasLoaded: Boolean,
        val stale: Boolean
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
    private val watchedShowSeedsState = MutableStateFlow<List<WatchProgress>>(emptyList())
    private val episodeProgressState = MutableStateFlow<Map<String, EpisodeProgressCacheEntry>>(emptyMap())
    private val hasLoadedRemoteProgress = MutableStateFlow(false)
    private val cacheMutex = Mutex()
    private val metadataMutex = Mutex()
    private val watchedMoviesMutex = Mutex()
    private val watchedShowSeedsMutex = Mutex()
    private val episodeProgressMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val inFlightEpisodeProgressKeys = mutableSetOf<String>()
    private val episodeProgressLastAttemptAtMs = mutableMapOf<String, Long>()
    private val serviceStartedAtMs = SystemClock.elapsedRealtime()
    private var cachedMoviesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null
    private var cachedEpisodesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null
    private var cachedUserStats: TimedCache<TraktCachedStats>? = null
    private var forceRefreshUntilMs: Long = 0L
    private var watchedMoviesUpdatedAtMs: Long = 0L
    private var watchedMoviesLastAttemptAtMs: Long = 0L
    private var hasLoadedWatchedMovies: Boolean = false
    private var watchedMoviesStale: Boolean = true
    private var watchedShowSeedsUpdatedAtMs: Long = 0L
    private var watchedShowSeedsLastAttemptAtMs: Long = 0L
    private var hasLoadedWatchedShowSeeds: Boolean = false
    private var watchedShowSeedsStale: Boolean = true
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
    @Volatile
    private var metadataWarmupScheduled: Boolean = false
    private val episodeProgressActivityVersion = AtomicLong(0L)

    private val playbackCacheTtlMs = 30_000L
    private val userStatsCacheTtlMs = Long.MAX_VALUE
    private val watchedMoviesCacheTtlMs = 10 * 60_000L
    private val watchedMoviesFetchThrottleMs = 15_000L
    private val episodeProgressCacheTtlMs = 5 * 60_000L
    private val episodeProgressFetchThrottleMs = 15_000L
    private val optimisticTtlMs = 3 * 60_000L
    private val initialMetadataHydrationDelayMs = 3_000L
    private val maxRecentEpisodeHistoryEntries = 300
    private val metadataHydrationLimit = 110
    private val metadataFetchSemaphore = Semaphore(5)
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

    fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return hasLoadedRemoteProgress
    }

    fun observeWatchedShowSeeds(): Flow<List<WatchProgress>> {
        return watchedShowSeedsState
            .onStart {
                emit(getWatchedShowSeedsSnapshot(forceRefresh = false))
            }
            .distinctUntilChanged()
    }

    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val cacheKey = canonicalLookupKey(contentId)
        return combine(episodeProgressState, optimisticProgress) { state, optimistic ->
            val now = System.currentTimeMillis()
            val merged = (state[cacheKey]?.progress ?: emptyMap()).toMutableMap()
            optimistic.values
                .asSequence()
                .filter { it.expiresAtMs > now }
                .map { it.progress }
                .filter { progress ->
                    progress.season != null &&
                        progress.episode != null &&
                        canonicalLookupKey(progress.contentId) == cacheKey
                }
                .forEach { progress ->
                    val season = progress.season ?: return@forEach
                    val episode = progress.episode ?: return@forEach
                    merged[season to episode] = progress
                }
            merged as Map<Pair<Int, Int>, WatchProgress>
        }
            .onStart {
                scope.launch {
                    ensureEpisodeProgressSnapshot(contentId = cacheKey, forceRefresh = false)
                }
            }
            .distinctUntilChanged()
    }

    fun observeAiredEpisodes(contentId: String): Flow<List<Pair<Int, Int>>> {
        val cacheKey = canonicalLookupKey(contentId)
        return episodeProgressState
            .map { state -> state[cacheKey]?.airedEpisodes.orEmpty() }
            .onStart {
                scope.launch {
                    ensureEpisodeProgressSnapshot(contentId = cacheKey, forceRefresh = false)
                }
            }
            .distinctUntilChanged()
    }

    fun observeEpisodeProgressLoaded(contentId: String): Flow<Boolean> {
        val cacheKey = canonicalLookupKey(contentId)
        return episodeProgressState
            .map { state -> state[cacheKey]?.hasCompletedSnapshot == true }
            .distinctUntilChanged()
    }

    suspend fun getEpisodeProgressSnapshot(
        contentId: String
    ): Map<Pair<Int, Int>, WatchProgress> {
        return ensureEpisodeProgressSnapshot(
            contentId = contentId,
            forceRefresh = false
        )
    }

    fun observeMovieWatched(contentId: String, videoId: String? = null): Flow<Boolean> {
        val rawKey = contentId.trim()
        val canonicalKey = canonicalLookupKey(rawKey)
        val videoCanonicalKey = videoId?.takeIf { it.isNotBlank() && it != contentId }
            ?.let { canonicalLookupKey(it) }

        return combine(watchedMoviesState, optimisticProgress) { watchedMovies, optimistic ->
            val optimisticEntry = optimistic[rawKey]?.progress
                ?: optimistic[canonicalKey]?.progress
                ?: videoCanonicalKey?.let { optimistic[it]?.progress }
            when {
                optimisticEntry?.isCompleted() == true -> true
                optimisticEntry?.isInProgress() == true -> false
                else -> watchedMovies.contains(rawKey)
                    || watchedMovies.contains(canonicalKey)
                    || (videoCanonicalKey != null && watchedMovies.contains(videoCanonicalKey))
            }
        }
            .onStart { isMovieWatched(rawKey, videoId) }
            .distinctUntilChanged()
    }

    fun observeAllWatchedMovieIds(): Flow<Set<String>> {
        return combine(watchedMoviesState, optimisticProgress) { watchedMovies, optimistic ->
            val result = watchedMovies.toMutableSet()
            optimistic.forEach { (key, entry) ->
                when {
                    entry.progress.isCompleted() -> result.add(key)
                    entry.progress.isInProgress() -> result.remove(key)
                }
            }
            result as Set<String>
        }
            .onStart {
                scope.launch { getWatchedMoviesSnapshot(forceRefresh = false) }
            }
            .distinctUntilChanged()
    }

    suspend fun markAsWatched(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ) {
        Log.d(TAG, "markAsWatched: contentId=${progress.contentId} videoId=${progress.videoId} " +
            "season=${progress.season} episode=${progress.episode} " +
            "contentType=${progress.contentType} title=$title year=$year")

        val body = buildHistoryAddRequest(progress, title, year)
            ?: throw IllegalStateException("Insufficient Trakt IDs to mark watched")

        val isSeriesEpisode = isSeriesEpisodeProgress(progress)
        val watchedShowSeedsSnapshot = if (isSeriesEpisode) {
            snapshotWatchedShowSeeds()
                .also { updateWatchedShowSeedOptimistically(progress) }
        } else {
            null
        }

        Log.d(TAG, "markAsWatched REQUEST: shows=${body.shows?.map { show ->
            "ids=${show.ids} title=${show.title} year=${show.year} seasons=${show.seasons?.map { s ->
                "number=${s.number} episodes=${s.episodes?.map { e -> "number=${e.number} watchedAt=${e.watchedAt}" }}"
            }}"
        }} movies=${body.movies?.map { m -> "ids=${m.ids} title=${m.title}" }}")

        val response = try {
            traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                traktApi.addHistory(authHeader, body)
            } ?: throw IllegalStateException("Trakt request failed")
        } catch (error: Throwable) {
            if (watchedShowSeedsSnapshot != null) {
                restoreWatchedShowSeeds(watchedShowSeedsSnapshot)
            }
            throw error
        }

        var responseBody = response.body()
        val initialFailure = !response.isSuccessful || hasHistoryAddNotFound(responseBody)
        var effectiveResponseCode = response.code()
        Log.d(TAG, "markAsWatched RESPONSE: code=${response.code()} " +
            "added=[movies=${responseBody?.added?.movies} episodes=${responseBody?.added?.episodes} " +
            "shows=${responseBody?.added?.shows} seasons=${responseBody?.added?.seasons}] " +
            "notFound=[movies=${responseBody?.notFound?.movies?.map { it.ids }} " +
            "shows=${responseBody?.notFound?.shows?.map { it.ids }} " +
            "episodes=${responseBody?.notFound?.episodes?.map { "s=${it.season} e=${it.number} ids=${it.ids}" }}]")

        val shouldRetryRemap = isSeriesEpisode && (
            !response.isSuccessful ||
                hasHistoryAddNotFound(responseBody) ||
                !hasSuccessfulHistoryAdd(responseBody)
        )
        var recoveredByRemap = false
        var effectiveProgress = progress
        if (shouldRetryRemap) {
            val remappedAttempt = attemptEpisodeRemapHistoryAdd(
                progress = progress,
                title = title,
                year = year
            )
            if (remappedAttempt != null) {
                responseBody = remappedAttempt.response.body()
                effectiveResponseCode = remappedAttempt.response.code()
                recoveredByRemap =
                    remappedAttempt.response.isSuccessful &&
                    !hasHistoryAddNotFound(responseBody) &&
                    hasSuccessfulHistoryAdd(responseBody)
                if (recoveredByRemap && remappedAttempt.remappedEpisode != null) {
                    effectiveProgress = progress.copy(
                        season = remappedAttempt.remappedEpisode.season,
                        episode = remappedAttempt.remappedEpisode.episode,
                        videoId = remappedAttempt.remappedEpisode.videoId ?: progress.videoId
                    )
                }
            }
        }

        if (initialFailure && !recoveredByRemap) {
            if (watchedShowSeedsSnapshot != null) {
                restoreWatchedShowSeeds(watchedShowSeedsSnapshot)
            }
            throw IllegalStateException("Failed to mark watched on Trakt ($effectiveResponseCode)")
        }
        if (!hasSuccessfulHistoryAdd(responseBody)) {
            trace("markAsWatched: Trakt accepted request with no new history rows (code=$effectiveResponseCode)")
        }

        if (progress.contentType.equals("movie", ignoreCase = true)) {
            setMovieWatchedInCache(progress.contentId, watched = true)
        } else if (
            progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)
        ) {
            // Optimistically add the episode to the cache instead of invalidating
            // the entire series cache (which causes all episodes to briefly appear
            // unwatched until the next Trakt fetch completes).
            val cacheKey = canonicalLookupKey(effectiveProgress.contentId.trim())
            val season = effectiveProgress.season
            val episode = effectiveProgress.episode
            if (season != null && episode != null) {
                val completedEntry = effectiveProgress.copy(
                    position = effectiveProgress.duration.coerceAtLeast(1L),
                    duration = effectiveProgress.duration.coerceAtLeast(1L),
                    progressPercent = 100f,
                    lastWatched = System.currentTimeMillis()
                )
                episodeProgressState.update { current ->
                    val entry = current[cacheKey]
                    if (entry != null) {
                        val updatedProgress = entry.progress.toMutableMap().apply {
                            this[season to episode] = completedEntry
                        }
                        current + (cacheKey to entry.copy(progress = updatedProgress))
                    } else {
                        // No cache entry yet — force a full fetch instead.
                        current
                    }
                }
                // If there was no cache entry, fall back to invalidation so the
                // next observer emission triggers a fresh fetch.
                if (episodeProgressState.value[cacheKey] == null) {
                    invalidateEpisodeProgressCache(effectiveProgress.contentId)
                }
            } else {
                invalidateEpisodeProgressCache(effectiveProgress.contentId)
            }
            updateWatchedShowSeedOptimistically(effectiveProgress)
        }
        refreshNow()
    }

    suspend fun isMovieWatched(contentId: String, videoId: String? = null): Boolean {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return false
        val canonicalKey = canonicalLookupKey(rawKey)
        val videoCanonicalKey = videoId?.takeIf { it.isNotBlank() && it != contentId }
            ?.let { canonicalLookupKey(it) }

        val optimistic = optimisticProgress.value[rawKey]?.progress
            ?: optimisticProgress.value[canonicalKey]?.progress
            ?: videoCanonicalKey?.let { optimisticProgress.value[it]?.progress }
        if (optimistic?.isCompleted() == true) return true

        val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
        return watchedMovies.contains(rawKey)
            || watchedMovies.contains(canonicalKey)
            || (videoCanonicalKey != null && watchedMovies.contains(videoCanonicalKey))
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

    suspend fun removeFromHistory(contentId: String, videoId: String? = null, season: Int?, episode: Int?) {
        Log.d(TAG, "removeFromHistory: contentId=$contentId videoId=$videoId season=$season episode=$episode")
        applyOptimisticRemoval(contentId, season, episode)

        var ids = toTraktIds(parseContentIds(contentId))
        if (!ids.hasAnyId() && !videoId.isNullOrBlank() && videoId != contentId) {
            ids = toTraktIds(parseContentIds(videoId))
        }
        Log.d(TAG, "removeFromHistory: parsed ids=$ids")
        if (!ids.hasAnyId()) {
            Log.d(TAG, "removeFromHistory: no valid Trakt IDs, skipping")
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

        Log.d(TAG, "removeFromHistory REQUEST: shows=${removeBody.shows?.map { s ->
            "ids=${s.ids} seasons=${s.seasons?.map { ss -> "number=${ss.number} episodes=${ss.episodes?.map { e -> e.number }}" }}"
        }} movies=${removeBody.movies?.map { it.ids }}")

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.removeHistory(authHeader, removeBody)
        }
        Log.d(TAG, "removeFromHistory RESPONSE: code=${response?.code()} body=${response?.body()}")

        if (!likelySeries) {
            setMovieWatchedInCache(
                contentId = normalizeContentId(ids = ids, fallback = contentId.trim()),
                watched = false
            )
        } else {
            // Optimistically remove just this episode from the cache instead of
            // invalidating the entire series cache (which causes all episodes to
            // briefly appear unwatched until the next Trakt fetch completes).
            val cacheKey = canonicalLookupKey(contentId.trim())
            if (season != null && episode != null) {
                episodeProgressState.update { current ->
                    val entry = current[cacheKey] ?: return@update current
                    val updatedProgress = entry.progress.toMutableMap().apply {
                        remove(season to episode)
                    }
                    current + (cacheKey to entry.copy(progress = updatedProgress))
                }
            } else {
                invalidateEpisodeProgressCache(contentId)
            }
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
        if (!traktAuthService.isCircuitClosed()) {
            trace("refreshRemoteSnapshot: circuit breaker open, skipping")
            throw IOException("Trakt circuit breaker is open")
        }

        val force = System.currentTimeMillis() < forceRefreshUntilMs

        if (!force && !hasActivityChanged()) return

        if ((force || watchedMoviesStale) && hasLoadedWatchedMovies) {
            getWatchedMoviesSnapshot(forceRefresh = true)
        }

        if (force && hasLoadedWatchedShowSeeds) {
            getWatchedShowSeedsSnapshot(forceRefresh = true)
        }

        val snapshot = fetchAllProgressSnapshot(force = force)
        remoteProgress.value = snapshot
        hasLoadedRemoteProgress.value = true
        reconcileOptimistic(snapshot)
        hydrateMetadata(snapshot)
        if (!force && watchedShowSeedsStale && hasLoadedWatchedShowSeeds) {
            getWatchedShowSeedsSnapshot(forceRefresh = true)
        }
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
            watchedShowSeedsStale = true
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
                        airedEpisodes = result.airedEpisodes,
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

    private suspend fun getWatchedShowSeedsSnapshot(forceRefresh: Boolean): List<WatchProgress> {
        val now = System.currentTimeMillis()
        return watchedShowSeedsMutex.withLock {
            val hasFreshCache = hasLoadedWatchedShowSeeds &&
                !watchedShowSeedsStale &&
                now - watchedShowSeedsUpdatedAtMs <= watchedMoviesCacheTtlMs
            if (!forceRefresh && hasFreshCache) {
                trace("watched-shows cache hit: size=${watchedShowSeedsState.value.size}")
                return@withLock watchedShowSeedsState.value
            }
            if (!forceRefresh && now - watchedShowSeedsLastAttemptAtMs < watchedMoviesFetchThrottleMs) {
                trace("watched-shows fetch throttled: ${now - watchedShowSeedsLastAttemptAtMs}ms since last attempt")
                return@withLock watchedShowSeedsState.value
            }

            watchedShowSeedsLastAttemptAtMs = now
            trace("watched-shows fetch: requesting /sync/watched/shows")
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatchedShows(
                    authorization = authHeader
                )
            } ?: run {
                trace("watched-shows fetch: request returned null (network/auth failure)")
                return@withLock watchedShowSeedsState.value
            }

            if (!response.isSuccessful) {
                trace("watched-shows fetch: non-success code=${response.code()}")
                return@withLock watchedShowSeedsState.value
            }

            val watchedShowSeeds = response.body().orEmpty()
                .mapNotNull(::mapWatchedShowSeed)
                .sortedByDescending { it.lastWatched }

            watchedShowSeedsState.value = watchedShowSeeds
            watchedShowSeedsUpdatedAtMs = System.currentTimeMillis()
            hasLoadedWatchedShowSeeds = true
            watchedShowSeedsStale = false
            trace("watched-shows cache refreshed: size=${watchedShowSeeds.size}")
            watchedShowSeeds
        }
    }

    private fun mapWatchedShowSeed(item: TraktWatchedShowItemDto): WatchProgress? {
        val show = item.show ?: return null
        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null

        val furthestEpisode = item.seasons.orEmpty()
            .asSequence()
            .filter { season -> (season.number ?: 0) > 0 }
            .flatMap { season ->
                val seasonNumber = season.number ?: return@flatMap emptySequence()
                season.episodes.orEmpty()
                    .asSequence()
                    .filter { episode -> (episode.number ?: 0) > 0 && (episode.plays ?: 1) > 0 }
                    .mapNotNull { episode ->
                        val episodeNumber = episode.number ?: return@mapNotNull null
                        Triple(
                            seasonNumber,
                            episodeNumber,
                            parseIsoToMillis(episode.lastWatchedAt)
                        )
                    }
            }
            .maxWithOrNull(
                compareBy<Triple<Int, Int, Long>>(
                    { it.first },
                    { it.second },
                    { it.third }
                )
            ) ?: return null

        val season = furthestEpisode.first
        val episode = furthestEpisode.second
        val lastWatched = furthestEpisode.third.takeIf { it > 0L }
            ?: parseIsoToMillis(item.lastWatchedAt)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = buildLightweightEpisodeVideoId(contentId, season, episode),
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS,
            traktShowId = show.ids?.trakt
        )
    }

    private fun updateWatchedShowSeedOptimistically(progress: WatchProgress) {
        if (!isSeriesEpisodeProgress(progress)) return
        val season = progress.season ?: return
        val episode = progress.episode ?: return
        val contentId = canonicalLookupKey(progress.contentId)
        if (contentId.isBlank()) return

        val now = System.currentTimeMillis()
        val candidate = WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = progress.name.takeIf { it.isNotBlank() } ?: contentId,
            poster = progress.poster,
            backdrop = progress.backdrop,
            logo = progress.logo,
            videoId = progress.videoId.takeIf { it.isNotBlank() }
                ?: buildLightweightEpisodeVideoId(contentId, season, episode),
            season = season,
            episode = episode,
            episodeTitle = progress.episodeTitle,
            position = 1L,
            duration = 1L,
            lastWatched = progress.lastWatched.takeIf { it > 0L } ?: now,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS,
            traktShowId = progress.traktShowId,
            traktEpisodeId = progress.traktEpisodeId
        )

        watchedShowSeedsState.update { current ->
            val updated = current.toMutableList()
            val existingIndex = updated.indexOfFirst { canonicalLookupKey(it.contentId) == contentId }
            if (existingIndex >= 0) {
                val existing = updated[existingIndex]
                val shouldReplace =
                    (candidate.season ?: -1) > (existing.season ?: -1) ||
                        (
                            candidate.season == existing.season &&
                                (
                                    (candidate.episode ?: -1) > (existing.episode ?: -1) ||
                                        (
                                            candidate.episode == existing.episode &&
                                                candidate.lastWatched >= existing.lastWatched
                                            )
                                    )
                            )
                if (!shouldReplace) {
                    return@update current
                }
                updated[existingIndex] = candidate
            } else {
                updated.add(candidate)
            }
            updated.sortedByDescending { it.lastWatched }
        }
        watchedShowSeedsUpdatedAtMs = now
        hasLoadedWatchedShowSeeds = true
        watchedShowSeedsStale = true
        trace("watched-shows optimistic update: contentId=$contentId s=$season e=$episode")
    }

    private fun snapshotWatchedShowSeeds(): WatchedShowSeedsSnapshot {
        return WatchedShowSeedsSnapshot(
            seeds = watchedShowSeedsState.value,
            updatedAtMs = watchedShowSeedsUpdatedAtMs,
            hasLoaded = hasLoadedWatchedShowSeeds,
            stale = watchedShowSeedsStale
        )
    }

    private fun restoreWatchedShowSeeds(snapshot: WatchedShowSeedsSnapshot) {
        watchedShowSeedsState.value = snapshot.seeds
        watchedShowSeedsUpdatedAtMs = snapshot.updatedAtMs
        hasLoadedWatchedShowSeeds = snapshot.hasLoaded
        watchedShowSeedsStale = snapshot.stale
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

    private fun buildLightweightEpisodeVideoId(contentId: String, season: Int, episode: Int): String {
        return "$contentId:$season:$episode"
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
        val inProgressEpisodes = getPlayback("episodes", force = force, startAt = playbackStartAt)
            .mapNotNull { mapPlaybackEpisode(it, applyAddonRemap = true) }

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
            for (item in items) {
                val contentId = normalizeContentId(item.show?.ids)
                if (contentId.isBlank()) continue
                if (results.containsKey(contentId)) continue

                val itemLastWatched = parseIsoToMillis(item.watchedAt)
                if (cutoffMs != null && itemLastWatched < cutoffMs) {
                    shouldStop = true
                    continue
                }

                val mapped = mapEpisodeHistoryItem(item, applyAddonRemap = true) ?: continue
                results.putIfAbsent(mapped.contentId, mapped)
                if (results.size >= maxRecentEpisodeHistoryEntries) {
                    shouldStop = true
                    continue
                }
            }

            val pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (items.size < pageLimit || shouldStop || (pageCount != null && page >= pageCount)) break
            page += 1
        }

        return results.values.toList()
    }

    private suspend fun mapEpisodeHistoryItem(
        item: TraktUserEpisodeHistoryItemDto,
        applyAddonRemap: Boolean
    ): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null

        val lastWatched = parseIsoToMillis(item.watchedAt)
        val resolvedEpisode = if (applyAddonRemap) {
            resolveAddonEpisodeProgress(
                contentId = contentId,
                season = season,
                episode = number,
                episodeTitle = episode.title
            )
        } else {
            null
        }
        val resolvedSeason = resolvedEpisode?.season ?: season
        val resolvedNumber = resolvedEpisode?.episode ?: number
        val videoId = resolveEpisodeVideoId(contentId, resolvedSeason, resolvedNumber)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = resolvedSeason,
            episode = resolvedNumber,
            episodeTitle = resolvedEpisode?.title ?: episode.title,
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
        var airedEpisodes = emptyList<Pair<Int, Int>>()
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
            airedEpisodes = seasons
                .asSequence()
                .filter { (it.number ?: 0) > 0 }
                .sortedBy { it.number }
                .flatMap { season ->
                    val seasonNumber = season.number ?: return@flatMap emptySequence()
                    season.episodes.orEmpty().asSequence()
                        .mapNotNull { episode ->
                            val episodeNumber = episode.number ?: return@mapNotNull null
                            seasonNumber to episodeNumber
                        }
                }
                .toList()
            for (season in seasons) {
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
            .mapNotNull { mapPlaybackEpisode(it, applyAddonRemap = true) }
            .filter { it.contentId == contentId }

        inProgress.forEach { progress ->
            val seasonNum = progress.season ?: return@forEach
            val episodeNum = progress.episode ?: return@forEach
            completed[seasonNum to episodeNum] = progress
        }

        return EpisodeProgressFetchResult(
            progress = completed,
            airedEpisodes = airedEpisodes,
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

    private suspend fun mapPlaybackEpisode(
        item: TraktPlaybackItemDto,
        applyAddonRemap: Boolean
    ): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null
        val resolvedEpisode = if (applyAddonRemap) {
            resolveAddonEpisodeProgress(
                contentId = contentId,
                season = season,
                episode = number,
                episodeTitle = episode.title
            )
        } else {
            null
        }
        val resolvedSeason = resolvedEpisode?.season ?: season
        val resolvedNumber = resolvedEpisode?.episode ?: number
        val videoId = resolveEpisodeVideoId(contentId, resolvedSeason, resolvedNumber)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = resolvedSeason,
            episode = resolvedNumber,
            episodeTitle = resolvedEpisode?.title ?: episode.title,
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

    private suspend fun mapSeasonProgress(
        contentId: String,
        season: TraktShowSeasonProgressDto
    ): List<WatchProgress> {
        val seasonNumber = season.number ?: return emptyList()
        return season.episodes.orEmpty()
            .filter { it.completed == true }
            .mapNotNull { episode ->
                val episodeNumber = episode.number ?: return@mapNotNull null
                val resolvedEpisode = resolveAddonEpisodeProgress(
                    contentId = contentId,
                    season = seasonNumber,
                    episode = episodeNumber,
                    episodeTitle = null
                )
                val resolvedSeason = resolvedEpisode?.season ?: seasonNumber
                val resolvedNumber = resolvedEpisode?.episode ?: episodeNumber
                WatchProgress(
                    contentId = contentId,
                    contentType = "series",
                    name = contentId,
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = resolveEpisodeVideoId(contentId, resolvedSeason, resolvedNumber),
                    season = resolvedSeason,
                    episode = resolvedNumber,
                    episodeTitle = resolvedEpisode?.title,
                    position = 1L,
                    duration = 1L,
                    lastWatched = parseIsoToMillis(episode.lastWatchedAt),
                    progressPercent = 100f,
                    source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
                )
            }
    }

    private suspend fun resolveAddonEpisodeProgress(
        contentId: String,
        season: Int,
        episode: Int,
        episodeTitle: String?
    ): EpisodeMappingEntry? {
        return runCatching {
            traktEpisodeMappingService.resolveAddonEpisodeMapping(
                contentId = contentId,
                contentType = "series",
                season = season,
                episode = episode,
                episodeTitle = episodeTitle
            )
        }.getOrElse { error ->
            Log.w(
                TAG,
                "resolveAddonEpisodeProgress failed for $contentId s=$season e=$episode",
                error
            )
            null
        }
    }

    private suspend fun resolveEpisodeVideoId(
        contentId: String,
        season: Int,
        episode: Int
    ): String {
        if (!hasLoadedRemoteProgress.value) {
            return "$contentId:$season:$episode"
        }
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

    private fun hasHistoryAddNotFound(body: TraktHistoryAddResponseDto?): Boolean {
        val notFound = body?.notFound ?: return false
        return !notFound.movies.isNullOrEmpty() ||
            !notFound.shows.isNullOrEmpty() ||
            !notFound.seasons.isNullOrEmpty() ||
            !notFound.episodes.isNullOrEmpty()
    }

    private fun buildHistoryAddRequest(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ): TraktHistoryAddRequestDto? {
        val ids = resolveHistoryIds(progress)
        Log.d(TAG, "buildHistoryAddRequest: resolvedIds=$ids contentId=${progress.contentId} videoId=${progress.videoId}")
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

    private suspend fun attemptEpisodeRemapHistoryAdd(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ): EpisodeHistoryAddAttempt? = runCatching {
        val remapped = resolveCanonicalEpisodeMapping(progress) ?: return@runCatching null
        val currentSeason = progress.season
        val currentEpisode = progress.episode
        if (currentSeason == remapped.season && currentEpisode == remapped.episode) {
            return@runCatching null
        }

        trace(
            "markAsWatched: retrying with remapped episode " +
                "from s=$currentSeason e=$currentEpisode to s=${remapped.season} e=${remapped.episode}"
        )

        val remappedBody = buildHistoryAddRequest(
            progress = progress.copy(
                season = remapped.season,
                episode = remapped.episode
            ),
            title = title,
            year = year
        ) ?: return@runCatching null

        val retryResponse = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addHistory(authHeader, remappedBody)
        } ?: return@runCatching null

        val retryBody = retryResponse.body()
        Log.d(
            TAG,
            "markAsWatched REMAP RESPONSE: code=${retryResponse.code()} " +
                "added=[movies=${retryBody?.added?.movies} episodes=${retryBody?.added?.episodes} " +
                "shows=${retryBody?.added?.shows} seasons=${retryBody?.added?.seasons}] " +
                "notFound=[movies=${retryBody?.notFound?.movies?.map { it.ids }} " +
                "shows=${retryBody?.notFound?.shows?.map { it.ids }} " +
                "episodes=${retryBody?.notFound?.episodes?.map { "s=${it.season} e=${it.number} ids=${it.ids}" }}]"
        )
        EpisodeHistoryAddAttempt(
            response = retryResponse,
            remappedEpisode = remapped
        )
    }.getOrElse { error ->
        Log.w(TAG, "markAsWatched: episode remap fallback failed", error)
        null
    }

    private suspend fun resolveCanonicalEpisodeMapping(
        progress: WatchProgress
    ): EpisodeMappingEntry? {
        return traktEpisodeMappingService.resolveEpisodeMapping(
            contentId = progress.contentId,
            contentType = progress.contentType,
            videoId = progress.videoId,
            season = progress.season,
            episode = progress.episode
        )
    }

    private fun isSeriesEpisodeProgress(progress: WatchProgress): Boolean {
        val normalizedType = progress.contentType.lowercase()
        return normalizedType in listOf("series", "tv") &&
            progress.season != null &&
            progress.episode != null
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

    private fun remainingMetadataWarmupDelayMs(): Long {
        val elapsed = SystemClock.elapsedRealtime() - serviceStartedAtMs
        return (initialMetadataHydrationDelayMs - elapsed).coerceAtLeast(0L)
    }

    private fun shouldDelayMetadataHydration(): Boolean {
        val remainingDelayMs = remainingMetadataWarmupDelayMs()
        if (remainingDelayMs <= 0L) return false
        if (metadataWarmupScheduled) return true
        metadataWarmupScheduled = true
        scope.launch {
            delay(remainingDelayMs)
            metadataWarmupScheduled = false
            hydrateMetadata(remoteProgress.value)
        }
        return true
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        if (shouldDelayMetadataHydration()) return
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
                    metadataFetchSemaphore.withPermit {
                        val metadata = fetchContentMetadata(
                            contentId = contentId,
                            contentType = progress.contentType
                        ) ?: return@launch
                        metadataState.update { current ->
                            current + (contentId to metadata)
                        }
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
