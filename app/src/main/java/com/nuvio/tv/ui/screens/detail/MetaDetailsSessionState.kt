package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.NextToWatch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaDetailsSessionState @Inject constructor() {
    private data class CacheKey(
        val profileId: Int,
        val progressSource: String,
        val contentId: String,
        val contentType: String
    )

    private val nextToWatchCache = ConcurrentHashMap<CacheKey, NextToWatch>()

    fun getNextToWatch(
        profileId: Int,
        progressSource: String,
        contentId: String,
        contentType: String
    ): NextToWatch? = nextToWatchCache[cacheKey(
        profileId = profileId,
        progressSource = progressSource,
        contentId = contentId,
        contentType = contentType
    )]

    fun putNextToWatch(
        profileId: Int,
        progressSource: String,
        contentId: String,
        contentType: String,
        nextToWatch: NextToWatch
    ) {
        nextToWatchCache[cacheKey(
            profileId = profileId,
            progressSource = progressSource,
            contentId = contentId,
            contentType = contentType
        )] = nextToWatch
    }

    private fun cacheKey(
        profileId: Int,
        progressSource: String,
        contentId: String,
        contentType: String
    ) = CacheKey(
        profileId = profileId,
        progressSource = progressSource.trim().uppercase(),
        contentId = contentId.trim().lowercase(),
        contentType = contentType.trim().lowercase()
    )
}
