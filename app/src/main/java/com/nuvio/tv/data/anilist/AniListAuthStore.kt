package com.nuvio.tv.data.anilist

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AniListAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

@Singleton
class AndroidAniListAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : AniListAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "anilist_auth", "com.nuvio.tv.anilist.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

class AniListAuthStore(
    private val persistence: AniListAuthPersistence,
    initialProfileId: Int = 1,
    private val now: () -> Long = System::currentTimeMillis
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private var currentScope = AniListAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private var busy = false
    private var lastError: AniListAuthError? = null
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<AniListAuthState> = mutableState.asStateFlow()

    fun scope(): AniListAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: AniListAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: AniListAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("AniList account changed")
    }

    fun accessToken(): String? = synchronized(lock) {
        stored.accessToken?.takeIf { isTokenValid(stored) }
    }

    fun isPending(): Boolean = synchronized(lock) {
        stored.pendingSinceEpochMs?.let { now() - it in 0..PENDING_WINDOW_MS } == true
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        stored = load(profileId)
        currentScope = AniListAuthScope(profileId, currentScope.generation + 1)
        busy = false
        lastError = null
        publish()
    }

    fun beginAuthorization(scope: AniListAuthScope): Boolean =
        mutate(scope, error = null) { it.copy(pendingSinceEpochMs = now()) }

    fun cancelAuthorization(scope: AniListAuthScope = scope(), error: AniListAuthError? = null): Boolean =
        mutate(scope, error = error) { it.copy(pendingSinceEpochMs = null) }

    fun authorize(token: String, expiresAtEpochMs: Long?, scope: AniListAuthScope): Boolean =
        mutate(scope, advanceGeneration = true, error = null) {
            AniListStoredAuth(accessToken = token, expiresAtEpochMs = expiresAtEpochMs)
        }

    fun saveViewer(viewer: AniListViewer, scope: AniListAuthScope): Boolean =
        mutate(scope, error = lastError) { it.copy(viewer = viewer) }

    fun setBusy(value: Boolean, scope: AniListAuthScope) = synchronized(lock) {
        if (currentScope != scope) return@synchronized
        busy = value
        publish()
    }

    fun clearAuth(
        scope: AniListAuthScope = scope(),
        error: AniListAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.accessToken != expectedAccessToken) return@synchronized false
        mutate(scope, advanceGeneration = true, error = error) { AniListStoredAuth() }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = AniListStoredAuth()
            busy = false
            lastError = null
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = AniListStoredAuth()
        busy = false
        lastError = null
        publish()
    }

    fun refreshExpiry() = synchronized(lock) {
        if (stored.accessToken != null && !isTokenValid(stored)) {
            persistence.write(currentScope.profileId, aniListJson.encodeToString(AniListStoredAuth.serializer(), AniListStoredAuth()))
            stored = AniListStoredAuth()
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            lastError = AniListAuthError.AUTHORIZATION_EXPIRED
            publish()
        }
    }

    private fun mutate(
        scope: AniListAuthScope,
        advanceGeneration: Boolean = false,
        error: AniListAuthError? = null,
        transform: (AniListStoredAuth) -> AniListStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, aniListJson.encodeToString(AniListStoredAuth.serializer(), value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        lastError = error
        publish()
        true
    }

    private fun load(profileId: Int): AniListStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { aniListJson.decodeFromString(AniListStoredAuth.serializer(), value) }.getOrNull()
    }?.takeIf { value -> value.accessToken == null || value.accessToken.isNotBlank() } ?: AniListStoredAuth()

    private fun isTokenValid(value: AniListStoredAuth): Boolean =
        !value.accessToken.isNullOrBlank() && (value.expiresAtEpochMs == null || value.expiresAtEpochMs > now())

    private fun publish() {
        mutableState.value = stateFor()
    }

    private fun stateFor() = AniListAuthState(
        scope = currentScope,
        isAuthenticated = isTokenValid(stored),
        viewer = stored.viewer,
        awaitingApproval = !isTokenValid(stored) &&
            stored.pendingSinceEpochMs?.let { now() - it in 0..PENDING_WINDOW_MS } == true,
        isBusy = busy,
        error = lastError
    )

    companion object {
        const val PENDING_WINDOW_MS = 30L * 60L * 1_000L
    }
}
