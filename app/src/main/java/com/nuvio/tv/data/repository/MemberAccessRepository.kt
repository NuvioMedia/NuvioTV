package com.nuvio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.DebugSettingsDataStore
import com.nuvio.tv.data.local.MemberAccessDataStore
import com.nuvio.tv.data.remote.supabase.MemberAccessRemoteDataSource
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val MemberAccessTag = "MemberAccess"
internal const val MemberAccessStaleAfterMs = 15 * 60 * 1000L

@Singleton
class MemberAccessRepository @Inject constructor(
    debugSettingsDataStore: DebugSettingsDataStore,
    private val authManager: AuthManager,
    private val remoteDataSource: MemberAccessRemoteDataSource,
    private val memberAccessDataStore: MemberAccessDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshGeneration = MutableStateFlow(0L)
    private val initialCachedAccess = memberAccessDataStore.getLastKnown()

    @Volatile
    private var optimisticCachedAccess = initialCachedAccess ?: MemberAccess.None

    @Volatile
    private var lastVerifiedAtMs: Long? = null

    @Volatile
    private var lastVerifiedUserId: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val access: StateFlow<MemberAccess> = combine(
        debugSettingsDataStore.memberTier,
        authManager.authState,
        refreshGeneration
    ) { debugTier, authState, generation -> Triple(debugTier, authState, generation) }
        .distinctUntilChanged()
        .transformLatest { (debugTier, authState) ->
            val previewAccess = resolveMemberAccess(BuildConfig.IS_DEBUG_BUILD, debugTier)
            if (previewAccess != MemberAccess.None) {
                emit(previewAccess)
            } else if (authState is AuthState.FullAccount) {
                val cachedAccess = runCatching {
                    memberAccessDataStore.get(authState.userId)
                }.onFailure { error ->
                    Log.w(MemberAccessTag, "Failed to load cached supporter access", error)
                }.getOrNull()
                optimisticCachedAccess = cachedAccess ?: MemberAccess.None
                emit(optimisticCachedAccess)

                try {
                    val remoteAccess = loadRemoteAccessWithRetry()
                    val persistedAccess = runCatching {
                        memberAccessDataStore.save(authState.userId, remoteAccess)
                    }.onFailure { error ->
                        Log.w(MemberAccessTag, "Failed to cache supporter access", error)
                    }.getOrDefault(remoteAccess)
                    optimisticCachedAccess = persistedAccess
                    lastVerifiedUserId = authState.userId
                    lastVerifiedAtMs = SystemClock.elapsedRealtime()
                    emit(persistedAccess)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(MemberAccessTag, "Supporter access verification failed", error)
                }
            } else if (authState is AuthState.Loading) {
                emit(optimisticCachedAccess)
            } else {
                if (authState is AuthState.SignedOut) {
                    optimisticCachedAccess = MemberAccess.None
                    lastVerifiedUserId = null
                    lastVerifiedAtMs = null
                    memberAccessDataStore.clear()
                }
                emit(MemberAccess.None)
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initialCachedAccess ?: MemberAccess.None)

    init {
        if (initialCachedAccess == null) {
            scope.launch {
                val migratedAccess = memberAccessDataStore.migrateLastKnown() ?: return@launch
                if (authManager.authState.value is AuthState.SignedOut) {
                    memberAccessDataStore.clear()
                    return@launch
                }
                optimisticCachedAccess = migratedAccess
                refreshGeneration.update { it + 1L }
            }
        }
        scope.launch {
            while (true) {
                delay(MemberAccessStaleAfterMs)
                refreshIfStale()
            }
        }
    }

    fun refresh() {
        refreshGeneration.update { it + 1L }
    }

    fun refreshIfStale() {
        val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId ?: return
        val verifiedAt = lastVerifiedAtMs.takeIf { lastVerifiedUserId == userId }
        if (shouldRefreshMemberAccess(verifiedAt, SystemClock.elapsedRealtime())) {
            refresh()
        }
    }

    private suspend fun loadRemoteAccessWithRetry(): MemberAccess {
        var failedAttempt = 0
        while (true) {
            try {
                return remoteDataSource.getMemberAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val retryDelayMs = memberAccessRetryDelayMs(failedAttempt) ?: throw error
                failedAttempt += 1
                delay(retryDelayMs)
            }
        }
    }
}

internal fun shouldRefreshMemberAccess(
    lastVerifiedAtMs: Long?,
    nowMs: Long,
    staleAfterMs: Long = MemberAccessStaleAfterMs
): Boolean {
    val verifiedAt = lastVerifiedAtMs ?: return true
    val ageMs = nowMs - verifiedAt
    return ageMs < 0L || ageMs >= staleAfterMs
}

internal fun memberAccessRetryDelayMs(failedAttempt: Int): Long? = when (failedAttempt) {
    0 -> 1_000L
    1 -> 2_000L
    2 -> 4_000L
    else -> null
}

internal fun resolveMemberAccess(
    isDebugBuild: Boolean,
    memberTier: MemberTier?
): MemberAccess {
    return if (isDebugBuild && memberTier != null) {
        MemberAccess.preview(memberTier)
    } else {
        MemberAccess.None
    }
}
