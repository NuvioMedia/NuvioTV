package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ExperienceMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExperienceModeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "experience_settings"
    }

    private val modeKey = stringPreferencesKey("mode")
    private val addonSetupSkippedKey = booleanPreferencesKey("addon_setup_skipped")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> profileFlow(extract: (androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    val mode: Flow<ExperienceMode?> = profileFlow { prefs ->
        prefs[modeKey]?.let { value ->
            runCatching { ExperienceMode.valueOf(value) }.getOrNull()
        }
    }

    val addonSetupSkipped: Flow<Boolean> = profileFlow { prefs ->
        prefs[addonSetupSkippedKey] ?: false
    }

    suspend fun setMode(mode: ExperienceMode) {
        store().edit { prefs ->
            prefs[modeKey] = mode.name
        }
    }

    suspend fun setAddonSetupSkipped(skipped: Boolean) {
        store().edit { prefs ->
            prefs[addonSetupSkippedKey] = skipped
        }
    }
}
