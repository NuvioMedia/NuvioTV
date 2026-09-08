package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "vpn_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val configKey = stringPreferencesKey("wireguard_config")

    val config: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[configKey] ?: "" }
    }

    suspend fun setConfig(rawConfig: String) {
        store().edit { it[configKey] = rawConfig.trim() }
    }
}
