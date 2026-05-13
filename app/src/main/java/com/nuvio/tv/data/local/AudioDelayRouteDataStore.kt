package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioDelayRouteDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "audio_delay_route_preference"
        private const val DELAY_MS = "delay_ms"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun key(routeKey: String) = intPreferencesKey("$DELAY_MS|$routeKey")

    suspend fun saveDelayMs(routeKey: String, delayMs: Int?) {
        store().edit { prefs ->
            val key = key(routeKey)
            if (delayMs != null && delayMs != 0) {
                prefs[key] = delayMs
            } else {
                prefs.remove(key)
            }
        }
    }

    suspend fun loadDelayMs(routeKey: String): Int? {
        return store().data.first()[key(routeKey)]
    }
}
