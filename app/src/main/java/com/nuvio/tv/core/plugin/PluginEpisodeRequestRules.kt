package com.nuvio.tv.core.plugin

import java.util.Locale

/**
 * Pure helpers for mapping anime video IDs into plugin season/episode args.
 *
 * CloudStream / local plugins often catalog anime with **absolute** episode
 * numbers (season omitted). Meta video IDs for anime trackers are always:
 * - `kitsu:6448:68` → absolute episode 68
 * - `mal:63375:68` → absolute episode 68
 * - `anilist:12345:12` → absolute episode 12
 * - `anidb:…:N` → absolute episode N
 *
 * These trackers have no season component in the video ID (never
 * `mal:…:season:episode`). Season coordinates live only in TMDB/TVDB-style
 * IDs such as `tt2098220:2:10`.
 *
 * Passing UI S2E10 into absolute-episode providers matches absolute episode 10
 * (S1E10) instead of absolute 68. When the videoId already encodes the
 * absolute episode, plugins must use that with `season = null`.
 */

private val ANIME_ID_PREFIXES = setOf("kitsu", "mal", "anilist", "anidb")

/**
 * Absolute episode number encoded in [videoId], or null when the id is not an
 * anime absolute-episode form (`prefix:showId:episode`).
 */
fun absoluteAnimeEpisodeNumber(videoId: String): Int? {
    val parts = videoId.trim().split(':')
    // MAL/Kitsu/AniList/AniDB episode IDs are exactly three segments.
    // There is no season field on these trackers.
    if (parts.size != 3) return null
    val prefix = parts[0].lowercase(Locale.US)
    if (prefix !in ANIME_ID_PREFIXES) return null
    // Require a non-empty numeric show id so garbage like "mal::12" is rejected.
    if (parts[1].isBlank() || parts[1].toLongOrNull() == null) return null
    return parts[2].toIntOrNull()
}

/**
 * Season/episode pair to send to local plugins for [videoId].
 *
 * Absolute anime IDs force `season = null` and the absolute episode number so
 * providers that store flat episode lists resolve the correct entry.
 */
fun resolvePluginSeasonEpisode(
    videoId: String,
    season: Int?,
    episode: Int?
): Pair<Int?, Int?> {
    val absolute = absoluteAnimeEpisodeNumber(videoId)
    return if (absolute != null) {
        null to absolute
    } else {
        season to episode
    }
}

/**
 * Index of the plugin catalog entry that safely matches [season]/[episode].
 *
 * [entries] are `(season, episode)` pairs from a CloudStream/local provider catalog.
 *
 * Rules:
 * - Season-bearing requests only match the exact S/E pair (no episode-number-only
 *   fallback — that is what mapped S2E10 → absolute episode 10 on flat lists).
 * - Absolute requests (`season == null`) prefer season-less entries, then any
 *   entry with that episode number (some absolute catalogs tag every row as S1).
 */
fun matchPluginEpisodeIndex(
    entries: List<Pair<Int?, Int?>>,
    season: Int?,
    episode: Int?
): Int? {
    if (entries.isEmpty() || episode == null) return null

    if (season != null) {
        val exact = entries.indexOfFirst { (s, e) -> s == season && e == episode }
        return exact.takeIf { it >= 0 }
    }

    val absolute = entries.indexOfFirst { (s, e) -> e == episode && s == null }
    if (absolute >= 0) return absolute
    val anyEpisode = entries.indexOfFirst { (_, e) -> e == episode }
    return anyEpisode.takeIf { it >= 0 }
}
