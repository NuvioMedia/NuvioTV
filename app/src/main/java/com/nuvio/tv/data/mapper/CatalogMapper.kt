package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

fun MetaPreviewDto.toDomain(): MetaPreview {
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        runtime = runtime,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        released = released,
        country = country,
        imdbId = imdbId,
        slug = slug,
        landscapePoster = landscapePoster,
        rawPosterUrl = rawPosterUrl,
        director = coerceStringList(director),
        writer = coerceStringList(writer).ifEmpty { coerceStringList(writers) },
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = mapTrailers(trailers, trailerStreams),
        trailerYtIds = collectTrailerYtIds(trailers, trailerStreams)
    )
}
