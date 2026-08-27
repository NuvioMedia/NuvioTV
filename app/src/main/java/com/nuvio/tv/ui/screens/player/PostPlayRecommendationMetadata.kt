package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.countryToLanguageCode
import com.nuvio.tv.domain.model.normalizeLanguageCode

internal fun resolvePostPlayRecommendation(
    candidate: MetaPreview,
    meta: Meta?,
    enrichment: TmdbEnrichment?,
    settings: TmdbSettings,
    tmdbId: String?
): PostPlayRecommendation {
    val baseTitle = meta?.name?.takeIf { it.isNotBlank() } ?: candidate.name
    val basePoster = meta?.rawPosterUrl
        ?: meta?.poster
        ?: candidate.rawPosterUrl
        ?: candidate.poster
    val baseBackdrop = meta?.background
        ?: meta?.landscapePoster
        ?: candidate.background
        ?: candidate.landscapePoster
        ?: basePoster
    val baseLogo = meta?.logo ?: candidate.logo
    val useArtwork = settings.enabled && settings.useArtwork
    val useBasicInfo = settings.enabled && settings.useBasicInfo
    val useDetails = settings.enabled && settings.useDetails
    val useReleaseDates = settings.enabled && settings.useReleaseDates
    val country = if (useDetails) enrichment?.countries?.joinToString(", ") ?: meta?.country ?: candidate.country
    else meta?.country ?: candidate.country
    val language = if (useDetails) enrichment?.language ?: meta?.language ?: candidate.language
    else meta?.language ?: candidate.language

    return PostPlayRecommendation(
        id = candidate.id,
        contentType = candidate.apiType,
        title = if (useBasicInfo) {
            enrichment?.localizedTitle?.takeIf { it.isNotBlank() } ?: baseTitle
        } else {
            baseTitle
        },
        poster = basePoster,
        backdrop = if (useArtwork) enrichment?.backdrop ?: baseBackdrop else baseBackdrop,
        logo = if (useArtwork) enrichment?.logo ?: baseLogo else baseLogo,
        description = if (useBasicInfo) enrichment?.description ?: meta?.description ?: candidate.description
        else meta?.description ?: candidate.description,
        releaseInfo = if (useReleaseDates) enrichment?.releaseInfo ?: meta?.releaseInfo ?: candidate.releaseInfo
        else meta?.releaseInfo ?: candidate.releaseInfo,
        rating = meta?.imdbRating ?: candidate.imdbRating,
        genres = if (useBasicInfo && !enrichment?.genres.isNullOrEmpty()) {
            enrichment.genres
        } else {
            meta?.genres?.takeIf { it.isNotEmpty() } ?: candidate.genres
        },
        runtime = if (useDetails) {
            enrichment?.runtimeMinutes?.toString() ?: meta?.runtime ?: candidate.runtime
        } else {
            meta?.runtime ?: candidate.runtime
        },
        sourceAddonBaseUrl = candidate.sourceAddonBaseUrl,
        tmdbId = tmdbId,
        tmdbRating = if (useBasicInfo) enrichment?.rating?.toFloat() else null,
        ageRating = if (useDetails) enrichment?.ageRating ?: meta?.ageRating ?: candidate.ageRating
        else meta?.ageRating ?: candidate.ageRating,
        status = if (useDetails) enrichment?.status ?: meta?.status ?: candidate.status
        else meta?.status ?: candidate.status,
        country = country,
        language = language,
        contentLanguage = normalizeLanguageCode(language) ?: countryToLanguageCode(country)
    )
}
