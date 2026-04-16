package com.omnio.tv.data.remote.dto.mdblist

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
