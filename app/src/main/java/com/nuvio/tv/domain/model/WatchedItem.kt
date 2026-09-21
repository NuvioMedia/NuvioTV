package com.nuvio.tv.domain.model

data class WatchedItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val watchedAt: Long,
    val poster: String? = null,
    val releaseInfo: String? = null,
    override val trackingProviderId: String? = null,
    override val trackingProviderItemId: String? = null,
    override val trackingSourceUrl: String? = null
) : TrackingAttributedItem {
    override val trackingContentId: String
        get() = contentId
}

data class WatchedMutationKey(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

fun WatchedItem.mutationKey(): WatchedMutationKey = WatchedMutationKey(
    contentId = contentId,
    season = season,
    episode = episode
)
