package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.data.repository.SimklIdResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.nuvio.tv.domain.model.Meta
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SimklRelatedService"
private const val RELATED_LIMIT = 20
private const val RELATED_CACHE_TTL_MS = 10 * 60_000L

private val SIMKL_REDIRECT_SOURCES = setOf(
    "tmdb", "tvdb", "mal", "anidb", "anilist", "kitsu", "simkl", "imdb"
)

@Serializable
private data class SimklRelatedItemDto(
    @SerialName("title") val title: String? = null,
    @SerialName("en_title") val enTitle: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("poster") val poster: String? = null,
    @SerialName("fanart") val fanart: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("anime_type") val animeType: String? = null,
    @SerialName("ids") val ids: SimklRelatedIdsDto? = null
)

@Serializable
private data class SimklRelatedIdsDto(
    @SerialName("simkl") val simkl: Long? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: String? = null,
    @SerialName("tvdb") val tvdb: String? = null,
    @SerialName("mal") val mal: String? = null,
    @SerialName("anidb") val anidb: String? = null,
    @SerialName("anilist") val anilist: String? = null,
    @SerialName("kitsu") val kitsu: String? = null,
    @SerialName("traktslug") val traktSlug: String? = null,
    @SerialName("tvdbslug") val tvdbSlug: String? = null
)

@Serializable
private data class SimklDetailDto(
    @SerialName("similar") val similar: List<SimklRelatedItemDto>? = null,
    @SerialName("users_recommendations") val usersRecommendations: List<SimklRelatedItemDto>? = null
)

