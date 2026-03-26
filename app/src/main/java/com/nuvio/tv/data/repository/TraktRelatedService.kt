package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val RELATED_LIMIT = 20
private const val RELATED_CACHE_TTL_MS = 10 * 60_000L

internal enum class TraktRelatedType(val apiValue: String) {
    MOVIE("movie"),
    SHOW("show")
}

internal data class ResolvedRelatedTarget(
    val type: TraktRelatedType,
    val pathId: String
)

@Singleton
class TraktRelatedService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    private data class TimedCache(
        val items: List<MetaPreview>,
        val updatedAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, TimedCache>()

    suspend fun getRelated(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        forceRefresh: Boolean = false
    ): List<MetaPreview> {
        val target = resolveRelatedTarget(meta, fallbackItemId, fallbackItemType) ?: return emptyList()
        val cacheKey = buildString {
            append(target.type.apiValue)
            append("|")
            append(target.pathId)
        }

        if (!forceRefresh) {
            cache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.updatedAtMs <= RELATED_CACHE_TTL_MS) {
                    return cached.items
                }
            }
        } else {
            cache.remove(cacheKey)
        }

        val items = when (target.type) {
            TraktRelatedType.MOVIE -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getMovieRelated(
                        authorization = authHeader,
                        id = target.pathId,
                        limit = RELATED_LIMIT
                    )
                } ?: throw IllegalStateException("Trakt related request failed")

                when {
                    response.code() == 404 -> emptyList()
                    !response.isSuccessful -> throw IllegalStateException("Failed to load Trakt related titles (${response.code()})")
                    else -> response.body().orEmpty()
                        .mapNotNull { dto ->
                            dto.toMetaPreview(
                                defaultType = ContentType.MOVIE,
                                rawType = "movie"
                            )
                        }
                }
            }

            TraktRelatedType.SHOW -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getShowRelated(
                        authorization = authHeader,
                        id = target.pathId,
                        limit = RELATED_LIMIT
                    )
                } ?: throw IllegalStateException("Trakt related request failed")

                when {
                    response.code() == 404 -> emptyList()
                    !response.isSuccessful -> throw IllegalStateException("Failed to load Trakt related titles (${response.code()})")
                    else -> response.body().orEmpty()
                        .mapNotNull { dto ->
                            dto.toMetaPreview(
                                defaultType = ContentType.SERIES,
                                rawType = "series"
                            )
                        }
                }
            }
        }

        val distinctItems = items.distinctBy { "${it.apiType}:${it.id}" }
        cache[cacheKey] = TimedCache(items = distinctItems, updatedAtMs = System.currentTimeMillis())
        return distinctItems
    }

    private suspend fun resolveRelatedTarget(
        meta: Meta,
        fallbackItemId: String?,
        fallbackItemType: String?
    ): ResolvedRelatedTarget? {
        val type = resolveRelatedType(meta = meta, fallbackItemType = fallbackItemType) ?: return null
        val directPathId = resolveDirectPathId(meta = meta, fallbackItemId = fallbackItemId)
        if (!directPathId.isNullOrBlank()) {
            return ResolvedRelatedTarget(type = type, pathId = directPathId)
        }

        val tmdbId = resolveTmdbCandidate(meta = meta, fallbackItemId = fallbackItemId) ?: return null
        val searchResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.searchById(
                authorization = authHeader,
                idType = "tmdb",
                id = tmdbId.toString(),
                type = type.apiValue
            )
        } ?: throw IllegalStateException("Trakt TMDB search request failed")

        if (!searchResponse.isSuccessful) {
            if (searchResponse.code() == 404) return null
            throw IllegalStateException("Failed to resolve Trakt id (${searchResponse.code()})")
        }

        val resolvedPathId = searchResponse.body()
            .orEmpty()
            .firstOrNull { it.type.equals(type.apiValue, ignoreCase = true) }
            ?.toTraktPathId(type)

        return resolvedPathId?.let { ResolvedRelatedTarget(type = type, pathId = it) }
    }

    private fun resolveRelatedType(meta: Meta, fallbackItemType: String?): TraktRelatedType? {
        val normalizedType = listOf(meta.apiType, meta.rawType, fallbackItemType)
            .firstNotNullOfOrNull { value -> value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } }

        return when (meta.type) {
            ContentType.MOVIE -> TraktRelatedType.MOVIE
            ContentType.SERIES, ContentType.TV -> TraktRelatedType.SHOW
            else -> when (normalizedType) {
                "movie" -> TraktRelatedType.MOVIE
                "series", "show", "tv" -> TraktRelatedType.SHOW
                else -> null
            }
        }
    }

    private fun resolveDirectPathId(meta: Meta, fallbackItemId: String?): String? {
        meta.imdbId?.takeIf { it.isNotBlank() }?.let { return it }

        val metaIds = parseContentIds(meta.id)
        metaIds.imdb?.takeIf { it.isNotBlank() }?.let { return it }
        metaIds.trakt?.let { return it.toString() }

        meta.slug?.takeIf { it.isNotBlank() }?.let { return it }

        val fallbackIds = parseContentIds(fallbackItemId)
        fallbackIds.imdb?.takeIf { it.isNotBlank() }?.let { return it }
        fallbackIds.trakt?.let { return it.toString() }

        return null
    }

    private fun resolveTmdbCandidate(meta: Meta, fallbackItemId: String?): Int? {
        val metaIds = parseContentIds(meta.id)
        if (metaIds.tmdb != null) return metaIds.tmdb

        return parseContentIds(fallbackItemId).tmdb
    }
}

