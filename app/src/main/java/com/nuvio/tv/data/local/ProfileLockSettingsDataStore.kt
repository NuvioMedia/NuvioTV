package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** PIN lock options of each profile. Synced with the profile's other settings. */
@Singleton
class ProfileLockSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        const val FEATURE = "profile_lock_settings"
    }

    private val askPinOnAppStartKey = booleanPreferencesKey("ask_pin_on_app_start")

    /**
     * Whether a locked profile asks for its PIN when Remember Last Profile opens it at app start.
     * On unless the profile turned it off.
     */
    fun askPinOnAppStart(profileId: Int): Flow<Boolean> =
        factory.get(profileId, FEATURE).data.map { prefs -> prefs[askPinOnAppStartKey] ?: true }

    suspend fun setAskPinOnAppStart(profileId: Int, enabled: Boolean) {
        factory.get(profileId, FEATURE).edit { prefs ->
            prefs[askPinOnAppStartKey] = enabled
        }
    }
}