@Singleton
class SimklRelatedService @Inject constructor(
    private val apiClient: SimklApiClient,
    private val simklIdResolver: SimklIdResolver
) {
    private data class TimedCache(val items: List<MetaPreview>, val updatedAtMs: Long)
    private data class ResolvedTarget(val type: String, val simklId: Long)

    private val cache = ConcurrentHashMap<String, TimedCache>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRelated(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        forceRefresh: Boolean = false
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val resolved = resolveSimklTarget(meta, fallbackItemId)
            ?: return@withContext emptyList()
        val cacheKey = "${resolved.type}|${resolved.simklId}"

        if (!forceRefresh) {
            cache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.updatedAtMs <= RELATED_CACHE_TTL_MS) {
                    return@withContext cached.items
                }
            }
        } else {
            cache.remove(cacheKey)
        }

        val detail = fetchDetail(resolved.type, resolved.simklId)
            ?: return@withContext emptyList()

        // Users recommendations first, then similar (deduplicated).
        val rawItems = buildList {
            detail.usersRecommendations.orEmpty().forEach { add(it) }
            detail.similar.orEmpty().forEach { item ->
                if (none { existing -> existing.ids?.simkl == item.ids?.simkl }) add(item)
            }
        }.take(RELATED_LIMIT)

        if (rawItems.isEmpty()) {
            cache[cacheKey] = TimedCache(emptyList(), System.currentTimeMillis())
            return@withContext emptyList()
        }

        val items = rawItems.mapNotNull(::itemToMetaPreview)
            .distinctBy { "${it.rawType}:${it.id}" }
        cache[cacheKey] = TimedCache(items, System.currentTimeMillis())
        items
    }

    private suspend fun fetchDetail(type: String, simklId: Long): SimklDetailDto? = try {
        val response = apiClient.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/$type/$simklId",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.TRANSIENT_FAILURES
            )
        )
        if (response.status in 200..299) json.decodeFromString(response.body) else null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Failed to fetch Simkl detail $type/$simklId: ${e.message}")
        null
    }

    private fun itemToMetaPreview(item: SimklRelatedItemDto): MetaPreview? {
        val title = item.enTitle?.takeIf(String::isNotBlank)
            ?: item.title?.takeIf(String::isNotBlank)
            ?: return null
        val contentId = resolveContentId(item.ids, item.type) ?: return null
        val year = item.year ?: return null
        val contentType = resolveContentType(item.type, item.animeType)
        val rawType = if (contentType == ContentType.MOVIE) "movie" else "series"
        val fanartUrl = simklFanartUrl(item.fanart)
        val posterCrop = simklPosterLandscapeUrl(item.poster)
        val posterUrl = simklPosterUrl(item.poster)
        val landscape = fanartUrl ?: posterCrop

        return MetaPreview(
            id = contentId,
            type = contentType,
            rawType = rawType,
            name = title,
            poster = landscape ?: posterUrl,
            posterShape = PosterShape.LANDSCAPE,
            background = landscape ?: posterUrl,
            logo = null,
            description = null,
            releaseInfo = year.toString(),
            imdbRating = null,
            genres = emptyList(),
            imdbId = item.ids?.imdb?.takeIf(String::isNotBlank),
            slug = item.ids?.slug?.takeIf(String::isNotBlank),
            landscapePoster = landscape,
            rawPosterUrl = posterUrl
        )
    }

    private suspend fun resolveSimklTarget(meta: Meta, fallbackItemId: String?): ResolvedTarget? {
        val (source, id) = parseSimklRedirectParam(meta.id)
            ?: meta.imdbId?.takeIf(String::isNotBlank)?.let { "imdb" to it }
            ?: parseSimklRedirectParam(fallbackItemId)
            ?: return null
        val resolved = simklIdResolver.resolveIds(source, id) ?: return null
        return ResolvedTarget(resolved.type, resolved.simklId)
    }

    private fun parseSimklRedirectParam(contentId: String?): Pair<String, String>? {
        val raw = contentId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (raw.startsWith("tt")) return "imdb" to raw.substringBefore(':')
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val prefix = raw.substring(0, colon).lowercase()
        val value = raw.substring(colon + 1).takeIf(String::isNotBlank) ?: return null
        return if (prefix in SIMKL_REDIRECT_SOURCES) prefix to value else null
    }

    private fun resolveContentType(type: String?, animeType: String?): ContentType = when (type?.trim()?.lowercase()) {
        "movie" -> ContentType.MOVIE
        else -> if (animeType == "movie") ContentType.MOVIE else ContentType.SERIES
    }

    private fun resolveContentId(ids: SimklRelatedIdsDto?, type: String?): String? {
        if (ids == null) return null
        if (type?.trim()?.lowercase() == "anime") {
            when (SimklAnimeIdPreferenceHolder.current) {
                SimklAnimeIdPreference.MAL -> {
                    ids.mal?.takeIf(String::isNotBlank)?.let { return "mal:$it" }
                    ids.kitsu?.takeIf(String::isNotBlank)?.let { return "kitsu:$it" }
                    ids.anidb?.takeIf(String::isNotBlank)?.let { return "anidb:$it" }
                }
                SimklAnimeIdPreference.KITSU -> {
                    ids.kitsu?.takeIf(String::isNotBlank)?.let { return "kitsu:$it" }
                    ids.mal?.takeIf(String::isNotBlank)?.let { return "mal:$it" }
                    ids.anidb?.takeIf(String::isNotBlank)?.let { return "anidb:$it" }
                }
                SimklAnimeIdPreference.TVDB -> ids.tvdb?.takeIf(String::isNotBlank)?.let { return "tvdb:$it" }
                SimklAnimeIdPreference.IMDB -> Unit
            }
        }
        return when {
            !ids.imdb.isNullOrBlank() -> ids.imdb
            !ids.tmdb.isNullOrBlank() -> "tmdb:${ids.tmdb}"
            !ids.tvdb.isNullOrBlank() -> "tvdb:${ids.tvdb}"
            !ids.mal.isNullOrBlank() -> "mal:${ids.mal}"
            !ids.anilist.isNullOrBlank() -> "anilist:${ids.anilist}"
            !ids.kitsu.isNullOrBlank() -> "kitsu:${ids.kitsu}"
            !ids.anidb.isNullOrBlank() -> "anidb:${ids.anidb}"
            ids.simkl != null -> "simkl:${ids.simkl}"
            else -> null
        }
    }
}

fun simklFanartUrl(path: String?): String? = path?.trim()?.trim('/')
    ?.takeIf(String::isNotBlank)
    ?.let { "https://wsrv.nl/?url=https://simkl.in/fanart/${it}_w.webp&q=90" }

fun simklPosterLandscapeUrl(path: String?): String? = path?.trim()?.trim('/')
    ?.takeIf(String::isNotBlank)
    ?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_w.webp&q=90" }
