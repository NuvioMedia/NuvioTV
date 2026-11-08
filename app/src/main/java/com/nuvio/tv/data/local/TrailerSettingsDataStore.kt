package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.player.TrailerVideoPolicy
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    @ApplicationContext context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trailer_settings"
    }

    private val default4kTrailers = TrailerVideoPolicy.default4kTrailers(context)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")
    private val playInBackgroundKey = booleanPreferencesKey("trailer_play_in_background")
    private val pauseOnScrollKey = booleanPreferencesKey("trailer_pause_on_scroll")
    private val allow4kKey = booleanPreferencesKey("trailer_allow_4k")

    val settings: Flow<TrailerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TrailerSettings(
                enabled = prefs[enabledKey] ?: true,
                delaySeconds = prefs[delaySecondsKey] ?: 7,
                playInBackground = prefs[playInBackgroundKey] ?: false,
                pauseOnScroll = prefs[pauseOnScrollKey] ?: true,
                allow4k = prefs[allow4kKey] ?: default4kTrailers
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds }
    }

    suspend fun setPlayInBackground(enabled: Boolean) {
        store().edit { it[playInBackgroundKey] = enabled }
    }

    suspend fun setPauseOnScroll(enabled: Boolean) {
        store().edit { it[pauseOnScrollKey] = enabled }
    }

    suspend fun setAllow4k(enabled: Boolean) {
        store().edit { it[allow4kKey] = enabled }
    }
}

data class TrailerSettings(
    val enabled: Boolean = true,
    val delaySeconds: Int = 7,
    val playInBackground: Boolean = false,
    val pauseOnScroll: Boolean = true,
    val allow4k: Boolean = true
)
