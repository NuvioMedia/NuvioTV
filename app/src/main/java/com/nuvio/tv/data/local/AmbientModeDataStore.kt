package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AmbientModeSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AmbientModeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "ambient_mode_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val enabledKey = booleanPreferencesKey("ambient_enabled")
    private val timeoutMinutesKey = intPreferencesKey("ambient_timeout_minutes")
    private val catalogKeysKey = stringPreferencesKey("ambient_catalog_keys")

    val settings: Flow<AmbientModeSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            AmbientModeSettings(
                enabled = prefs[enabledKey] ?: true,
                timeoutMinutes = prefs[timeoutMinutesKey] ?: AmbientModeSettings.DEFAULT_TIMEOUT_MINUTES,
                catalogKeys = decodeKeys(prefs[catalogKeysKey])
            )
        }
    }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setTimeoutMinutes(minutes: Int) {
        store().edit { it[timeoutMinutesKey] = minutes }
    }

    suspend fun setCatalogKeys(keys: List<String>) {
        val limited = keys.distinct().take(AmbientModeSettings.MAX_CATALOGS)
        store().edit { it[catalogKeysKey] = JSONArray(limited).toString() }
    }

    private fun decodeKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
    }
}
