package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.GroupPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogGroup
import com.nuvio.tv.domain.model.MainGroup
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.content.Context
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.GroupConfigServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import com.nuvio.tv.R
import android.util.Log

import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GroupManagementViewModel @Inject constructor(
    private val groupPreferenceDataStore: GroupPreferenceDataStore,
    private val addonRepository: AddonRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupManagementUiState())
    val uiState: StateFlow<GroupManagementUiState> = _uiState.asStateFlow()

    private var server: GroupConfigServer? = null
    private var logoBytes: ByteArray? = null

    init {
        observeData()
    }

    override fun onCleared() {
        super.onCleared()
        stopServerInternal()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                groupPreferenceDataStore.catalogGroups,
                groupPreferenceDataStore.mainGroups,
                addonRepository.getInstalledAddons()
            ) { catalogGroups, mainGroups, addons ->
                
                val allCatalogs = buildAvailableCatalogs(addons)
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        catalogGroups = catalogGroups,
                        mainGroups = mainGroups,
                        availableCatalogs = allCatalogs
                    )
                }
            }.collectLatest { }
        }
    }

    private fun buildAvailableCatalogs(addons: List<Addon>): List<AvailableCatalog> {
        val entries = mutableListOf<AvailableCatalog>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = "${addon.id}:::${catalog.apiType}:::${catalog.id}"
                    if (seenKeys.add(key)) {
                        entries.add(
                            AvailableCatalog(
                                key = key,
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

    fun saveCatalogGroup(id: String?, name: String, logoUrl: String?, catalogKeys: List<String>) {
        viewModelScope.launch {
            val group = CatalogGroup(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                logoUrl = resolveImageUrl(logoUrl),
                catalogKeys = catalogKeys
            )
            groupPreferenceDataStore.updateCatalogGroup(group)
            
            // Auto create main group if this is the first subgroup and there are no main groups? 
            // Optional UX improvement, skip for now.
        }
    }

    fun deleteCatalogGroup(id: String) {
        viewModelScope.launch {
            groupPreferenceDataStore.removeCatalogGroup(id)
        }
    }

    fun saveMainGroup(id: String?, name: String, posterType: String, posterSize: String, subGroupIds: List<String>) {
        viewModelScope.launch {
            val group = MainGroup(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                posterType = posterType,
                posterSize = posterSize,
                subGroupIds = subGroupIds
            )
            groupPreferenceDataStore.updateMainGroup(group)
        }
    }

    fun deleteMainGroup(id: String) {
        viewModelScope.launch {
            groupPreferenceDataStore.removeMainGroup(id)
        }
    }

    fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(serverUrl = "Connect to Wi-Fi to use this feature") }
            return
        }
        
        // Lazy load resources
        try {
            if (logoBytes == null) {
                val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
                logoBytes = inputStream.use { it.readBytes() }
            }
        } catch (_: Exception) {}
        
        val webPageHtml = try {
            context.assets.open("group_web.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            _uiState.update { it.copy(serverUrl = "Missing HTML asset") }
            return
        }
        
        val activeToken = UUID.randomUUID().toString()

        stopServerInternal()

        server = GroupConfigServer.startOnAvailablePort(
            serverToken = activeToken,
            webPageHtml = webPageHtml,
            currentPageStateProvider = {
                GroupConfigServer.PageState(
                    availableCatalogs = uiState.value.availableCatalogs.map { catalog ->
                        GroupConfigServer.CatalogInfo(
                            key = catalog.key,
                            catalogName = catalog.catalogName,
                            addonName = catalog.addonName,
                            type = catalog.typeLabel,
                            isSelected = false
                        )
                    },
                    catalogGroups = uiState.value.catalogGroups,
                    mainGroups = uiState.value.mainGroups
                )
            },
            onChangeProposed = { change -> handleChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            _uiState.update { it.copy(serverUrl = "Could not start server. All ports in use.") }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}/?token=$activeToken"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url
            )
        }
    }

    fun stopQrMode() {
        stopServerInternal()
        logoBytes = null
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingChange = null
            )
        }
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
    }

    private fun handleChangeProposed(change: GroupConfigServer.PendingGroupChange) {
        _uiState.update {
            it.copy(
                pendingChange = change
            )
        }
    }

    fun confirmPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.confirmChange(pending.id)
        
        _uiState.update { it.copy(pendingChange = null) }
        
        viewModelScope.launch {
            // Sanitize blank logoUrls to null so Coil doesn't fail on empty-string URLs
            val sanitizedCatalogGroups = pending.proposedCatalogGroups.map { cg ->
                cg.copy(logoUrl = resolveImageUrl(cg.logoUrl))
            }
            val sanitizedMainGroups = pending.proposedMainGroups
            Log.d("GroupMgmt", "Syncing from phone: ${sanitizedCatalogGroups.size} catalog groups, ${sanitizedMainGroups.size} main groups")
            sanitizedCatalogGroups.forEach { cg ->
                Log.d("GroupMgmt", "  CatalogGroup id=${cg.id} name=${cg.name} keys=${cg.catalogKeys}")
            }
            sanitizedMainGroups.forEach { mg ->
                Log.d("GroupMgmt", "  MainGroup id=${mg.id} name=${mg.name} subGroupIds=${mg.subGroupIds}")
            }
            groupPreferenceDataStore.setCatalogGroups(sanitizedCatalogGroups)
            groupPreferenceDataStore.setMainGroups(sanitizedMainGroups)
            delay(1500)
            stopQrMode()
        }
    }

    fun rejectPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.rejectChange(pending.id)
        _uiState.update { it.copy(pendingChange = null) }
    }

    fun clearQrSelectedKeys() {
        _uiState.update { it.copy(qrSelectedKeys = null) }
    }

    // Helper extensions
    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }

    private fun resolveImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        // 1. Handle HTML Embed codes (extract src="...")
        if (trimmed.startsWith("<") && trimmed.contains("src=\"")) {
            val srcMatch = Regex("""src="([^"]+)"""").find(trimmed)
            if (srcMatch != null) {
                return resolveImageUrl(srcMatch.groupValues[1])
            }
        }

        // 2. Handle Google Drive links
        val driveRegex = Regex("""drive\.google\.com/(?:file/d/|open\?id=|uc\?id=)([^/&?]+)""")
        val match = driveRegex.find(trimmed)
        if (match != null) {
            val id = match.groupValues[1]
            // Using /uc?id= instead of /thumbnail ensures we get the original uncompressed file
            // as requested by the user. Note: Large files may show a Google virus scan warning
            // if fetched via browser, but Coil's loader handles the direct stream fine.
            return "https://drive.google.com/uc?export=download&id=$id"
        }

        return try {
            java.net.URL(trimmed)
            trimmed
        } catch (e: Exception) {
            null
        }
    }
}

data class GroupManagementUiState(
    val isLoading: Boolean = true,
    val catalogGroups: List<CatalogGroup> = emptyList(),
    val mainGroups: List<MainGroup> = emptyList(),
    val availableCatalogs: List<AvailableCatalog> = emptyList(),
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val pendingChange: com.nuvio.tv.core.server.GroupConfigServer.PendingGroupChange? = null,
    val qrSelectedKeys: List<String>? = null
)

data class AvailableCatalog(
    val key: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
