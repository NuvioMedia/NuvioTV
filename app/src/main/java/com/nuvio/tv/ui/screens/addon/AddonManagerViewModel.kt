package com.nuvio.tv.ui.screens.addon

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AddonManagerViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddonManagerUiState())
    val uiState: StateFlow<AddonManagerUiState> = _uiState.asStateFlow()

    val isReadOnly: Boolean
        get() {
            val profile = profileManager.activeProfile ?: return false
            return !profile.isPrimary && profile.usesPrimaryAddons
        }

    private var server: AddonConfigServer? = null
    private var logoBytes: ByteArray? = null
    private var homeCatalogOrderKeys: List<String> = emptyList()
    private var disabledHomeCatalogKeys: Set<String> = emptySet()

    init {
        observeInstalledAddons()
        observeCatalogPreferences()
        loadLogoBytes()
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun onInstallUrlChange(url: String) {
        _uiState.update { it.copy(installUrl = url, error = null) }
    }

    fun installAddon() {
        val rawUrl = uiState.value.installUrl.trim()
        if (rawUrl.isBlank()) {
            _uiState.update { it.copy(error = "Enter a valid addon URL") }
            return
        }

        val normalizedUrl = normalizeAddonUrl(rawUrl)
        if (normalizedUrl == null) {
            _uiState.update { it.copy(error = "Addon URL must start with http or https") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, error = null) }

            when (val result = addonRepository.fetchAddon(normalizedUrl)) {
                is NetworkResult.Success -> {
                    addonRepository.addAddon(normalizedUrl)
                    _uiState.update { it.copy(isInstalling = false, installUrl = "") }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            error = result.message ?: "Unable to install addon"
                        )
                    }
                }
                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isInstalling = true) }
                }
            }
        }
    }

    private fun normalizeAddonUrl(input: String): String? {
        var trimmed = input.trim()
        if (trimmed.startsWith("stremio://")) {
            trimmed = trimmed.replaceFirst("stremio://", "https://")
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null
        }

        val withoutManifest = if (trimmed.endsWith("/manifest.json")) {
            trimmed.removeSuffix("/manifest.json")
        } else {
            trimmed
        }

        return withoutManifest.trimEnd('/')
    }

    private fun normalizeUrlForComparison(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }

    fun removeAddon(baseUrl: String) {
        viewModelScope.launch {
            addonRepository.removeAddon(baseUrl)
        }
    }

    fun moveAddonUp(baseUrl: String) {
        reorderAddon(baseUrl, -1)
    }

    fun moveAddonDown(baseUrl: String) {
        reorderAddon(baseUrl, 1)
    }

    private fun reorderAddon(baseUrl: String, direction: Int) {
        val current = _uiState.value.installedAddons
        val index = current.indexOfFirst { it.baseUrl == baseUrl }
        if (index == -1) return

        val newIndex = index + direction
        if (newIndex !in current.indices) return

        val reordered = current.toMutableList().apply {
            val item = removeAt(index)
            add(newIndex, item)
        }

        viewModelScope.launch {
            addonRepository.setAddonOrder(reordered.map { it.baseUrl })
        }
    }

    fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(error = "Connect to Wi-Fi or Ethernet to use this feature") }
            return
        }

        stopServerInternal()

        server = AddonConfigServer.startOnAvailablePort(
            currentPageStateProvider = {
                val addons = _uiState.value.installedAddons
                val orderedCatalogs = buildOrderedCatalogEntries(
                    addons = addons,
                    savedOrderKeys = homeCatalogOrderKeys,
                    disabledKeys = disabledHomeCatalogKeys
                )
                AddonConfigServer.PageState(
                    addons = addons.map { addon ->
                        AddonConfigServer.AddonInfo(
                            url = addon.baseUrl,
                            name = addon.displayName.ifBlank { addon.baseUrl },
                            description = addon.description
                        )
                    },
                    catalogs = orderedCatalogs.map { catalog ->
                        AddonConfigServer.CatalogInfo(
                            key = catalog.key,
                            disableKey = catalog.disableKey,
                            catalogName = catalog.catalogName,
                            addonName = catalog.addonName,
                            type = catalog.typeLabel,
                            isDisabled = catalog.isDisabled
                        )
                    }
                )
            },
            onChangeProposed = { change -> handleChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            _uiState.update { it.copy(error = "Could not start server. All ports in use.") }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                error = null
            )
        }
    }

    fun stopQrMode() {
        stopServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingChange = null
            )
        }
    }

    private suspend fun fetchAddonInfo(url: String): AddonConfigServer.AddonInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val result = withTimeoutOrNull(15_000L) {
                    addonRepository.fetchAddon(url)
                } ?: return@withContext null

                when (result) {
                    is NetworkResult.Success -> AddonConfigServer.AddonInfo(
                        url = result.data.baseUrl,
                        name = result.data.name.ifBlank { url },
                        description = result.data.description
                    )
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
    }

    private fun handleChangeProposed(change: AddonConfigServer.PendingAddonChange) {
        val currentUrls = _uiState.value.installedAddons.map { normalizeUrlForComparison(it.baseUrl) }.toSet()
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()
        val currentCatalogEntries = buildOrderedCatalogEntries(
            addons = _uiState.value.installedAddons,
            savedOrderKeys = homeCatalogOrderKeys,
            disabledKeys = disabledHomeCatalogKeys
        )
        val availableCatalogKeys = currentCatalogEntries.map { it.key }.toSet()
        val availableDisableKeyToName = currentCatalogEntries.associate { entry ->
            entry.disableKey to "${entry.catalogName} • ${entry.addonName}"
        }

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentUrls }
        val removed = _uiState.value.installedAddons
            .map { it.baseUrl }
            .filter { normalizeUrlForComparison(it) !in proposedNormalized }
        val resolvedProposedCatalogOrderKeys = if (change.proposedCatalogOrderKeys.isEmpty()) {
            currentCatalogEntries.map { it.key }
        } else {
            change.proposedCatalogOrderKeys
                .asSequence()
                .filter { it in availableCatalogKeys }
                .distinct()
                .toList()
        }
        val currentDisabledCatalogKeys = currentCatalogEntries
            .filter { it.isDisabled }
            .map { it.disableKey }
            .toSet()
        val resolvedProposedDisabledCatalogKeys = if (change.proposedDisabledCatalogKeys.isEmpty()) {
            currentDisabledCatalogKeys.toList()
        } else {
            change.proposedDisabledCatalogKeys
                .asSequence()
                .filter { it in availableDisableKeyToName }
                .distinct()
                .toList()
        }
        val proposedDisabledSet = resolvedProposedDisabledCatalogKeys.toSet()
        val newlyDisabledCatalogs = (proposedDisabledSet - currentDisabledCatalogKeys)
            .mapNotNull { availableDisableKeyToName[it] }
        val newlyEnabledCatalogs = (currentDisabledCatalogKeys - proposedDisabledSet)
            .mapNotNull { availableDisableKeyToName[it] }
        val catalogsReordered = resolvedProposedCatalogOrderKeys != currentCatalogEntries.map { it.key }

        val removedNameMap = _uiState.value.installedAddons
            .associateBy({ normalizeUrlForComparison(it.baseUrl) }, { it.displayName })
        val removedNames = removed.associateWith { url ->
            removedNameMap[normalizeUrlForComparison(url)] ?: url
        }

        _uiState.update {
            it.copy(
                pendingChange = PendingChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    proposedCatalogOrderKeys = resolvedProposedCatalogOrderKeys,
                    proposedDisabledCatalogKeys = resolvedProposedDisabledCatalogKeys,
                    addedUrls = added,
                    removedUrls = removed,
                    catalogsReordered = catalogsReordered,
                    disabledCatalogNames = newlyDisabledCatalogs,
                    enabledCatalogNames = newlyEnabledCatalogs,
                    removedNames = removedNames
                )
            )
        }

        if (added.isNotEmpty()) {
            viewModelScope.launch {
                val addedNames = withContext(Dispatchers.IO) {
                    added.associateWith { url ->
                        fetchAddonInfo(url)?.name ?: url
                    }
                }
                _uiState.update { state ->
                    val pending = state.pendingChange
                    if (pending == null || pending.changeId != change.id) {
                        state
                    } else {
                        state.copy(
                            pendingChange = pending.copy(addedNames = addedNames)
                        )
                    }
                }
            }
        }
    }

    fun confirmPendingChange() {
        val pending = _uiState.value.pendingChange ?: return

        _uiState.update { it.copy(pendingChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            addonRepository.setAddonOrder(pending.proposedUrls)
            applyCatalogPreferencesFromPending(pending, pending.proposedUrls)
            server?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingChange = null) }

            delay(2500)

            stopServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    fun rejectPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingChange = null) }
    }

    private suspend fun applyCatalogPreferencesFromPending(
        pending: PendingChangeInfo,
        validUrls: List<String>
    ) {
        val validUrlSet = validUrls.map { normalizeUrlForComparison(it) }.toSet()
        val targetAddons = _uiState.value.installedAddons.filter { addon ->
            normalizeUrlForComparison(addon.baseUrl) in validUrlSet
        }
        val availableCatalogEntries = buildOrderedCatalogEntries(
            addons = targetAddons,
            savedOrderKeys = homeCatalogOrderKeys,
            disabledKeys = disabledHomeCatalogKeys
        )
        val availableCatalogKeys = availableCatalogEntries.map { it.key }.toSet()
        val availableDisableKeys = availableCatalogEntries.map { it.disableKey }.toSet()

        val validCatalogOrder = pending.proposedCatalogOrderKeys
            .asSequence()
            .filter { it in availableCatalogKeys }
            .distinct()
            .toList()
        val validDisabledCatalogs = pending.proposedDisabledCatalogKeys
            .asSequence()
            .filter { it in availableDisableKeys }
            .distinct()
            .toList()

        layoutPreferenceDataStore.setHomeCatalogOrderKeys(validCatalogOrder)
        layoutPreferenceDataStore.setDisabledHomeCatalogKeys(validDisabledCatalogs)
    }

    private fun observeCatalogPreferences() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeCatalogOrderKeys.collect { keys ->
                homeCatalogOrderKeys = keys
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.disabledHomeCatalogKeys.collect { keys ->
                disabledHomeCatalogKeys = keys.toSet()
            }
        }
    }

    private fun observeInstalledAddons() {
        viewModelScope.launch {
            if (_uiState.value.installedAddons.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            addonRepository.getInstalledAddons()
                .catch { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message) }
                }
                .collect { addons ->
                    _uiState.update { state ->
                        state.copy(
                            installedAddons = addons,
                            isLoading = false,
                            error = null
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopServerInternal()
    }

    private fun buildOrderedCatalogEntries(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>
    ): List<QrCatalogEntry> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val entryByKey = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }
        val savedValid = savedOrderKeys
            .asSequence()
            .filter { it in entryByKey }
            .distinct()
            .toList()
        val savedSet = savedValid.toSet()
        val effectiveOrder = savedValid + defaultOrderKeys.filterNot { it in savedSet }

        return effectiveOrder.mapNotNull { key ->
            val entry = entryByKey[key] ?: return@mapNotNull null
            entry.copy(isDisabled = entry.disableKey in disabledKeys)
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<QrCatalogEntry> {
        val entries = mutableListOf<QrCatalogEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            QrCatalogEntry(
                                key = key,
                                disableKey = disableCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }
        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun disableCatalogKey(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): String {
        return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name == "search" && extra.isRequired }
    }

    private data class QrCatalogEntry(
        val key: String,
        val disableKey: String,
        val catalogName: String,
        val addonName: String,
        val typeLabel: String,
        val isDisabled: Boolean = false
    )
}
