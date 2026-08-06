package com.nuvio.tv.domain.model

interface TrackingAttributedItem {
    val trackingContentId: String
    val trackingProviderId: String?
    val trackingProviderItemId: String?
    val trackingSourceUrl: String?
}

data class TrackingAttribution(
    val providerId: String,
    val providerItemId: String?,
    val sourceUrl: String
)

fun resolveTrackingAttribution(
    contentId: String,
    providerId: String,
    items: Sequence<TrackingAttributedItem>
): TrackingAttribution? = items.firstNotNullOfOrNull { item ->
    val sourceUrl = item.trackingSourceUrl?.trim()?.takeIf(String::isNotEmpty)
    if (
        sourceUrl != null &&
        item.trackingContentId.equals(contentId, ignoreCase = true) &&
        item.trackingProviderId.equals(providerId, ignoreCase = true)
    ) {
        TrackingAttribution(
            providerId = providerId,
            providerItemId = item.trackingProviderItemId,
            sourceUrl = sourceUrl
        )
    } else {
        null
    }
}
