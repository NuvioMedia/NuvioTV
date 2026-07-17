package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Metadata about the content being played in an external player.
 * Stored here so progress can be saved regardless of which screen initiated playback.
 */
data class ExternalPlaybackMetadata(
    val contentId: String,
    val contentType: String,
    val contentName: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val year: String?
) {
    /**
     * Builds a display title for external players.
     * For series: "Show Name - S02E05" or "Show Name - S02E05 - Episode Title"
     * For movies: just the content name.
     */
    fun buildPlayerTitle(includeEpisodeTitle: Boolean = false): String {
        val base = contentName
        if (season == null || episode == null) return base
        val seasonEp = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        return if (includeEpisodeTitle && !episodeTitle.isNullOrBlank()) {
            "$base - $seasonEp - $episodeTitle"
        } else {
            "$base - $seasonEp"
        }
    }
}

/**
 * Emitted when an external episode finishes and auto-play-next is enabled. Carries
 * the resolved next episode plus the metadata needed to build the Screen.Stream route.
 */
data class ExternalAutoNextEpisode(
    val contentId: String,
    val contentType: String,
    val contentName: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val year: String?,
    val nextVideoId: String,
    val nextSeason: Int?,
    val nextEpisode: Int,
    // Lets the collector skip a value replayed after a config change while still
    // acting on a genuinely new event after a process restart.
    val requestedAtMs: Long = System.currentTimeMillis()
)

/** Visuals for the loader shown while an external episode auto-advances. */
data class ExternalAutoNextOverlay(
    val backdrop: String?,
    val logo: String?,
    val title: String?,
    val message: String? = null,
    val progress: Float? = null
)

data class ExternalNextEpisodeSnapshot(
    val metadataResolved: Boolean,
    val nextVideoId: String? = null,
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null
) {
    val hasNextEpisode: Boolean?
        get() = if (!metadataResolved) null else nextVideoId != null && nextEpisode != null

    companion object {
        val Unknown = ExternalNextEpisodeSnapshot(metadataResolved = false)
        val NoPlayableNextEpisode = ExternalNextEpisodeSnapshot(metadataResolved = true)
    }
}

internal fun resolveExternalNextEpisodeSnapshot(
    videos: List<Video>,
    currentSeason: Int?,
    currentEpisode: Int?
): ExternalNextEpisodeSnapshot {
    if (currentEpisode == null) return ExternalNextEpisodeSnapshot.Unknown
    val currentExists = videos.any { video ->
        video.episode == currentEpisode && (currentSeason == null || video.season == currentSeason)
    }
    if (!currentExists) return ExternalNextEpisodeSnapshot.Unknown

    val nextVideo = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = videos,
        currentSeason = currentSeason,
        currentEpisode = currentEpisode
    ) ?: return ExternalNextEpisodeSnapshot.NoPlayableNextEpisode
    val nextEpisode = nextVideo.episode ?: return ExternalNextEpisodeSnapshot.Unknown
    val isPlayable = ExternalAutoNextPolicy.isPlayableNextEpisode(
        available = nextVideo.available,
        hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)
    )
    if (!isPlayable) return ExternalNextEpisodeSnapshot.NoPlayableNextEpisode

    return ExternalNextEpisodeSnapshot(
        metadataResolved = true,
        nextVideoId = nextVideo.id,
        nextSeason = nextVideo.season,
        nextEpisode = nextEpisode
    )
}

/**
 * Application-scoped singleton that tracks external player playback.
 *
 * This lives independently of any composable or screen lifecycle, so it survives
 * navigation changes (e.g. StreamScreen being popped from backstack).
 *
 * Responsibilities:
 * - Hold metadata about what's being played externally
 * - Process ActivityResult data when external player returns
 * - Run Zidoo REST API polling on Zidoo devices
 * - Save progress to WatchProgressRepository
 * - Send Trakt scrobble (start + stop)
 * - Start/stop the keep-alive foreground service
 */
