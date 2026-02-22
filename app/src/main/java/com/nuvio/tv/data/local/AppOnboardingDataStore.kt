package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_onboarding")

@Singleton
class AppOnboardingDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.appOnboardingDataStore
    private val hasSeenAuthQrOnFirstLaunchKey = booleanPreferencesKey("has_seen_auth_qr_on_first_launch")

    val hasSeenAuthQrOnFirstLaunch: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasSeenAuthQrOnFirstLaunchKey] ?: false
    }

    suspend fun setHasSeenAuthQrOnFirstLaunch(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[hasSeenAuthQrOnFirstLaunchKey] = value
        }
    }
}
