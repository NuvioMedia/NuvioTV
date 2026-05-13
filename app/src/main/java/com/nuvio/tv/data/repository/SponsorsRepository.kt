package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SponsorsApi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class DevelopmentSponsor(
    val id: String,
    val name: String,
    val channelUrl: String?,
    val createdAt: String,
    val sortTimestamp: Long
)

@Singleton
class SponsorsRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val sponsorsApi: SponsorsApi
) {

    suspend fun getSponsors(): Result<List<DevelopmentSponsor>> = runCatching {
        val response = sponsorsApi.getSponsors()
        if (!response.isSuccessful) {
            error(appContext.getString(com.nuvio.tv.R.string.sponsors_error_api_http, response.code()))
        }

        response.body()
            ?.sponsors
            .orEmpty()
            .mapIndexedNotNull { index, sponsor ->
                val name = sponsor.name?.trim().orEmpty()
                val createdAt = sponsor.createdAt?.trim().orEmpty()
                if (name.isBlank() || createdAt.isBlank()) return@mapIndexedNotNull null

                DevelopmentSponsor(
                    id = sponsor.id?.trim()?.takeIf { it.isNotBlank() } ?: "$name|$index",
                    name = name,
                    channelUrl = sponsor.channelUrl?.trim()?.takeIf { it.isNotBlank() },
                    createdAt = createdAt,
                    sortTimestamp = parseTimestamp(createdAt)
                )
            }
            .sortedByDescending { it.sortTimestamp }
    }

    private fun parseTimestamp(rawDate: String): Long {
        return runCatching { Instant.parse(rawDate).toEpochMilli() }
            .getOrDefault(Long.MIN_VALUE)
    }
}
