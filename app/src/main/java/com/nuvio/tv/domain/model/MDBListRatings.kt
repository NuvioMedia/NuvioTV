package com.nuvio.tv.domain.model

data class MDBListRatings(
    val trakt: Double? = null,
    val imdb: Double? = null,
    val tmdb: Double? = null,
    val letterboxd: Double? = null,
    val tomatoes: Double? = null,
    val audience: Double? = null,
    val metacritic: Double? = null,
    val mal: Double? = null,
    val tomatoesCertified: Boolean = false,
    val audienceCertified: Boolean = false
) {
    val tomatoesStatus: RottenTomatoesStatus?
        get() = when {
            tomatoes == null -> null
            tomatoesCertified && tomatoes >= 70 -> RottenTomatoesStatus.CERTIFIED_FRESH
            tomatoes >= 60 -> RottenTomatoesStatus.FRESH
            else -> RottenTomatoesStatus.ROTTEN
        }

    val audienceStatus: RottenTomatoesStatus?
        get() = when {
            audience == null -> null
            audienceCertified && audience >= 80 -> RottenTomatoesStatus.VERIFIED_HOT
            audience >= 60 -> RottenTomatoesStatus.HOT
            else -> RottenTomatoesStatus.STALE
        }

    fun isEmpty(): Boolean = trakt == null && imdb == null && tmdb == null &&
        letterboxd == null && tomatoes == null && audience == null && metacritic == null && mal == null
}

enum class RottenTomatoesStatus {
    FRESH,
    ROTTEN,
    CERTIFIED_FRESH,
    HOT,
    STALE,
    VERIFIED_HOT
}

data class MDBListRatingsResult(
    val ratings: MDBListRatings,
    val hasImdbRating: Boolean
)
