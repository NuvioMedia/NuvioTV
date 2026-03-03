package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesResponseDto(
    @Json(name = "all") val all: String? = null,
    @Json(name = "movies") val movies: TraktLastActivitiesMediaDto? = null,
    @Json(name = "episodes") val episodes: TraktLastActivitiesMediaDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesMediaDto(
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "paused_at") val pausedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktPlaybackItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedMovieItemDto(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserEpisodeHistoryItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "action") val action: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowProgressResponseDto(
    @Json(name = "aired") val aired: Int? = null,
    @Json(name = "completed") val completed: Int? = null,
    @Json(name = "seasons") val seasons: List<TraktShowSeasonProgressDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowSeasonProgressDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktShowEpisodeProgressDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowEpisodeProgressDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "completed") val completed: Boolean? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveRequestDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktHistoryShowRemoveDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryShowRemoveDto(
    @Json(name = "ids") val ids: TraktIdsDto,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonRemoveDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistorySeasonRemoveDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeRemoveDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryEpisodeRemoveDto(
    @Json(name = "number") val number: Int
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveResponseDto(
    @Json(name = "deleted") val deleted: TraktHistoryRemoveCountDto? = null,
    @Json(name = "not_found") val notFound: TraktHistoryRemoveNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveCountDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null,
    @Json(name = "seasons") val seasons: List<Map<String, Any?>>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null,
    @Json(name = "ids") val ids: List<Long>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddRequestDto(
    @Json(name = "movies") val movies: List<TraktHistoryMovieAddDto>? = null,
    @Json(name = "shows") val shows: List<TraktHistoryShowAddDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeAddDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryMovieAddDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryShowAddDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistorySeasonAddDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeAddDto>? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryEpisodeAddDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddResponseDto(
    @Json(name = "added") val added: TraktHistoryRemoveCountDto? = null,
    @Json(name = "not_found") val notFound: TraktHistoryAddNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "action") val action: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)
