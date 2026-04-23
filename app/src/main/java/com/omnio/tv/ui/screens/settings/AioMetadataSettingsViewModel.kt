package com.omnio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.BuildConfig
import com.omnio.tv.core.profile.ProfileManager
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigResponseDto
import com.omnio.tv.domain.model.AioMetadataProvider
import com.omnio.tv.domain.repository.AioMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AioMetadataSettingsViewModel @Inject constructor(
    private val repository: AioMetadataRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val isMutating: Boolean = false,
        val enabled: Boolean = false,
        val uuid: String = "",
        val manifestUrl: String = "",
        val providers: Map<String, Boolean> = emptyMap(),
        val providerKeys: Map<String, String> = emptyMap(),
        val isPrimaryProfileBlocked: Boolean = false,
        val hasConfig: Boolean = false,
        val errorMessage: String? = null,
        val statusMessage: String? = null,
    ) {
        val configureUrl: String
            get() {
                if (uuid.isBlank()) return ""
                val base = BuildConfig.AIOMETADATA_BASE_URL.trimEnd('/')
                if (base.isBlank()) return ""
                return "$base/configure?uuid=$uuid"
            }
    }

    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        viewModelScope.launch { refresh() }
    }

    private fun observeSettings() {
        combine(
            repository.settings,
            profileManager.profiles,
            profileManager.activeProfileId,
        ) { settings, _, _ -> settings }
            .onEach { settings ->
                val profile = profileManager.activeProfile
                val primaryBlocked = profile?.usesPrimaryAddons == true
                val cached = repository.cachedConfig()
                _uiState.update {
                    it.copy(
                        enabled = settings.enabled,
                        uuid = settings.aioUuid,
                        manifestUrl = settings.manifestUrl,
                        isPrimaryProfileBlocked = primaryBlocked,
                        hasConfig = settings.aioUuid.isNotBlank(),
                        providers = cached?.providers ?: it.providers,
                        providerKeys = cached?.providerKeys ?: it.providerKeys,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun onRefreshClick() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val result = repository.refresh()
        result
            .onSuccess { config ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        providers = config?.providers ?: it.providers,
                        providerKeys = config?.providerKeys ?: it.providerKeys,
                        hasConfig = config != null,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to reach AIOMetadata."
                    )
                }
            }
    }

    fun onToggleEnabled() {
        val current = _uiState.value
        if (current.isMutating || current.isPrimaryProfileBlocked) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, errorMessage = null, statusMessage = null) }

            // If turning ON but no config has been created yet, create one first.
            val target = !current.enabled
            val manifest = if (target && current.uuid.isBlank()) {
                val createResult = repository.createConfig(emptyRequest())
                val uuid = createResult.getOrNull()
                if (uuid == null) {
                    _uiState.update {
                        it.copy(
                            isMutating = false,
                            errorMessage = createResult.exceptionOrNull()?.message
                                ?: "Unable to create AIOMetadata config."
                        )
                    }
                    return@launch
                }
                repository.cachedConfig()?.manifestUrl.orEmpty()
            } else {
                current.manifestUrl
            }

            val setResult = repository.setEnabled(target, manifest)
            _uiState.update {
                it.copy(
                    isMutating = false,
                    errorMessage = setResult.exceptionOrNull()?.message
                )
            }
        }
    }

    fun onProviderEnabledChanged(providerKey: String, enabled: Boolean) {
        mutateConfig { current ->
            current.copy(providers = current.providers + (providerKey to enabled))
        }
    }

    fun onProviderKeyChanged(providerKey: String, value: String) {
        val trimmed = value.trim()
        mutateConfig { current ->
            val newKeys = current.providerKeys.toMutableMap()
            if (trimmed.isBlank()) newKeys.remove(providerKey) else newKeys[providerKey] = trimmed
            current.copy(providerKeys = newKeys)
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private inline fun mutateConfig(crossinline transform: (AioConfigRequestDto) -> AioConfigRequestDto) {
        val current = _uiState.value
        if (current.isMutating) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, errorMessage = null, statusMessage = null) }

            val baseRequest = repository.cachedConfig()?.toRequest() ?: emptyRequest()
            val next = transform(baseRequest)

            val result = if (current.uuid.isBlank()) {
                repository.createConfig(next).map { uuid ->
                    repository.cachedConfig() ?: AioConfigResponseDto(uuid = uuid)
                }
            } else {
                repository.updateConfig(current.uuid, next)
            }

            result
                .onSuccess { config ->
                    _uiState.update { state ->
                        val incomingManifest = config.manifestUrl.orEmpty()
                        state.copy(
                            isMutating = false,
                            uuid = config.uuid,
                            providers = config.providers,
                            providerKeys = config.providerKeys,
                            manifestUrl = incomingManifest.ifBlank { state.manifestUrl },
                            hasConfig = true,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isMutating = false,
                            errorMessage = error.message ?: "Unable to update AIOMetadata config."
                        )
                    }
                }
        }
    }

    companion object {
        val KNOWN_PROVIDERS: List<AioMetadataProvider> = AioMetadataProvider.entries

        private fun emptyRequest(): AioConfigRequestDto = AioConfigRequestDto()

        private fun AioConfigResponseDto.toRequest(): AioConfigRequestDto = AioConfigRequestDto(
            providers = providers,
            providerKeys = providerKeys,
            catalogs = catalogs,
            settings = settings,
        )
    }
}

