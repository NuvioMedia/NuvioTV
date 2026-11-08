package com.nuvio.tv.data.remote.dto.mdblist

import com.nuvio.tv.domain.model.MDBListRatings
import com.squareup.moshi.Json

data class MDBListMediaRequestDto(
    val ids: List<String>,
    @Json(name = "append_to_response") val appendToResponse: List<String> = listOf("keyword")
)

data class MDBListMediaResponseDto(
    val ratings: List<MDBListMediaRatingDto>? = null,
    val keywords: List<Any?>? = null,
    val ids: Map<String, Any?>? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    val imdbid: String? = null
) {
    fun resolvedImdbId(): String? = imdbId ?: imdbid ?: ids?.get("imdb") as? String

    fun toRatings(): MDBListRatings {
        val keywordNames = keywords.orEmpty().mapNotNull { keyword ->
            val name = when (keyword) {
                is Map<*, *> -> keyword["name"] as? String
                is String -> keyword
                else -> null
            }
            name?.substringAfterLast('.')
        }.toSet()
        val values = ratings.orEmpty().mapNotNull { rating ->
            val source = when (rating.source) {
                "popcorn", "audience", "tomatoesaudience" -> "audience"
                "myanimelist", "mal" -> "mal"
                "imdb", "trakt", "tmdb", "letterboxd", "tomatoes", "metacritic" -> rating.source
                else -> return@mapNotNull null
            }
            val maximum = when (source) {
                "imdb", "mal" -> 10.0
                "letterboxd" -> 5.0
                else -> 100.0
            }
            val rawValue = rating.value?.takeIf { it >= 0 } ?: return@mapNotNull null
            val value = if (source == "letterboxd" && rating.score != null) rating.score / 20.0 else rawValue
            if (value !in 0.0..maximum) return@mapNotNull null
            source to value
        }.distinctBy { it.first }.toMap()
        return MDBListRatings(
            trakt = values["trakt"],
            imdb = values["imdb"],
            tmdb = values["tmdb"],
            letterboxd = values["letterboxd"],
            tomatoes = values["tomatoes"],
            audience = values["audience"],
            metacritic = values["metacritic"],
            mal = values["mal"],
            tomatoesCertified = values["tomatoes"] != null && "certified-fresh" in keywordNames,
            audienceCertified = values["audience"] != null && "certified-hot" in keywordNames
        )
    }
}

data class MDBListMediaRatingDto(
    val source: String,
    val value: Double? = null,
    val score: Double? = null
)
