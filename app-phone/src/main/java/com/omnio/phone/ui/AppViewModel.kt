package com.omnio.phone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.core.sync.StartupSyncService
import com.omnio.tv.domain.auth.AuthManager
import com.omnio.tv.domain.model.AuthState
import com.omnio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface AppGate {
    data object Initializing : AppGate
    data object SignedOut : AppGate
    data object PreparingLibrary : AppGate
    data object Ready : AppGate
}

private const val SYNC_GATE_TIMEOUT_MS = 8_000L

@HiltViewModel
class AppViewModel @Inject constructor(
    authManager: AuthManager,
    private val addonRepository: AddonRepository,
    private val startupSyncService: StartupSyncService
) : ViewModel() {

    private val _gate = MutableStateFlow<AppGate>(AppGate.Initializing)
    val gate: StateFlow<AppGate> = _gate.asStateFlow()

    init {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.Loading -> _gate.value = AppGate.Initializing
                    is AuthState.SignedOut -> _gate.value = AppGate.SignedOut
                    is AuthState.FullAccount -> {
                        _gate.value = AppGate.PreparingLibrary
                        startupSyncService.requestSyncNow()
                        withTimeoutOrNull(SYNC_GATE_TIMEOUT_MS) {
                            addonRepository.getInstalledAddons()
                                .first { it.isNotEmpty() }
                        }
                        if (_gate.value == AppGate.PreparingLibrary) {
                            _gate.value = AppGate.Ready
                        }
                    }
                }
            }
        }
    }
}
