package com.nuvio.tv.ui.util

private val TMDB_SIZED_IMAGE_PATH =
    Regex("""^(https://image\.tmdb\.org/t/p/)(?:w|h)\d+(/.+)$""", RegexOption.IGNORE_CASE)

/**
 * Full-screen artwork needs more source pixels than TMDB's 1280px backdrop.
 * Coil still decodes the image to the requested display size.
 */
fun String?.preferOriginalTmdbArtwork(enabled: Boolean = true): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!enabled) return value
    return TMDB_SIZED_IMAGE_PATH.replace(value) { match ->
        "${match.groupValues[1]}original${match.groupValues[2]}"
    }
}
