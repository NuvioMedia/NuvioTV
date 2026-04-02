package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.embyAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "emby_auth_store"
)

data class EmbyAuthState(
    val serverUrl: String? = null,
    val apiKey: String? = null,
    val userId: String? = null,
    val deviceId: String? = null
) {
    val isConnected: Boolean
        get() = !serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && !userId.isNullOrBlank()
}

@Singleton
class EmbyAuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val userIdKey = stringPreferencesKey("user_id")
    private val deviceIdKey = stringPreferencesKey("device_id")

    val state: Flow<EmbyAuthState> = context.embyAuthDataStore.data.map { preferences ->
        EmbyAuthState(
            serverUrl = preferences[serverUrlKey],
            apiKey = preferences[apiKeyKey],
            userId = preferences[userIdKey],
            deviceId = preferences[deviceIdKey]
        )
    }

    val isConnected: Flow<Boolean> = state.map { it.isConnected }

    suspend fun saveCredentials(
        serverUrl: String,
        apiKey: String,
        userId: String,
        deviceId: String? = null
    ) {
        context.embyAuthDataStore.edit { preferences ->
            preferences[serverUrlKey] = serverUrl.trimEnd('/')
            preferences[apiKeyKey] = apiKey
            preferences[userIdKey] = userId
            preferences[deviceIdKey] = deviceId
                ?: preferences[deviceIdKey]
                ?: UUID.randomUUID().toString()
        }
    }

    suspend fun clearCredentials() {
        context.embyAuthDataStore.edit { preferences ->
            preferences.remove(serverUrlKey)
            preferences.remove(apiKeyKey)
            preferences.remove(userIdKey)
        }
    }
}
