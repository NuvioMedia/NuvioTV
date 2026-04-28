package com.omnio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnio.tv.domain.profile.ProfileManager
import com.omnio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.omnio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.legacyTraktAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trakt_auth_store"
)

private const val TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS = 86_400

internal fun normalizeTraktTokenLifetimeSeconds(expiresIn: Int): Int {
    if (expiresIn <= 0) return TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS
    return expiresIn.coerceAtMost(TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS)
}

data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null,
    val pollInterval: Int? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktAuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "trakt_auth"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")

    private val usernameKey = stringPreferencesKey("username")
    private val userSlugKey = stringPreferencesKey("user_slug")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    private val migrationMutex = Mutex()
    @Volatile private var legacyMigrated = false

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeId, profiles ->
        val active = profiles.firstOrNull { it.id == activeId }
        if (active?.traktSharing?.sharesPrimaryToken == true) 1 else activeId
    }.distinctUntilChanged()

    private fun effectiveProfileIdNow(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.traktSharing.sharesPrimaryToken) 1 else profileManager.activeProfileId.value
    }

    private suspend fun store(profileId: Int = effectiveProfileIdNow()): DataStore<Preferences> {
        if (profileId == 1) ensureLegacyMigrated()
        return factory.get(profileId, FEATURE)
    }

    private suspend fun ensureLegacyMigrated() {
        if (legacyMigrated) return
        migrationMutex.withLock {
            if (legacyMigrated) return
            try {
                val legacy = context.legacyTraktAuthDataStore
                val legacyData = legacy.data.first().asMap()
                if (legacyData.isNotEmpty()) {
                    factory.get(1, FEATURE).edit { prefs ->
                        legacyData.forEach { (key, value) ->
                            when (value) {
                                is String -> prefs[stringPreferencesKey(key.name)] = value
                                is Int -> prefs[intPreferencesKey(key.name)] = value
                                is Long -> prefs[longPreferencesKey(key.name)] = value
                                else -> Unit
                            }
                        }
                    }
                    legacy.edit { it.clear() }
                }
            } catch (_: Throwable) {
                // Best-effort migration; never block auth on a failed migration.
            }
            legacyMigrated = true
        }
    }

    private fun Preferences.toState() = TraktAuthState(
        accessToken = this[accessTokenKey],
        refreshToken = this[refreshTokenKey],
        tokenType = this[tokenTypeKey],
        createdAt = this[createdAtKey],
        expiresIn = this[expiresInKey]?.let(::normalizeTraktTokenLifetimeSeconds),
        username = this[usernameKey],
        userSlug = this[userSlugKey],
        deviceCode = this[deviceCodeKey],
        userCode = this[userCodeKey],
        verificationUrl = this[verificationUrlKey],
        expiresAt = this[expiresAtKey],
        pollInterval = this[pollIntervalKey]
    )

    val state: Flow<TraktAuthState> = effectiveProfileIdFlow.flatMapLatest { profileId ->
        flow {
            if (profileId == 1) ensureLegacyMigrated()
            emitAll(factory.get(profileId, FEATURE).data.map { it.toState() })
        }
    }.flowOn(Dispatchers.IO)

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    suspend fun saveToken(token: TraktTokenResponseDto) {
        store().edit { preferences ->
            preferences[accessTokenKey] = token.accessToken
            preferences[refreshTokenKey] = token.refreshToken
            preferences[tokenTypeKey] = token.tokenType
            preferences[createdAtKey] = token.createdAt
            preferences[expiresInKey] = normalizeTraktTokenLifetimeSeconds(token.expiresIn)
        }
    }

    suspend fun saveUser(username: String?, userSlug: String?) {
        store().edit { preferences ->
            if (username.isNullOrBlank()) {
                preferences.remove(usernameKey)
            } else {
                preferences[usernameKey] = username
            }
            if (userSlug.isNullOrBlank()) {
                preferences.remove(userSlugKey)
            } else {
                preferences[userSlugKey] = userSlug
            }
        }
    }

    suspend fun saveDeviceFlow(data: TraktDeviceCodeResponseDto) {
        val now = System.currentTimeMillis()
        store().edit { preferences ->
            preferences[deviceCodeKey] = data.deviceCode
            preferences[userCodeKey] = data.userCode
            preferences[verificationUrlKey] = data.verificationUrl
            preferences[expiresAtKey] = now + (data.expiresIn * 1000L)
            preferences[pollIntervalKey] = data.interval
        }
    }

    suspend fun updatePollInterval(seconds: Int) {
        store().edit { preferences ->
            preferences[pollIntervalKey] = seconds
        }
    }

    suspend fun clearDeviceFlow() {
        store().edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() {
        store().edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(tokenTypeKey)
            preferences.remove(createdAtKey)
            preferences.remove(expiresInKey)
            preferences.remove(usernameKey)
            preferences.remove(userSlugKey)
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }
}
