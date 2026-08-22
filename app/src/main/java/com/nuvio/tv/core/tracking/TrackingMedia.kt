package com.nuvio.tv.core.tracking

data class TrackingExternalIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: String? = null,
    val trakt: Long? = null,
    val simkl: Long? = null,
    val mal: Long? = null,
    val anidb: Long? = null,
    val anilist: Long? = null,
    val kitsu: Long? = null
) {
    val hasAny: Boolean
        get() = listOf(imdb, tmdb, tvdb, trakt, simkl, mal, anidb, anilist, kitsu).any { it != null }

    fun mergeMissing(other: TrackingExternalIds): TrackingExternalIds = TrackingExternalIds(
        imdb = imdb ?: other.imdb,
        tmdb = tmdb ?: other.tmdb,
        tvdb = tvdb ?: other.tvdb,
        trakt = trakt ?: other.trakt,
        simkl = simkl ?: other.simkl,
        mal = mal ?: other.mal,
        anidb = anidb ?: other.anidb,
        anilist = anilist ?: other.anilist,
        kitsu = kitsu ?: other.kitsu
    )
}

enum class TrackingMediaKind {
    MOVIE,
    SHOW,
    ANIME
}

data class TrackingEpisode(
    val season: Int? = null,
    val number: Int,
    val title: String? = null,
    val tvdbId: Long? = null,
    val usesTvdbSeasonMapping: Boolean = false
)

data class TrackingCatalogReference(
    val contentId: String,
    val contentType: String,
    val videoId: String? = null
)

data class TrackingMediaReference(
    val kind: TrackingMediaKind,
    val title: String? = null,
    val year: Int? = null,
    val ids: TrackingExternalIds = TrackingExternalIds(),
    val episode: TrackingEpisode? = null,
    val catalog: TrackingCatalogReference? = null,
    val posterUrl: String? = null
) {
    val hasResolvableIdentity: Boolean
        get() = ids.hasAny || !title.isNullOrBlank()

    val stableKey: String
        get() = buildString {
            append(kind.name.lowercase())
            append(':')
            append(
                ids.simkl?.let { "simkl:$it" }
                    ?: ids.imdb?.takeIf(String::isNotBlank)?.let { "imdb:$it" }
                    ?: ids.tmdb?.let { "tmdb:$it" }
                    ?: ids.tvdb?.takeIf(String::isNotBlank)?.let { "tvdb:$it" }
                    ?: ids.trakt?.let { "trakt:$it" }
                    ?: ids.mal?.let { "mal:$it" }
                    ?: ids.anidb?.let { "anidb:$it" }
                    ?: ids.anilist?.let { "anilist:$it" }
                    ?: ids.kitsu?.let { "kitsu:$it" }
                    ?: "title:${title.orEmpty().trim().lowercase()}:${year ?: 0}"
            )
        }
}

fun parseTrackingExternalIds(rawValue: String?): TrackingExternalIds {
    if (rawValue.isNullOrBlank()) return TrackingExternalIds()
    val full = rawValue.trim()
    if (full.startsWith("tt", ignoreCase = true)) {
        return TrackingExternalIds(imdb = full.substringBefore(':'))
    }
    val prefix = full.substringBefore(':').lowercase()
    val value = full.substringAfter(':', "").substringBefore(':').trim()
    return when (prefix) {
        "imdb" -> TrackingExternalIds(imdb = value.takeIf(String::isNotBlank))
        "tmdb" -> TrackingExternalIds(tmdb = value.toLongOrNull())
        "tvdb" -> TrackingExternalIds(tvdb = value.takeIf(String::isNotBlank))
        "trakt" -> TrackingExternalIds(trakt = value.toLongOrNull())
        "simkl" -> TrackingExternalIds(simkl = value.toLongOrNull())
        "mal" -> TrackingExternalIds(mal = value.toLongOrNull())
        "anidb" -> TrackingExternalIds(anidb = value.toLongOrNull())
        "anilist" -> TrackingExternalIds(anilist = value.toLongOrNull())
        "kitsu" -> TrackingExternalIds(kitsu = value.toLongOrNull())
        else -> TrackingExternalIds(trakt = full.toLongOrNull())
    }
}

fun trackingMediaKind(contentType: String, ids: TrackingExternalIds = TrackingExternalIds()): TrackingMediaKind {
    val normalized = contentType.trim().lowercase()
    val hasAnimeIds = ids.mal != null || ids.anidb != null || ids.anilist != null || ids.kitsu != null
    return when {
        hasAnimeIds || normalized == "anime" -> TrackingMediaKind.ANIME
        normalized in setOf("movie", "film") -> TrackingMediaKind.MOVIE
        else -> TrackingMediaKind.SHOW
    }
}

fun extractTrackingYear(value: String?): Int? = value
    ?.let { TRACKING_YEAR_PATTERN.find(it)?.value }
    ?.toIntOrNull()

fun buildTrackingMediaReference(
    contentType: String,
    parentMetaId: String,
    videoId: String? = null,
    title: String? = null,
    releaseInfo: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null
): TrackingMediaReference {
    val ids = parseTrackingExternalIds(parentMetaId).mergeMissing(parseTrackingExternalIds(videoId))
    return TrackingMediaReference(
        kind = trackingMediaKind(contentType, ids),
        title = title?.trim()?.takeIf(String::isNotBlank),
        year = extractTrackingYear(releaseInfo),
        ids = ids,
        episode = episodeNumber?.let { number ->
            TrackingEpisode(season = seasonNumber, number = number, title = episodeTitle)
        },
        catalog = TrackingCatalogReference(
            contentId = parentMetaId.trim(),
            contentType = contentType.trim(),
            videoId = videoId?.trim()?.takeIf(String::isNotBlank)
        )
    )
}

private val TRACKING_YEAR_PATTERN = Regex("(19|20)\\d{2}")
