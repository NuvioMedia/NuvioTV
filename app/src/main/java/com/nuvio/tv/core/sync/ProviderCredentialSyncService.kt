package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val PROVIDER_CREDENTIAL_TAG = "ProviderCredentialSync"
private const val PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS = 500L
private const val PROVIDER_CREDENTIAL_FOREGROUND_DELAY_MS = 2500L
private const val PROVIDER_CREDENTIAL_FOREGROUND_MIN_INTERVAL_MS = 60_000L
private const val API_KEY_FIELD = "api_key"
private const val CLIENT_ID_FIELD = "client_id"

private data class ProviderCredentialScope(
    val userId: String,
    val profileId: Int
)

@Singleton
class ProviderCredentialSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val stateLock = Any()
    private val observedSnapshots = mutableMapOf<Int, ProviderCredentialSnapshot>()
    private val baselineSnapshots = mutableMapOf<ProviderCredentialScope, ProviderCredentialSnapshot>()
    private val pendingScopes = mutableSetOf<ProviderCredentialScope>()
    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs: Long = 0L

    init {
        observeLocalCredentials()
        observeAuthState()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(error)) throw error
            block()
        }
    }

    suspend fun syncFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val credentialScope = currentScope(profileId) ?: return@withLock Result.success(false)
                val localSnapshot = currentSnapshot(profileId)
                val shouldPush = synchronized(stateLock) {
                    val baseline = baselineSnapshots.getOrPut(credentialScope) {
                        observedSnapshots[profileId] ?: localSnapshot
                    }
                    credentialScope in pendingScopes || baseline != localSnapshot
                }
                if (shouldPush) {
                    pushSnapshot(localSnapshot)
                    synchronized(stateLock) {
                        pendingScopes.remove(credentialScope)
                        baselineSnapshots[credentialScope] = localSnapshot
                    }
                }

                val rows = pullRows(profileId)
                if (shouldSeedProviderCredentials(localSnapshot, rows)) {
                    seedSnapshot(localSnapshot)
                }
                requireCurrentScope(credentialScope)
                val remoteSnapshot = localSnapshot.mergeRemote(rows)
                val applied = remoteSnapshot != localSnapshot
                if (applied) {
                    applySnapshot(remoteSnapshot)
                }
                requireCurrentScope(credentialScope)
                synchronized(stateLock) {
                    observedSnapshots[profileId] = remoteSnapshot
                    baselineSnapshots[credentialScope] = remoteSnapshot
                    pendingScopes.remove(credentialScope)
                }
                lastForegroundPullAtMs = SystemClock.elapsedRealtime()
                Log.d(
                    PROVIDER_CREDENTIAL_TAG,
                    "Synchronized ${remoteSnapshot.values.size} credentials for profile $profileId applied=$applied"
                )
                Result.success(applied)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(PROVIDER_CREDENTIAL_TAG, "Failed to synchronize provider credentials", error)
                Result.failure(error)
            }
        }
    }

    fun requestForegroundPull(force: Boolean = false) {
        if (!authManager.isAuthenticated) return
        val now = SystemClock.elapsedRealtime()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < PROVIDER_CREDENTIAL_FOREGROUND_MIN_INTERVAL_MS) return

        foregroundPullJob = scope.launch {
            if (!force) {
                delay(PROVIDER_CREDENTIAL_FOREGROUND_DELAY_MS)
            }
            if (!authManager.isAuthenticated) return@launch
            syncFromRemote()
        }
    }

    private suspend fun seedSnapshot(snapshot: ProviderCredentialSnapshot) {
        val params = credentialParams(snapshot)
        withJwtRefreshRetry {
            postgrest.rpc("sync_seed_provider_credentials", params)
        }
    }

    private suspend fun pushSnapshot(snapshot: ProviderCredentialSnapshot) {
        val params = credentialParams(snapshot)
        withJwtRefreshRetry {
            postgrest.rpc("sync_push_provider_credentials", params)
        }
        Log.d(
            PROVIDER_CREDENTIAL_TAG,
            "Pushed ${snapshot.values.size} credentials for profile ${snapshot.profileId}"
        )
    }

    private suspend fun pullRows(profileId: Int): List<SupabaseProviderCredential> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_provider_credentials", params)
        }
        return response.decodeList()
    }

    private fun credentialParams(snapshot: ProviderCredentialSnapshot) = buildJsonObject {
        put("p_profile_id", snapshot.profileId)
        put("p_credentials", buildJsonArray {
            snapshot.values.forEach { credential ->
                addJsonObject {
                    put("provider", credential.provider)
                    put("credential_json", credential.credentialJson())
                }
            }
        })
        putSyncOriginClientId(syncClientIdentity)
    }

    private suspend fun currentSnapshot(profileId: Int): ProviderCredentialSnapshot {
        check(profileManager.activeProfileId.value == profileId)
        val debrid = debridSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val animeSkipClientId = animeSkipSettingsDataStore.clientId.first()
        check(profileManager.activeProfileId.value == profileId)
        return ProviderCredentialSnapshot(
            profileId = profileId,
            values = buildList {
                DebridProviders.all().forEach { provider ->
                    add(
                        ProviderCredentialValue(
                            provider = ProviderCredentialIds.debrid(provider.id),
                            field = API_KEY_FIELD,
                            value = debrid.apiKeyFor(provider.id)
                        )
                    )
                }
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.MDBLIST,
                        field = API_KEY_FIELD,
                        value = mdbList.apiKey
                    )
                )
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.ANIMESKIP,
                        field = CLIENT_ID_FIELD,
                        value = animeSkipClientId
                    )
                )
            }
        )
    }

    private suspend fun applySnapshot(snapshot: ProviderCredentialSnapshot) {
        snapshot.values.forEach { credential ->
            check(profileManager.activeProfileId.value == snapshot.profileId)
            when {
                credential.provider.startsWith("debrid:") -> {
                    val providerId = credential.provider.substringAfter("debrid:")
                    debridSettingsDataStore.setProviderApiKey(providerId, credential.value)
                }
                credential.provider == ProviderCredentialIds.MDBLIST -> {
                    mdbListSettingsDataStore.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.ANIMESKIP -> {
                    animeSkipSettingsDataStore.setClientId(credential.value)
                }
            }
        }
    }

    private fun currentScope(profileId: Int): ProviderCredentialScope? {
        val state = authManager.authState.value as? AuthState.FullAccount ?: return null
        if (profileManager.activeProfileId.value != profileId) return null
        return ProviderCredentialScope(state.userId, profileId)
    }

    private fun requireCurrentScope(expected: ProviderCredentialScope) {
        if (currentScope(expected.profileId) != expected) {
            throw CancellationException("Provider credential sync target changed")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeLocalCredentials() {
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    combine(
                        debridSettingsDataStore.settings,
                        mdbListSettingsDataStore.settings,
                        animeSkipSettingsDataStore.clientId
                    ) { debrid, mdbList, animeSkipClientId ->
                        ProviderCredentialSnapshot(
                            profileId = profileId,
                            values = buildList {
                                DebridProviders.all().forEach { provider ->
                                    add(
                                        ProviderCredentialValue(
                                            provider = ProviderCredentialIds.debrid(provider.id),
                                            field = API_KEY_FIELD,
                                            value = debrid.apiKeyFor(provider.id)
                                        )
                                    )
                                }
                                add(
                                    ProviderCredentialValue(
                                        provider = ProviderCredentialIds.MDBLIST,
                                        field = API_KEY_FIELD,
                                        value = mdbList.apiKey
                                    )
                                )
                                add(
                                    ProviderCredentialValue(
                                        provider = ProviderCredentialIds.ANIMESKIP,
                                        field = CLIENT_ID_FIELD,
                                        value = animeSkipClientId
                                    )
                                )
                            }
                        )
                    }
                }
                .distinctUntilChanged()
                .debounce(PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS)
                .collect { snapshot ->
                    handleLocalSnapshot(snapshot)
                }
        }
    }

    private suspend fun handleLocalSnapshot(snapshot: ProviderCredentialSnapshot) {
        syncMutex.withLock {
            val previous = synchronized(stateLock) {
                observedSnapshots.put(snapshot.profileId, snapshot)
            }
            val credentialScope = currentScope(snapshot.profileId) ?: return
            if (previous == null) {
                synchronized(stateLock) {
                    baselineSnapshots.putIfAbsent(credentialScope, snapshot)
                }
                return
            }
            if (previous == snapshot) return
            val baseline = synchronized(stateLock) {
                baselineSnapshots.getOrPut(credentialScope) { previous }
            }
            if (baseline == snapshot) return

            try {
                pushSnapshot(snapshot)
                synchronized(stateLock) {
                    baselineSnapshots[credentialScope] = snapshot
                    pendingScopes.remove(credentialScope)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                synchronized(stateLock) {
                    pendingScopes.add(credentialScope)
                }
                Log.e(
                    PROVIDER_CREDENTIAL_TAG,
                    "Failed to push local provider credential changes for profile ${snapshot.profileId}",
                    error
                )
            }
        }
    }

    private fun observeAuthState() {
        scope.launch {
            authManager.authState.collect { state ->
                if (state is AuthState.FullAccount) return@collect
                synchronized(stateLock) {
                    observedSnapshots.clear()
                    baselineSnapshots.clear()
                    pendingScopes.clear()
                }
            }
        }
    }
}
