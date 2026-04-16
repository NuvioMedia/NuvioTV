package com.omnio.tv.domain.model

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
