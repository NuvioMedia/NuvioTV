package com.nuvio.tv.core.streams

/** Converts the TMDB-style "tv" label to the addon/plugin type only when episode context proves it is a series. */
internal fun externalStreamType(type: String, season: Int?, episode: Int?): String {
    val normalized = type.trim().lowercase()
    return if (normalized == "tv" && season != null && episode != null) "series" else normalized
}
