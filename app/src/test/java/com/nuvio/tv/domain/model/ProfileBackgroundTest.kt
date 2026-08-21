package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileBackgroundTest {
    private val backgroundAccess = CosmeticEntitlements(
        setOf(CosmeticEntitlement.PROFILE_BACKGROUNDS)
    )

    @Test
    fun profilesWithoutASelectionUseTheLegacyBackground() {
        assertNull(resolveProfileBackgroundSelection(profile(id = 1), backgroundAccess))
        assertNull(resolveProfileBackgroundSelection(profile(id = 2), backgroundAccess))
    }

    @Test
    fun savedSelectionIsRenderedForMembers() {
        val profile = profile(id = 1, backgroundId = "graphite")

        assertEquals(
            ProfileBackgroundSelection.Catalog("graphite"),
            resolveProfileBackgroundSelection(profile, backgroundAccess)
        )
    }

    @Test
    fun customBackgroundIsRenderedForMembers() {
        val profile = profile(id = 1, backgroundUrl = "https://example.com/background.jpg")

        assertEquals(
            ProfileBackgroundSelection.Custom("https://example.com/background.jpg"),
            resolveProfileBackgroundSelection(profile, backgroundAccess)
        )
    }

    @Test
    fun customBackgroundWinsOverCatalogSelection() {
        val profile = profile(
            id = 1,
            backgroundId = "jade",
            backgroundUrl = "https://example.com/background.jpg"
        )

        assertEquals(
            ProfileBackgroundSelection.Custom("https://example.com/background.jpg"),
            resolveProfileBackgroundSelection(profile, backgroundAccess)
        )
    }

    @Test
    fun unavailableEntitlementHidesSavedSelection() {
        val profile = profile(id = 1, backgroundId = "jade")

        assertNull(resolveProfileBackgroundSelection(profile, CosmeticEntitlements.None))
    }

    private fun profile(
        id: Int,
        backgroundId: String? = null,
        backgroundUrl: String? = null
    ) = UserProfile(
        id = id,
        name = "Profile $id",
        avatarColorHex = "#1E88E5",
        profileBackgroundId = backgroundId,
        profileBackgroundUrl = backgroundUrl
    )
}
