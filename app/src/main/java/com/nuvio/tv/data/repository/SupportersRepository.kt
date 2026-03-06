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

@Singleton
class SupportersRepository @Inject constructor(
    private val donationsApi: DonationsApi
) {

    suspend fun getSupporters(limit: Int = 200): Result<List<SupporterDonation>> = runCatching {
        val response = donationsApi.getDonations(limit = limit)
        if (!response.isSuccessful) {
            error("Donations API error: ${response.code()}")
        }

        response.body()
            ?.donations
            .orEmpty()
            .mapNotNull { donation ->
                val name = donation.name?.trim().orEmpty()
                val date = donation.date?.trim().orEmpty()
                if (name.isBlank() || date.isBlank()) return@mapNotNull null

                SupporterDonation(
                    key = "$name|$date",
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
    }

    private fun parseTimestamp(rawDate: String): Long {
        return runCatching { Instant.parse(rawDate).toEpochMilli() }
            .getOrDefault(Long.MIN_VALUE)
    }
}
