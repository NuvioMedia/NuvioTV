package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val nextSeason: Int,
    val nextEpisode: Int,
    // Lets the collector skip a value replayed after a config change while still
    // acting on a genuinely new event after a process restart.
    val requestedAtMs: Long = System.currentTimeMillis()
)

/** Visuals for the loader shown while an external episode auto-advances. */
data class ExternalAutoNextOverlay(
    val backdrop: String?,
    val logo: String?,
    val title: String?
)

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
    private val playerSettingsDataStore: PlayerSettingsDataStore
) {
    companion object {
        private const val TAG = "ExtPlaybackTracker"
        private const val AUTO_NEXT_TAG = "ExtAutoNext"
        /** Max time the auto-advance loader stays up if the next player never launches. */
        private const val AUTO_NEXT_OVERLAY_TIMEOUT_MS = 20_000L
        /** Max time to wait for series meta when resolving the next episode. */
        private const val META_FETCH_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var zidooMonitorJob: Job? = null

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
    fun startTracking(metadata: ExternalPlaybackMetadata, autoLaunch: Boolean = false) {
        pendingMetadata = metadata
        isAutoLaunch = autoLaunch
        // Next player is launching and will cover the screen — drop the loader.
        _autoNextOverlay.value = null
        // Persist so progress-save + auto-next survive the player killing our process.
        persistMetadata(metadata)

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
    fun launchPlayer(
        metadata: ExternalPlaybackMetadata,
        url: String,
        title: String?,
        headers: Map<String, String>?,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null,
        autoLaunch: Boolean = false,
        context: Context
    ) {
        startTracking(metadata, autoLaunch = autoLaunch)

        // Fetch resume position if not provided, then launch
        if (resumePositionMs > 0L) {
            doLaunch(url, title, headers, resumePositionMs, subtitles, context)
        } else {
            scope.launch {
                val position = getResumePosition(metadata)
                doLaunch(url, title, headers, position, subtitles, context)
            }
        }
    }

    private fun doLaunch(
        url: String,
        title: String?,
        headers: Map<String, String>?,
        resumePositionMs: Long,
        subtitles: List<SubtitleInput>?,
        context: Context
    ) {

        val input = ExternalPlayerInput(
            url = url,
            title = title,
            headers = headers,
            resumePositionMs = resumePositionMs,
            subtitles = subtitles
        )

        if (ZidooPlayerMonitor.isZidooDevice()) {
            // Zidoo doesn't return ActivityResult - use fire-and-forget
            ExternalPlayerLauncher.launch(
                context = context,
                url = url,
                title = title,
                headers = headers,
                resumePositionMs = resumePositionMs,
                subtitles = subtitles
            )
        } else {
            // Use Activity-level launcher for ActivityResult
            val launcher = activityLauncher
            if (launcher != null) {
                try {
                    launcher.launch(input)
                } catch (e: Exception) {
                    Log.w(TAG, "ActivityResultLauncher failed, falling back to fire-and-forget", e)
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = title,
                        headers = headers,
                        resumePositionMs = resumePositionMs,
                        subtitles = subtitles
                    )
                }
            } else {
                Log.w(TAG, "No activityLauncher registered, using fire-and-forget")
                ExternalPlayerLauncher.launch(
                    context = context,
                    url = url,
                    title = title,
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = subtitles
                )
            }
        }
    }

    /**
     * Called when ActivityResult is received from external player.
     * Processes the result and saves progress.
     */
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
        }

        if (result != null) {
            Log.d(TAG, "External player returned: pos=${result.positionMs}ms, dur=${result.durationMs}ms, endedByUser=${result.endedByUser}")
            saveProgress(metadata, result.positionMs, result.durationMs)
            // If the episode finished naturally, try to auto-advance to the next one.
            if (isPlaybackCompleted(result)) {
                maybeTriggerAutoNextEpisode(metadata)
            }
        } else {
            Log.d(TAG, "External player returned no progress data")
        }

        // Result consumed — safe to drop the persisted copy now.
        clearPersistedMetadata()

        // On Zidoo, the monitor job handles progress - don't stop it prematurely
        if (!ZidooPlayerMonitor.isZidooDevice() || result != null) {
            stopTracking()
        }
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

    // True on a natural end (end_by != "user") or, for players that don't report
    // end_by, when position reached COMPLETED_THRESHOLD (90%).
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
        val type = metadata.contentType.lowercase()
        if (season == null || episode == null || type !in listOf("series", "tv")) {
            return
        }

        // Show the loader before the async work below so it covers the cold-start window.
        val overlay = ExternalAutoNextOverlay(
            backdrop = metadata.backdrop ?: metadata.poster,
            logo = metadata.logo,
            title = metadata.contentName
        )
        _autoNextOverlay.value = overlay
        fun dismissOverlayIfCurrent() {
            if (_autoNextOverlay.value === overlay) _autoNextOverlay.value = null
        }

        scope.launch {
            // Gate exactly like the internal path does.
            val autoPlayNextEnabled = playerSettingsDataStore.playerSettings.first()
                .streamAutoPlayNextEpisodeEnabled
            if (!autoPlayNextEnabled) {
                Log.d(AUTO_NEXT_TAG, "Auto-play next episode is OFF; skipping auto-advance")
                dismissOverlayIfCurrent()
                return@launch
            }

            // Bounded so a hung addon flow (never emits non-Loading) can't suspend here
            // forever and leave the loader up — withTimeoutOrNull returns null on timeout.
            val result = withTimeoutOrNull(META_FETCH_TIMEOUT_MS) {
                metaRepository
                    .getMetaFromAllAddons(type = metadata.contentType, id = metadata.contentId)
                    .first { it !is NetworkResult.Loading }
            }
            val meta = (result as? NetworkResult.Success)?.data
            if (meta == null) {
                Log.d(AUTO_NEXT_TAG, "Could not load series meta for ${metadata.contentId} (timeout or error); skipping")
                dismissOverlayIfCurrent()
                return@launch
            }

            val nextVideo = PlayerNextEpisodeRules.resolveNextEpisode(
                videos = meta.videos,
                currentSeason = season,
                currentEpisode = episode
            )
            val nextSeason = nextVideo?.season
            val nextEpisode = nextVideo?.episode
            if (nextVideo == null || nextSeason == null || nextEpisode == null) {
                Log.d(AUTO_NEXT_TAG, "No next episode after S${season}E${episode} for ${metadata.contentId}")
                dismissOverlayIfCurrent()
                return@launch
            }

            Log.d(
                AUTO_NEXT_TAG,
                "Next episode resolved: S${nextSeason}E${nextEpisode} videoId=${nextVideo.id} " +
                    "(from S${season}E${episode}, content=${metadata.contentId})"
            )

            _autoPlayNext.emit(
                ExternalAutoNextEpisode(
                    contentId = metadata.contentId,
                    contentType = metadata.contentType,
                    contentName = metadata.contentName,
                    poster = metadata.poster,
                    backdrop = metadata.backdrop,
                    logo = metadata.logo,
                    year = metadata.year,
                    nextVideoId = nextVideo.id,
                    nextSeason = nextSeason,
                    nextEpisode = nextEpisode
                )
            )

            // Safety net: normally cleared when the next player launches, but in Manual
            // mode the Stream screen waits for the user, so don't leave the loader stuck.
            delay(AUTO_NEXT_OVERLAY_TIMEOUT_MS)
            dismissOverlayIfCurrent()
        }
    }

    /** Hide the auto-advance loader (e.g. user pressed Back to cancel waiting). */
    fun dismissAutoNextOverlay() {
        _autoNextOverlay.value = null
    }

    /**
     * Stop tracking and clean up resources.
     */
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

    private fun saveProgress(metadata: ExternalPlaybackMetadata, positionMs: Long, durationMs: Long?) {
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
