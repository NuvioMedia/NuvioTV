package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DonationsResponseDto(
    val currency: String? = null,
    val monthlyGoal: DonationMonthlyGoalDto? = null,
    val donations: List<DonationDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class DonationMonthlyGoalDto(
    val progressPercent: Double? = null,
    val monthLabel: String? = null
)

@JsonClass(generateAdapter = true)
data class DonationDto(
    val id: String? = null,
    val name: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val date: String? = null,
    val createdAt: String? = null,
    val message: String? = null
)
