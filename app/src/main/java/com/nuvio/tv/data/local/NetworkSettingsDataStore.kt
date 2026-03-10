package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(name = "network_settings")

@Singleton
class NetworkSettingsDataStore @Inject constructor(
    @ApplicationContext val context: Context
) {
    val dataStore = context.networkDataStore

    private val dnsProviderKey = intPreferencesKey("dns_provider")

    val dnsProvider: Flow<Int> = dataStore.data.map { prefs ->
        prefs[dnsProviderKey] ?: 0
    }

    suspend fun setDnsProvider(provider: Int) {
        dataStore.edit { prefs ->
            prefs[dnsProviderKey] = provider
        }
    }
}
