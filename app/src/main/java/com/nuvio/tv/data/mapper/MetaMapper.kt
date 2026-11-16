package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AppExtrasDto
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaLinkDto
import com.nuvio.tv.data.remote.dto.VideoDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video

fun MetaDto.toDomain(episodeLabel: String = "Episode"): Meta {
    val directorMembers = mapPeople(appExtras?.directors, roleFallback = "Director", forceRole = true)
    val writerMembers = mapPeople(appExtras?.writers, roleFallback = "Writer", forceRole = true)
    val castMembers = mapPeople(appExtras?.cast)
    val directors = coerceStringList(director).ifEmpty { directorMembers.map { it.name } }
    val writersList = coerceStringList(writer).ifEmpty { coerceStringList(writers) }
        .ifEmpty { writerMembers.map { it.name } }
    val castList = coerceStringList(cast).ifEmpty { castMembers.map { it.name } }
    val trailersList = mapTrailers(trailers, trailerStreams)
    val videoList = videos?.map { it.toDomain(episodeLabel) } ?: emptyList()

    return Meta(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        imdbId = imdbId,
        slug = slug,
        released = released,
        landscapePoster = landscapePoster,
        description = description,
        releaseInfo = releaseInfo,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        runtime = runtime,
        director = directors,
        writer = writersList,
        cast = castList,
        castMembers = directorMembers + writerMembers + castMembers,
        videos = videoList,
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = listOf(
            appExtras?.certificationLocal,
            appExtras?.certification
        ).firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf(String::isNotBlank)
        },
        country = country,
        awards = awards,
        language = language,
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        trailerYtIds = trailersList.mapNotNull { it.ytId }.distinct(),
        rawPosterUrl = rawPosterUrl,
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = trailersList,
        releaseDates = mapReleaseDates(appExtras?.releaseDates),
        hasPoster = hasPoster,
        hasBackground = hasBackground,
        hasLandscapePoster = hasLandscapePoster,
        hasLogo = hasLogo,
        hasLinks = hasLinks,
        hasVideos = hasVideos,
        seasonPosters = mapSeasonPosters(appExtras, videoList)
    )
}

fun VideoDto.toDomain(episodeLabel: String = "Episode"): Video {
    return Video(
        id = id,
        title = name ?: title ?: "$episodeLabel ${episode ?: number ?: 0}",
        released = released,
        thumbnail = thumbnail,
        streams = streams?.map { it.toDomain(addonName = "Embedded Streams", addonLogo = null) } ?: emptyList(),
        season = season,
        episode = episode ?: number,
        overview = overview ?: description,
        runtime = parseEpisodeRuntimeMinutes(runtime),
        rating = rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 },
        available = available,
        seasonPoster = (seasonPoster ?: seasonPosterPath)?.trim()?.takeIf { it.isNotBlank() }
    )
}

fun MetaLinkDto.toDomain(): MetaLink? {
    return url?.let {
        MetaLink(
            name = name,
            category = category,
            url = it
        )
    }
}

private fun mapSeasonPosters(appExtras: AppExtrasDto?, videos: List<Video>): Map<Int, String> {
    val keyed = parseKeyedSeasonPosters(appExtras?.seasonPosters)
        .ifEmpty { parseKeyedSeasonPosters(appExtras?.seasonPosterByNumber) }
    if (keyed.isNotEmpty()) return keyed

    val posters = appExtras?.seasonPosters as? List<*> ?: return emptyMap()
    val seasons = videos
        .mapNotNull { it.season }
        .filter { it >= 0 }
        .distinct()
        .sorted()
    val positiveSeasons = seasons.filter { it > 0 }
    val posterSeasons = when {
        seasons.size == posters.size -> seasons
        positiveSeasons.size == posters.size -> positiveSeasons
        positiveSeasons.isNotEmpty() &&
            posters.size == positiveSeasons.size + 1 &&
            posters.first() == null -> listOf(0) + positiveSeasons
        else -> List(posters.size) { index -> index + 1 }
    }
    return posters.mapIndexedNotNull { index, element ->
        (element as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { posterSeasons[index] to it }
    }.toMap()
}

private fun parseKeyedSeasonPosters(value: Any?): Map<Int, String> {
    val posters = value as? Map<*, *> ?: return emptyMap()
    return posters.entries.mapNotNull { (key, poster) ->
        val season = key?.toString()?.trim()?.toIntOrNull() ?: return@mapNotNull null
        (poster as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { season to it }
    }.toMap()
}

private fun parseEpisodeRuntimeMinutes(runtime: String?): Int? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = "(\\d+)\\s*h".toRegex().find(normalized)
    val minuteMatch = "(\\d+)\\s*m(?:in)?".toRegex().find(normalized)
    val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = minuteMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (hours != null || minutes != null) {
        return (hours ?: 0) * 60 + (minutes ?: 0)
    }

    return normalized.filter(Char::isDigit).toIntOrNull()
}
