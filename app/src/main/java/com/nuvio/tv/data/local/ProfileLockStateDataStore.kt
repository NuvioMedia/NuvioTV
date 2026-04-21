package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileLockStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "profile_lock_state")

@Singleton
class ProfileLockStateDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.profileLockStateDataStore
    private val pinEnabledMapKey = stringPreferencesKey("pin_enabled_map")

    val pinEnabled: Flow<Map<Int, Boolean>> = dataStore.data.map { prefs ->
        decode(prefs[pinEnabledMapKey])
    }

    suspend fun replaceAll(states: Map<Int, Boolean>) {
        dataStore.edit { prefs ->
            prefs[pinEnabledMapKey] = encode(states)
        }
    }

    suspend fun setPinEnabled(profileId: Int, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = decode(prefs[pinEnabledMapKey]).toMutableMap()
            current[profileId] = enabled
            prefs[pinEnabledMapKey] = encode(current)
        }
    }

    private fun encode(map: Map<Int, Boolean>): String =
        map.entries.joinToString(separator = ",") { "${it.key}:${it.value}" }

    private fun decode(raw: String?): Map<Int, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val enabled = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null
            id to enabled
        }.toMap()
    }
}
