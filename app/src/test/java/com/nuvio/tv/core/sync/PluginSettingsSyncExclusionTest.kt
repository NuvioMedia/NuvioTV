package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsSyncExclusionTest {

    @Test
    fun `plugin settings feature name matches the PluginDataStore wire value`() {
        // The feature string doubles as the on-disk datastore name and the key
        // in the remote settings blob; renaming it would orphan synced data.
        assertEquals("plugin_settings", PLUGIN_SETTINGS_FEATURE)
    }

    @Test
    fun `group by repository preference is synced via profile settings`() {
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(
                feature = PLUGIN_SETTINGS_FEATURE,
                keyName = "group_streams_by_repository"
            )
        )
    }

    @Test
    fun `plugin repositories and scrapers stay local to profile settings sync`() {
        listOf("repositories", "scrapers", "scraper_settings", "plugins_enabled").forEach { key ->
            assertTrue(
                "expected $key to be excluded from profile settings sync",
                shouldExcludePreferenceFromProfileSettingsSync(
                    feature = PLUGIN_SETTINGS_FEATURE,
                    keyName = key
                )
            )
        }
    }

    @Test
    fun `plugin exclusion does not leak into other features`() {
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(
                feature = "theme_settings",
                keyName = "repositories"
            )
        )
    }
}
