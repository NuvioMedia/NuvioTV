package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBannerPolicyTest {

    @Test
    fun `automatic check shows a new update when the banner is enabled`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = null,
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `dismissed update stays hidden during automatic checks`() {
        assertFalse(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = "v1.2.0",
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `a newer tag is shown after the previous update was dismissed`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = "v1.2.0",
                updateTag = "v1.3.0"
            )
        )
    }

    @Test
    fun `manual check shows the update despite dismissal and disabled banner`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = true,
                bannerEnabled = false,
                dismissedTag = "v1.2.0",
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `current version never shows as an update`() {
        assertFalse(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = false,
                force = true,
                bannerEnabled = true,
                dismissedTag = null,
                updateTag = "v1.2.0"
            )
        )
    }
}
