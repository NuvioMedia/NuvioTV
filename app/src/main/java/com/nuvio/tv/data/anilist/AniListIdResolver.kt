package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.data.simkl.SimklApiConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AniListTarget(
    val mediaId: Long,
    val episode: Int
)

internal data class AniListAnimeVideoId(
    val prefix: String,
    val id: Long,
    val episode: Int?
)

private val ANIME_VIDEO_ID_PREFIXES = setOf("mal", "anidb", "anilist", "kitsu")
private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")

internal fun parseAniListAnimeVideoId(videoId: String?): AniListAnimeVideoId? {
    val trimmed = videoId?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in ANIME_VIDEO_ID_PREFIXES) return null
    val rest = trimmed.substringAfter(':', "")
    val id = rest.substringBefore(':').toLongOrNull() ?: return null
    val episode = rest.substringAfter(':', "").substringBefore(':').toIntOrNull()
    return AniListAnimeVideoId(prefix, id, episode)
}

internal fun AniListMapping.contentId(preference: SimklAnimeIdPreference): String = when (preference) {
    SimklAnimeIdPreference.MAL -> mal?.let { "mal:$it" } ?: kitsu?.let { "kitsu:$it" } ?: imdb ?: "anilist:$anilist"
    SimklAnimeIdPreference.KITSU -> kitsu?.let { "kitsu:$it" } ?: mal?.let { "mal:$it" } ?: imdb ?: "anilist:$anilist"
    SimklAnimeIdPreference.IMDB,
    SimklAnimeIdPreference.TVDB -> imdb ?: kitsu?.let { "kitsu:$it" } ?: mal?.let { "mal:$it" } ?: "anilist:$anilist"
}

internal fun AniListMapping.aliases(): Set<String> = linkedSetOf<String>().apply {
    imdb?.takeIf(String::isNotBlank)?.let(::add)
    mal?.let { add("mal:$it") }
    kitsu?.let { add("kitsu:$it") }
    anidb?.let { add("anidb:$it") }
    add("anilist:$anilist")
}

internal fun AniListMapping.isImdbContent(contentId: String): Boolean =
    imdb != null && contentId.equals(imdb, ignoreCase = true)

internal fun AniListMapping.coordinates(contentId: String, episode: Int): Pair<Int, Int> {
    if (!isImdbContent(contentId)) return 1 to episode
    episodes.firstOrNull { it.anime == episode }?.let { return it.season to it.episode }
    episodes.filter { it.anime < episode }.maxByOrNull { it.anime }?.let { previous ->
        return previous.season to (previous.episode + (episode - previous.anime))
    }
    episodes.filter { it.anime > episode }.minByOrNull { it.anime }?.let { next ->
        return next.season to (next.episode - (next.anime - episode)).coerceAtLeast(1)
    }
    return (tvdbSeason ?: 1) to (episode + (episodeOffset ?: 0))
}

internal fun AniListMapping.exactAnimeEpisode(season: Int, episode: Int): Int? =
    episodes.firstOrNull { it.season == season && it.episode == episode }?.anime

internal fun AniListMapping.approximateAnimeEpisode(season: Int, episode: Int, totalEpisodes: Int): Int? {
    val inSeason = episodes.filter { it.season == season }
    val candidate = if (inSeason.isNotEmpty()) {
        val previous = inSeason.filter { it.episode <= episode }.maxByOrNull { it.episode } ?: return null
        previous.anime + (episode - previous.episode)
    } else if (episodes.isEmpty() && (tvdbSeason ?: 1) == season) {
        episode - (episodeOffset ?: 0)
    } else {
        return null
    }
    return candidate.takeIf { it >= 1 && (totalEpisodes <= 0 || it <= totalEpisodes) }
}

internal fun aniListVideoId(contentId: String, season: Int, episode: Int): String {
    val prefix = contentId.substringBefore(':').lowercase()
    return if (prefix in ANIME_VIDEO_ID_PREFIXES && contentId.contains(':')) {
        "$contentId:$episode"
    } else {
        "$contentId:$season:$episode"
    }
}

internal fun fallbackAniListMapping(media: AniListMedia): AniListMapping =
    AniListMapping(anilist = media.id, mal = media.idMal)

