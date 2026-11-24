package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AppExtrasCastMemberDto
import com.nuvio.tv.data.remote.dto.MetaBehaviorHintsDto
import com.nuvio.tv.data.remote.dto.MetaReleaseDateCountryDto
import com.nuvio.tv.data.remote.dto.MetaReleaseDatesEnvelopeDto
import com.nuvio.tv.data.remote.dto.MetaTrailerDto
import com.nuvio.tv.data.remote.dto.TrailerStreamDto
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaReleaseDate
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import com.nuvio.tv.domain.model.MetaTrailer

internal fun coerceStringList(value: Any?): List<String> {
    return when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is List<*> -> value.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> entry["name"] as? String
                else -> null
            }
        }
        is Map<*, *> -> {
            val name = value["name"] as? String
            if (!name.isNullOrBlank()) listOf(name) else emptyList()
        }
        else -> emptyList()
    }.mapNotNull { it.trim().takeIf(String::isNotBlank) }
}

internal fun mapPeople(
    people: List<AppExtrasCastMemberDto>?,
    roleFallback: String? = null,
    forceRole: Boolean = false
): List<MetaCastMember> {
    return people.orEmpty().mapNotNull { person ->
        val name = person.name.trim()
        if (name.isBlank()) return@mapNotNull null
        MetaCastMember(
            name = name,
            character = if (forceRole) roleFallback else person.character?.takeIf { it.isNotBlank() } ?: roleFallback,
            photo = person.photo?.takeIf { it.isNotBlank() },
            tmdbId = person.tmdbId
        )
    }
}

internal fun mapBehaviorHints(dto: MetaBehaviorHintsDto?): MetaBehaviorHints? {
    if (dto == null) return null
    return MetaBehaviorHints(
        defaultVideoId = dto.defaultVideoId?.takeIf { it.isNotBlank() },
        hasScheduledVideos = dto.hasScheduledVideos
    )
}

internal fun mapTrailers(
    trailers: List<MetaTrailerDto>?,
    trailerStreams: List<TrailerStreamDto>?
): List<MetaTrailer> {
    val streamNamesByYtId = trailerStreams.orEmpty()
        .mapNotNull { stream ->
            val ytId = stream.ytId?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = listOf(stream.title, stream.name, stream.description)
                .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            ytId to name
        }
        .distinctBy { it.first }
        .toMap()
    val fromTrailers = trailers.orEmpty().map {
        val ytId = (it.source ?: it.ytId)?.takeIf(String::isNotBlank)
        MetaTrailer(
            source = it.source?.takeIf(String::isNotBlank),
            type = it.type?.takeIf(String::isNotBlank),
            name = listOf(it.name, it.title, it.description)
                .firstNotNullOfOrNull { value -> value?.trim()?.takeIf(String::isNotBlank) }
                ?: ytId?.let { id -> streamNamesByYtId[id.trim()] },
            ytId = ytId,
            lang = it.lang?.takeIf(String::isNotBlank),
            url = it.url?.trim()?.takeIf { url -> ytId == null && isHttpTrailerUrl(url) },
            thumbnail = it.thumbnail?.trim()?.takeIf(String::isNotBlank)
        )
    }
    val seenYtIds = fromTrailers.mapNotNull { it.ytId }.toMutableSet()
    val seenUrls = fromTrailers.mapNotNull { it.url }.toMutableSet()
    val fromStreams = trailerStreams.orEmpty().mapNotNull { stream ->
        val name = listOf(stream.title, stream.name, stream.description)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
        val thumbnail = stream.thumbnail?.trim()?.takeIf(String::isNotBlank)
        val ytId = stream.ytId?.trim()?.takeIf(String::isNotBlank)
        if (ytId != null) {
            if (!seenYtIds.add(ytId)) return@mapNotNull null
            return@mapNotNull MetaTrailer(name = name, ytId = ytId, thumbnail = thumbnail)
        }
        val url = stream.url?.trim()?.takeIf(::isHttpTrailerUrl) ?: return@mapNotNull null
        if (!seenUrls.add(url)) return@mapNotNull null
        MetaTrailer(name = name, url = url, thumbnail = thumbnail)
    }
    return (fromTrailers + fromStreams).sortedByDescending { it.url != null }
}

private fun isHttpTrailerUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

internal fun collectTrailerUrls(
    trailers: List<MetaTrailerDto>?,
    trailerStreams: List<TrailerStreamDto>?
): List<String> {
    return mapTrailers(trailers, trailerStreams)
        .mapNotNull { it.url }
        .distinct()
}

internal fun collectTrailerYtIds(
    trailers: List<MetaTrailerDto>?,
    trailerStreams: List<TrailerStreamDto>?
): List<String> {
    return mapTrailers(trailers, trailerStreams)
        .mapNotNull { it.ytId }
        .distinct()
}

internal fun mapReleaseDates(
    envelope: MetaReleaseDatesEnvelopeDto?
): List<MetaReleaseDateCountry> {
    return envelope?.results.orEmpty().mapNotNull(::mapReleaseDateCountry)
}

private fun mapReleaseDateCountry(dto: MetaReleaseDateCountryDto): MetaReleaseDateCountry? {
    val countryCode = dto.iso31661.trim()
    if (countryCode.isBlank()) return null
    return MetaReleaseDateCountry(
        countryCode = countryCode,
        releaseDates = dto.releaseDates.orEmpty().map {
            MetaReleaseDate(
                certification = it.certification?.takeIf(String::isNotBlank),
                descriptors = it.descriptors.orEmpty(),
                languageCode = it.iso6391?.takeIf(String::isNotBlank),
                note = it.note?.takeIf(String::isNotBlank),
                releaseDate = it.releaseDate?.takeIf(String::isNotBlank),
                type = it.type
            )
        }
    )
}
