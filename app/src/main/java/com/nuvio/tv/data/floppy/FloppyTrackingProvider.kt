package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Floppy receives playback events and the user's manual watched and unwatched changes. */
@Singleton
class FloppyTrackingProvider @Inject constructor(
    auth: FloppyAuthStore,
    override val scrobbler: FloppyTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.FLOPPY,
        "Floppy",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.SCROBBLE,
            TrackingCapability.WATCHED_WRITE
        )
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

/** Manual marks and unmarks travel through the same scrobble endpoint as playback. */
@Singleton
class FloppyTrackingHistoryWriter @Inject constructor(
    private val auth: FloppyAuthStore,
    private val api: FloppyApiClient
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.FLOPPY

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        val credentials = auth.credentials() ?: return TrackingMutationResult(0)
        if (auth.state.value.profileId != profileId) return TrackingMutationResult(0)
        val bodies = items
            .filterNot(TrackingHistoryItem::isWholeSeriesMark)
            .mapNotNull(::floppyManualMarkBody)
        return send(credentials, bodies)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        val credentials = auth.credentials() ?: return TrackingMutationResult(0)
        if (auth.state.value.profileId != profileId) return TrackingMutationResult(0)
        return send(credentials, items.mapNotNull(::floppyUnwatchBody))
    }

    private suspend fun send(
        credentials: FloppyCredentials,
        bodies: List<JsonObject>
    ): TrackingMutationResult {
        var notFound = 0
        bodies.forEach { body ->
            try {
                api.scrobble(credentials, body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FloppyApiException) {
                if (error.status != 404) throw error
                notFound++
            }
        }
        return TrackingMutationResult(attemptedCount = bodies.size, notFoundCount = notFound)
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

    return buildJsonObject {
        put("action", action.wireValue)
        if (!putMedia(event.media)) return null
        if (action == TrackingScrobbleAction.STOP) {
            put("completed", percent >= FLOPPY_COMPLETED_PERCENT)
        }
    }
}

/** A mark is a completed stop by hand, so it adds a play instead of inventing a duration. */
internal fun floppyManualMarkBody(item: TrackingHistoryItem): JsonObject? = buildJsonObject {
    put("action", TrackingScrobbleAction.STOP.wireValue)
    if (!putMedia(item.media)) return null
    put("completed", true)
    put("manual", true)
    item.watchedAtEpochMs?.let { put("played_at", Instant.ofEpochMilli(it).toString()) }
    val watchedAt = item.watchedAtEpochMs?.let { "-$it" }.orEmpty()
    put("client_event_id", "nuvio-mark-${item.media.stableKey}$watchedAt".take(200))
}

/** Unwatch retracts the play this client recorded, using the same identity a mark uses. */
internal fun floppyUnwatchBody(media: TrackingMediaReference): JsonObject? = buildJsonObject {
    put("action", "unwatch")
    if (!putMedia(media)) return null
}

private fun JsonObjectBuilder.putMedia(media: TrackingMediaReference): Boolean {
    val ids = media.ids
    val imdb = ids.imdb?.takeIf { IMDB_ID.matches(it) }
    val tmdb = ids.tmdb?.takeIf { it > 0L }
    val tvdb = ids.tvdb?.toLongOrNull()?.takeIf { it > 0L }
    if (imdb == null && tmdb == null && tvdb == null) return false

    val isMovie = media.kind == TrackingMediaKind.MOVIE
    val episode = media.episode.takeUnless { isMovie }
    if (!isMovie) {
        // An episode needs real coordinates, and TVDB-ordered numbering is not what Floppy resolves by.
        if (episode == null || episode.usesTvdbSeasonMapping) return false
        val season = episode.season ?: return false
        if (season < 0 || episode.number < 1) return false
    }

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
    return true
}

private val IMDB_ID = Regex("tt[0-9]+")
