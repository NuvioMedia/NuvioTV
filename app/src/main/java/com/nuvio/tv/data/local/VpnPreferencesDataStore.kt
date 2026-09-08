package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val autoConnectKey = booleanPreferencesKey("auto_connect_intent")

    val config: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[configKey] ?: "" }
    }

    /** Whether the user's last explicit action was to turn the VPN on - not whether the
     *  last attempt actually succeeded, so a transient failure doesn't disable auto-connect
     *  on the next launch. Only an explicit disconnect clears it; a denied VPN permission
     *  prompt does not, so the next app startup asks for the system permission again. */
    val autoConnect: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[autoConnectKey] ?: false }
    }

    suspend fun setConfig(rawConfig: String) {
        store().edit { it[configKey] = rawConfig.trim() }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        store().edit { it[autoConnectKey] = enabled }
    }
}
