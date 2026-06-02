package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.toTraktIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
    private val traktAuthService: TraktAuthService
) {
    companion object {
        private const val TAG = "ExtPlaybackTracker"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var zidooMonitorJob: Job? = null

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

        // Start foreground service only on Zidoo 
        // If other devices will kill our process,
        // we can enable this for them as well
        if (ZidooPlayerMonitor.isZidooDevice()) {
            ExternalPlaybackKeepAliveService.start(appContext)
        }

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
        val metadata = pendingMetadata
        if (metadata == null) {
            Log.d(TAG, "onActivityResult but no pending metadata")
            stopTracking()
            return
        }

        if (result != null) {
            Log.d(TAG, "External player returned: pos=${result.positionMs}ms, dur=${result.durationMs}ms")
            saveProgress(metadata, result.positionMs, result.durationMs)
        } else {
            Log.d(TAG, "External player returned no progress data")
        }

        // On Zidoo, the monitor job handles progress - don't stop it prematurely
        if (!ZidooPlayerMonitor.isZidooDevice() || result != null) {
            stopTracking()
        }
    }

    /**
     * Stop tracking and clean up resources.
     */
    fun stopTracking() {
        zidooMonitorJob?.cancel()
        zidooMonitorJob = null
        pendingMetadata = null
        isAutoLaunch = false
        if (ZidooPlayerMonitor.isZidooDevice()) {
            ExternalPlaybackKeepAliveService.stop(appContext)
        }
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
        return wp.position
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
