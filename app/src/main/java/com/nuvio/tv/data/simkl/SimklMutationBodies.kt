package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.round

fun buildSimklListMutationBody(
    items: Collection<TrackingMediaReference>,
    destination: TrackingListStatus,
    json: Json = SimklMutationJson
): String {
    val requestItems = items.map { item ->
        item to SimklListItemDto(
            to = destination.wireValue,
            title = item.title.nonBlankOrNull(),
            year = item.year,
            ids = item.ids.toSimklJsonObjectOrNull()
        )
    }
    return json.encodeToString(
        SimklListMutationRequestDto(
            movies = requestItems.filter { (item, _) -> item.kind == TrackingMediaKind.MOVIE }
                .map { it.second },
            shows = requestItems.filter { (item, _) -> item.kind != TrackingMediaKind.MOVIE }
                .map { it.second }
        )
    )
}

fun buildSimklHistoryMutationBody(
    items: Collection<TrackingHistoryItem>,
    json: Json = SimklMutationJson
): String = json.encodeToString(buildHistoryRequest(items, includeWatchedAt = true))

fun buildSimklHistoryRemovalBody(
    items: Collection<TrackingMediaReference>,
    json: Json = SimklMutationJson
): String = json.encodeToString(
    buildHistoryRequest(
        items = items.map { media -> TrackingHistoryItem(media = media) },
        includeWatchedAt = false
    )
)

fun buildSimklScrobbleBody(
    event: TrackingScrobbleEvent,
    json: Json = SimklMutationJson
): String {
    val media = event.media.toScrobbleMediaDto()
    val usesTvStyleAnimeCoordinates = event.media.kind == TrackingMediaKind.ANIME &&
        event.media.episode?.season != null
    return json.encodeToString(
        SimklScrobbleRequestDto(
            progress = event.progressPercent.clampAndRoundProgress(),
            movie = media.takeIf { event.media.kind == TrackingMediaKind.MOVIE },
            show = media.takeIf {
                event.media.kind == TrackingMediaKind.SHOW || usesTvStyleAnimeCoordinates
            },
            anime = media.takeIf {
                event.media.kind == TrackingMediaKind.ANIME && !usesTvStyleAnimeCoordinates
            },
            episode = event.media.episode?.toEpisodeDto(
                includeSeason = true,
                includeWatchedAt = false,
                watchedAtEpochMs = null
            )
        )
    )
}

private fun buildHistoryRequest(
    items: Collection<TrackingHistoryItem>,
    includeWatchedAt: Boolean
): SimklHistoryMutationRequestDto {
    val movies = items.filter { item -> item.media.kind == TrackingMediaKind.MOVIE }
        .map { item ->
            item.media.toHistoryItemDto(
                watchedAtEpochMs = item.watchedAtEpochMs.takeIf { includeWatchedAt },
                includeWatchedAt = includeWatchedAt
            )
        }
    val shows = items.filter { item -> item.media.kind != TrackingMediaKind.MOVIE }
        .groupBy { item -> item.media.stableKey }
        .values
        .map { matchingItems -> buildShowHistoryItem(matchingItems, includeWatchedAt) }
    return SimklHistoryMutationRequestDto(movies = movies, shows = shows)
}

private fun buildShowHistoryItem(
    items: List<TrackingHistoryItem>,
    includeWatchedAt: Boolean
): SimklHistoryItemDto {
    val first = items.first()
    val parentMutation = items.lastOrNull { item -> item.media.episode == null }
    if (parentMutation != null) {
        return parentMutation.media.toHistoryItemDto(
            watchedAtEpochMs = parentMutation.watchedAtEpochMs.takeIf { includeWatchedAt },
            includeWatchedAt = includeWatchedAt,
            status = if (includeWatchedAt) TrackingListStatus.COMPLETED.wireValue else null
        )
    }

    val episodeMutations = items.mapNotNull { item ->
        item.media.episode?.let { episode -> item to episode }
    }
    val flatEpisodes = episodeMutations.filter { (_, episode) -> episode.season == null }
        .map { (item, episode) ->
            episode.toEpisodeDto(false, includeWatchedAt, item.watchedAtEpochMs)
        }
        .distinctBy(SimklEpisodeMutationDto::number)
    val seasons = episodeMutations.filter { (_, episode) -> episode.season != null }
        .groupBy { (_, episode) -> requireNotNull(episode.season) }
        .map { (season, seasonItems) ->
            SimklSeasonMutationDto(
                number = season,
                episodes = seasonItems.map { (item, episode) ->
                    episode.toEpisodeDto(false, includeWatchedAt, item.watchedAtEpochMs)
                }.distinctBy(SimklEpisodeMutationDto::number)
            )
        }
        .sortedBy(SimklSeasonMutationDto::number)

    return first.media.toHistoryItemDto(
        watchedAtEpochMs = null,
        includeWatchedAt = includeWatchedAt,
        episodes = flatEpisodes,
        seasons = seasons,
        useTvdbAnimeSeasons = first.media.kind == TrackingMediaKind.ANIME && seasons.isNotEmpty()
    )
}

