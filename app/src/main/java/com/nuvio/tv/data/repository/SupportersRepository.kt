package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.DonationsApi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SupporterDonation(
    val key: String,
    val name: String,
    val date: String,
    val message: String?,
    val sortTimestamp: Long
)

data class DonationProgress(
    val progressPercent: Int
)

data class SupportersResult(
    val supporters: List<SupporterDonation>,
    val progress: DonationProgress?
)

@Singleton
class SupportersRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val donationsApi: DonationsApi
) {

    suspend fun getSupporters(): Result<SupportersResult> = runCatching {
        val response = donationsApi.getDonations()
        if (!response.isSuccessful) {
            error(appContext.getString(com.nuvio.tv.R.string.supporters_error_api_http, response.code()))
        }

        val donationsResponse = response.body()
        val supporters = donationsResponse
            ?.donations
            .orEmpty()
            .mapNotNull { donation ->
                val name = donation.name?.trim().orEmpty()
                val date = donation.date?.trim()
                    ?: donation.createdAt?.trim()
                    ?: ""
                if (name.isBlank() || date.isBlank()) return@mapNotNull null

                SupporterDonation(
                    key = donation.id?.trim()?.takeIf { it.isNotBlank() } ?: "$name|$date",
                    name = name,
                    date = date,
                    message = donation.message?.trim()?.takeIf { it.isNotBlank() },
                    sortTimestamp = parseTimestamp(date)
                )
            }
            .sortedByDescending { it.sortTimestamp }
            .mapIndexed { index, donation ->
                donation.copy(key = "${donation.key}#$index")
            }

        val progress = donationsResponse
            ?.monthlyGoal
            ?.progressPercent
            ?.toInt()
            ?.coerceIn(0, 100)
            ?.let { percent -> DonationProgress(progressPercent = percent) }

        SupportersResult(
            supporters = supporters,
            progress = progress
        )
    }

    private fun parseTimestamp(rawDate: String): Long {
        return runCatching { Instant.parse(rawDate).toEpochMilli() }
            .getOrDefault(Long.MIN_VALUE)
    }
}
