package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface WeTrakrAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

class WeTrakrAuthStore(
    private val persistence: WeTrakrAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = WeTrakrAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<WeTrakrAuthState> = mutableState.asStateFlow()

    fun scope(): WeTrakrAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: WeTrakrAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: WeTrakrAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("WeTrakr account changed")
    }

    fun authorization(): WeTrakrAuthorization? = synchronized(lock) {
        stored.tokens?.let { WeTrakrAuthorization(currentScope, it) }
    }

    fun deviceCode(scope: WeTrakrAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        val value = load(profileId)
        currentScope = WeTrakrAuthScope(profileId, currentScope.generation + 1)
        stored = value
        publish()
    }

    fun saveSession(session: WeTrakrDeviceSession, deviceCode: String, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            it.copy(session = session, deviceCode = deviceCode)
        }

    fun updateSession(session: WeTrakrDeviceSession, scope: WeTrakrAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: WeTrakrAuthError? = null, scope: WeTrakrAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) {
            it.copy(session = null, deviceCode = null)
        }

    fun authorize(tokens: WeTrakrTokens, scope: WeTrakrAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) { WeTrakrStoredAuth(tokens = tokens) }

    fun refreshTokens(tokens: WeTrakrTokens, expected: WeTrakrAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun saveUser(user: WeTrakrUser, scope: WeTrakrAuthScope): Boolean =
        mutate(scope) { it.copy(user = user) }

    fun clearAuth(
        scope: WeTrakrAuthScope = scope(),
        error: WeTrakrAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        mutate(scope, advanceGeneration = true, error = error) { WeTrakrStoredAuth() }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = WeTrakrStoredAuth()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = WeTrakrStoredAuth()
        publish()
    }

    private fun mutate(
        scope: WeTrakrAuthScope,
        advanceGeneration: Boolean = false,
        error: WeTrakrAuthError? = null,
        transform: (WeTrakrStoredAuth) -> WeTrakrStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): WeTrakrStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<WeTrakrStoredAuth>(value) }.getOrNull()
    }?.takeIf { value ->
        value.tokens == null || (value.tokens.accessToken.isNotBlank() && value.tokens.refreshToken.isNotBlank())
    } ?: WeTrakrStoredAuth()

    private fun publish(error: WeTrakrAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: WeTrakrAuthError? = null) = WeTrakrAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        user = stored.user,
        session = stored.session,
        error = error
    )
}
