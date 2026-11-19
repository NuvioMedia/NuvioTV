package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatingProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Singleton
class SimklRatings @Inject constructor(
    private val client: SimklApiClient,
    private val sync: SimklSyncRepository,
    private val profiles: ProfileManager
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val changes: Flow<Any?> = sync.state

    override fun supports(media: TrackingMediaReference): Boolean =
        media.episode == null && media.hasResolvableIdentity

    override fun rating(media: TrackingMediaReference): Double? =
        sync.state.value.snapshot.ratingEntry(media)?.userRating?.toDouble()

    override suspend fun rate(profileId: Int, media: TrackingMediaReference, rating: Int?): TrackingMutationResult {
        if (profileId != profiles.activeProfileId.value) return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        sync.ensureLoaded()
        val snapshot = sync.state.value.snapshot
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings",
                body = buildSimklRatingBody(
                    media = snapshot.enrichMediaReference(media),
                    entry = snapshot.ratingEntry(media),
                    rating = rating
                ),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val notFound = simklNotFoundCount(response.body)
        if (notFound == 0) sync.commitRating(media, rating)
        return TrackingMutationResult(attemptedCount = 1, notFoundCount = notFound.coerceAtMost(1))
    }
}

internal fun SimklSyncSnapshot.ratingEntry(media: TrackingMediaReference): SimklLibraryEntry? {
    val movie = media.kind == TrackingMediaKind.MOVIE ||
        media.catalog?.contentType?.lowercase() in setOf("movie", "film")
    val target = media.toSimklMedia()
    return entries.firstOrNull { entry ->
        entry.isMovieEntry() == movie && (
            media.catalog?.contentId?.let(entry::matchesSimklContentId) == true ||
                entry.media?.matchesTarget(target) == true
            )
    }
}

internal fun SimklSyncSnapshot.withRating(
    media: TrackingMediaReference,
    rating: Int?,
    ratedAt: String?
): SimklSyncSnapshot? {
    val entry = ratingEntry(media) ?: return null
    return copy(
        entries = entries.map { candidate ->
            if (candidate === entry) {
                candidate.copy(userRating = rating, userRatedAt = ratedAt.takeIf { rating != null })
            } else {
                candidate
            }
        }
    )
}

internal fun buildSimklRatingBody(
    media: TrackingMediaReference,
    entry: SimklLibraryEntry?,
    rating: Int?
): String {
    val anime = entry?.mediaType == SimklMediaType.ANIME ||
        entry == null && media.kind == TrackingMediaKind.ANIME
    val bucket = when {
        anime -> if (rating == null) "shows" else "anime"
        entry?.mediaType == SimklMediaType.MOVIES || entry == null && media.kind == TrackingMediaKind.MOVIE -> "movies"
        else -> "shows"
    }
    val item = buildJsonObject {
        rating?.let { put("rating", it) }
        media.title?.takeIf(String::isNotBlank)?.let { put("title", it) }
        media.year?.let { put("year", it) }
        media.ids.toSimklJsonObjectOrNull()?.let { put("ids", it) }
    }
    return buildJsonObject { put(bucket, JsonArray(listOf(item))) }.toString()
}

internal fun simklNotFoundCount(body: String): Int {
    val payload = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return 0
    val notFound = runCatching { payload["not_found"]?.jsonObject }.getOrNull() ?: return 0
    return notFound.values.sumOf { value -> (value as? JsonArray)?.size ?: 0 }
}
