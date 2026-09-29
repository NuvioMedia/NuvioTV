package com.nuvio.tv.core.poster

import com.nuvio.tv.core.poster.CustomPosterUrlResolver.ContentIds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

/**
 * Applies a custom poster URL pattern to a [MetaPreview], replacing the poster URL
 * with one generated from the pattern. The original poster is preserved in [MetaPreview.rawPosterUrl]
 * so that Coil can fall back to it on load error.
 *
 * If the pattern is blank, or the resolver returns `null` (missing required ID), the preview
 * is returned unchanged.
 *
 * When the pattern contains `{shape}`, the landscape poster is also resolved.
 */
fun MetaPreview.withCustomPosterUrl(
    pattern: String
): MetaPreview {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(id, explicitImdbId = imdbId)
    val type = this.type.toApiString(rawType)
    val supportsShape = "{shape}" in pattern

    // If the pattern doesn't support shape, only override portrait posters
    if (!supportsShape && posterShape != PosterShape.POSTER) return this

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = type,
        shape = if (supportsShape) posterShape.toShapeParam() else "poster"
    )

    val resolvedLandscape = if (supportsShape) {
        CustomPosterUrlResolver.resolve(
            pattern = pattern,
            ids = ids,
            type = type,
            shape = "landscape"
        )
    } else null

    if (resolvedPoster == null && resolvedLandscape == null) return this

    return copy(
        poster = resolvedPoster ?: poster,
        rawPosterUrl = rawPosterUrl ?: poster, // preserve original for Coil fallback
        landscapePoster = resolvedLandscape ?: landscapePoster
    )
}

private fun PosterShape.toShapeParam(): String = when (this) {
    PosterShape.POSTER -> "poster"
    PosterShape.LANDSCAPE -> "landscape"
    PosterShape.SQUARE -> "square"
}

/**
 * Applies [withCustomPosterUrl] to every item in a list.
 */
fun List<MetaPreview>.withCustomPosterUrls(
    pattern: String
): List<MetaPreview> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}

/**
 * Applies a custom poster URL pattern to a [Meta] (detail screen).
 * Same logic as [MetaPreview.withCustomPosterUrl].
 */
fun Meta.withCustomPosterUrl(
    pattern: String
): Meta {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(id, explicitImdbId = imdbId)
    val type = this.type.toApiString(rawType)
    val supportsShape = "{shape}" in pattern

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = type,
        shape = "poster"
    )

    val resolvedLandscape = if (supportsShape) {
        CustomPosterUrlResolver.resolve(
            pattern = pattern,
            ids = ids,
            type = type,
            shape = "landscape"
        )
    } else null

    if (resolvedPoster == null && resolvedLandscape == null) return this

    return copy(
        poster = resolvedPoster ?: poster,
        rawPosterUrl = rawPosterUrl ?: poster,
        landscapePoster = resolvedLandscape ?: landscapePoster
    )
}

/**
 * Applies a custom poster URL pattern to a [LibraryEntry].
 */
fun LibraryEntry.withCustomPosterUrl(
    pattern: String
): LibraryEntry {
    if (pattern.isBlank()) return this

    val ids = CustomPosterUrlResolver.extractIds(id, explicitImdbId = imdbId)
    val type = this.type.let { if (it.equals("tv", ignoreCase = true)) "series" else it }

    val resolvedPoster = CustomPosterUrlResolver.resolve(
        pattern = pattern,
        ids = ids,
        type = type,
        shape = "poster"
    ) ?: return this

    return copy(
        poster = resolvedPoster,
        rawPosterUrl = rawPosterUrl ?: poster
    )
}

/**
 * Applies [withCustomPosterUrl] to every item in a list of [LibraryEntry].
 */
@JvmName("withCustomPosterUrlsLibrary")
fun List<LibraryEntry>.withCustomPosterUrls(
    pattern: String
): List<LibraryEntry> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}

/**
 * Applies custom poster URLs to all items in a [TmdbEntityBrowseData].
 */
fun com.nuvio.tv.core.tmdb.TmdbEntityBrowseData.withCustomPosterUrls(
    pattern: String
): com.nuvio.tv.core.tmdb.TmdbEntityBrowseData {
    if (pattern.isBlank()) return this
    return copy(
        rails = rails.map { rail ->
            rail.copy(items = rail.items.withCustomPosterUrls(pattern))
        }
    )
}

/**
 * Applies a custom poster URL to a [ContinueWatchingItem].
 */
fun com.nuvio.tv.ui.screens.home.ContinueWatchingItem.withCustomPosterUrl(
    pattern: String
): com.nuvio.tv.ui.screens.home.ContinueWatchingItem {
    if (pattern.isBlank()) return this
    val supportsShape = "{shape}" in pattern
    return when (this) {
        is com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress -> {
            val ids = CustomPosterUrlResolver.extractIds(progress.contentId)
            val type = if (progress.contentType.equals("movie", ignoreCase = true)) "movie" else "series"
            val resolvedPoster = CustomPosterUrlResolver.resolve(pattern, ids, type)
            val resolvedLandscape = if (supportsShape) {
                CustomPosterUrlResolver.resolve(pattern, ids, type, shape = "landscape")
            } else null
            if (resolvedPoster == null && resolvedLandscape == null) return this
            copy(
                progress = progress.copy(poster = resolvedPoster ?: progress.poster),
                originalPoster = originalPoster ?: progress.poster,
                customLandscapePoster = resolvedLandscape
            )
        }
        is com.nuvio.tv.ui.screens.home.ContinueWatchingItem.NextUp -> {
            val ids = CustomPosterUrlResolver.extractIds(info.contentId)
            val type = if (info.contentType.equals("movie", ignoreCase = true)) "movie" else "series"
            val resolvedPoster = CustomPosterUrlResolver.resolve(pattern, ids, type)
            val resolvedLandscape = if (supportsShape) {
                CustomPosterUrlResolver.resolve(pattern, ids, type, shape = "landscape")
            } else null
            if (resolvedPoster == null && resolvedLandscape == null) return this
            copy(
                info = info.copy(poster = resolvedPoster ?: info.poster),
                originalPoster = originalPoster ?: info.poster,
                customLandscapePoster = resolvedLandscape
            )
        }
    }
}

/**
 * Applies [withCustomPosterUrl] to every item in a list of [ContinueWatchingItem].
 */
@JvmName("withCustomPosterUrlsCw")
fun List<com.nuvio.tv.ui.screens.home.ContinueWatchingItem>.withCustomPosterUrls(
    pattern: String
): List<com.nuvio.tv.ui.screens.home.ContinueWatchingItem> {
    if (pattern.isBlank()) return this
    return map { it.withCustomPosterUrl(pattern) }
}
