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

    @Test
    fun `surround format settings stay device local under player settings`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "surround_format_mode"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "surround_channel_target"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_ac3_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_eac3_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_truehd_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_dts_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_dtshd_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "denied_codec_handling"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "audio_rejections_seen"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "audio_rejections_confirmed"))
    }

    @Test
    fun `surround format keys are not excluded under an unrelated feature`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "surround_format_mode"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "audio_rejections_confirmed"))
    }
}
