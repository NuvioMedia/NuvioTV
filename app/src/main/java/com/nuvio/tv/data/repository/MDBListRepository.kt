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
        val imdbId = resolveImdbId(
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

        return ratingsLoader.getRatings(mediaType, imdbId, credential)?.imdb
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
        val imdbId = resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType) ?: return null

        val ratings = ratingsLoader.getRatings(mediaType, imdbId, credential)?.let { allRatings ->
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

    private suspend fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }
        extractImdbId(meta.imdbId)?.let { return it }

        val tmdbId = extractTmdbId(meta.id)
            ?: extractTmdbId(fallbackItemId)
            ?: meta.id.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()

        if (tmdbId != null) {
            val mapped = tmdbService.tmdbToImdb(tmdbId, fallbackItemType)
            if (!mapped.isNullOrBlank()) return mapped
        }

        val lookupType = if (fallbackItemType.isNotBlank()) fallbackItemType else mediaType
        val converted = tmdbService.ensureTmdbId(meta.id, lookupType)?.toIntOrNull()?.let { tmdbNumericId ->
            tmdbService.tmdbToImdb(tmdbNumericId, lookupType)
        }
        return converted?.takeIf { it.startsWith("tt") }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
    }

    private fun extractTmdbId(rawId: String?): Int? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
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
