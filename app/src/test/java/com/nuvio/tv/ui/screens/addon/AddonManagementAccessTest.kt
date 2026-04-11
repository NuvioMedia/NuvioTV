package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManagementAccessTest {

    @Test
    fun `secondary profile using primary addons is read only and collections only`() {
        val profile = UserProfile(
            id = 2,
            name = "Secondary",
            avatarColorHex = "#FFFFFF",
            usesPrimaryAddons = true
        )

        assertTrue(AddonManagementAccess.isReadOnly(profile))
        assertEquals(
            AddonConfigServer.WebConfigMode.COLLECTIONS_ONLY,
            AddonManagementAccess.webConfigMode(profile)
        )
    }

    @Test
    fun `primary profile keeps full addon management`() {
        val profile = UserProfile(
            id = 1,
            name = "Primary",
            avatarColorHex = "#000000",
            usesPrimaryAddons = true
        )

        assertFalse(AddonManagementAccess.isReadOnly(profile))
        assertEquals(
            AddonConfigServer.WebConfigMode.FULL,
            AddonManagementAccess.webConfigMode(profile)
        )
    }
}