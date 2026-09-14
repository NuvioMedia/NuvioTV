package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "op", "mixed-op", "ed", "mixed-ed", "recap", "outro", "credits", "ending"
    val provider: String   // "introdb", "aniskip", "animeskip"
)

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val animeSkipApi: AnimeSkipApi,
    private val simklResolver: SimklIdResolver,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    internal suspend fun getSkipIntervals(request: SkipEpisodeRequest): List<SkipInterval> = when (request.source) {
        "mal" -> getSkipIntervalsForMal(request.id, request.episode, request.imdbId, request.imdbSeason, request.imdbEpisode)
        "kitsu" -> getSkipIntervalsForKitsu(request.id, request.episode, request.imdbId, request.imdbSeason, request.imdbEpisode)
        "imdb" -> getSkipIntervals(request.id, requireNotNull(request.season), request.episode)
        else -> emptyList()
    }

    /**
     * Standard path for IMDB-identified content.
     */
    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> = coroutineScope {
        if (imdbId == null) return@coroutineScope emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
            }.orEmpty()
        }
        val resolved = async {
            // A slow mapping provider must not discard a successful IntroDB result.
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                simklResolver.resolveAnimeEpisode(imdbId, season, episode)
            }
        }
        val aniSkip = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                val target = resolved.await() ?: return@withTimeoutOrNull emptyList()
                target.ids.mal?.let { fetchFromAniSkip(it, target.episode) }.orEmpty()
            }.orEmpty()
        }
        val animeSkip = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                val target = resolved.await() ?: return@withTimeoutOrNull emptyList()
                target.ids.anilist?.let { fetchFromAnimeSkip(it, target.episode, season = null) }.orEmpty()
            }.orEmpty()
        }

        return@coroutineScope mergeByPriority(
            aniSkip.await(),
            animeSkip.await(),
            introDbDeferred.await()
        ).also { if (it.isNotEmpty()) cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null
    ): List<SkipInterval> = getAnimeSkipIntervals("mal", malId, episode, imdbId, imdbSeason, imdbEpisode)

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null
    ): List<SkipInterval> = getAnimeSkipIntervals("kitsu", kitsuId, episode, imdbId, imdbSeason, imdbEpisode)

    private suspend fun getAnimeSkipIntervals(
        source: String,
        id: String,
        episode: Int,
        imdbId: String?,
        imdbSeason: Int?,
        imdbEpisode: Int?
    ): List<SkipInterval> = coroutineScope {
        val cacheKey = "$source:$id:$episode:$imdbId:$imdbSeason:$imdbEpisode"
        cache[cacheKey]?.let { return@coroutineScope it }
        val ids = async { withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { simklResolver.resolveIds(source, id) } }
        val aniSkip = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                val malId = if (source == "mal") id else ids.await()?.mal
                malId?.let { fetchFromAniSkip(it, episode) }.orEmpty()
            }.orEmpty()
        }
        val animeSkip = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                ids.await()?.anilist?.let { fetchFromAnimeSkip(it, episode, season = null) }.orEmpty()
            }.orEmpty()
        }
        val introDb = async {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                if (!introDbConfigured) return@withTimeoutOrNull emptyList()
                val imdb = imdbId ?: ids.await()?.imdb ?: return@withTimeoutOrNull emptyList()
                val coordinates = if (imdbSeason != null && imdbEpisode != null) {
                    imdbSeason to imdbEpisode
                } else {
                    simklResolver.resolveEpisodeTvdb(source, id, episode)
                } ?: return@withTimeoutOrNull emptyList()
                fetchFromIntroDb(imdb, coordinates.first, coordinates.second)
            }.orEmpty()
        }
        mergeByPriority(aniSkip.await(), animeSkip.await(), introDb.await()).also {
            if (it.isNotEmpty()) cache[cacheKey] = it
        }
    }

    /**
     * Merge provider results into one best-of: fill each segment category (opening / ending /
     * recap) from the highest-priority provider that has it. Arguments MUST be passed in priority
     * order (AniSkip has native anime IDs, then Anime-Skip, then IntroDB as fallback),
     * so a partial result from one provider never shadows a complete segment from another.
     */
    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                chosen.putIfAbsent(category, interval)
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending" -> "ending"
        "recap" -> "recap"
        else -> null
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode)
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                listOfNotNull(
                    data.intro.toSkipIntervalOrNull("intro"),
                    data.recap.toSkipIntervalOrNull("recap"),
                    data.outro.toSkipIntervalOrNull("outro")
                )
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: no data for $imdbId S${season}E${episode}")
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types)
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()!!.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season number when using fallback ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodesResponse = animeSkipApi.query(
                    clientId = clientId,
                    body = AnimeSkipRequest(
                        query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    )
                )
                if (!episodesResponse.isSuccessful) continue

                val episodes = episodesResponse.body()?.data?.findEpisodesByShowId ?: continue
                val targetEpisode = episodes.filter { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                }.singleOrNull() ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits", "new credits" -> "ed"
                        "mixed intro" -> "mixed-op"
                        "mixed credits" -> "mixed-ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipApi.query(
                clientId = clientId,
                body = AnimeSkipRequest(
                    query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                )
            ).body()?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 3_000L
        private const val NO_ID = "__none__"
    }
}
