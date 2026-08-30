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
 *  - playerStatsHudEnabled  (playback stats overlay)
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
    private val playerStatsHudEnabledKey = booleanPreferencesKey("player_stats_hud_enabled")

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

    val playerStatsHudEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[playerStatsHudEnabledKey] ?: false
    }

    suspend fun setPlayerStatsHudEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[playerStatsHudEnabledKey] = enabled
        }
    }
}
