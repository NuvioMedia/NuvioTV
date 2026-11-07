package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.UsenetPhoneSetup
import com.nuvio.tv.core.server.UsenetSourcesConfigServer
import com.nuvio.tv.core.server.UsenetSourcesWebPage
import com.nuvio.tv.core.usenet.IndexerStatus
import com.nuvio.tv.core.usenet.NewznabClient
import com.nuvio.tv.core.usenet.NntpProviderTester
import com.nuvio.tv.core.usenet.ProviderTestResult
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSettings
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class UsenetSourcesUiState(
    val profileId: Int = 0,
    val configuration: UsenetSourceConfiguration = UsenetSourceConfiguration(),
    val loaded: Boolean = false,
    val error: Boolean = false
)

/** The QR code shown while a phone can edit sources; [lastSaved] names the source it saved last. */
data class UsenetPhoneSetupUiState(val url: String, val qrCode: Bitmap, val lastSaved: String? = null)

@HiltViewModel
class UsenetSourcesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: UsenetSourceSettings,
    private val profiles: ProfileManager,
    private val client: NewznabClient,
    private val providerTester: NntpProviderTester,
    private val usenetSettings: UsenetSettings
) : ViewModel() {
    private val state = MutableStateFlow(UsenetSourcesUiState())
    val uiState = state.asStateFlow()
    private val phone = MutableStateFlow<UsenetPhoneSetupUiState?>(null)
    val phoneSetup = phone.asStateFlow()
    private var phoneServer: UsenetSourcesConfigServer? = null

    /** By indexer id; searches update these while settings are open. */
    val indexerStatuses: StateFlow<Map<String, IndexerStatus>> =
        combine(uiState, client.statusChanges) { ui, _ ->
            ui.configuration.indexers.associate { it.id to client.status(it) }
        }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            settings.snapshots.flowOn(Dispatchers.IO).collectLatest { (profileId, result) ->
                state.value = result.fold(
                    onSuccess = { UsenetSourcesUiState(profileId, it, loaded = true) },
                    onFailure = { UsenetSourcesUiState(profileId, error = true) }
                )
            }
        }
    }

    fun update(configuration: UsenetSourceConfiguration, profileId: Int) {
        save(configuration, profileId)
    }

    private fun save(configuration: UsenetSourceConfiguration, profileId: Int): Boolean {
        if (!state.value.loaded || profiles.activeProfileId.value != profileId) return false
        // Synchronous so that quick successive edits never build on a stale configuration.
        val normalized = configuration.normalized()
        return try {
            settings.update(normalized, profileId)
            state.value = UsenetSourcesUiState(profileId, normalized, loaded = true)
            true
        } catch (_: Exception) { state.value = state.value.copy(error = true); false }
    }

    suspend fun test(indexer: UsenetIndexer): Boolean = withContext(Dispatchers.IO) {
        try { indexer.validate(); client.verify(indexer); true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** Null when the entered settings are invalid. */
    suspend fun testProvider(provider: UsenetProvider): ProviderTestResult? {
        if (runCatching { provider.validate() }.isFailure) return null
        return providerTester.test(provider, usenetSettings.settings.value.allowPrivateNetwork)
    }

    fun startPhoneSetup() {
        val current = state.value
        if (!current.loaded) return
        val ip = DeviceIpAddress.get(context) ?: return toast(R.string.error_network_required)
        stopPhoneSetup()
        val localized = UsenetSourcesWebPage.localized(context)
        val logo by lazy { runCatching { context.resources.openRawResource(R.drawable.app_logo_wordmark).use { it.readBytes() } }.getOrNull() }
        val server = UsenetSourcesConfigServer.startOnAvailablePort(
            setup = PhoneSetup(current.profileId),
            page = { UsenetSourcesWebPage.html(localized) },
            text = localized::getString,
            logo = { logo }
        ) ?: return toast(R.string.error_server_ports_unavailable)
        phoneServer = server
        val url = "http://$ip:${server.listeningPort}/?t=${server.token}"
        phone.value = UsenetPhoneSetupUiState(url, QrCodeGenerator.generate(url, 512))
    }

    fun stopPhoneSetup() {
        phoneServer?.stop()
        phoneServer = null
        phone.value = null
    }

    override fun onCleared() {
        stopPhoneSetup()
        super.onCleared()
    }

    private fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /** Runs on the server's threads; changes go through the main thread like edits made with the remote. */
    private inner class PhoneSetup(private val profileId: Int) : UsenetPhoneSetup {
        override fun configuration() = runBlocking(Dispatchers.Main.immediate) { current() }

        override fun save(name: String, change: (UsenetSourceConfiguration) -> UsenetSourceConfiguration) =
            runBlocking(Dispatchers.Main.immediate) {
                val saved = current()?.let { save(change(it), profileId) } == true
                if (saved) phone.update { it?.copy(lastSaved = name) }
                saved
            }

        override fun testProvider(provider: UsenetProvider) = runBlocking { this@UsenetSourcesViewModel.testProvider(provider) }

        override fun testIndexer(indexer: UsenetIndexer) = runBlocking { test(indexer) }

        /** Null once the screen switched profile: the code was shown for this one. */
        private fun current() = state.value.takeIf {
            it.loaded && it.profileId == profileId && profiles.activeProfileId.value == profileId
        }?.configuration
    }
}
