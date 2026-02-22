package com.nuvio.tv.ui.screens.plugin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.RepositoryConfigServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginUiState())
    val uiState: StateFlow<PluginUiState> = _uiState.asStateFlow()

    val isReadOnly: Boolean
        get() {
            val profile = profileManager.activeProfile ?: return false
            return !profile.isPrimary && profile.usesPrimaryPlugins
        }

    private var repoServer: RepositoryConfigServer? = null
    private var logoBytes: ByteArray? = null

    init {
        loadLogoBytes()
        observePluginData()
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    private fun observePluginData() {
        viewModelScope.launch {
            combine(
                pluginManager.pluginsEnabled,
                pluginManager.repositories,
                pluginManager.scrapers
            ) { enabled, repos, scrapers ->
                Triple(enabled, repos, scrapers)
            }.collect { (enabled, repos, scrapers) ->
                val visibleScrapers = if (isReadOnly) {
                    scrapers.filter { it.enabled }
                } else {
                    scrapers
                }
                _uiState.update {
                    it.copy(
                        pluginsEnabled = enabled,
                        repositories = repos,
                        scrapers = visibleScrapers
                    )
                }
            }
        }
    }

    fun onEvent(event: PluginUiEvent) {
        when (event) {
            is PluginUiEvent.AddRepository -> addRepository(event.url)
            is PluginUiEvent.RemoveRepository -> removeRepository(event.repoId)
            is PluginUiEvent.RefreshRepository -> refreshRepository(event.repoId)
            is PluginUiEvent.ToggleScraper -> toggleScraper(event.scraperId, event.enabled)
            is PluginUiEvent.TestScraper -> testScraper(event.scraperId)
            is PluginUiEvent.SetPluginsEnabled -> setPluginsEnabled(event.enabled)
            PluginUiEvent.ClearTestResults -> _uiState.update { it.copy(testResults = null, testScraperId = null) }
            PluginUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            PluginUiEvent.ClearSuccess -> _uiState.update { it.copy(successMessage = null) }
            PluginUiEvent.StartQrMode -> startQrMode()
            PluginUiEvent.StopQrMode -> stopQrMode()
            PluginUiEvent.ConfirmPendingRepoChange -> confirmPendingRepoChange()
            PluginUiEvent.RejectPendingRepoChange -> rejectPendingRepoChange()
        }
    }

    private fun addRepository(url: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid URL") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingRepo = true, errorMessage = null) }

            val result = pluginManager.addRepository(url)

            result.fold(
                onSuccess = { repo ->
                    _uiState.update {
                        it.copy(
                            isAddingRepo = false,
                            successMessage = "Added ${repo.name} with ${repo.scraperCount} providers"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isAddingRepo = false,
                            errorMessage = "Failed to add repository: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    private fun removeRepository(repoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            pluginManager.removeRepository(repoId)
            _uiState.update { it.copy(isLoading = false, successMessage = "Repository removed") }
        }
    }

    private fun refreshRepository(repoId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = pluginManager.refreshRepository(repoId)

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, successMessage = "Repository refreshed") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to refresh: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    private fun toggleScraper(scraperId: String, enabled: Boolean) {
        viewModelScope.launch {
            pluginManager.toggleScraper(scraperId, enabled)
        }
    }

    private fun setPluginsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            pluginManager.setPluginsEnabled(enabled)
        }
    }

    private fun testScraper(scraperId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testScraperId = scraperId, testResults = null) }

            val result = pluginManager.testScraper(scraperId)

            result.fold(
                onSuccess = { results ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResults = results,
                            successMessage = if (results.isEmpty()) "No results found" else "Found ${results.size} streams"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResults = emptyList(),
                            errorMessage = "Test failed: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    private fun normalizeUrlForComparison(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }

    private fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(errorMessage = "Connect to Wi-Fi or Ethernet to use this feature") }
            return
        }

        stopRepoServerInternal()

        repoServer = RepositoryConfigServer.startOnAvailablePort(
            currentRepositoriesProvider = {
                _uiState.value.repositories.map { repo ->
                    RepositoryConfigServer.RepositoryInfo(
                        url = repo.url,
                        name = repo.name.ifBlank { repo.url },
                        description = repo.description
                    )
                }
            },
            onChangeProposed = { change -> handleRepoChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = repoServer
        if (activeServer == null) {
            _uiState.update { it.copy(errorMessage = "Could not start server. All ports in use.") }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                errorMessage = null
            )
        }
    }

    fun stopQrMode() {
        stopRepoServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingRepoChange = null
            )
        }
    }

    private fun stopRepoServerInternal() {
        repoServer?.stop()
        repoServer = null
    }

    private fun handleRepoChangeProposed(change: RepositoryConfigServer.PendingRepoChange) {
        val currentUrls = _uiState.value.repositories.map { normalizeUrlForComparison(it.url) }.toSet()
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentUrls }
        val removed = _uiState.value.repositories
            .map { it.url }
            .filter { normalizeUrlForComparison(it) !in proposedNormalized }

        _uiState.update {
            it.copy(
                pendingRepoChange = PendingRepoChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    addedUrls = added,
                    removedUrls = removed
                )
            )
        }
    }

    private fun confirmPendingRepoChange() {
        val pending = _uiState.value.pendingRepoChange ?: return

        _uiState.update { it.copy(pendingRepoChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            for (url in pending.addedUrls) {
                pluginManager.addRepository(url)
            }

            val currentRepos = _uiState.value.repositories
            for (url in pending.removedUrls) {
                val repo = currentRepos.find { normalizeUrlForComparison(it.url) == normalizeUrlForComparison(url) }
                if (repo != null) {
                    pluginManager.removeRepository(repo.id)
                }
            }

            repoServer?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingRepoChange = null) }

            delay(2500)

            stopRepoServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    private fun rejectPendingRepoChange() {
        val pending = _uiState.value.pendingRepoChange ?: return
        repoServer?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingRepoChange = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopRepoServerInternal()
    }
}