private fun TraktMovieDto.toMetaPreview(
    defaultType: ContentType,
    rawType: String
): MetaPreview? {
    return toMetaPreviewInternal(
        title = title ?: originalTitle,
        year = year,
        ids = ids,
        overview = overview,
        releaseDate = released,
        runtimeMinutes = runtime,
        ratingValue = rating,
        genresList = genres,
        certificationValue = certification,
        languagesList = languages,
        countryValue = country,
        statusValue = status,
        originalTitleValue = originalTitle,
        imagesValue = images,
        defaultType = defaultType,
        rawType = rawType
    )
}

private fun TraktShowDto.toMetaPreview(
    defaultType: ContentType,
    rawType: String
): MetaPreview? {
    return toMetaPreviewInternal(
        title = title ?: originalTitle,
        year = year,
        ids = ids,
        overview = overview,
        releaseDate = firstAired,
        runtimeMinutes = runtime,
        ratingValue = rating,
        genresList = genres,
        certificationValue = certification,
        languagesList = languages,
        countryValue = country,
        statusValue = status,
        originalTitleValue = originalTitle,
        imagesValue = images,
        defaultType = defaultType,
        rawType = rawType
    )
}

private fun toMetaPreviewInternal(
    title: String?,
    year: Int?,
    ids: TraktIdsDto?,
    overview: String?,
    releaseDate: String?,
    runtimeMinutes: Int?,
    ratingValue: Double?,
    genresList: List<String>?,
    certificationValue: String?,
    languagesList: List<String>?,
    countryValue: String?,
    statusValue: String?,
    originalTitleValue: String?,
    imagesValue: TraktImagesDto?,
    defaultType: ContentType,
    rawType: String
): MetaPreview? {
    val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val fallbackId = when {
        ids?.trakt != null -> "trakt:${ids.trakt}"
        !ids?.slug.isNullOrBlank() -> ids.slug
        else -> null
    }
    val contentId = normalizeContentId(ids, fallback = fallbackId)
    if (contentId.isBlank()) return null

    val poster = imagesValue.bestLandscapeImage()
    val background = imagesValue.bestBackdropImage()
    val logo = imagesValue.bestLogoImage()
    val releaseInfo = year?.toString() ?: extractYear(releaseDate)?.toString()

    return MetaPreview(
        id = contentId,
        type = defaultType,
        rawType = rawType,
        name = normalizedTitle,
        poster = poster,
        posterShape = PosterShape.LANDSCAPE,
        background = background,
        logo = logo,
        description = overview?.trim()?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo,
        imdbRating = ratingValue?.toFloat(),
        genres = genresList.orEmpty(),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        status = statusValue?.trim()?.takeIf { it.isNotBlank() },
        ageRating = certificationValue?.trim()?.takeIf { it.isNotBlank() },
        language = languagesList.orEmpty().firstOrNull()?.takeIf { it.isNotBlank() },
        released = releaseDate?.trim()?.takeIf { it.isNotBlank() },
        country = countryValue?.trim()?.takeIf { it.isNotBlank() },
        imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
        slug = ids?.slug?.takeIf { it.isNotBlank() },
        landscapePoster = background,
        rawPosterUrl = poster
    )
}

private fun TraktImagesDto?.bestLandscapeImage(): String? {
    if (this == null) return null
    return thumb.firstImageUrl()
        ?: fanart.firstImageUrl()
        ?: banner.firstImageUrl()
        ?: poster.firstImageUrl()
}

private fun TraktImagesDto?.bestBackdropImage(): String? {
    if (this == null) return null
    return fanart.firstImageUrl()
        ?: banner.firstImageUrl()
        ?: thumb.firstImageUrl()
        ?: poster.firstImageUrl()
}

private fun TraktImagesDto?.bestLogoImage(): String? {
    if (this == null) return null
    return logo.firstImageUrl() ?: clearart.firstImageUrl()
}

private fun List<String>?.firstImageUrl(): String? {
    return this.orEmpty()
        .firstOrNull { !it.isNullOrBlank() }
        ?.toHttpsImageUrl()
}

private fun String.toHttpsImageUrl(): String {
    val normalized = trim()
    return when {
        normalized.startsWith("https://", ignoreCase = true) -> normalized
        normalized.startsWith("http://", ignoreCase = true) -> "https://${normalized.removePrefix("http://")}"
        normalized.startsWith("//") -> "https:$normalized"
        else -> "https://$normalized"
    }
}

internal fun TraktSearchResultDto.toTraktPathId(expectedType: TraktRelatedType): String? {
    val ids = when (expectedType) {
        TraktRelatedType.MOVIE -> movie?.ids
        TraktRelatedType.SHOW -> show?.ids
    }
    return ids.toBestRelatedPathId()
}

internal fun TraktIdsDto?.toBestRelatedPathId(): String? {
    if (this == null) return null
    return when {
        !imdb.isNullOrBlank() -> imdb
        trakt != null -> trakt.toString()
        !slug.isNullOrBlank() -> slug
        else -> null
    }
}
