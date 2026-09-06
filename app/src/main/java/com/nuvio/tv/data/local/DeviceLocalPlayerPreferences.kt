package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.ui.screens.player.AspectMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local player preferences that are NOT tied to any profile.
 * These values stay on the device and are never synced across devices or profiles.
 *
 * Currently stores:
 *  - aspectMode  (player aspect ratio mode)
 *  - playerStatsHudButtonEnabled  (whether stats overlay button is available in stream info)
 *  - playerStatsHudActive  (whether playback stats HUD is turned on by the user)
 */
@Singleton
class DeviceLocalPlayerPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        }
    ) {
        context.preferencesDataStoreFile("device_local_player_prefs")
    }

    private val aspectModeKey = stringPreferencesKey("aspect_mode")
    private val playerStatsHudButtonEnabledKey = booleanPreferencesKey("player_stats_hud_enabled")
    private val playerStatsHudActiveKey = booleanPreferencesKey("player_stats_hud_active")
    private val customUserAgentKey = stringPreferencesKey("custom_user_agent")

    val aspectMode: Flow<AspectMode> = store.data.map { prefs ->
        prefs[aspectModeKey]?.let {
            runCatching { AspectMode.valueOf(it) }.getOrDefault(AspectMode.ORIGINAL)
        } ?: AspectMode.ORIGINAL
    }

    suspend fun setAspectMode(mode: AspectMode) {
        store.edit { prefs ->
            prefs[aspectModeKey] = mode.name
        }
    }

    val playerStatsHudButtonEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[playerStatsHudButtonEnabledKey] ?: false
    }

    /**
     * Alias for [playerStatsHudButtonEnabled] for backward compatibility with settings UI.
     */
    val playerStatsHudEnabled: Flow<Boolean> = playerStatsHudButtonEnabled

    val playerStatsHudActive: Flow<Boolean> = store.data.map { prefs ->
        prefs[playerStatsHudActiveKey] ?: false
    }

    suspend fun setPlayerStatsHudButtonEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[playerStatsHudButtonEnabledKey] = enabled
            if (!enabled) {
                // Disabling advanced setting option will disable HUD regardless of previous state
                prefs[playerStatsHudActiveKey] = false
            }
        }
    }

    /**
     * Alias for [setPlayerStatsHudButtonEnabled] for backward compatibility with settings UI.
     */
    suspend fun setPlayerStatsHudEnabled(enabled: Boolean) {
        setPlayerStatsHudButtonEnabled(enabled)
    }

    suspend fun setPlayerStatsHudActive(active: Boolean) {
        store.edit { prefs ->
            prefs[playerStatsHudActiveKey] = active
        }
    }

    val customUserAgent: Flow<String> = store.data.map { prefs ->
        prefs[customUserAgentKey] ?: ""
    }

    suspend fun setCustomUserAgent(value: String) {
        val sanitized = sanitizeUserAgent(value)
        store.edit { prefs ->
            if (sanitized.isBlank()) {
                prefs.remove(customUserAgentKey)
            } else {
                prefs[customUserAgentKey] = sanitized
            }
        }
    }

    private fun sanitizeUserAgent(raw: String): String {
        val stripped = raw.filter { it.code in 0x20..0x7E }
            .trim()
        return stripped.take(MAX_USER_AGENT_LENGTH)
    }

    companion object {
        private const val MAX_USER_AGENT_LENGTH = 256
    }
}
