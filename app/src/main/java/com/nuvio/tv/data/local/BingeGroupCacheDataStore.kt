package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-used binge group per content ID (series).
 * When a user watches an episode with a stream that has a bingeGroup,
 * we store it so that subsequent episode plays (from Continue Watching,
 * Details, or Next Episode) can automatically prefer the same source group.
 *
 * Stored locally only - not synced to remote.
 */
@Singleton
class BingeGroupCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "binge_group_cache"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun prefKey(contentId: String) = stringPreferencesKey("bg_$contentId")

    suspend fun save(contentId: String, bingeGroup: String) {
        store().edit { prefs ->
            prefs[prefKey(contentId)] = bingeGroup
        }
    }

    suspend fun replace(contentId: String, bingeGroup: String?) {
        store().edit { prefs ->
            val value = bingeGroup?.takeIf { it.isNotBlank() }
            if (value == null) {
                prefs.remove(prefKey(contentId))
            } else {
                prefs[prefKey(contentId)] = value
            }
        }
    }

    suspend fun get(contentId: String): String? {
        return store().data.first()[prefKey(contentId)]
    }

    suspend fun remove(contentId: String) {
        store().edit { prefs ->
            prefs.remove(prefKey(contentId))
        }
    }
}
