package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class StartupSyncState(
    val lastFullPullUserId: String?,
    val lastFullPullAtMs: Long,
    val lastFullPullIncludedProfileSettings: Boolean
)

@Singleton
class StartupSyncPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "startup_sync_state"
    }

    private val lastFullPullUserIdKey = stringPreferencesKey("last_full_pull_user_id")
    private val lastFullPullAtMsKey = longPreferencesKey("last_full_pull_at_ms")
    private val lastFullPullIncludedProfileSettingsKey = booleanPreferencesKey("last_full_pull_included_profile_settings")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun getState(profileId: Int): StartupSyncState {
        val prefs = store(profileId).data.first()
        return StartupSyncState(
            lastFullPullUserId = prefs[lastFullPullUserIdKey],
            lastFullPullAtMs = prefs[lastFullPullAtMsKey] ?: 0L,
            lastFullPullIncludedProfileSettings = prefs[lastFullPullIncludedProfileSettingsKey] ?: false
        )
    }

    suspend fun markFullPull(
        profileId: Int,
        userId: String,
        includeProfileSettings: Boolean
    ) {
        store(profileId).edit { prefs ->
            prefs[lastFullPullUserIdKey] = userId
            prefs[lastFullPullAtMsKey] = System.currentTimeMillis()
            prefs[lastFullPullIncludedProfileSettingsKey] = includeProfileSettings
        }
    }
}
