package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Floppy is a scrobble-only tracker for now: it receives playback events and nothing else. */
@Singleton
class FloppyTrackingProvider @Inject constructor(
    auth: FloppyAuthStore,
    override val scrobbler: FloppyTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.FLOPPY,
        "Floppy",
        setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.SCROBBLE)
    )

    override val isAuthenticated: StateFlow<Boolean> = auth.state
        .map { it.isConnected }
        .stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            SharingStarted.Eagerly,
            auth.state.value.isConnected
        )
}

@Singleton
class FloppyTrackingScrobbler @Inject constructor(
    private val auth: FloppyAuthStore,
    private val api: FloppyApiClient
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.FLOPPY

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val credentials = auth.credentials() ?: return
        val body = floppyScrobbleBody(action, event) ?: return
        api.scrobble(credentials, body)
    }
}

/** NuvioTV treats 80% as watched everywhere; Floppy is told the same so both agree. */
internal const val FLOPPY_COMPLETED_PERCENT = 80.0

/**
 * Floppy tracks any stop as In Progress, and without a position it cannot tell a skim from a
 * real viewing. A stop under 1% is not sent; 1% is also NuvioTV's own cut-off for a seek.
 */
internal const val FLOPPY_MIN_STOP_PERCENT = 1.0

/**
 * Builds the body of `POST /api/v1/scrobble/`, or null when the event cannot be matched to a
 * movie or episode by id (Floppy never guesses by title, so there is nothing useful to send).
 *
 * NuvioTV only knows a percentage, not seconds, so a stop sends an explicit `completed` flag
 * instead of a position. Floppy then records a watch only at or above the completion mark, and
 * tracks anything shorter as In Progress.
 */
internal fun floppyScrobbleBody(action: TrackingScrobbleAction, event: TrackingScrobbleEvent): JsonObject? {
    val percent = event.progressPercent
    if (!percent.isFinite()) return null
    if (action == TrackingScrobbleAction.STOP && percent < FLOPPY_MIN_STOP_PERCENT) return null
    val media = event.media
    val ids = media.ids
    val imdb = ids.imdb?.takeIf { IMDB_ID.matches(it) }
    val tmdb = ids.tmdb?.takeIf { it > 0L }
    val tvdb = ids.tvdb?.toLongOrNull()?.takeIf { it > 0L }
    if (imdb == null && tmdb == null && tvdb == null) return null

    val isMovie = media.kind == TrackingMediaKind.MOVIE
    val episode = media.episode.takeUnless { isMovie }
    if (!isMovie) {
        // An episode needs real coordinates, and TVDB-ordered numbering is not what Floppy resolves by.
        if (episode == null || episode.usesTvdbSeasonMapping) return null
        val season = episode.season ?: return null
        if (season < 0 || episode.number < 1) return null
    }

    return buildJsonObject {
        put("action", action.wireValue)
        put("media_type", if (isMovie) "movie" else "episode")
        put("ids", buildJsonObject {
            imdb?.let { put("imdb", it) }
            tmdb?.let { put("tmdb", it.toString()) }
            tvdb?.let { put("tvdb", it.toString()) }
            ids.anidb?.takeIf { it > 0L && !isMovie }?.let { put("anidb", it.toString()) }
        })
        if (isMovie) {
            media.title?.let { put("title", it) }
        } else if (episode != null) {
            media.title?.let { put("series_title", it) }
            episode.title?.let { put("title", it) }
            put("season_number", requireNotNull(episode.season))
            put("episode_number", episode.number)
        }
        if (action == TrackingScrobbleAction.STOP) {
            put("completed", percent >= FLOPPY_COMPLETED_PERCENT)
        }
    }
}

private val IMDB_ID = Regex("tt[0-9]+")
