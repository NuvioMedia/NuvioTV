package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.TvType

/** Map CloudStream categories to the external Nuvio/Stremio content types. */
fun TvType.toNuvioType(): String = when (this) {
    TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> "movie"
    TvType.Live -> "tv"
    else -> "series"
}

/** Parse TvType from string name, case-insensitive. */
fun tvTypeFromString(value: String): TvType? = TvType.entries.firstOrNull {
    it.name.equals(value, ignoreCase = true)
}
