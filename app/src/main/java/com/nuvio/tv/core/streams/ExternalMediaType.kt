package com.nuvio.tv.core.streams

/** Canonicalizes Stremio's built-in types while preserving custom types. */
internal fun canonicalExternalMediaType(type: String): String {
    val trimmed = type.trim()
    val normalized = trimmed.lowercase()
    return if (normalized in STANDARD_EXTERNAL_MEDIA_TYPES) normalized else trimmed
}

/** Converts an internal TMDB "tv" label only when episode context identifies a series. */
internal fun externalStreamType(type: String, season: Int?, episode: Int?): String {
    val normalized = canonicalExternalMediaType(type)
    return if (normalized == "tv" && season != null && episode != null) "series" else normalized
}

private val STANDARD_EXTERNAL_MEDIA_TYPES = setOf("movie", "series", "tv", "channel")
