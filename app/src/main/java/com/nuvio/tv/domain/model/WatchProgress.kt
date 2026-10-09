package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents the watch progress for a content item (movie or episode).
 */
@Immutable
data class WatchProgress(
    val contentId: String,           // IMDB ID of the movie/series
    val contentType: String,         // "movie" or "series"
    val name: String,                // Movie or series name
    val poster: String?,             // Poster URL
    val backdrop: String?,           // Backdrop URL
    val logo: String?,               // Logo URL
    val videoId: String,             // Specific video/episode ID being watched
    val season: Int?,                // Season number (null for movies)
    val episode: Int?,               // Episode number (null for movies)
    val episodeTitle: String?,       // Episode title (null for movies)
    val position: Long,              // Current playback position in ms
    val duration: Long,              // Total duration in ms
    val lastWatched: Long,           // Timestamp when last watched
    val addonBaseUrl: String? = null, // Addon that was used to play
    val progressPercent: Float? = null, // 0..100 from remote sources like Trakt playback
    val source: String = SOURCE_LOCAL,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val simklPlaybackId: Long? = null,
    override val trackingProviderId: String? = null,
    override val trackingProviderItemId: String? = null,
    override val trackingSourceUrl: String? = null,
    val completionThresholdOverride: Float? = null,
    val excludedNextUpSeasons: Set<Int> = emptySet(),
    /**
     * The threshold the row was reported with, as a fraction, for the rows where that number still
     * decides something.
     *
     * A Simkl playback row is read back from the account and carries the completion threshold its
     * write side reported it under, which is the user's `simklWatchedThresholdPercent` and not Simkl's
     * own 80 percent. The field is what the row was reported with, not what the row is decided by:
     * [isCompleted] short circuits on [isProviderPlaybackPosition] before it ever reads this number,
     * because a row a provider keeps open is a position that does not complete on a percentage. For a
     * row that is not a provider playback position this number is still the threshold it is read with,
     * and [progressPercentage] is compared against it. Null keeps the default of the source
     * ([COMPLETED_THRESHOLD], or [SIMKL_COMPLETED_THRESHOLD] for a Simkl playback row that arrived
     * without one).
     *
     * Held next to [completionThresholdOverride], which a source sets for its own rows; both end up
     * deciding the same thing, but they are filled in by different producers.
     */
    val completionThresholdFraction: Float? = null,
    /**
     * True for a row that is a position a provider keeps open, not a watch it recorded.
     *
     * Where such a playback ends is the credits marker of the release being played, and a row the
     * provider publishes does not carry it, so its percentage alone cannot say the watch is over.
     * Reading it as completed drops the position out of Continue Watching, which is why this row never
     * completes on a percentage or a position. A watch the provider really recorded arrives as history
     * instead, and that row supersedes this one.
     *
     * A Simkl playback row and a Simkl watch recorded into history share [SOURCE_SIMKL_PLAYBACK], so
     * the flag is carried by the row the playback projection builds rather than derived from the
     * source.
     */
    val isProviderPlaybackPosition: Boolean = false
) : TrackingAttributedItem {
    override val trackingContentId: String
        get() = contentId

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
        const val SOURCE_TRAKT_HISTORY = "trakt_history"
        const val SOURCE_TRAKT_SHOW_PROGRESS = "trakt_show_progress"
        const val SOURCE_SIMKL_PLAYBACK = "simkl_playback"
        const val SOURCE_REMOTE_PLAYBACK = "remote_playback"
        const val SOURCE_REMOTE_HISTORY = "remote_history"
        const val STARTED_THRESHOLD = 0.02f
        const val COMPLETED_THRESHOLD = 0.90f
        const val SIMKL_COMPLETED_THRESHOLD = 0.80f
    }

    /**
     * Progress percentage (0.0 to 1.0)
     */
    val progressPercentage: Float
        get() {
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        }

    /**
     * Returns true if the content has been watched past the threshold (default 90%). A row a provider
     * keeps open never completes on its percentage, whatever threshold it is read with, because where
     * such a playback ends is the credits marker and the row does not carry one. Only a watch the
     * provider really recorded, which arrives as history, completes such a row.
     */
    fun isCompleted(threshold: Float = completionThreshold()): Boolean =
        !isProviderPlaybackPosition && progressPercentage >= threshold

    /**
     * Returns true if the content has been started but not completed. The complement of [isCompleted],
     * so a row a provider keeps open stays resumable instead of falling between both answers.
     */
    fun isInProgress(startThreshold: Float = STARTED_THRESHOLD, endThreshold: Float = completionThreshold()): Boolean =
        !isCompleted(endThreshold) && progressPercentage >= startThreshold

    private fun completionThreshold(): Float =
        completionThresholdOverride?.takeIf { it.isFinite() && it > 0f && it <= 1f }
            ?: completionThresholdFraction
            ?: if (source == SOURCE_SIMKL_PLAYBACK) SIMKL_COMPLETED_THRESHOLD else COMPLETED_THRESHOLD

    /**
     * Returns the remaining time in milliseconds
     */
    val remainingTime: Long
        get() = (duration - position).coerceAtLeast(0)

    fun resolveResumePosition(actualDuration: Long): Long {
        if (actualDuration <= 0) return position.coerceAtLeast(0L)
        // Position is the most precise resume indicator. Prefer it over
        // progressPercent even when saved duration is 0 (e.g. progress was
        // saved while paused and getEffectiveDuration returned 0).
        if (position > 0) {
            return position.coerceIn(0L, actualDuration)
        }
        progressPercent?.let { explicitPercent ->
            val fraction = (explicitPercent / 100f).coerceIn(0f, 1f)
            return (actualDuration * fraction).toLong()
        }
        return position.coerceAtLeast(0L)
    }
}

/**
 * Represents the next item to watch for a series or a movie to resume.
 */
@Immutable
data class NextToWatch(
    val watchProgress: WatchProgress?,  // Null if nothing has been watched yet
    val isResume: Boolean,              // True if resuming current item, false if next episode
    val nextVideoId: String?,           // Video ID to play next
    val nextSeason: Int?,               // Next season number
    val nextEpisode: Int?,              // Next episode number
    val displayText: String             // Text to show on button (e.g., "Resume S1E2", "Play S1E3")
)