@Singleton
class ExternalPlaybackTracker @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val skipIntroRepository: SkipIntroRepository
) {
    companion object {
        private const val TAG = "ExtPlaybackTracker"
        private const val AUTO_NEXT_TAG = "ExtAutoNext"
        /** Longer than StreamScreen's 60 second hard timeout, so it owns launch failures. */
        private const val AUTO_NEXT_OVERLAY_TIMEOUT_MS = 65_000L
        /** Max time to wait for series meta when resolving the next episode. */
        private const val META_FETCH_TIMEOUT_MS = 15_000L
        /** Retry a failed background lookup while the current episode is still playing. */
        private const val NEXT_EPISODE_PREFETCH_ATTEMPTS = 3
        private const val NEXT_EPISODE_PREFETCH_RETRY_DELAY_MS = 5_000L
        /** Do not let a retrying prefetch hold the completion handoff for the full retry window. */
        private const val NEXT_EPISODE_PREFETCH_RETURN_WAIT_MS = 1_000L
        /** A "completed" playback shorter than this is treated as a debrid cache-sync placeholder
         *  (e.g. Comet's few-second clip), not a real episode: not marked watched, no auto-advance. */
        private const val MIN_REAL_PLAYBACK_DURATION_MS = 30_000L
        /** A launch within this of an auto-next emit counts as a chain continuation. */
        private const val CONTINUATION_WINDOW_MS = 12_000L
        /** Upper bound on how long resolving skip segments may delay an external launch. */
        private const val SKIP_RESOLVE_TIMEOUT_MS = 4_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var zidooMonitorJob: Job? = null
    // The in-flight auto-next resolution (meta fetch -> emit next episode). Held so the user can
    // cancel it by backing out of the "Loading next episode" loader before it navigates.
    private var autoNextJob: Job? = null
    // Set when the user backs out of the loader; blocks the loader from re-raising and auto-next
    // from firing for the current return (e.g. while VLC's duration backfill is still running).
    // Reset on each new launch in startTracking.
    private var autoNextCancelled = false
    // Durable version of autoNextCancelled: survives the auto-launched chain so one Back press
    // stops a runaway loop. Reset on a fresh (non-continuation) launch.
    private var autoNextChainAborted = false
    // Timestamp of the last auto-next emit. A launch within CONTINUATION_WINDOW_MS of it counts as
    // a chain continuation (so an abort survives it); a later launch is fresh and clears the abort.
    // Using a time window instead of a sticky flag means the abort can never get permanently stuck
    // if a continuation never actually launches (e.g. user backed out before it auto-played).
    private var lastAutoNextEmitMs = 0L
    // True from next-episode navigation until external playback covers Nuvio or the handoff ends.
    // This distinguishes a cancellable continuation from an unrelated direct auto-play launch.
    private var autoNextNavigationPending = false
    // Set when the loader is released on a routine screen settle, so a later onStart can't re-raise a
    // loader that no longer has a job behind it (which would leave it stuck). Reset on a fresh launch.
    private var autoNextOverlaySuppressed = false
    // Resolve the successor while the current episode is playing. Besides making auto-next
    // immediate on return, this lets us avoid showing a loader after a known series finale.
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeSnapshot: ExternalNextEpisodeSnapshot = ExternalNextEpisodeSnapshot.Unknown
    private var autoNextEnabledForPendingLaunch: Boolean? = null

    // Fires on external-episode completion; collected by MainActivity to navigate to
    // the next episode's Stream route. replay = 1 so the event still reaches the
    // collector when the player killed our process and it re-subscribes after restart.
    private val _autoPlayNext = MutableSharedFlow<ExternalAutoNextEpisode>(
        replay = 1,
        extraBufferCapacity = 1
    )
    val autoPlayNext: SharedFlow<ExternalAutoNextEpisode> = _autoPlayNext.asSharedFlow()

    // Non-null while auto-advancing: MainActivity shows a loader covering the
    // cold-start/source-resolution window. Cleared on the next launch, failure, or timeout.
    private val _autoNextOverlay = MutableStateFlow<ExternalAutoNextOverlay?>(null)
    val autoNextOverlay: StateFlow<ExternalAutoNextOverlay?> = _autoNextOverlay.asStateFlow()

    // Disk-persisted copy of pendingMetadata so onActivityResult still works after the
    // player kills our process. Written on startTracking, read + cleared in
    // onActivityResult (not in stopTracking, to avoid racing StreamScreen's ON_RESUME).
    private val persistedPrefs by lazy {
        appContext.getSharedPreferences("external_playback_pending", Context.MODE_PRIVATE)
    }

    /**
     * The ActivityResultLauncher registered in MainActivity.
     * Set during Activity.onCreate, used to launch external players with result tracking.
     */
    var activityLauncher: androidx.activity.result.ActivityResultLauncher<ExternalPlayerInput>? = null

    /** Currently pending external playback metadata, or null if nothing is playing externally. */
    var pendingMetadata: ExternalPlaybackMetadata? = null
        private set

    /** True when the external player was launched automatically (not by manual stream click). */
    var isAutoLaunch: Boolean = false
        private set

    val isTracking: Boolean get() = pendingMetadata != null

    /**
     * Called before launching an external player. Stores metadata and starts keep-alive service.
     */
    fun startTracking(
        metadata: ExternalPlaybackMetadata,
        autoLaunch: Boolean = false,
        nextEpisodeSnapshot: ExternalNextEpisodeSnapshot? = null,
        autoNextEnabled: Boolean? = null
    ) {
        // A manual stream choice supersedes any completion handoff still resolving for the
        // previous player session. Auto-launched continuations keep their handoff alive.
        if (!autoLaunch) {
            autoNextJob?.cancel()
            autoNextJob = null
            autoNextNavigationPending = false
        }
        pendingMetadata = metadata
        isAutoLaunch = autoLaunch
        // A manual launch is always fresh; only an auto-launch within the window is a continuation
        // that keeps a user's abort in effect (so one Back press stops a runaway chain).
        val shouldResetAbort = !autoNextNavigationPending &&
            ExternalAutoNextPolicy.shouldResetChainAbort(
                autoLaunch = autoLaunch,
                nowMs = System.currentTimeMillis(),
                lastAutoNextEmitMs = lastAutoNextEmitMs,
                continuationWindowMs = CONTINUATION_WINDOW_MS
            )
        if (shouldResetAbort) {
            autoNextCancelled = false
            autoNextChainAborted = false
        }
        autoNextOverlaySuppressed = false
        nextEpisodePrefetchJob?.cancel()
        this.nextEpisodeSnapshot = nextEpisodeSnapshot ?: ExternalNextEpisodeSnapshot.Unknown
        autoNextEnabledForPendingLaunch = autoNextEnabled
        // Persist so progress-save + auto-next survive the player killing our process.
        persistMetadata(metadata)
        persistAutoNextState(this.nextEpisodeSnapshot, autoNextEnabled)
        startNextEpisodePrefetch(metadata)

        // Keep the process alive while the external player is foregrounded. Some boxes
        // (e.g. NVIDIA Shield) otherwise kill it, dropping tracking state. Started while
        // we're still foreground, so background-FGS-start restrictions don't apply.
        ExternalPlaybackKeepAliveService.start(appContext)

        Log.d(TAG, "Started tracking: content=${metadata.contentId}, video=${metadata.videoId}")

        // On Zidoo devices, start REST API polling
        if (ZidooPlayerMonitor.isZidooDevice()) {
            startZidooMonitor(metadata)
        }
    }

    /**
     * Launch external player with progress tracking.
     * Uses the Activity-level launcher for ActivityResult, or fire-and-forget on Zidoo.
     * If resumePositionMs is 0, fetches the saved position from the repository.
     *
     * @param metadata Content metadata for progress saving
     * @param url Stream URL to play
     * @param title Display title
     * @param headers HTTP headers for the stream
     * @param resumePositionMs Position to resume from (ms), 0 to auto-fetch
     * @param context Fallback context for fire-and-forget launch
     */
    suspend fun launchPlayer(
        metadata: ExternalPlaybackMetadata,
        url: String,
        title: String?,
        headers: Map<String, String>?,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null,
        autoLaunch: Boolean = false,
        nextEpisodeSnapshot: ExternalNextEpisodeSnapshot? = null,
        context: Context
    ): Boolean {
        if (shouldCancelPendingAutoLaunch(autoLaunch)) {
            Log.d(AUTO_NEXT_TAG, "auto launch cancelled before tracker setup")
            return false
        }
        val autoNextEnabled = playerSettingsDataStore.playerSettings.first()
            .streamAutoPlayNextEpisodeEnabled
        if (shouldCancelPendingAutoLaunch(autoLaunch)) {
            Log.d(AUTO_NEXT_TAG, "auto launch cancelled while reading player settings")
            return false
        }
        startTracking(
            metadata = metadata,
            autoLaunch = autoLaunch,
            nextEpisodeSnapshot = nextEpisodeSnapshot,
            autoNextEnabled = autoNextEnabled
        )

        // Resolve resume position (if not given) and intro/outro skip segments off the main
        // thread, then launch. Skip resolution is bounded so it never stalls the launch for long
        // and is cached, so an auto-next chain pays it only once.
        val launched = coroutineScope {
            val positionDeferred = async {
                if (resumePositionMs > 0L) resumePositionMs else getResumePosition(metadata)
            }
            val skipSegmentsDeferred = async {
                resolveSkipSegmentsJson(metadata)
            }
            val position = positionDeferred.await()
            val skipSegmentsJson = skipSegmentsDeferred.await()
            if (shouldCancelPendingAutoLaunch(autoLaunch)) {
                Log.d(AUTO_NEXT_TAG, "auto launch cancelled during player preparation")
                false
            } else {
                withContext(Dispatchers.Main.immediate) {
                    doLaunch(url, title, headers, position, subtitles, skipSegmentsJson, context)
                }
            }
        }
        if (!launched) {
            if (shouldCancelPendingAutoLaunch(autoLaunch)) {
                Log.d(AUTO_NEXT_TAG, "cancelled auto launch cleaned up before external intent")
            } else {
                Log.w(TAG, "External player launch failed")
            }
            releaseAutoNextOverlay(forceRelease = true)
            clearPersistedMetadata()
            stopTracking()
        }
        return launched
    }

    /**
     * Resolves intro/outro skip segments for [metadata] via the same repository the internal
     * player uses, and serializes them to a JSON array string for the external player. Mirrors
     * the id-format handling in `fetchSkipIntervals`. Returns null when skip is disabled, the
     * content can't be identified, or nothing is found.
     */
    private suspend fun resolveSkipSegmentsJson(metadata: ExternalPlaybackMetadata): String? {
        // Opt-in via the External Player setting (not the internal player's "Skip Intro", which is
        // greyed out while external player is selected).
        if (!playerSettingsDataStore.playerSettings.first().externalPlayerSendSkipSegments) return null

        // videoId carries the episode-specific id (e.g. mal:/kitsu:/imdb); fall back to contentId.
        val effectiveId = metadata.videoId.takeIf { it.isNotBlank() } ?: metadata.contentId

        val intervals = withTimeoutOrNull(SKIP_RESOLVE_TIMEOUT_MS) {
            when {
                effectiveId.startsWith("mal:") -> {
                    val parts = effectiveId.split(":")
                    val malId = parts.getOrNull(1) ?: return@withTimeoutOrNull null
                    val ep = parts.getOrNull(2)?.toIntOrNull() ?: metadata.episode ?: return@withTimeoutOrNull null
                    skipIntroRepository.getSkipIntervalsForMal(malId, ep)
                }
                effectiveId.startsWith("kitsu:") -> {
                    val parts = effectiveId.split(":")
                    val kitsuId = parts.getOrNull(1) ?: return@withTimeoutOrNull null
                    val ep = parts.getOrNull(2)?.toIntOrNull() ?: metadata.episode ?: return@withTimeoutOrNull null
                    skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, ep)
                }
                else -> {
                    val imdbId = effectiveId.split(":").firstOrNull()?.takeIf { it.startsWith("tt") }
                        ?: return@withTimeoutOrNull null
                    val s = metadata.season ?: return@withTimeoutOrNull null
                    val e = metadata.episode ?: return@withTimeoutOrNull null
                    skipIntroRepository.getSkipIntervals(imdbId, s, e)
                }
            }
        }
        if (intervals.isNullOrEmpty()) return null

        val arr = org.json.JSONArray()
        intervals.forEach { iv ->
            arr.put(
                org.json.JSONObject()
                    .put("type", iv.type)
                    .put("start", iv.startTime)
                    .put("end", iv.endTime)
            )
        }
        return arr.toString()
    }

    private fun doLaunch(
        url: String,
        title: String?,
        headers: Map<String, String>?,
        resumePositionMs: Long,
        subtitles: List<SubtitleInput>?,
        skipSegmentsJson: String?,
        context: Context
    ): Boolean {

        val input = ExternalPlayerInput(
            url = url,
            title = title,
            headers = headers,
            resumePositionMs = resumePositionMs,
            subtitles = subtitles,
            skipSegmentsJson = skipSegmentsJson
        )

        if (ZidooPlayerMonitor.isZidooDevice()) {
            // Zidoo doesn't return ActivityResult - use fire-and-forget
            return ExternalPlayerLauncher.launch(
                context = context,
                url = url,
                title = title,
                headers = headers,
                resumePositionMs = resumePositionMs,
                subtitles = subtitles,
                skipSegmentsJson = skipSegmentsJson
            )
        } else {
            // Use Activity-level launcher for ActivityResult
            val launcher = activityLauncher
            if (launcher != null) {
                return try {
                    launcher.launch(input)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "ActivityResultLauncher failed, falling back to fire-and-forget", e)
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = title,
                        headers = headers,
                        resumePositionMs = resumePositionMs,
                        subtitles = subtitles,
                        skipSegmentsJson = skipSegmentsJson
                    )
                }
            } else {
                Log.w(TAG, "No activityLauncher registered, using fire-and-forget")
                return ExternalPlayerLauncher.launch(
                    context = context,
                    url = url,
                    title = title,
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = subtitles,
                    skipSegmentsJson = skipSegmentsJson
                )
            }
        }
    }

    // ===================== External-player result handling =====================

    /** Entry point for the player's ActivityResult: recover metadata, backfill a missing
     *  duration if needed, save progress, and auto-advance on completion. */
    fun onActivityResult(result: ExternalPlayerResult?) {
        // If the player killed our process, pendingMetadata is null after recreation —
        // fall back to the persisted copy so we still save progress and auto-advance.
        val metadata = pendingMetadata ?: loadPersistedMetadata()
        if (metadata == null) {
            Log.d(TAG, "onActivityResult but no pending metadata (in-memory or persisted)")
            clearPersistedMetadata()
            stopTracking()
            return
        }
        if (pendingMetadata == null) {
            Log.d(TAG, "onActivityResult recovered metadata from disk (process was recreated)")
            nextEpisodeSnapshot = loadPersistedNextEpisodeSnapshot()
            autoNextEnabledForPendingLaunch = loadPersistedAutoNextEnabled()
        }

        if (result == null) {
            Log.d(TAG, "External player returned no progress data")
            _autoNextOverlay.value = null
            clearPersistedMetadata()
            // On Zidoo, the monitor job handles progress - don't stop it prematurely.
            if (!ZidooPlayerMonitor.isZidooDevice()) stopTracking()
            return
        }

        // Covers process recreation, where onStart could not use in-memory state. At this point
        // the result is available, so only a completion may claim the transition loader.
        if (isPlaybackCompleted(result)) {
            raiseAutoNextOverlay(metadata, allowUnknownNextEpisode = true)
        }

        Log.d(TAG, "External player returned: pos=${result.positionMs}ms, dur=${result.durationMs}ms, endedByUser=${result.endedByUser}")

        // Some players (notably VLC on network streams) return a real position but no usable
        // duration, so Nuvio can't compute a % and nothing is saved as resumable/watched.
        // Backfill the duration (saved progress, else episode/movie runtime) off-thread, then
        // process. Players that DO report a duration keep the synchronous path below unchanged.
        if ((result.durationMs == null || result.durationMs <= 0L) && result.positionMs > 0L) {
            scope.launch {
                val fallback = resolveFallbackDurationMs(metadata)
                val enriched = if (fallback > 0L) {
                    Log.d(TAG, "Backfilled missing duration: ${fallback}ms")
                    result.copy(durationMs = fallback)
                } else {
                    result
                }
                processResult(metadata, enriched)
            }
            return
        }

        processResult(metadata, result)
    }

    /** Completion check + save + auto-next + cleanup for a result with a resolved duration. */
    private fun processResult(metadata: ExternalPlaybackMetadata, result: ExternalPlayerResult) {
        // Debrid cache-sync placeholders (e.g. Comet) play a few-second clip to its end and report
        // a normal completion. Ignore an implausibly short playback so it isn't marked watched and
        // doesn't chain auto-next through the season. A missing/zero duration is left to the normal
        // path (so Just Player's end-only completion still works).
        val duration = result.durationMs
        if (duration != null && duration in 1 until MIN_REAL_PLAYBACK_DURATION_MS) {
            Log.d(TAG, "Ignoring ${duration}ms playback (likely a cache-sync placeholder)")
            _autoNextOverlay.value = null
            clearPersistedMetadata()
            stopTracking()
            return
        }

        if (isPlaybackCompleted(result)) {
            // Mark watched even when the player returns no position/duration (e.g. Just
            // Player sends only end_by=playback_completion). An explicit 100% forces
            // WatchProgress.isCompleted(), which makes the repository flag the item watched
            // (and sync it) regardless of the reported position/duration.
            saveProgress(metadata, result.positionMs, result.durationMs, explicitPercent = 100f)
            // Try to auto-advance to the next episode.
            maybeTriggerAutoNextEpisode(metadata)
        } else {
            saveProgress(metadata, result.positionMs, result.durationMs)
            // Not a completion — drop the optimistic auto-next loader so we fall back to the
            // stream screen instead of leaving the loader stuck.
            _autoNextOverlay.value = null
        }

        // Result consumed — safe to drop the persisted copy now.
        clearPersistedMetadata()
        stopTracking()
    }

    // --- Duration backfill: for players that report a position but no usable duration (VLC) ---
    // NOTE: meta runtime is approximate, so the 90% completion check / saved % can be slightly
    // off — still far better than saving 0% and losing resume + watched entirely.

    /**
     * Best-effort duration (ms) for a player that returned a position but no usable duration.
     * Tries the previously-saved duration for this item, then the runtime from meta
     * (episode runtime for series, top-level runtime for movies). Returns 0 if unknown.
     */
    private suspend fun resolveFallbackDurationMs(metadata: ExternalPlaybackMetadata): Long {
        val existing = currentSavedProgress(metadata)
        if (existing != null && existing.duration > 0L) return existing.duration
        return fetchRuntimeMsFromMeta(metadata)
    }

    private suspend fun currentSavedProgress(metadata: ExternalPlaybackMetadata): WatchProgress? {
        val flow = if (metadata.season != null && metadata.episode != null) {
            watchProgressRepository.getEpisodeProgress(metadata.contentId, metadata.season, metadata.episode)
        } else {
            watchProgressRepository.getProgress(metadata.contentId)
        }
        return flow.firstOrNull()
    }

    private suspend fun fetchRuntimeMsFromMeta(metadata: ExternalPlaybackMetadata): Long {
        val fetched = withTimeoutOrNull(META_FETCH_TIMEOUT_MS) {
            metaRepository
                .getMetaFromAllAddons(type = metadata.contentType, id = metadata.contentId)
                .first { it !is NetworkResult.Loading }
        }
        val meta = (fetched as? NetworkResult.Success)?.data ?: return 0L
        val season = metadata.season
        val episode = metadata.episode
        val minutes: Int? = if (season != null && episode != null) {
            meta.videos.firstOrNull { it.season == season && it.episode == episode }?.runtime
                ?: parseRuntimeMinutes(meta.runtime)
        } else {
            parseRuntimeMinutes(meta.runtime)
        }
        return (minutes ?: 0).toLong() * 60_000L
    }

    /** Parses "24 min", "120", or "1h 30m" style runtime strings into minutes. */
    private fun parseRuntimeMinutes(runtime: String?): Int? {
        if (runtime.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*h").find(runtime)?.groupValues?.get(1)?.toIntOrNull()
        val mins = Regex("(\\d+)\\s*m").find(runtime)?.groupValues?.get(1)?.toIntOrNull()
        if (hours != null || mins != null) return (hours ?: 0) * 60 + (mins ?: 0)
        return Regex("\\d+").find(runtime)?.value?.toIntOrNull()
    }

    // --- Disk persistence for pendingMetadata (survives process death) -------------

    private fun persistMetadata(m: ExternalPlaybackMetadata) {
        persistedPrefs.edit()
            .putString("contentId", m.contentId)
            .putString("contentType", m.contentType)
            .putString("contentName", m.contentName)
            .putString("poster", m.poster)
            .putString("backdrop", m.backdrop)
            .putString("logo", m.logo)
            .putString("videoId", m.videoId)
            .putInt("season", m.season ?: Int.MIN_VALUE)
            .putInt("episode", m.episode ?: Int.MIN_VALUE)
            .putString("episodeTitle", m.episodeTitle)
            .putString("year", m.year)
            .apply()
    }

    private fun persistAutoNextState(
        snapshot: ExternalNextEpisodeSnapshot,
        autoNextEnabled: Boolean?
    ) {
        persistedPrefs.edit()
            .putBoolean("autoNextEnabledPresent", autoNextEnabled != null)
            .putBoolean("autoNextEnabled", autoNextEnabled == true)
            .putBoolean("nextEpisodeSnapshotPresent", true)
            .putBoolean("nextEpisodeMetadataResolved", snapshot.metadataResolved)
            .putString("nextEpisodeVideoId", snapshot.nextVideoId)
            .putInt("nextEpisodeSeason", snapshot.nextSeason ?: Int.MIN_VALUE)
            .putInt("nextEpisodeNumber", snapshot.nextEpisode ?: Int.MIN_VALUE)
            .apply()
    }

    private fun loadPersistedAutoNextEnabled(): Boolean? {
        val p = persistedPrefs
        if (!p.getBoolean("autoNextEnabledPresent", false)) return null
        return p.getBoolean("autoNextEnabled", false)
    }

    private fun loadPersistedNextEpisodeSnapshot(): ExternalNextEpisodeSnapshot {
        val p = persistedPrefs
        if (!p.getBoolean("nextEpisodeSnapshotPresent", false)) {
            return ExternalNextEpisodeSnapshot.Unknown
        }
        return ExternalNextEpisodeSnapshot(
            metadataResolved = p.getBoolean("nextEpisodeMetadataResolved", false),
            nextVideoId = p.getString("nextEpisodeVideoId", null),
            nextSeason = p.getInt("nextEpisodeSeason", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE },
            nextEpisode = p.getInt("nextEpisodeNumber", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
        )
    }

    private fun loadPersistedMetadata(): ExternalPlaybackMetadata? {
        val p = persistedPrefs
        val contentId = p.getString("contentId", null) ?: return null
        val season = p.getInt("season", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val episode = p.getInt("episode", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        return ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = p.getString("contentType", "movie") ?: "movie",
            contentName = p.getString("contentName", "") ?: "",
            poster = p.getString("poster", null),
            backdrop = p.getString("backdrop", null),
            logo = p.getString("logo", null),
            videoId = p.getString("videoId", contentId) ?: contentId,
            season = season,
            episode = episode,
            episodeTitle = p.getString("episodeTitle", null),
            year = p.getString("year", null)
        )
    }

    private fun clearPersistedMetadata() {
        persistedPrefs.edit().clear().apply()
    }

    // ===================== Completion + auto-next =====================

    private fun startNextEpisodePrefetch(metadata: ExternalPlaybackMetadata) {
        if (!ExternalAutoNextPolicy.shouldAttemptAdvance(
                episode = metadata.episode,
                contentType = metadata.contentType,
                cancelled = false,
                chainAborted = false
            )) {
            return
        }

        nextEpisodePrefetchJob = scope.launch {
            val enabled = autoNextEnabledForPendingLaunch
                ?: playerSettingsDataStore.playerSettings.first()
                    .streamAutoPlayNextEpisodeEnabled
            autoNextEnabledForPendingLaunch = enabled
            persistAutoNextState(nextEpisodeSnapshot, enabled)
            if (!enabled) return@launch

            repeat(NEXT_EPISODE_PREFETCH_ATTEMPTS) { attempt ->
                val refreshedSnapshot = resolveNextEpisodeSnapshot(metadata)
                if (refreshedSnapshot.metadataResolved) {
                    // Keep a playable successor captured from the screen's complete episode list
                    // if a background addon refresh returns a thinner list with no successor.
                    val keepLoadedSuccessor = nextEpisodeSnapshot.hasNextEpisode == true &&
                        refreshedSnapshot.hasNextEpisode == false
                    if (!keepLoadedSuccessor) {
                        nextEpisodeSnapshot = refreshedSnapshot
                        persistAutoNextState(refreshedSnapshot, enabled)
                    }
                    if (refreshedSnapshot.hasNextEpisode == false) {
                        Log.d(
                            AUTO_NEXT_TAG,
                            if (keepLoadedSuccessor) {
                                "Prefetch returned no successor; retaining loaded episode snapshot"
                            } else {
                                "Prefetch confirmed no next episode after S${metadata.season}E${metadata.episode}"
                            }
                        )
                    } else {
                        Log.d(
                            AUTO_NEXT_TAG,
                            "Prefetched next episode S${refreshedSnapshot.nextSeason}" +
                                "E${refreshedSnapshot.nextEpisode} videoId=${refreshedSnapshot.nextVideoId}"
                        )
                    }
                    return@launch
                }

                val attemptNumber = attempt + 1
                Log.d(
                    AUTO_NEXT_TAG,
                    "Next episode prefetch attempt $attemptNumber/$NEXT_EPISODE_PREFETCH_ATTEMPTS failed"
                )
                if (attemptNumber < NEXT_EPISODE_PREFETCH_ATTEMPTS) {
                    delay(NEXT_EPISODE_PREFETCH_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun resolveNextEpisodeSnapshot(
        metadata: ExternalPlaybackMetadata
    ): ExternalNextEpisodeSnapshot {
        val result = withTimeoutOrNull(META_FETCH_TIMEOUT_MS) {
            metaRepository
                .getMetaFromAllAddons(type = metadata.contentType, id = metadata.contentId)
                .first { it !is NetworkResult.Loading }
        }
        val meta = (result as? NetworkResult.Success)?.data
            ?: return ExternalNextEpisodeSnapshot.Unknown
        return resolveExternalNextEpisodeSnapshot(
            videos = meta.videos,
            currentSeason = metadata.season,
            currentEpisode = metadata.episode
        )
    }

    // True on a natural end (end_by != "user"), or for players without end_by once the
    // position reaches COMPLETED_THRESHOLD (90%).
    private fun isPlaybackCompleted(result: ExternalPlayerResult): Boolean {
        if (!result.endedByUser) return true
        val duration = result.durationMs ?: 0L
        return duration > 0L &&
            result.positionMs >= (WatchProgress.COMPLETED_THRESHOLD * duration).toLong()
    }

    /**
     * Resolves the next episode via the same rules the internal player uses
     * ([PlayerNextEpisodeRules.resolveNextEpisode]), gated by the "Auto-play next
     * episode" setting, then emits an event for MainActivity to navigate.
     * [metadata] is captured by value so it survives stopTracking() clearing it.
     */
    private fun maybeTriggerAutoNextEpisode(metadata: ExternalPlaybackMetadata) {
        val season = metadata.season
        val episode = metadata.episode
        // Season may be null (absolute-numbered anime); only the episode and a series/tv type are
        // required. The `episode == null` here is redundant with the policy but gives the smart cast.
        val attemptAdvance = ExternalAutoNextPolicy.shouldAttemptAdvance(
            episode = episode,
            contentType = metadata.contentType,
            cancelled = autoNextCancelled,
            chainAborted = autoNextChainAborted
        )
        if (!attemptAdvance || episode == null) {
            Log.d(AUTO_NEXT_TAG, "Auto-next not attempted: season=$season episode=$episode " +
                "type=${metadata.contentType} cancelled=$autoNextCancelled chainAborted=$autoNextChainAborted")
            _autoNextOverlay.value = null
            return
        }

        // Reuse the overlay raised in onStart so ownership remains stable through navigation.
        val overlay = _autoNextOverlay.value ?: ExternalAutoNextOverlay(
            backdrop = metadata.backdrop ?: metadata.poster,
            logo = metadata.logo,
            title = metadata.contentName
        )
        fun dismissOverlayIfCurrent() {
            if (_autoNextOverlay.value === overlay) _autoNextOverlay.value = null
        }

        autoNextJob?.cancel()
        autoNextJob = scope.launch {
            // Gate exactly like the internal path does.
            val autoPlayNextEnabled = autoNextEnabledForPendingLaunch
                ?: playerSettingsDataStore.playerSettings.first()
                    .streamAutoPlayNextEpisodeEnabled
            if (!autoPlayNextEnabled) {
                Log.d(AUTO_NEXT_TAG, "Auto-play next episode is OFF; skipping auto-advance")
                dismissOverlayIfCurrent()
                return@launch
            }

            // A snapshot from the already loaded episode list is immediately authoritative. If
            // that was unavailable, briefly join the background refresh before resolving here.
            if (!nextEpisodeSnapshot.metadataResolved) {
                nextEpisodePrefetchJob?.let { prefetchJob ->
                    withTimeoutOrNull(NEXT_EPISODE_PREFETCH_RETURN_WAIT_MS) { prefetchJob.join() }
                    if (prefetchJob.isActive) prefetchJob.cancel()
                }
            }
            val resolvedSnapshot = nextEpisodeSnapshot.takeIf { it.metadataResolved }
                ?: resolveNextEpisodeSnapshot(metadata).also { refreshed ->
                    if (refreshed.metadataResolved) {
                        nextEpisodeSnapshot = refreshed
                        persistAutoNextState(refreshed, autoPlayNextEnabled)
                    }
                }
            if (!resolvedSnapshot.metadataResolved) {
                Log.d(AUTO_NEXT_TAG, "Could not load series meta for ${metadata.contentId} (timeout or error); skipping")
                dismissOverlayIfCurrent()
                return@launch
            }

            val nextVideoId = resolvedSnapshot.nextVideoId
            val nextEpisode = resolvedSnapshot.nextEpisode
            if (nextVideoId == null || nextEpisode == null) {
                Log.d(AUTO_NEXT_TAG, "No next episode after S${season}E${episode} for ${metadata.contentId}")
                dismissOverlayIfCurrent()
                return@launch
            }
            val nextSeason = resolvedSnapshot.nextSeason

            val shouldShowLoader = ExternalAutoNextPolicy.shouldRaiseLoader(
                episode = episode,
                contentType = metadata.contentType,
                cancelled = autoNextCancelled,
                chainAborted = autoNextChainAborted,
                overlaySuppressed = autoNextOverlaySuppressed,
                alreadyShowing = _autoNextOverlay.value != null,
                autoNextEnabled = true,
                hasNextEpisode = true
            )
            if (shouldShowLoader) _autoNextOverlay.value = overlay

            Log.d(
                AUTO_NEXT_TAG,
                "Next episode resolved: S${nextSeason}E${nextEpisode} videoId=$nextVideoId " +
                    "(from S${season}E${episode}, content=${metadata.contentId})"
            )

            // Mark the time of this emit so the resulting launch is recognised as a chain
            // continuation (and a user abort survives it).
            lastAutoNextEmitMs = System.currentTimeMillis()
            autoNextNavigationPending = true
            _autoPlayNext.emit(
                ExternalAutoNextEpisode(
                    contentId = metadata.contentId,
                    contentType = metadata.contentType,
                    contentName = metadata.contentName,
                    poster = metadata.poster,
                    backdrop = metadata.backdrop,
                    logo = metadata.logo,
                    year = metadata.year,
                    nextVideoId = nextVideoId,
                    nextSeason = nextSeason,
                    nextEpisode = nextEpisode
                )
            )

            // StreamScreen explicitly releases on manual fallback. This is only a final guard
            // beyond its 60 second source-resolution timeout.
            delay(AUTO_NEXT_OVERLAY_TIMEOUT_MS)
            Log.d(AUTO_NEXT_TAG, "safety-net timeout -> clearing loader")
            autoNextNavigationPending = false
            // Clear unconditionally: the identity-guarded variant left a stale overlay stuck when a
            // re-raise had replaced the object this job captured.
            _autoNextOverlay.value = null
        }
    }

    // ===================== "Loading next episode" loader =====================
    // WARNING: autoNextOverlay is the ONLY cover for the player->Nuvio transition. To hide the
    // episode-list flash, raise THIS loader early (raiseAutoNextOverlayOnReturn). Do NOT add a
    // second full-screen cover to mask the gap — a competing overlay caused flicker and hid this
    // loader's text. Cancellation: backing out sets autoNextCancelled (reset per launch in
    // startTracking) so neither the loader nor the advance re-fires for the current return.

    /** Hide the loader and cancel the pending auto-next, so backing out actually stops it instead
     *  of advancing anyway. Sets the durable chain abort too, so one Back press stops a runaway
     *  auto-next loop (it won't re-fire until a fresh/manual launch). Progress stays saved. */
    fun dismissAutoNextOverlay() {
        Log.d(AUTO_NEXT_TAG, "dismissAutoNextOverlay (user back) overlayWasShowing=${_autoNextOverlay.value != null}")
        autoNextCancelled = true
        autoNextChainAborted = true
        autoNextJob?.cancel()
        autoNextJob = null
        _autoNextOverlay.value = null
    }

    /** Hide the loader when Stream settles. A possible active handoff retains ownership until the
     * next external player covers Nuvio. Explicit manual fallback and launch failures force it off. */
    fun releaseAutoNextOverlay(forceRelease: Boolean = false) {
        if (!forceRelease && ExternalAutoNextPolicy.shouldIgnoreLoaderRelease(isTracking)) {
            Log.d(AUTO_NEXT_TAG, "releaseAutoNextOverlay ignored while external player is active")
            return
        }
        val overlayShowing = _autoNextOverlay.value != null
        val holdLoader = ExternalAutoNextPolicy.shouldHoldLoaderOnSettle(
            overlayShowing = overlayShowing,
            handoffActive = autoNextJob?.isActive == true,
            mayHaveNextEpisode = nextEpisodeSnapshot.hasNextEpisode != false,
            forceRelease = forceRelease
        )
        Log.d(
            AUTO_NEXT_TAG,
            "releaseAutoNextOverlay (settle) overlayWasShowing=$overlayShowing " +
                "holdLoader=$holdLoader forceRelease=$forceRelease"
        )
        if (holdLoader) return
        if (forceRelease || !autoNextCancelled) {
            autoNextNavigationPending = false
        }
        autoNextOverlaySuppressed = true
        _autoNextOverlay.value = null
    }

    /** Forward StreamScreen's detailed launch stage into the continuous root overlay. Null
     * messages retain the previous stage so intermediate state cleanup cannot flash generic text. */
    fun updateAutoNextOverlayStatus(message: String?, progress: Float?) {
        val current = _autoNextOverlay.value ?: return
        val updated = current.copy(
            message = message?.takeIf { it.isNotBlank() } ?: current.message,
            progress = progress
        )
        if (updated == current) return
        _autoNextOverlay.value = updated
        Log.d(AUTO_NEXT_TAG, "loader status message=${updated.message} progress=${updated.progress}")
    }

    /** The launched external player now owns the display, so the Nuvio transition cover can end. */
    fun onExternalPlayerCoveredApp() {
        if (!isTracking) return
        Log.d(AUTO_NEXT_TAG, "external player covered app -> clearing transition loader")
        autoNextJob?.cancel()
        autoNextJob = null
        autoNextNavigationPending = false
        _autoNextOverlay.value = null
    }

    /** The next-episode auto-play was navigated to but the user has aborted the chain — the Stream
     *  screen calls this to skip the auto-launch and fall back to the source list. Only within the
     *  continuation window, so it can't suppress a fresh first auto-play of an unrelated title. */
    fun isAutoNextContinuationAborted(): Boolean =
        ExternalAutoNextPolicy.isAbortedContinuation(
            chainAborted = autoNextChainAborted,
            nowMs = System.currentTimeMillis(),
            lastAutoNextEmitMs = lastAutoNextEmitMs,
            continuationWindowMs = CONTINUATION_WINDOW_MS
        )

    /** Called by the Stream screen when it skips an aborted continuation, so the window expires and
     *  the next launch is treated as fresh (re-enabling auto-next). */
    fun consumeAbortedAutoNextContinuation() {
        lastAutoNextEmitMs = 0L
        autoNextNavigationPending = false
    }

    private fun shouldCancelPendingAutoLaunch(autoLaunch: Boolean): Boolean =
        ExternalAutoNextPolicy.shouldCancelPendingAutoLaunch(
            autoLaunch = autoLaunch,
            autoNextNavigationPending = autoNextNavigationPending,
            cancelled = autoNextCancelled,
            chainAborted = autoNextChainAborted
        )

    /** Raise the loader the instant we return (from MainActivity.onStart, before the result is
     *  parsed and the window repaints) so there's no episode-list flash. No-op for non-episodes;
     *  idempotent. Kept for a completion, dismissed by onActivityResult otherwise. */
    fun raiseAutoNextOverlayOnReturn() {
        val metadata = pendingMetadata ?: loadPersistedMetadata() ?: return
        if (pendingMetadata == null) {
            nextEpisodeSnapshot = loadPersistedNextEpisodeSnapshot()
            autoNextEnabledForPendingLaunch = loadPersistedAutoNextEnabled()
        }
        raiseAutoNextOverlay(metadata, allowUnknownNextEpisode = true)
    }

    private fun raiseAutoNextOverlay(
        metadata: ExternalPlaybackMetadata,
        allowUnknownNextEpisode: Boolean = false
    ) {
        val hasNextEpisode = nextEpisodeSnapshot.hasNextEpisode
        val shouldRaise = ExternalAutoNextPolicy.shouldRaiseLoader(
            episode = metadata.episode,
            contentType = metadata.contentType,
            cancelled = autoNextCancelled,
            chainAborted = autoNextChainAborted,
            overlaySuppressed = autoNextOverlaySuppressed,
            alreadyShowing = _autoNextOverlay.value != null,
            autoNextEnabled = autoNextEnabledForPendingLaunch,
            hasNextEpisode = hasNextEpisode,
            allowUnknownNextEpisode = allowUnknownNextEpisode
        )
        if (!shouldRaise) {
            if (hasNextEpisode == false) {
                Log.d(AUTO_NEXT_TAG, "loader skipped for known final episode ${metadata.videoId}")
            }
            return
        }
        _autoNextOverlay.value = ExternalAutoNextOverlay(
            backdrop = metadata.backdrop ?: metadata.poster,
            logo = metadata.logo,
            title = metadata.contentName
        )
        Log.d(AUTO_NEXT_TAG, "raised loader for ${metadata.videoId}")
    }

    // ===================== Tracking lifecycle + Zidoo =====================

    /** Stop tracking and clean up resources. */
    fun stopTracking() {
        zidooMonitorJob?.cancel()
        zidooMonitorJob = null
        pendingMetadata = null
        isAutoLaunch = false
        ExternalPlaybackKeepAliveService.stop(appContext)
        Log.d(TAG, "Stopped tracking")
    }

    /**
     * Called on Zidoo when the user returns to the app.
     * Does NOT cancel the monitor job — it needs to finish detecting playback end
     * and saving progress. Only clears the auto-launch flag so the UI can dismiss overlays.
     */
    fun dismissOverlayOnly() {
        isAutoLaunch = false
        Log.d(TAG, "Dismissed overlay only (Zidoo monitor still running)")
    }

    private fun startZidooMonitor(metadata: ExternalPlaybackMetadata) {
        zidooMonitorJob?.cancel()
        zidooMonitorJob = scope.launch(Dispatchers.Default) {
            val resumePosition = getResumePosition(metadata)
            val result = ZidooPlayerMonitor.awaitPlaybackEnd(resumePositionMs = resumePosition)
            if (result != null) {
                Log.d(TAG, "Zidoo monitor: pos=${result.positionMs}ms, dur=${result.durationMs}ms")
                saveProgress(metadata, result.positionMs, result.durationMs)
            }
            // Don't call stopTracking here - let the ActivityResult path handle it
            // (on Zidoo, ActivityResult won't fire, so we stop after saving)
            pendingMetadata = null
            ExternalPlaybackKeepAliveService.stop(appContext)
        }
    }

    private suspend fun getResumePosition(metadata: ExternalPlaybackMetadata): Long {
        val flow = if (metadata.season != null && metadata.episode != null) {
            watchProgressRepository.getEpisodeProgress(metadata.contentId, metadata.season, metadata.episode)
        } else {
            watchProgressRepository.getProgress(metadata.contentId)
        }
        val wp = flow.firstOrNull() ?: return 0L
        if (wp.isCompleted()) return 0L
        return if (wp.duration > 0L) {
            wp.resolveResumePosition(wp.duration)
        } else {
            wp.position
        }
    }

    // ===================== Progress save + Trakt scrobble =====================

    private fun saveProgress(
        metadata: ExternalPlaybackMetadata,
        positionMs: Long,
        durationMs: Long?,
        explicitPercent: Float? = null
    ) {
        val effectiveDuration = durationMs ?: 0L

        scope.launch {
            val progress = WatchProgress(
                contentId = metadata.contentId,
                contentType = metadata.contentType,
                name = metadata.contentName,
                poster = metadata.poster,
                backdrop = metadata.backdrop,
                logo = metadata.logo,
                videoId = metadata.videoId,
                season = metadata.season,
                episode = metadata.episode,
                episodeTitle = metadata.episodeTitle,
                position = positionMs,
                duration = effectiveDuration,
                progressPercent = explicitPercent,
                lastWatched = System.currentTimeMillis()
            )
            Log.d(TAG, "Saving progress: pos=${positionMs}ms, dur=${effectiveDuration}ms, " +
                "content=${metadata.contentId}, video=${metadata.videoId}, " +
                "progressPct=${progress.progressPercentage}, isInProgress=${progress.isInProgress()}")
            watchProgressRepository.saveProgress(progress)

            // Trakt scrobble
            if (traktAuthService.getCurrentAuthState().isAuthenticated &&
                traktAuthService.hasRequiredCredentials()) {
                val progressPercent = if (effectiveDuration > 0L) {
                    (positionMs.toFloat() / effectiveDuration.toFloat() * 100f).coerceIn(0f, 100f)
                } else {
                    0f
                }
                if (progressPercent > 0f) {
                    val scrobbleItem = buildScrobbleItem(metadata)
                    if (scrobbleItem != null) {
                        Log.d(TAG, "Sending Trakt scrobble: ${progressPercent}%")
                        traktScrobbleService.scrobbleStart(scrobbleItem, progressPercent = 0f)
                        traktScrobbleService.scrobbleStop(scrobbleItem, progressPercent = progressPercent)
                    }
                }
            }
        }
    }

    private suspend fun buildScrobbleItem(metadata: ExternalPlaybackMetadata): TraktScrobbleItem? {
        val parsedIds = parseContentIds(metadata.contentId)
        val ids = toTraktIds(parsedIds)
        if (ids.trakt == null && ids.imdb.isNullOrBlank() && ids.tmdb == null) return null

        val parsedYear = extractYear(metadata.year)
        val isEpisode = metadata.contentType.lowercase() in listOf("series", "tv") &&
            metadata.season != null && metadata.episode != null

        return if (isEpisode) {
            val mapped = traktEpisodeMappingService.prefetchEpisodeMapping(
                contentId = metadata.contentId,
                contentType = metadata.contentType,
                videoId = metadata.videoId,
                season = metadata.season,
                episode = metadata.episode
            )
            val effectiveSeason = mapped?.season ?: metadata.season ?: return null
            val effectiveEpisode = mapped?.episode ?: metadata.episode ?: return null

            TraktScrobbleItem.Episode(
                showTitle = metadata.contentName,
                showYear = parsedYear,
                showIds = ids,
                season = effectiveSeason,
                number = effectiveEpisode,
                episodeTitle = metadata.episodeTitle
            )
        } else {
            TraktScrobbleItem.Movie(
                title = metadata.contentName,
                year = parsedYear,
                ids = ids
            )
        }
    }
}
