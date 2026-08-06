package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingAttributionTest {
    @Test
    fun `resolver selects matching provider and content`() {
        val result = resolveTrackingAttribution(
            contentId = "tt123",
            providerId = "simkl",
            items = sequenceOf(
                attributedItem("tt123", "trakt", "https://trakt.tv/movies/123"),
                attributedItem("tt123", "simkl", "https://simkl.com/movies/456"),
                attributedItem("tt999", "simkl", "https://simkl.com/movies/999")
            )
        )

        assertEquals("simkl", result?.providerId)
        assertEquals("simkl:456", result?.providerItemId)
        assertEquals("https://simkl.com/movies/456", result?.sourceUrl)
    }

    @Test
    fun `resolver ignores missing and blank attribution`() {
        assertNull(
            resolveTrackingAttribution(
                contentId = "tt123",
                providerId = "simkl",
                items = sequenceOf(
                    attributedItem("tt123", "simkl", " "),
                    attributedItem("tt999", "simkl", "https://simkl.com/movies/999")
                )
            )
        )
    }

    private fun attributedItem(
        contentId: String,
        providerId: String,
        sourceUrl: String?
    ): TrackingAttributedItem = object : TrackingAttributedItem {
        override val trackingContentId = contentId
        override val trackingProviderId = providerId
        override val trackingProviderItemId = "simkl:456"
        override val trackingSourceUrl = sourceUrl
    }
}
