package com.nuvio.tv.updater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = true
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
        viewModelScope.launch {
            val enabled = updatePreferences.updateBannerEnabled.first()
            _uiState.update { it.copy(updateBannerEnabled = enabled) }
            if (enabled && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!force && !_uiState.value.updateBannerEnabled) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isChecking = true,
                    showBanner = false,
                    showUnknownSourcesDialog = false,
                    errorMessage = null,
                    feedbackMessage = null
                )
            }

            val dismissedTag = updatePreferences.ignoredTag.first()
            val result = updateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val shouldShow = UpdateBannerPolicy.shouldShow(
                        isRemoteNewer = remoteNewer,
                        force = force,
                        bannerEnabled = _uiState.value.updateBannerEnabled,
                        dismissedTag = dismissedTag,
                        updateTag = update.tag
                    )

                    _uiState.update { state ->
                        state.copy(
                            isChecking = false,
                            update = update.takeIf { remoteNewer },
                            isUpdateAvailable = remoteNewer,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = state.downloadedApkPath.takeIf {
                                remoteNewer && state.update?.tag == update.tag
                            },
                            showBanner = shouldShow,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback && !remoteNewer) {
                                context.getString(R.string.update_latest_version)
                            } else {
                                null
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            showBanner = false,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback) {
                                error.message ?: context.getString(R.string.update_error_check_failed)
                            } else {
                                null
                            }
                        )
                    }
                }
        }
    }

    fun dismissBanner() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null
            )
        }
        val tag = state.update?.tag
        if (tag != null) {
            viewModelScope.launch {
                updatePreferences.setIgnoredTag(tag)
            }
        }
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        val changed = _uiState.value.updateBannerEnabled != enabled
        _uiState.update {
            it.copy(
                updateBannerEnabled = enabled,
                showBanner = if (enabled) it.showBanner else false,
                showUnknownSourcesDialog = if (enabled) it.showUnknownSourcesDialog else false
            )
        }
        viewModelScope.launch {
            updatePreferences.setUpdateBannerEnabled(enabled)
            if (enabled && changed && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null
                )
            }

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(File(context.cacheDir, "updates"), safeName)
            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, destination) { downloaded, total ->
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
                            downloadProgress = null,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    installUpdateOrRequestPermission()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = error.message ?: context.getString(R.string.update_error_download_failed),
                            showBanner = true
                        )
                    }
                }
        }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update {
                it.copy(
                    errorMessage = context.getString(R.string.update_error_apk_missing),
                    showBanner = true
                )
            }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true, showBanner = true) }
            return
        }

        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        ApkInstaller.launchInstall(context, apkFile)
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let(context::startActivity)
    }
}
