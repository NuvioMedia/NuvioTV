package com.nuvio.tv.ui.screens.addon

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.AddonInfo
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.core.server.CatalogInfo
import com.nuvio.tv.core.server.CatalogSourceInfo
import com.nuvio.tv.core.server.CollectionInfo
import com.nuvio.tv.core.server.CollectionSourceInfo
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.FolderInfo
import com.nuvio.tv.core.server.PageState
import com.nuvio.tv.core.server.PendingAddonChange
import com.nuvio.tv.core.server.TmdbFiltersInfo
import com.nuvio.tv.core.server.TmdbSourceMetadataInfo
import com.nuvio.tv.core.server.TmdbSourceMetadataRequest
import com.nuvio.tv.core.server.TmdbSourceSearchRequest
import com.nuvio.tv.core.server.TmdbSourceSearchResultInfo
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class AddonManagerViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val collectionsDataStore: CollectionsDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val profileManager: ProfileManager,
    private val tmdbCollectionSourceResolver: TmdbCollectionSourceResolver,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddonManagerUiState())
    val uiState: StateFlow<AddonManagerUiState> = _uiState.asStateFlow()

    val isReadOnly: Boolean
        get() {
            return AddonManagementAccess.isReadOnly(profileManager.activeProfile)
        }

    val webConfigMode: AddonWebConfigMode
        get() = AddonManagementAccess.webConfigMode(profileManager.activeProfile)

    private var server: AddonConfigServer? = null
    private var logoBytes: ByteArray? = null
    private var homeCatalogOrderKeys: List<String> = emptyList()
    private var disabledHomeCatalogKeys: Set<String> = emptySet()
    private var currentCollections: List<Collection> = emptyList()

    init {
        observeInstalledAddons()
        observeCatalogPreferences()
        observeCollections()
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

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null, transientMessageIsError = false) }
    }

    fun installAddon() {
        val rawUrl = uiState.value.installUrl.trim()
        if (rawUrl.isBlank()) {
            val message = context.getString(R.string.addon_error_invalid_url)
            _uiState.update {
                it.copy(
                    error = message,
                    transientMessage = message,
                    transientMessageIsError = true
                )
            }
            return
        }

        val normalizedUrl = normalizeAddonUrl(rawUrl)
        if (normalizedUrl == null) {
            val message = context.getString(R.string.addon_error_invalid_scheme)
            _uiState.update {
                it.copy(
                    error = message,
                    transientMessage = message,
                    transientMessageIsError = true
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, error = null, transientMessage = null) }

            when (val result = addonRepository.fetchAddon(normalizedUrl)) {
                is NetworkResult.Success -> {
                    addonRepository.addAddon(normalizedUrl)
                    val addonName = result.data.displayName.ifBlank { result.data.baseUrl }
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installUrl = "",
                            transientMessage = context.getString(R.string.addon_install_success, addonName),
                            transientMessageIsError = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    val message = result.message
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            error = message,
                            transientMessage = message,
                            transientMessageIsError = true
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
            _uiState.update { it.copy(error = context.getString(R.string.error_network_required)) }
            return
        }

        stopServerInternal()

        server = AddonConfigServer.startOnAvailablePort(
            context = context,
            webConfigMode = webConfigMode,
            currentPageStateProvider = {
                val addons = _uiState.value.installedAddons
                val orderedCatalogs = buildOrderedCatalogEntries(
                    addons = addons,
                    savedOrderKeys = homeCatalogOrderKeys,
                    disabledKeys = disabledHomeCatalogKeys
                )
                // Build unified catalog list with collections interleaved
                val catalogInfos = orderedCatalogs.map { catalog ->
                    CatalogInfo(
                        key = catalog.key,
                        disableKey = catalog.disableKey,
                        catalogName = catalog.catalogName,
                        addonName = catalog.addonName,
                        type = catalog.typeLabel,
                        isDisabled = catalog.isDisabled
                    )
                }
                val collectionInfos = currentCollections.map { col ->
                    val colKey = "collection_${col.id}"
                    CatalogInfo(
                        key = colKey,
                        disableKey = colKey,
                        catalogName = col.title,
                        addonName = "${col.folders.size} folder${if (col.folders.size != 1) "s" else ""}",
                        type = "collection",
                        isDisabled = colKey in disabledHomeCatalogKeys
                    )
                }
                // Interleave based on saved order
                val catalogByKey = (catalogInfos + collectionInfos).associateBy { it.key }
                val savedOrder = homeCatalogOrderKeys
                val orderedKeys = savedOrder.filter { it in catalogByKey }
                val unseenKeys = catalogByKey.keys - orderedKeys.toSet()
                val unifiedCatalogs = (orderedKeys + unseenKeys).mapNotNull { catalogByKey[it] }

                PageState(
                    addons = addons.map { addon ->
                        AddonInfo(
                            url = addon.baseUrl,
                            name = addon.displayName.ifBlank { addon.baseUrl },
                            description = addon.description
                        )
                    },
                    catalogs = unifiedCatalogs,
                    collections = collectionsToServerFormat(currentCollections),
                    disabledCollectionKeys = disabledHomeCatalogKeys
                        .filter { it.startsWith("collection_") }
                )
            },
            onChangeProposed = { change -> handleChangeProposed(change) },
            tmdbMetadataProvider = { request -> fetchTmdbSourceMetadata(request) },
            tmdbSearchProvider = { request -> searchTmdbSources(request) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            _uiState.update { it.copy(error = context.getString(R.string.error_server_ports_unavailable)) }
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

    private suspend fun fetchAddonInfo(url: String): AddonInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val result = withTimeoutOrNull(15_000L) {
                    addonRepository.fetchAddon(url)
                } ?: return@withContext null

                when (result) {
                    is NetworkResult.Success -> AddonInfo(
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

    private fun fetchTmdbSourceMetadata(request: TmdbSourceMetadataRequest): TmdbSourceMetadataInfo? {
        val sourceType = runCatching { TmdbCollectionSourceType.valueOf(request.sourceType.uppercase()) }.getOrNull()
            ?: return null
        return runBlocking {
            runCatching {
                val metadata = when (sourceType) {
                    TmdbCollectionSourceType.LIST -> tmdbCollectionSourceResolver.listImportMetadata(request.tmdbId)
                    TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.collectionImportMetadata(request.tmdbId)
                    TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.companyImportMetadata(request.tmdbId)
                    TmdbCollectionSourceType.NETWORK -> tmdbCollectionSourceResolver.networkImportMetadata(request.tmdbId)
                    TmdbCollectionSourceType.DISCOVER -> return@runBlocking null
                }
                TmdbSourceMetadataInfo(
                    title = metadata.title,
                    coverImageUrl = metadata.coverImageUrl
                )
            }.getOrNull()
        }
    }

    private fun searchTmdbSources(request: TmdbSourceSearchRequest): List<TmdbSourceSearchResultInfo> {
        val sourceType = runCatching { TmdbCollectionSourceType.valueOf(request.sourceType.uppercase()) }.getOrNull()
            ?: return emptyList()
        return runBlocking {
            runCatching {
                when (sourceType) {
                    TmdbCollectionSourceType.COMPANY -> tmdbCollectionSourceResolver.searchCompanies(request.query)
                        .map {
                            TmdbSourceSearchResultInfo(
                                id = it.id,
                                title = it.name ?: "TMDB Company ${it.id}",
                                subtitle = it.originCountry?.takeIf { value -> value.isNotBlank() }
                            )
                        }
                    TmdbCollectionSourceType.COLLECTION -> tmdbCollectionSourceResolver.searchCollections(request.query)
                        .map {
                            TmdbSourceSearchResultInfo(
                                id = it.id,
                                title = it.name ?: "TMDB Collection ${it.id}",
                                subtitle = it.overview?.takeIf { value -> value.isNotBlank() }
                            )
                        }
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }
    }

    private fun handleChangeProposed(change: PendingAddonChange) {
        val currentUrls = _uiState.value.installedAddons.map { normalizeUrlForComparison(it.baseUrl) }.toSet()
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()
        val currentCatalogEntries = buildOrderedCatalogEntries(
            addons = _uiState.value.installedAddons,
            savedOrderKeys = homeCatalogOrderKeys,
            disabledKeys = disabledHomeCatalogKeys
        )
        val availableCatalogKeys = currentCatalogEntries.map { it.key }.toSet()
        val collectionKeysSet = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeysSet
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
                .filter { it in allValidOrderKeys }
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

        val proposedCollectionsJson = change.proposedCollectionsJson
        val collectionsChanged = proposedCollectionsJson != null
        val proposedCollectionCount = if (proposedCollectionsJson != null) {
            try { parseCollectionsFromJson(proposedCollectionsJson).size } catch (_: Exception) { 0 }
        } else 0
        val proposedDisabledCollectionKeys = change.proposedDisabledCollectionKeys

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
                    removedNames = removedNames,
                    collectionsChanged = collectionsChanged,
                    proposedCollectionsJson = proposedCollectionsJson,
                    proposedCollectionCount = proposedCollectionCount,
                    proposedDisabledCollectionKeys = proposedDisabledCollectionKeys
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
            if (pending.collectionsChanged && pending.proposedCollectionsJson != null) {
                try {
                    val newCollections = parseCollectionsFromJson(pending.proposedCollectionsJson)
                    collectionsDataStore.setCollections(newCollections)
                } catch (_: Exception) { }
            }
            // Apply disabled collection key changes
            if (pending.proposedDisabledCollectionKeys.isNotEmpty() || disabledHomeCatalogKeys.any { it.startsWith("collection_") }) {
                val nonCollectionDisabledKeys = disabledHomeCatalogKeys.filter { !it.startsWith("collection_") }
                val mergedDisabledKeys = nonCollectionDisabledKeys + pending.proposedDisabledCollectionKeys
                layoutPreferenceDataStore.setDisabledHomeCatalogKeys(mergedDisabledKeys)
                homeCatalogSettingsSyncService.triggerPush()
            }
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
        // Collection keys are also valid in the ordering
        val collectionKeys = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeys

        val validCatalogOrder = pending.proposedCatalogOrderKeys
            .asSequence()
            .filter { it in allValidOrderKeys }
            .distinct()
            .toList()
        val validDisabledCatalogs = pending.proposedDisabledCatalogKeys
            .asSequence()
            .filter { it in availableDisableKeys }
            .distinct()
            .toList()

        layoutPreferenceDataStore.setHomeCatalogOrderKeys(validCatalogOrder)
        layoutPreferenceDataStore.setDisabledHomeCatalogKeys(validDisabledCatalogs)
        homeCatalogSettingsSyncService.triggerPush()
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

    private fun observeCollections() {
        viewModelScope.launch {
            collectionsDataStore.collections.collect { cols ->
                currentCollections = cols
            }
        }
    }

    private fun collectionsToServerFormat(cols: List<Collection>): List<CollectionInfo> {
        return cols.map { col ->
            CollectionInfo(
                id = col.id,
                title = col.title,
                backdropImageUrl = col.backdropImageUrl,
                pinToTop = col.pinToTop,
                focusGlowEnabled = col.focusGlowEnabled,
                viewMode = col.viewMode.name,
                showAllTab = col.showAllTab,
                folders = col.folders.map { folder ->
                    FolderInfo(
                        id = folder.id,
                        title = folder.title,
                        coverImageUrl = folder.coverImageUrl,
                        focusGifUrl = folder.focusGifUrl,
                        focusGifEnabled = folder.focusGifEnabled,
                        coverEmoji = folder.coverEmoji,
                        tileShape = folder.tileShape.name,
                        hideTitle = folder.hideTitle,
                        heroBackdropUrl = folder.heroBackdropUrl,
                        titleLogoUrl = folder.titleLogoUrl,
                        catalogSources = folder.catalogSources.map { src ->
                            CatalogSourceInfo(
                                addonId = src.addonId,
                                type = src.type,
                                catalogId = src.catalogId,
                                genre = src.genre
                            )
                        },
                        sources = folder.sources.map { source ->
                            when (source) {
                                is AddonCatalogCollectionSource -> CollectionSourceInfo(
                                    provider = "addon",
                                    addonId = source.addonId,
                                    type = source.type,
                                    catalogId = source.catalogId,
                                    genre = source.genre
                                )
                                is TmdbCollectionSource -> CollectionSourceInfo(
                                    provider = "tmdb",
                                    tmdbSourceType = source.sourceType.name,
                                    title = source.title,
                                    tmdbId = source.tmdbId,
                                    mediaType = source.mediaType.name,
                                    sortBy = source.sortBy,
                                    filters = TmdbFiltersInfo(
                                        withGenres = source.filters.withGenres,
                                        releaseDateGte = source.filters.releaseDateGte,
                                        releaseDateLte = source.filters.releaseDateLte,
                                        voteAverageGte = source.filters.voteAverageGte,
                                        voteAverageLte = source.filters.voteAverageLte,
                                        voteCountGte = source.filters.voteCountGte,
                                        withOriginalLanguage = source.filters.withOriginalLanguage,
                                        withOriginCountry = source.filters.withOriginCountry,
                                        withKeywords = source.filters.withKeywords,
                                        withCompanies = source.filters.withCompanies,
                                        withNetworks = source.filters.withNetworks,
                                        year = source.filters.year
                                    )
                                )
                            }
                        }
                    )
                }
            )
        }
    }

    private fun parseCollectionsFromJson(json: String): List<Collection> {
        return collectionsDataStore.importFromJson(json)
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
            entry.copy(
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys)
            )
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
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
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
        return homeCatalogKey(addonId, type, catalogId)
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }

    private data class QrCatalogEntry(
        val key: String,
        val disableKey: String,
        val legacyDisableKey: String? = null,
        val catalogName: String,
        val addonName: String,
        val typeLabel: String,
        val isDisabled: Boolean = false
    )
}
