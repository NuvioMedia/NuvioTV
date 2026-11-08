package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.anilist.AniListConfig
import com.nuvio.tv.data.repository.SimklIdResolver
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class TrackingAnimeIdMapper @Inject constructor(
    private val simklIdResolver: SimklIdResolver,
    @Named("simkl") private val client: OkHttpClient
) {
    private data class Mapped(
        val imdb: String,
        val isMovie: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    private data class AnimeVideo(
        val source: String,
        val id: Long,
        val episode: Int
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Mapped>()
    private val animePrefixes = setOf("kitsu", "mal", "anilist", "anidb")

    suspend fun toImdbReference(media: TrackingMediaReference): TrackingMediaReference {
        val mapped = map(
            videoId = media.catalog?.videoId,
            contentType = media.catalog?.contentType,
            ids = media.ids,
            episode = media.episode?.number
        ) ?: return media
        val ids = media.ids.withImdb(mapped.imdb)
        if (mapped.isMovie) {
            return media.copy(
                kind = TrackingMediaKind.MOVIE,
                ids = ids,
                episode = null,
                catalog = TrackingCatalogReference(contentId = mapped.imdb, contentType = "movie")
            )
        }
        val season = mapped.season ?: return media
        val number = mapped.episode ?: return media
        return media.copy(
            kind = TrackingMediaKind.SHOW,
            ids = ids,
            episode = media.episode?.copy(season = season, number = number)
                ?: TrackingEpisode(season = season, number = number),
            catalog = TrackingCatalogReference(
                contentId = mapped.imdb,
                contentType = "series",
                videoId = "${mapped.imdb}:$season:$number"
            )
        )
    }

    suspend fun toImdbReferences(items: Collection<TrackingMediaReference>): List<TrackingMediaReference> =
        items.map { toImdbReference(it) }

    suspend fun toImdbHistoryItems(items: Collection<TrackingHistoryItem>): List<TrackingHistoryItem> =
        items.map { it.copy(media = toImdbReference(it.media)) }

    data class AnimeImdbMapping(
        val imdb: String,
        val episodes: List<SimklIdResolver.EpisodeMapping>,
        val fallbackSeason: Int?
    ) {
        fun imdbEpisodeFor(number: Int): Pair<Int, Int>? {
            episodes.firstOrNull { it.animeEpisode == number }?.let { return it.tvdbSeason to it.tvdbEpisode }
            episodes.filter { it.animeEpisode < number }.maxByOrNull { it.animeEpisode }?.let { previous ->
                return previous.tvdbSeason to previous.tvdbEpisode + (number - previous.animeEpisode)
            }
            if (episodes.isNotEmpty()) return null
            val season = fallbackSeason ?: return null
            return season to number
        }
    }

    suspend fun animeImdbMapping(source: String, id: Long): AnimeImdbMapping? {
        val normalizedSource = source.trim().lowercase()
        if (normalizedSource !in animePrefixes || id <= 0L) return null
        return lock.withLock {
            val resolved = resolveSimkl(normalizedSource, id) ?: return@withLock null
            val imdb = resolved.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return@withLock null
            val episodes = try {
                simklIdResolver.getEpisodeMapping(resolved.simklId, resolved.type)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            AnimeImdbMapping(imdb = imdb, episodes = episodes, fallbackSeason = resolved.tvdbSeason)
        }
    }

    private fun TrackingExternalIds.withImdb(imdb: String): TrackingExternalIds {
        val current = this.imdb?.trim()?.takeIf(String::isNotBlank)
        return when {
            current == null -> mergeMissing(TrackingExternalIds(imdb = imdb))
            current.equals(imdb, ignoreCase = true) -> this
            else -> copy(imdb = imdb, tmdb = null, tvdb = null, trakt = null)
        }
    }

    private suspend fun map(
        videoId: String?,
        contentType: String?,
        ids: TrackingExternalIds,
        episode: Int?
    ): Mapped? {
        val type = contentType?.trim()?.lowercase()
        val isMovie = type == "movie" || type == "film"
        val video = if (isMovie) null else animeVideo(videoId)
        val hasStandardIds = !ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank() || ids.trakt != null
        val (source, id) = when {
            video != null -> video.source to video.id
            hasStandardIds -> return null
            ids.kitsu != null -> "kitsu" to ids.kitsu
            ids.mal != null -> "mal" to ids.mal
            ids.anilist != null -> "anilist" to ids.anilist
            ids.anidb != null -> "anidb" to ids.anidb
            else -> return null
        }
        val animeEpisode = if (isMovie) null else (video?.episode ?: episode ?: return null)
        val key = "$source:$id:${animeEpisode ?: "movie"}"
        return lock.withLock {
            cache[key]?.let { return@withLock it }
            val result = lookup(source, id, isMovie, animeEpisode) ?: return@withLock null
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            cache[key] = result
            result
        }
    }

    private suspend fun lookup(source: String, id: Long, isMovie: Boolean, animeEpisode: Int?): Mapped? {
        val resolved = resolveSimkl(source, id) ?: return null
        val imdb = resolved.imdb?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
        if (isMovie) return Mapped(imdb = imdb, isMovie = true)
        val number = animeEpisode ?: return null
        val episodes = try {
            simklIdResolver.getEpisodeMapping(resolved.simklId, resolved.type)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        episodes.firstOrNull { it.animeEpisode == number }?.let {
            return Mapped(imdb = imdb, isMovie = false, season = it.tvdbSeason, episode = it.tvdbEpisode)
        }
        episodes.filter { it.animeEpisode < number }.maxByOrNull { it.animeEpisode }?.let { previous ->
            return Mapped(
                imdb = imdb,
                isMovie = false,
                season = previous.tvdbSeason,
                episode = previous.tvdbEpisode + (number - previous.animeEpisode)
            )
        }
        if (episodes.isNotEmpty()) return null
        val season = resolved.tvdbSeason ?: return null
        return Mapped(imdb = imdb, isMovie = false, season = season, episode = number)
    }

    private suspend fun resolveSimkl(source: String, id: Long): SimklIdResolver.ResolvedIds? {
        val candidates = buildList {
            if (source == "kitsu" || source == "anidb") {
                armIds(source, id)?.let { (anilist, mal) ->
                    anilist?.let { add("anilist" to it) }
                    mal?.let { add("mal" to it) }
                }
            }
            add(source to id)
        }.distinct()
        for ((candidateSource, candidateId) in candidates) {
            val resolved = try {
                simklIdResolver.resolveIds(candidateSource, candidateId.toString(), "anime")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: continue
            if (resolved.isEntryFor(candidateSource, candidateId)) return resolved
        }
        return null
    }

    private fun SimklIdResolver.ResolvedIds.isEntryFor(source: String, id: Long): Boolean {
        val reported = when (source) {
            "anilist" -> anilist
            "mal" -> mal
            "kitsu" -> kitsu
            else -> null
        }?.trim()?.toLongOrNull() ?: return true
        return reported == id
    }

    private suspend fun armIds(source: String, id: Long): Pair<Long?, Long?>? {
        val body = try {
            httpGetText("${AniListConfig.ARM_BASE_URL}/ids?source=$source&id=$id&include=anilist,myanimelist")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } ?: return null
        val value = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        fun long(key: String): Long? = (value[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        val anilist = long("anilist")
        val mal = long("myanimelist")
        return if (anilist == null && mal == null) null else anilist to mal
    }

    private suspend fun httpGetText(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun animeVideo(videoId: String?): AnimeVideo? {
        val parts = videoId?.trim()?.split(':') ?: return null
        if (parts.size < 3) return null
        val source = parts[0].lowercase()
        if (source !in animePrefixes) return null
        val id = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val episode = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return AnimeVideo(source, id, episode)
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 512
    }
}
