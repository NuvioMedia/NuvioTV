package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DonationsResponseDto(
    val donations: List<DonationDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class DonationDto(
    val name: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val date: String? = null,
    val message: String? = null
)
