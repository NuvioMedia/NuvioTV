package com.nuvio.tv.data.remote.dto.mdblist

import com.nuvio.tv.domain.model.MDBListRatings
import com.squareup.moshi.Json

data class MDBListRatingRequestDto(
    val ids: List<String>,
    val provider: String
)

data class MDBListRatingResponseDto(
    @Json(name = "ratings") val ratings: List<MDBListRatingItemDto>? = null
)

data class MDBListRatingItemDto(
    @Json(name = "rating") val rating: Double? = null
)

data class MDBListMediaResponseDto(
    val ratings: List<MDBListMediaRatingDto>? = null,
    val keywords: List<Any?>? = null
) {
    fun toRottenTomatoesRatings(): MDBListRatings {
        val keywordNames = keywords.orEmpty().mapNotNull { keyword ->
            val name = when (keyword) {
                is Map<*, *> -> keyword["name"] as? String
                is String -> keyword
                else -> null
            }
            name?.substringAfterLast('.')
        }.toSet()
        val validRatings = ratings.orEmpty().filter { it.value != null && it.value in 0.0..100.0 }
        return MDBListRatings(
            tomatoes = validRatings.firstOrNull { it.source == "tomatoes" }?.value,
            audience = validRatings.firstOrNull {
                it.source == "popcorn" || it.source == "audience" || it.source == "tomatoesaudience"
            }?.value,
            tomatoesCertified = "certified-fresh" in keywordNames,
            audienceCertified = "certified-hot" in keywordNames
        )
    }
}

data class MDBListMediaRatingDto(
    val source: String,
    val value: Double? = null
)
