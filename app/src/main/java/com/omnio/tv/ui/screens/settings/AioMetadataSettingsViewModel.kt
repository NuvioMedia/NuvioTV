package com.omnio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.BuildConfig
import com.omnio.tv.R
import com.omnio.tv.core.profile.ProfileManager
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigInnerDto
import com.omnio.tv.data.remote.dto.aiometadata.AioMetadataDefaultConfig
import com.omnio.tv.domain.model.AioMetadataProvider
import com.omnio.tv.domain.repository.AioMetadataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val isMutating: Boolean = false,
        val enabled: Boolean = false,
        val uuid: String = "",
        val manifestUrl: String = "",
        val configPassword: String = "",
        val providers: Map<String, Boolean> = emptyMap(),
        val apiKeys: Map<String, String> = emptyMap(),
        val catalogs: List<Map<String, Any?>> = emptyList(),
        val settings: Map<String, Any?> = emptyMap(),
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
                return "$base/stremio/$uuid/configure"
            }

        /** Upstream requires both TMDB and TVDB before it will mint a UUID. */
        val canEnable: Boolean
            get() = apiKeys["tmdb"].orEmpty().isNotBlank() &&
                apiKeys["tvdb"].orEmpty().isNotBlank()
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
                        providers = cached?.toNuvioProviderStates() ?: it.providers,
                        apiKeys = cached?.apiKeys ?: it.apiKeys,
                        catalogs = cached?.catalogs ?: it.catalogs,
                        settings = cached?.settings ?: it.settings,
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
        val password = repository.getConfigPassword()
        val result = repository.refresh()
        result
            .onSuccess { config ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        configPassword = password.orEmpty(),
                        providers = config?.toNuvioProviderStates() ?: it.providers,
                        apiKeys = if (config != null) config.apiKeys
                                  else if (it.apiKeys.isEmpty()) mapOf("rpdb" to DEFAULT_RPDB_KEY)
                                  else it.apiKeys,
                        catalogs = config?.catalogs ?: it.catalogs,
                        settings = config?.settings ?: it.settings,
                        hasConfig = config != null,
                    )
                }

                val uuid = _uiState.value.uuid
                if (config != null && uuid.isNotBlank()) {
                    // Migrate legacy configs that stored provider toggle booleans inside
                    // the upstream providers routing map (corrupts the web configure UI).
                    migrateProviderBooleansIfNeeded(uuid, config)

                    // Silently apply the default template to configs created before the
                    // template feature (detected by absent nuvio_template_version marker).
                    if (!AioMetadataDefaultConfig.isTemplateApplied(config)) {
                        applyTemplateInBackground(uuid, config.apiKeys)
                    }
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

    private fun applyTemplateInBackground(uuid: String, currentApiKeys: Map<String, String>) {
        viewModelScope.launch {
            runCatching { AioMetadataDefaultConfig.build(appContext, currentApiKeys) }
                .onSuccess { templateConfig ->
                    repository.updateConfig(uuid, templateConfig)
                        .onSuccess { config ->
                            _uiState.update { state ->
                                state.copy(
                                    providers = config.toNuvioProviderStates(),
                                    apiKeys = config.apiKeys,
                                    catalogs = config.catalogs,
                                    settings = config.settings,
                                )
                            }
                        }
                }
        }
    }

    private fun migrateProviderBooleansIfNeeded(uuid: String, config: AioConfigInnerDto) {
        val providerKeys = AioMetadataProvider.entries.map { it.key }.toSet()
        val booleansInProviders = config.providers.filter { (k, v) -> k in providerKeys && v is Boolean }
        if (booleansInProviders.isEmpty()) return

        viewModelScope.launch {
            val migrated = config.copy(
                providers = config.providers - booleansInProviders.keys,
                settings = config.settings + booleansInProviders.mapKeys { (k, _) -> "nuvio_provider_$k" },
            )
            repository.updateConfig(uuid, migrated)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            providers = updated.toNuvioProviderStates(),
                            settings = updated.settings,
                        )
                    }
                }
        }
    }

    fun onToggleEnabled() {
        val current = _uiState.value
        if (current.isMutating || current.isPrimaryProfileBlocked) return

        val target = !current.enabled
        if (target && !current.canEnable) {
            _uiState.update {
                it.copy(statusMessage = appContext.getString(R.string.aio_metadata_enable_blocked_no_keys))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, errorMessage = null, statusMessage = null) }

            val manifest = if (target && current.uuid.isBlank()) {
                val templateResult = runCatching { AioMetadataDefaultConfig.build(appContext, current.apiKeys) }
                val templateConfig = templateResult.getOrNull()
                if (templateConfig == null) {
                    _uiState.update {
                        it.copy(isMutating = false, errorMessage = "Unable to build AIOMetadata config.")
                    }
                    return@launch
                }
                val createResult = repository.createConfig(templateConfig)
                val created = createResult.getOrNull()
                if (created == null) {
                    _uiState.update {
                        it.copy(
                            isMutating = false,
                            errorMessage = createResult.exceptionOrNull()?.message
                                ?: "Unable to create AIOMetadata config."
                        )
                    }
                    return@launch
                }
                val newPassword = repository.getConfigPassword()
                _uiState.update { it.copy(configPassword = newPassword.orEmpty()) }
                created.manifestUrl
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
            current.copy(settings = current.settings + ("nuvio_provider_$providerKey" to enabled))
        }
    }

    fun onProviderKeyChanged(providerKey: String, value: String) {
        val trimmed = value.trim()
        mutateConfig { current ->
            val newKeys = current.apiKeys.toMutableMap()
            if (trimmed.isBlank()) newKeys.remove(providerKey) else newKeys[providerKey] = trimmed
            current.copy(apiKeys = newKeys)
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private inline fun mutateConfig(crossinline transform: (AioConfigInnerDto) -> AioConfigInnerDto) {
        val current = _uiState.value
        if (current.isMutating) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, errorMessage = null, statusMessage = null) }

            // Prefer the in-memory cache (has routing config). Fall back to toInnerConfig()
            // only when there is no UUID yet; once a UUID exists the cache should always
            // be populated — bail out rather than risk sending an empty providers map.
            val base = repository.cachedConfig() ?: if (current.uuid.isBlank()) {
                current.toInnerConfig()
            } else {
                _uiState.update { it.copy(isMutating = false) }
                return@launch
            }
            val next = transform(base)

            // First save requires both TMDB + TVDB — otherwise upstream returns 400.
            // Edits that don't satisfy that constraint are stored in UI state only;
            // the network call waits until the user fills both required keys.
            if (current.uuid.isBlank() && !hasRequiredKeys(next)) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        providers = next.toNuvioProviderStates(),
                        apiKeys = next.apiKeys,
                        catalogs = next.catalogs,
                        settings = next.settings,
                    )
                }
                return@launch
            }

            val result: Result<AioConfigInnerDto> = if (current.uuid.isBlank()) {
                // First creation: send full default template with user's API keys.
                runCatching { AioMetadataDefaultConfig.build(appContext, next.apiKeys) }
                    .fold(
                        onSuccess = { defaultConfig ->
                            repository.createConfig(defaultConfig).map { defaultConfig }
                        },
                        onFailure = { Result.failure(it) }
                    )
            } else {
                repository.updateConfig(current.uuid, next)
            }

            result
                .onSuccess { config ->
                    _uiState.update { state ->
                        state.copy(
                            isMutating = false,
                            providers = config.toNuvioProviderStates(),
                            apiKeys = config.apiKeys,
                            catalogs = config.catalogs,
                            settings = config.settings,
                            hasConfig = true,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isMutating = false,
                            // Preserve the user's edit locally so they don't lose input.
                            providers = next.toNuvioProviderStates(),
                            apiKeys = next.apiKeys,
                            catalogs = next.catalogs,
                            settings = next.settings,
                            errorMessage = error.message ?: "Unable to update AIOMetadata config."
                        )
                    }
                }
        }
    }

    private fun UiState.toInnerConfig() = AioConfigInnerDto(
        providers = emptyMap(), // routing config comes from cachedConfig or template
        apiKeys = apiKeys,
        catalogs = catalogs,
        settings = settings,
    )

    private fun AioConfigInnerDto.toNuvioProviderStates(): Map<String, Boolean> =
        AioMetadataProvider.entries.associate { provider ->
            provider.key to (settings["nuvio_provider_${provider.key}"] as? Boolean ?: false)
        }

    private fun hasRequiredKeys(inner: AioConfigInnerDto): Boolean =
        inner.apiKeys["tmdb"].orEmpty().isNotBlank() &&
            inner.apiKeys["tvdb"].orEmpty().isNotBlank()

    companion object {
        val KNOWN_PROVIDERS: List<AioMetadataProvider> = AioMetadataProvider.entries
        const val DEFAULT_RPDB_KEY = "t0-free-rpdb"
    }
}

