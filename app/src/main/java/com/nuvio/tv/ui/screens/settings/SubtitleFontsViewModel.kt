package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.player.SubtitleFont
import com.nuvio.tv.core.player.SubtitleFontStore
import com.nuvio.tv.core.player.SubtitleFonts
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.SubtitleFontUploadServer
import com.nuvio.tv.core.util.readAppLogoBytes
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SubtitleFontImportState(
    val isActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val error: String? = null
)

@HiltViewModel
class SubtitleFontsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    private val store = SubtitleFontStore.get(context)
    private val logoBytes: ByteArray? by lazy { context.readAppLogoBytes() }
    val fonts: StateFlow<List<SubtitleFont>> = store.fonts

    private val mutableImportState = MutableStateFlow(SubtitleFontImportState())
    val importState: StateFlow<SubtitleFontImportState> = mutableImportState.asStateFlow()
    private var server: SubtitleFontUploadServer? = null

    fun remove(font: SubtitleFont, selectedFontId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.remove(font.id) }
            if (selectedFontId == font.id) playerSettingsDataStore.setSubtitleFontId(SubtitleFonts.DEFAULT)
        }
    }

    fun startImport() {
        stopServer()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            showImportError(R.string.error_network_required)
            return
        }
        val startedServer = SubtitleFontUploadServer.startOnAvailablePort(
            context = context,
            onFontUploaded = ::importUploadedFont,
            logoProvider = { logoBytes }
        )
        if (startedServer == null) {
            showImportError(R.string.error_server_ports_unavailable)
            return
        }
        server = startedServer
        val url = "http://$ip:${startedServer.listeningPort}"
        mutableImportState.value = SubtitleFontImportState(
            isActive = true,
            qrCodeBitmap = QrCodeGenerator.generate(url, 512),
            serverUrl = url
        )
    }

    fun stopImport() {
        stopServer()
        mutableImportState.value = SubtitleFontImportState()
    }

    private fun importUploadedFont(fileName: String, bytes: ByteArray): Boolean {
        val font = runCatching { store.import(fileName, bytes) }.getOrNull() ?: return false
        viewModelScope.launch {
            playerSettingsDataStore.setSubtitleFontId(font.id)
            delay(IMPORT_CLOSE_DELAY_MS)
            stopImport()
        }
        return true
    }

    private fun showImportError(message: Int) {
        mutableImportState.value = SubtitleFontImportState(
            isActive = true,
            error = context.withAppLocale().getString(message)
        )
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }

    private companion object {
        const val IMPORT_CLOSE_DELAY_MS = 1_000L
    }
}
