package com.omnio.phone.ui.screens.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.data.local.LibraryPreferences
import com.omnio.tv.domain.auth.AuthManager
import com.omnio.tv.domain.model.AuthState
import com.omnio.tv.domain.plugin.PluginManager
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountConnectedStats(
    val addons: Int = 0,
    val plugins: Int = 0,
    val library: Int = 0,
    val watchProgress: Int = 0
)

data class PhoneSettingsUiState(
    val email: String? = null,
    val isSigningOut: Boolean = false,
    val versionName: String = "",
    val versionCode: String = "",
    val isDebugBuild: Boolean = false,
    val message: String? = null,
    val connectedStats: AccountConnectedStats? = null,
    val isStatsLoading: Boolean = true
)

@HiltViewModel
class PhoneSettingsViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressRepository: WatchProgressRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneSettingsUiState())
    val uiState: StateFlow<PhoneSettingsUiState> = _uiState.asStateFlow()

    init {
        loadVersion()
        observeAuth()
        observeConnectedStats()
    }

    private fun loadVersion() {
        val (name, code) = readVersion()
        val isDebug = context.packageName.endsWith(".debug")
        _uiState.update {
            it.copy(versionName = name, versionCode = code, isDebugBuild = isDebug)
        }
    }

    private fun readVersion(): Pair<String, String> {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: ""
            val code = PackageInfoCompat.getLongVersionCode(info).toString()
            name to code
        } catch (_: PackageManager.NameNotFoundException) {
            "" to ""
        }
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                val email = (state as? AuthState.FullAccount)?.email
                _uiState.update { it.copy(email = email) }
            }
        }
    }

    private fun observeConnectedStats() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                pluginManager.repositories,
                libraryPreferences.libraryItems,
                watchProgressRepository.allProgress
            ) { addons, plugins, library, progress ->
                AccountConnectedStats(
                    addons = addons.size,
                    plugins = plugins.size,
                    library = library.size,
                    watchProgress = progress.size
                )
            }.collect { stats ->
                _uiState.update { it.copy(connectedStats = stats, isStatsLoading = false) }
            }
        }
    }

    fun signOut() {
        if (_uiState.value.isSigningOut) return
        _uiState.update { it.copy(isSigningOut = true, message = null) }
        viewModelScope.launch {
            runCatching { authManager.signOut(explicit = true) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSigningOut = false,
                            message = error.message ?: "Failed to sign out"
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
