package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trailer_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")

    val settings: Flow<TrailerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TrailerSettings(
                enabled = prefs[enabledKey] ?: false,
                delaySeconds = prefs[delaySecondsKey] ?: 7
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds }
    }
}

data class TrailerSettings(
    val enabled: Boolean = false,
    val delaySeconds: Int = 7
)
