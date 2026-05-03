package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SponsorsResponseDto(
    val sponsors: List<SponsorDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SponsorDto(
    val id: String? = null,
    val name: String? = null,
    val channelUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
