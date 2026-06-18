package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
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
class SponsorsRepository @Inject constructor() {

    suspend fun getSponsors(): Result<List<DevelopmentSponsor>> = runCatching {
        parseSponsorNames(BuildConfig.SPONSOR_NAMES)
    }

    internal fun parseSponsorNames(rawNames: String): List<DevelopmentSponsor> {
        return rawNames
            .split(",")
            .mapIndexedNotNull { index, rawName ->
                val name = rawName.trim()
                if (name.isBlank()) return@mapIndexedNotNull null
                DevelopmentSponsor(
                    id = "${name.lowercase()}|$index",
                    name = name,
                    channelUrl = null,
                    createdAt = "",
                    sortTimestamp = (Int.MAX_VALUE - index).toLong()
                )
            }
    }
}
