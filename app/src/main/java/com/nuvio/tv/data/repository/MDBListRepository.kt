package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListRatingsClient
import com.nuvio.tv.data.mdblist.MdbListRatingsLoader
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private data class MediaRef(val provider: String, val id: String, val mediaType: String)

@Singleton
class MDBListRepository internal constructor(
    private val api: MdbListRatingsClient,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService,
    private val ratingsLoader: MdbListRatingsLoader
) {
    @Inject constructor(
        api: MdbListRatingsClient,
        settingsDataStore: MDBListSettingsDataStore,
        tmdbService: TmdbService
    ) : this(api, settingsDataStore, tmdbService, MdbListRatingsLoader(api))

    fun isAvailable(settings: MDBListSettings): Boolean = settings.enabled && api.credential(settings.apiKey) != null

    suspend fun getImdbRatingForItem(itemId: String, itemType: String): Double? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null
        val credential = api.credential(settings.apiKey) ?: return null

        val mediaType = normalizeMediaType(itemType)
        val ref = resolveMediaRef(
            meta = Meta(
                id = itemId,
                type = when (normalizeMediaType(itemType)) {
                    "show" -> com.nuvio.tv.domain.model.ContentType.SERIES
                    else -> com.nuvio.tv.domain.model.ContentType.MOVIE
                },
                name = itemId,
                poster = null,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            ),
            fallbackItemId = itemId,
            fallbackItemType = itemType,
            mediaType = mediaType
        ) ?: return null

        return ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.imdb
    }

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val credential = api.credential(settings.apiKey) ?: return null

        if (!settings.hasEnabledProviders()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val ref = resolveMediaRef(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val ratings = ratingsLoader.getRatings(ref.provider, ref.mediaType, ref.id, credential)?.let { allRatings ->
            MDBListRatings(
                trakt = allRatings.trakt.takeIf { settings.showTrakt },
                imdb = allRatings.imdb.takeIf { settings.showImdb },
                tmdb = allRatings.tmdb.takeIf { settings.showTmdb },
                letterboxd = allRatings.letterboxd.takeIf { settings.showLetterboxd },
                tomatoes = allRatings.tomatoes.takeIf { settings.showTomatoes },
                audience = allRatings.audience.takeIf { settings.showAudience },
                metacritic = allRatings.metacritic.takeIf { settings.showMetacritic },
                mal = allRatings.mal.takeIf { settings.showMal },
                tomatoesCertified = settings.showTomatoes && allRatings.tomatoesCertified,
                audienceCertified = settings.showAudience && allRatings.audienceCertified
            )
        }?.takeUnless { it.isEmpty() } ?: return null

        return MDBListRatingsResult(ratings, hasImdbRating = ratings.imdb != null)
    }

    private fun MDBListSettings.hasEnabledProviders(): Boolean =
        showTrakt || showImdb || showTmdb || showLetterboxd || showTomatoes || showAudience || showMetacritic || showMal

    private suspend fun resolveMediaRef(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): MediaRef? {
        // 1. Try IMDB ID first (most reliable).
        extractImdbId(meta.id)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(fallbackItemId)?.let { return MediaRef("imdb", it, mediaType) }
        extractImdbId(meta.imdbId)?.let { return MediaRef("imdb", it, mediaType) }

        // 2. Try TMDB ID directly — no need to convert to IMDB.
        val tmdbId = extractPrefixedId(meta.id, "tmdb")
            ?: extractPrefixedId(fallbackItemId, "tmdb")

        if (tmdbId != null) {
            // First try to get IMDB ID (preferred for cache dedup across providers).
            val imdb = runCatching { tmdbService.tmdbToImdb(tmdbId.toInt(), fallbackItemType) }.getOrNull()
            if (!imdb.isNullOrBlank() && imdb.startsWith("tt")) return MediaRef("imdb", imdb, mediaType)
            return MediaRef("tmdb", tmdbId, mediaType)
        }

        // 3. Try MAL ID — use media_type "any" since MDBList doesn't distinguish
        //    movie/show for MAL anime entries.
        val malId = extractPrefixedId(meta.id, "mal")
            ?: extractPrefixedId(fallbackItemId, "mal")
        if (malId != null) return MediaRef("mal", malId, "any")

        // 4. Try TVDB ID.
        val tvdbId = extractPrefixedId(meta.id, "tvdb")
            ?: extractPrefixedId(fallbackItemId, "tvdb")
        if (tvdbId != null) return MediaRef("tvdb", tvdbId, mediaType)

        return null
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractPrefixedId(rawId: String?, prefix: String): String? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("$prefix:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }
}
