package com.nuvio.tv.domain.model

data class MDBListRatings(
    val trakt: Double? = null,
    val imdb: Double? = null,
    val tmdb: Double? = null,
    val letterboxd: Double? = null,
    val tomatoes: Double? = null,
    val audience: Double? = null,
    val metacritic: Double? = null,
    val mal: Double? = null
) {
    fun isEmpty(): Boolean = trakt == null && imdb == null && tmdb == null &&
        letterboxd == null && tomatoes == null && audience == null && metacritic == null && mal == null
}

data class MDBListRatingsResult(
    val ratings: MDBListRatings,
    val hasImdbRating: Boolean
)
