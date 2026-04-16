package com.omnio.tv.data.local

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

private val Context.authSessionNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session_notice_store"
)

enum class StartupAuthNotice {
    OMNIO,
    TRAKT
}

@Singleton
class AuthSessionNoticeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val hadOmnioAuthKey = booleanPreferencesKey("had_omnio_auth")
    private val omnioExplicitLogoutKey = booleanPreferencesKey("omnio_explicit_logout")
    private val pendingOmnioNoticeKey = booleanPreferencesKey("pending_omnio_notice")

    private val hadTraktAuthKey = booleanPreferencesKey("had_trakt_auth")
    private val traktExplicitLogoutKey = booleanPreferencesKey("trakt_explicit_logout")
    private val pendingTraktNoticeKey = booleanPreferencesKey("pending_trakt_notice")

    val pendingNotice: Flow<StartupAuthNotice?> = context.authSessionNoticeDataStore.data.map { preferences ->
        when {
            preferences[pendingOmnioNoticeKey] == true -> StartupAuthNotice.OMNIO
            preferences[pendingTraktNoticeKey] == true -> StartupAuthNotice.TRAKT
            else -> null
        }
    }

    suspend fun markOmnioAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadOmnioAuthKey] = true
            preferences[omnioExplicitLogoutKey] = false
            preferences[pendingOmnioNoticeKey] = false
        }
    }

    suspend fun markOmnioExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadOmnioAuthKey] = false
            preferences[omnioExplicitLogoutKey] = true
            preferences[pendingOmnioNoticeKey] = false
        }
    }

    suspend fun markUnexpectedOmnioLogoutIfNeeded() {
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadOmnioAuthKey] == true
            val explicitLogout = preferences[omnioExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingOmnioNoticeKey] = true
            }
            preferences[hadOmnioAuthKey] = false
            preferences[omnioExplicitLogoutKey] = false
        }
    }

    suspend fun markTraktAuthenticated() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = true
            preferences[traktExplicitLogoutKey] = false
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markTraktExplicitLogout() {
        context.authSessionNoticeDataStore.edit { preferences ->
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = true
            preferences[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markUnexpectedTraktLogoutIfNeeded() {
        context.authSessionNoticeDataStore.edit { preferences ->
            val hadAuth = preferences[hadTraktAuthKey] == true
            val explicitLogout = preferences[traktExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                preferences[pendingTraktNoticeKey] = true
            }
            preferences[hadTraktAuthKey] = false
            preferences[traktExplicitLogoutKey] = false
        }
    }

    suspend fun consumeNotice(notice: StartupAuthNotice) {
        context.authSessionNoticeDataStore.edit { preferences ->
            when (notice) {
                StartupAuthNotice.OMNIO -> preferences[pendingOmnioNoticeKey] = false
                StartupAuthNotice.TRAKT -> preferences[pendingTraktNoticeKey] = false
            }
        }
    }
}
