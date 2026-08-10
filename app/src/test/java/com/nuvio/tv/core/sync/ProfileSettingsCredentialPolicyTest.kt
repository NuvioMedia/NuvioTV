package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }
}
