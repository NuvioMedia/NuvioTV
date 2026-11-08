package com.nuvio.tv.data.anilist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val aniListJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
}

object AniListListStatus {
    const val CURRENT = "CURRENT"
    const val PLANNING = "PLANNING"
    const val COMPLETED = "COMPLETED"
    const val DROPPED = "DROPPED"
    const val PAUSED = "PAUSED"
    const val REPEATING = "REPEATING"
}

object AniListMediaStatus {
    const val FINISHED = "FINISHED"
    const val RELEASING = "RELEASING"
    const val NOT_YET_RELEASED = "NOT_YET_RELEASED"
    const val CANCELLED = "CANCELLED"
    const val HIATUS = "HIATUS"
}

@Serializable
data class AniListTitle(
    val userPreferred: String? = null,
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)

@Serializable
data class AniListCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val color: String? = null
)

@Serializable
data class AniListAiringEpisode(
    val episode: Int? = null,
    val airingAt: Long? = null
)

@Serializable
data class AniListFuzzyDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null
)

@Serializable
data class AniListMedia(
    val id: Long,
    val idMal: Long? = null,
    val siteUrl: String? = null,
    val status: String? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val seasonYear: Int? = null,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val isAdult: Boolean = false,
    val title: AniListTitle? = null,
    val coverImage: AniListCoverImage? = null,
    val startDate: AniListFuzzyDate? = null,
    val nextAiringEpisode: AniListAiringEpisode? = null
)

@Serializable
data class AniListEntry(
    val id: Long,
    val mediaId: Long,
    val status: String? = null,
    val progress: Int = 0,
    val repeat: Int = 0,
    val score: Double = 0.0,
    val updatedAt: Long = 0L,
    val createdAt: Long = 0L,
    val media: AniListMedia? = null
)

@Serializable
data class AniListAvatar(
    val large: String? = null,
    val medium: String? = null
)

@Serializable
data class AniListViewer(
    val id: Long,
    val name: String? = null,
    val siteUrl: String? = null,
    val avatar: AniListAvatar? = null
)

@Serializable
data class AniListEpisodeCoordinate(
    val anime: Int,
    val season: Int,
    val episode: Int
)

@Serializable
data class AniListMapping(
    val anilist: Long,
    val mal: Long? = null,
    val kitsu: Long? = null,
    val anidb: Long? = null,
    val imdb: String? = null,
    val tvdbSeason: Int? = null,
    val episodeOffset: Int? = null,
    val episodes: List<AniListEpisodeCoordinate> = emptyList(),
    val seasonResolved: Boolean = false,
    val resolvedAtEpochMs: Long = 0L
)

@Serializable
data class AniListSyncSnapshot(
    val viewerId: Long,
    val entries: List<AniListEntry> = emptyList(),
    val mappings: Map<Long, AniListMapping> = emptyMap(),
    val dismissed: Map<Long, Long> = emptyMap(),
    val checkedAtEpochMs: Long? = null
)

data class AniListSyncState(
    val scope: AniListAuthScope? = null,
    val snapshot: AniListSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: AniListSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean
        get() = snapshot?.checkedAtEpochMs != null
}

enum class AniListSyncError { UNAVAILABLE, RATE_LIMIT, AUTHORIZATION_REVOKED }

data class AniListAuthScope(val profileId: Int, val generation: Long)

enum class AniListAuthError {
    MISSING_CLIENT_ID,
    ACCESS_DENIED,
    INVALID_CALLBACK,
    AUTHORIZATION_EXPIRED,
    AUTHORIZATION_REVOKED
}

data class AniListAuthState(
    val scope: AniListAuthScope = AniListAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val viewer: AniListViewer? = null,
    val awaitingApproval: Boolean = false,
    val isBusy: Boolean = false,
    val error: AniListAuthError? = null
)

@Serializable
internal data class AniListStoredAuth(
    val accessToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    val viewer: AniListViewer? = null,
    val pendingSinceEpochMs: Long? = null
) {
    override fun toString(): String = "AniListStoredAuth()"
}

class AniListAuthException(val error: AniListAuthError) : Exception(error.name)

open class AniListApiException(
    val status: Int,
    val retryAtEpochMs: Long? = null,
    message: String? = null
) : Exception(message ?: "AniList request failed ($status)")

class AniListNotFoundException(message: String? = null) : AniListApiException(404, message = message)

internal fun Throwable.toAniListSyncError(): AniListSyncError = when {
    this is AniListAuthException -> AniListSyncError.AUTHORIZATION_REVOKED
    this is AniListApiException && status == 429 -> AniListSyncError.RATE_LIMIT
    else -> AniListSyncError.UNAVAILABLE
}

internal val AniListMedia.isMovie: Boolean
    get() = format.equals("MOVIE", ignoreCase = true)

internal val AniListMedia.contentType: String
    get() = if (isMovie) "movie" else "series"

internal fun AniListMedia.displayTitle(): String =
    listOf(title?.userPreferred, title?.english, title?.romaji, title?.native)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?: "AniList $id"

internal fun AniListMedia.posterUrl(): String? =
    coverImage?.extraLarge?.takeIf(String::isNotBlank)
        ?: coverImage?.large?.takeIf(String::isNotBlank)
        ?: coverImage?.medium?.takeIf(String::isNotBlank)

internal fun AniListMedia.currentEpisodeCount(): Int {
    var ceil = -1
    episodes?.let { ceil = it }
    nextAiringEpisode?.episode?.let { next ->
        if (next > 0) ceil = next - 1
    }
    return ceil
}

internal fun AniListMedia.totalEpisodeCount(): Int = episodes ?: -1

internal fun AniListMedia.releaseYear(): Int? = seasonYear ?: startDate?.year

internal fun AniListEntry.effectiveProgress(): Int {
    val total = media?.totalEpisodeCount() ?: -1
    return if (status == AniListListStatus.COMPLETED && progress <= 0 && total > 0) total else progress
}

internal fun AniListEntry.isCountedAsWatched(): Boolean =
    status != AniListListStatus.PLANNING && effectiveProgress() > 0