private fun TrackingMediaReference.toHistoryItemDto(
    watchedAtEpochMs: Long?,
    includeWatchedAt: Boolean,
    status: String? = null,
    episodes: List<SimklEpisodeMutationDto> = emptyList(),
    seasons: List<SimklSeasonMutationDto> = emptyList(),
    useTvdbAnimeSeasons: Boolean = false
): SimklHistoryItemDto = SimklHistoryItemDto(
    title = title.nonBlankOrNull(),
    year = year,
    ids = ids.toSimklJsonObjectOrNull(),
    watchedAt = watchedAtEpochMs.takeIf { includeWatchedAt }?.epochMsToUtcIso(),
    status = status,
    episodes = episodes,
    seasons = seasons,
    useTvdbAnimeSeasons = useTvdbAnimeSeasons
)

private fun TrackingMediaReference.toScrobbleMediaDto(): SimklScrobbleMediaDto =
    SimklScrobbleMediaDto(title.nonBlankOrNull(), year, ids.toSimklJsonObjectOrNull())

private fun TrackingEpisode.toEpisodeDto(
    includeSeason: Boolean,
    includeWatchedAt: Boolean,
    watchedAtEpochMs: Long?
): SimklEpisodeMutationDto = SimklEpisodeMutationDto(
    season = season.takeIf { includeSeason },
    number = number,
    watchedAt = watchedAtEpochMs.takeIf { includeWatchedAt }?.epochMsToUtcIso()
)

internal fun TrackingExternalIds.toSimklJsonObjectOrNull(): JsonObject? {
    val value = buildJsonObject {
        simkl?.let { put("simkl", it) }
        imdb.nonBlankOrNull()?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb.nonBlankOrNull()?.let { tvdbValue ->
            tvdbValue.toLongOrNull()?.let { put("tvdb", it) } ?: put("tvdb", tvdbValue)
        }
        mal?.let { put("mal", it) }
        anidb?.let { put("anidb", it) }
        anilist?.let { put("anilist", it) }
        kitsu?.let { put("kitsu", it) }
    }
    return value.takeIf { it.isNotEmpty() }
}

private fun Double.clampAndRoundProgress(): Double =
    round(coerceIn(0.0, 100.0) * 100.0) / 100.0

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

fun Long.epochMsToUtcIso(): String? {
    if (this < 10_000_000_000L) return null
    val totalSeconds = this / 1_000L
    val second = (totalSeconds % 60L).toInt()
    val minute = ((totalSeconds / 60L) % 60L).toInt()
    val hour = ((totalSeconds / 3_600L) % 24L).toInt()
    var days = totalSeconds / 86_400L
    var year = 1970
    while (true) {
        val daysInYear = if (year.isLeapYear()) 366 else 365
        if (days < daysInYear) break
        days -= daysInYear
        year += 1
    }
    val monthDays = if (year.isLeapYear()) {
        intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    } else {
        intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    }
    var monthIndex = 0
    while (monthIndex < monthDays.size && days >= monthDays[monthIndex]) {
        days -= monthDays[monthIndex]
        monthIndex += 1
    }
    val month = monthIndex + 1
    val day = days.toInt() + 1
    return "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}T${hour.pad(2)}:${minute.pad(2)}:${second.pad(2)}Z"
}

private fun Int.isLeapYear(): Boolean = (this % 4 == 0 && this % 100 != 0) || this % 400 == 0
private fun Int.pad(length: Int): String = toString().padStart(length, '0')

private val SimklMutationJson = Json { encodeDefaults = false; explicitNulls = false }

@Serializable
private data class SimklListMutationRequestDto(
    val movies: List<SimklListItemDto> = emptyList(),
    val shows: List<SimklListItemDto> = emptyList()
)

@Serializable
private data class SimklListItemDto(
    val to: String,
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null
)

@Serializable
private data class SimklHistoryMutationRequestDto(
    val movies: List<SimklHistoryItemDto> = emptyList(),
    val shows: List<SimklHistoryItemDto> = emptyList()
)

@Serializable
private data class SimklHistoryItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val status: String? = null,
    val episodes: List<SimklEpisodeMutationDto> = emptyList(),
    val seasons: List<SimklSeasonMutationDto> = emptyList(),
    @SerialName("use_tvdb_anime_seasons") val useTvdbAnimeSeasons: Boolean = false
)

@Serializable
private data class SimklSeasonMutationDto(
    val number: Int,
    val episodes: List<SimklEpisodeMutationDto> = emptyList()
)

@Serializable
private data class SimklEpisodeMutationDto(
    val season: Int? = null,
    val number: Int,
    @SerialName("watched_at") val watchedAt: String? = null
)

@Serializable
private data class SimklScrobbleRequestDto(
    val progress: Double,
    val movie: SimklScrobbleMediaDto? = null,
    val show: SimklScrobbleMediaDto? = null,
    val anime: SimklScrobbleMediaDto? = null,
    val episode: SimklEpisodeMutationDto? = null
)

@Serializable
private data class SimklScrobbleMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null
)
