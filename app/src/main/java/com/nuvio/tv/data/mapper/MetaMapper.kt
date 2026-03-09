package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaLinkDto
import com.nuvio.tv.data.remote.dto.VideoDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video

fun MetaDto.toDomain(episodeLabel: String = "Episode"): Meta {
    val directorMembers = mapPeople(appExtras?.directors, roleFallback = "Director")
    val writerMembers = mapPeople(appExtras?.writers, roleFallback = "Writer")
    val castMembers = mapPeople(appExtras?.cast)
    val directors = coerceStringList(director).ifEmpty { directorMembers.map { it.name } }
    val writersList = coerceStringList(writer).ifEmpty { coerceStringList(writers) }
        .ifEmpty { writerMembers.map { it.name } }
    val castList = coerceStringList(cast).ifEmpty { castMembers.map { it.name } }
    val trailersList = mapTrailers(trailers, trailerStreams)

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
        videos = videos?.map { it.toDomain(episodeLabel) } ?: emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = appExtras?.certification?.takeIf { it.isNotBlank() },
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
        hasVideos = hasVideos
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
        available = available
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
