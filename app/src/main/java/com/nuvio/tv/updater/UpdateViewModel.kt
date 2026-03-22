package com.nuvio.tv.updater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showNoUpdateToastHint: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        // Lightweight check on app start.
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, showNoUpdateToastHint = false) }

            val ignoredTag = updatePreferences.ignoredTag.first()

            val result = updateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val shouldShow = remoteNewer && (ignoredTag == null || ignoredTag != update.tag)

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = update,
                            isUpdateAvailable = remoteNewer,
                            showDialog = shouldShow || force,
                            showNoUpdateToastHint = showNoUpdateFeedback && !remoteNewer,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            showDialog = force, // show error dialog if user forced a check
                            errorMessage = e.message ?: "Update check failed"
                        )
                    }
                }
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showDialog = false, showUnknownSourcesDialog = false, errorMessage = null) }
    }

    fun ignoreThisVersion() {
        viewModelScope.launch {
            val tag = _uiState.value.update?.tag
            updatePreferences.setIgnoredTag(tag)
            _uiState.update { it.copy(showDialog = false) }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, errorMessage = null) }

            // Auto-cleanup: Delete any previously downloaded APKs to save storage space
            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.deleteRecursively()
            }

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(updatesDir, safeName)

            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            }

            result
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    // Auto-start installation flow immediately after successful download.
                    // If unknown sources permission is missing, this will surface the settings prompt.
                    installUpdateOrRequestPermission()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = e.message ?: "Download failed"
                        )
                    }
                }
        }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(errorMessage = "Downloaded file is missing") }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }

        ApkInstaller.launchInstall(context, apkFile)
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let { intent ->
            context.startActivity(intent)
        }
    }
}
