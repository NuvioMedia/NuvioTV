package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sentrySettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sentry_settings")

@Singleton
class SentrySettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.sentrySettingsDataStore
    private val enabledKey = booleanPreferencesKey("enabled")

    val enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    suspend fun isEnabled(): Boolean {
        return enabled.first()
    }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[enabledKey] = value
        }
    }
}
