package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.remote.supabase.MembershipOverviewRemoteDataSource
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.MembershipOverview
import com.nuvio.tv.domain.model.MembershipOverviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val MembershipOverviewTag = "MembershipOverview"

@Singleton
class MembershipOverviewRepository @Inject constructor(
    authManager: AuthManager,
    private val remoteDataSource: MembershipOverviewRemoteDataSource
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshGeneration = MutableStateFlow(0L)
    private val _state = MutableStateFlow(MembershipOverviewState())
    val state: StateFlow<MembershipOverviewState> = _state.asStateFlow()
    private var currentUserId: String? = null

    init {
        scope.launch {
            combine(authManager.authState, refreshGeneration) { authState, _ -> authState }
                .collectLatest(::loadOverview)
        }
    }

    fun refresh() {
        refreshGeneration.update { it + 1L }
    }

    private suspend fun loadOverview(authState: AuthState) {
        if (authState is AuthState.Loading) {
            _state.value = MembershipOverviewState()
            return
        }

        val account = authState as? AuthState.FullAccount
        if (account == null) {
            currentUserId = null
            _state.value = MembershipOverviewState(
                overview = MembershipOverview(),
                isLoading = false
            )
            return
        }

        val previous = _state.value.overview.takeIf { currentUserId == account.userId }
        currentUserId = account.userId
        _state.value = MembershipOverviewState(
            overview = previous,
            isLoading = previous == null,
            isRefreshing = previous != null
        )

        try {
            _state.value = MembershipOverviewState(
                overview = remoteDataSource.getMembershipOverview(),
                isLoading = false
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(MembershipOverviewTag, "Unable to load membership overview", error)
            _state.value = MembershipOverviewState(
                overview = previous,
                isLoading = false,
                hasError = true
            )
        }
    }
}
