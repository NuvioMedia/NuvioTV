package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trakt_settings"
        const val CONTINUE_WATCHING_DAYS_CAP_ALL = 0
        const val DEFAULT_CONTINUE_WATCHING_DAYS_CAP = 60
        const val DEFAULT_SHOW_UNAIRED_NEXT_UP = true
        const val MIN_CONTINUE_WATCHING_DAYS_CAP = 7
        const val MAX_CONTINUE_WATCHING_DAYS_CAP = 365
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val continueWatchingDaysCapKey = intPreferencesKey("continue_watching_days_cap")
    private val dismissedNextUpKeysKey = stringSetPreferencesKey("dismissed_next_up_keys")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")

    val continueWatchingDaysCap: Flow<Int> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            normalizeContinueWatchingDaysCap(
                prefs[continueWatchingDaysCapKey] ?: DEFAULT_CONTINUE_WATCHING_DAYS_CAP
            )
        }
    }

    val dismissedNextUpKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[dismissedNextUpKeysKey] ?: emptySet()
        }
    }

    val showUnairedNextUp: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[showUnairedNextUpKey] ?: DEFAULT_SHOW_UNAIRED_NEXT_UP
        }
    }

    suspend fun setContinueWatchingDaysCap(days: Int) {
        store().edit { prefs ->
            prefs[continueWatchingDaysCapKey] = normalizeContinueWatchingDaysCap(days)
        }
    }

    private fun normalizeContinueWatchingDaysCap(days: Int): Int {
        return if (days == CONTINUE_WATCHING_DAYS_CAP_ALL) {
            CONTINUE_WATCHING_DAYS_CAP_ALL
        } else {
            days.coerceIn(MIN_CONTINUE_WATCHING_DAYS_CAP, MAX_CONTINUE_WATCHING_DAYS_CAP)
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            prefs[dismissedNextUpKeysKey] = current + key
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }
}
