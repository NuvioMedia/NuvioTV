package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug_settings")

@Singleton
class DebugSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.debugDataStore

    private val accountTabEnabledKey = booleanPreferencesKey("account_tab_enabled")
    private val syncCodeFeaturesEnabledKey = booleanPreferencesKey("sync_code_features_enabled")
    private val deviceTierOverrideKey = stringPreferencesKey("device_tier_override")

    val accountTabEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[accountTabEnabledKey] ?: false
    }

    val syncCodeFeaturesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[syncCodeFeaturesEnabledKey] ?: false
    }

    val deviceTierOverride: Flow<String> = dataStore.data.map { prefs ->
        prefs[deviceTierOverrideKey] ?: "auto"
    }

    suspend fun setAccountTabEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[accountTabEnabledKey] = enabled
        }
    }

    suspend fun setSyncCodeFeaturesEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[syncCodeFeaturesEnabledKey] = enabled
        }
    }

    suspend fun setDeviceTierOverride(value: String) {
        dataStore.edit { prefs ->
            prefs[deviceTierOverrideKey] = value
        }
    }
}
