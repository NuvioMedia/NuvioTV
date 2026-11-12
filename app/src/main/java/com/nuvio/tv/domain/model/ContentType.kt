package com.nuvio.tv.domain.model

enum class ContentType {
    MOVIE,
    SERIES,
    CHANNEL,
    TV,
    UNKNOWN;

    companion object {
        fun fromString(value: String): ContentType = when (value.trim().lowercase()) {
            "movie" -> MOVIE
            "series" -> SERIES
            "channel" -> CHANNEL
            "tv" -> TV
            else -> UNKNOWN
        }
    }

    fun toApiString(fallbackType: String? = null): String = when (this) {
        MOVIE -> "movie"
        SERIES -> "series"
        CHANNEL -> "channel"
        TV -> "tv"
        UNKNOWN -> fallbackType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "movie"
    }
}

/**
 * Whether items of this add-on type are shows with episodes. Items of any other type, such as
 * "movie" or an add-on's "youtube", are one video and are marked watched as a whole.
 */
fun isSeriesLikeType(apiType: String?): Boolean = when (apiType?.trim()?.lowercase()) {
    "series", "tv", "anime" -> true
    else -> false
}