class AniListIdResolver(
    private val api: AniListApiClient,
    private val simklIdResolver: SimklIdResolver,
    private val simklConfig: SimklApiConfiguration,
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val cacheLock = Mutex()
    private val animeIdCache = mutableMapOf<String, Long?>()
    private val imdbCache = mutableMapOf<String, List<Long>>()

    internal suspend fun resolve(reference: TrackingMediaReference, snapshot: AniListSyncSnapshot?): AniListTarget? {
        val episodeNumber = reference.episode?.number
        val season = reference.episode?.season
        val isMovie = reference.kind == TrackingMediaKind.MOVIE ||
            reference.catalog?.contentType.equals("movie", ignoreCase = true)

        parseAniListAnimeVideoId(reference.catalog?.videoId)?.let { parts ->
            animeIdToAniList(parts.prefix, parts.id, snapshot)?.let { mediaId ->
                return AniListTarget(mediaId, (parts.episode ?: episodeNumber ?: 1).coerceAtLeast(1))
            }
        }

        val ids = reference.ids
        val nativeEpisode = (episodeNumber ?: 1).coerceAtLeast(1)
        ids.anilist?.let { return AniListTarget(it, nativeEpisode) }
        ids.mal?.let { mal -> animeIdToAniList("mal", mal, snapshot)?.let { return AniListTarget(it, nativeEpisode) } }
        ids.kitsu?.let { kitsu -> animeIdToAniList("kitsu", kitsu, snapshot)?.let { return AniListTarget(it, nativeEpisode) } }
        ids.anidb?.let { anidb -> animeIdToAniList("anidb", anidb, snapshot)?.let { return AniListTarget(it, nativeEpisode) } }
        val imdb = ids.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
        return resolveImdb(imdb, season, episodeNumber, isMovie, snapshot)
    }

    internal suspend fun mapping(media: AniListMedia, existing: AniListMapping?, wantSeason: Boolean): AniListMapping? {
        val nowMs = now()
        var mapping = existing ?: fallbackAniListMapping(media)
        var changed = existing == null
        val stale = existing == null || existing.resolvedAtEpochMs <= 0L ||
            nowMs - existing.resolvedAtEpochMs > MAPPING_TTL_MS
        if (stale) {
            val result = getJson("${AniListConfig.ARM_BASE_URL}/ids?source=anilist&id=${media.id}&include=myanimelist,kitsu,anidb,imdb")
            if (result != null) {
                val value = result as? JsonObject
                mapping = mapping.copy(
                    mal = value?.longValue("myanimelist") ?: media.idMal ?: mapping.mal,
                    kitsu = value?.longValue("kitsu") ?: mapping.kitsu,
                    anidb = value?.longValue("anidb") ?: mapping.anidb,
                    imdb = value?.stringValue("imdb")?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: mapping.imdb,
                    seasonResolved = if (existing == null) false else mapping.seasonResolved,
                    resolvedAtEpochMs = nowMs
                )
                changed = true
            }
        }
        if (wantSeason && !mapping.seasonResolved && !media.isMovie) {
            val ids = if (simklConfig.clientId.isNotBlank()) {
                try {
                    simklIdResolver.resolveIds("anilist", media.id.toString(), "anime")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            mapping = if (ids != null) {
                val episodes = try {
                    simklIdResolver.getEpisodeMapping(ids.simklId, ids.type)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                val coordinates = episodes
                    .map { AniListEpisodeCoordinate(anime = it.animeEpisode, season = it.tvdbSeason, episode = it.tvdbEpisode) }
                    .distinctBy(AniListEpisodeCoordinate::anime)
                    .sortedBy(AniListEpisodeCoordinate::anime)
                val first = coordinates.firstOrNull()
                mapping.copy(
                    imdb = mapping.imdb ?: ids.imdb?.takeIf { it.startsWith("tt", ignoreCase = true) },
                    tvdbSeason = first?.season ?: ids.tvdbSeason,
                    episodeOffset = first?.let { it.episode - it.anime },
                    episodes = coordinates,
                    seasonResolved = true
                )
            } else {
                val imdb = mapping.imdb
                if (imdb == null) {
                    mapping.copy(seasonResolved = true)
                } else {
                    mapping.copy(
                        tvdbSeason = guessImdbSeason(imdb, media.id) ?: 1,
                        episodeOffset = 0,
                        episodes = emptyList(),
                        seasonResolved = true
                    )
                }
            }
            changed = true
        }
        return mapping.takeIf { changed }
    }

    private suspend fun guessImdbSeason(imdb: String, mediaId: Long): Int? {
        val candidates = imdbCandidates(imdb)
        if (candidates.size <= 1) return 1
        val media = try {
            api.mediaPage(null, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        val ordered = orderedSeriesMedia(media)
        val index = ordered.indexOfFirst { it.id == mediaId }
        return if (index >= 0) index + 1 else null
    }

    private fun orderedSeriesMedia(media: List<AniListMedia>): List<AniListMedia> = media
        .filter { it.format?.uppercase() in SERIES_FORMATS }
        .sortedWith(
            compareBy<AniListMedia>(
                { it.startDate?.year ?: Int.MAX_VALUE },
                { it.startDate?.month ?: 12 },
                { it.startDate?.day ?: 31 }
            )
        )

    private suspend fun animeIdToAniList(prefix: String, id: Long, snapshot: AniListSyncSnapshot?): Long? {
        if (prefix == "anilist") return id
        snapshot?.let { snap ->
            snap.mappings.values.firstOrNull { mapping ->
                when (prefix) {
                    "mal" -> mapping.mal == id
                    "kitsu" -> mapping.kitsu == id
                    "anidb" -> mapping.anidb == id
                    else -> false
                }
            }?.let { return it.anilist }
            if (prefix == "mal") {
                snap.entries.firstOrNull { it.media?.idMal == id }?.let { return it.mediaId }
            }
        }
        val key = "$prefix:$id"
        cacheLock.withLock {
            if (animeIdCache.containsKey(key)) return animeIdCache[key]
        }
        val resolved: Long? = when (prefix) {
            "mal" -> try {
                api.mediaByMalId(null, id)?.first?.id
            } catch (error: CancellationException) {
                throw error
            } catch (_: AniListNotFoundException) {
                null
            } catch (_: Throwable) {
                return armIds(prefix, id)
            } ?: armIds(prefix, id)
            else -> armIds(prefix, id)
        }
        cacheLock.withLock { animeIdCache[key] = resolved }
        return resolved
    }

    private suspend fun armIds(prefix: String, id: Long): Long? {
        val source = when (prefix) {
            "mal" -> "myanimelist"
            else -> prefix
        }
        val value = getJson("${AniListConfig.ARM_BASE_URL}/ids?source=$source&id=$id&include=anilist") as? JsonObject
        return value?.longValue("anilist")
    }

    private suspend fun resolveImdb(
        imdb: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
        snapshot: AniListSyncSnapshot?
    ): AniListTarget? {
        snapshot?.let { snap ->
            val candidates = snap.mappings.values.filter { it.imdb.equals(imdb, ignoreCase = true) }
            if (isMovie) {
                candidates.singleOrNull()?.let { return AniListTarget(it.anilist, 1) }
            } else if (season != null && episode != null) {
                val resolved = candidates.filter { it.seasonResolved }
                resolved.firstNotNullOfOrNull { mapping ->
                    mapping.exactAnimeEpisode(season, episode)?.let { AniListTarget(mapping.anilist, it) }
                }?.let { return it }
                resolved.firstNotNullOfOrNull { mapping ->
                    val total = snap.entries.firstOrNull { it.mediaId == mapping.anilist }?.media?.totalEpisodeCount() ?: -1
                    mapping.approximateAnimeEpisode(season, episode, total)?.let { AniListTarget(mapping.anilist, it) }
                }?.let { return it }
            }
        }

        if (!isMovie && season != null && episode != null && simklConfig.clientId.isNotBlank()) {
            val ids = try {
                simklIdResolver.resolveIdsForImdbEpisode(imdb, season, episode)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (ids != null) {
                if (ids.type != "anime") return null
                val anilist = ids.anilist?.toLongOrNull()
                    ?: ids.mal?.toLongOrNull()?.let { animeIdToAniList("mal", it, snapshot) }
                if (anilist != null) {
                    val episodes = try {
                        simklIdResolver.getEpisodeMapping(ids.simklId, ids.type)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val animeEpisode = episodes.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                        ?.animeEpisode
                    return AniListTarget(anilist, (animeEpisode ?: episode).coerceAtLeast(1))
                }
            }
        }

        val candidates = imdbCandidates(imdb)
        if (candidates.isEmpty()) return null
        val fallbackEpisode = if (isMovie) 1 else (episode ?: 1).coerceAtLeast(1)
        if (candidates.size == 1) return AniListTarget(candidates.first(), fallbackEpisode)
        val media = try {
            api.mediaPage(null, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
        if (isMovie) {
            media.firstOrNull { it.isMovie }?.let { return AniListTarget(it.id, 1) }
            return AniListTarget(candidates.first(), 1)
        }
        val ordered = orderedSeriesMedia(media)
        if (ordered.isNotEmpty()) {
            val index = ((season ?: 1) - 1).coerceIn(0, ordered.lastIndex)
            return AniListTarget(ordered[index].id, fallbackEpisode)
        }
        return AniListTarget(candidates.first(), fallbackEpisode)
    }

    private suspend fun imdbCandidates(imdb: String): List<Long> {
        val key = imdb.lowercase()
        cacheLock.withLock { imdbCache[key]?.let { return it } }
        val element = getJson("${AniListConfig.ARM_BASE_URL}/imdb?id=$imdb&include=anilist") ?: return emptyList()
        val values = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.longValue("anilist") }
            is JsonObject -> listOfNotNull(element.longValue("anilist"))
            else -> emptyList()
        }.distinct()
        cacheLock.withLock { imdbCache[key] = values }
        return values
    }

    private suspend fun getJson(url: String): JsonElement? = try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            client.newCall(request).execute().use { response ->
                val status = response.code
                val body = response.body?.string().orEmpty()
                when {
                    status == 404 -> JsonNull
                    status !in 200..299 -> null
                    body.isBlank() -> JsonNull
                    else -> runCatching { aniListJson.parseToJsonElement(body) }.getOrNull()
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { primitive -> primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    companion object {
        const val MAPPING_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
