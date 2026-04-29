package com.omnio.phone.ui.screens.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.auth.AuthManager
import com.omnio.tv.domain.model.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhoneSettingsUiState(
    val email: String? = null,
    val isSigningOut: Boolean = false,
    val versionName: String = "",
    val versionCode: String = "",
    val isDebugBuild: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class PhoneSettingsViewModel @Inject constructor(
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneSettingsUiState())
    val uiState: StateFlow<PhoneSettingsUiState> = _uiState.asStateFlow()

    init {
        loadVersion()
        observeAuth()
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
