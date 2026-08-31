package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BsmProfileRatingDto(
    val profileIndex: Int = 0,
    val contentRating: String? = null,
    val profileType: String? = null,
    val dateOfBirth: String? = null
)
