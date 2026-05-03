package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchTogetherDataStore by preferencesDataStore(name = "watch_together_settings")

@Singleton
class WatchTogetherSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SESSION_TOKEN = stringPreferencesKey("session_token")
        private val ROOM_CODE = stringPreferencesKey("room_code")
        private val USER_ID = stringPreferencesKey("user_id")
        private val USERNAME = stringPreferencesKey("username")
    }

    val sessionToken: Flow<String?> = context.watchTogetherDataStore.data.map { it[SESSION_TOKEN] }
    val roomCode: Flow<String?> = context.watchTogetherDataStore.data.map { it[ROOM_CODE] }
    val userId: Flow<String?> = context.watchTogetherDataStore.data.map { it[USER_ID] }
    val username: Flow<String?> = context.watchTogetherDataStore.data.map { it[USERNAME] }

    suspend fun saveSession(token: String, code: String, id: String) {
        context.watchTogetherDataStore.edit {
            it[SESSION_TOKEN] = token
            it[ROOM_CODE] = code
            it[USER_ID] = id
        }
    }

    suspend fun saveUsername(name: String) {
        context.watchTogetherDataStore.edit { it[USERNAME] = name }
    }

    suspend fun clearSession() {
        context.watchTogetherDataStore.edit {
            it.remove(SESSION_TOKEN)
            it.remove(ROOM_CODE)
            it.remove(USER_ID)
        }
    }
}
