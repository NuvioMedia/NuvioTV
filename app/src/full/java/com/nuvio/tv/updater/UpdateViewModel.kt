package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.Job
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
    val updateBannerEnabled: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val autoInstallEnabled: Boolean = false,
    val autoInstallSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val autoInstallNoticeEnabled: Boolean = true,
    /** Release installed automatically since the last launch, shown in the banner until dismissed. */
    val installedUpdate: AppUpdate? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val downloadStore: UpdateDownloadStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null

    init {
        viewModelScope.launch {
            val enabled = updatePreferences.updateBannerEnabled.first()
            val channel = updatePreferences.getOrInitializeUpdateChannel()
            val autoInstallEnabled = updatePreferences.autoInstallEnabled.first()
            val autoInstallNoticeEnabled = updatePreferences.autoInstallNoticeEnabled.first()
            _uiState.update {
                it.copy(
                    updateBannerEnabled = enabled,
                    updateChannel = channel,
                    autoInstallEnabled = autoInstallEnabled,
                    autoInstallNoticeEnabled = autoInstallNoticeEnabled
                )
            }
            downloadStore.clearIfInstalled()
            consumeAutoInstalledUpdate()
            syncBackgroundJobs()
            if (enabled && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!force && !_uiState.value.updateBannerEnabled) return

        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val channel = _uiState.value.updateChannel
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
            val result = updateRepository.getLatestUpdate(channel)
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val downloadedFile = if (remoteNewer) downloadStore.downloadedFileFor(update) else null
                    // The release just installed carries the notes the "updated" banner can show.
                    val installedUpdate = _uiState.value.installedUpdate?.let { installed ->
                        if (installed.tag == update.tag) update else installed
                    }
                    if (remoteNewer && downloadedFile == null && update.tag != dismissedTag && !BuildConfig.IS_DEBUG_BUILD) {
                        UpdateJobService.scheduleCheckNow(context)
                    }
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
                            downloadedApkPath = downloadedFile?.absolutePath
                                ?: state.downloadedApkPath.takeIf { remoteNewer && state.update?.tag == update.tag },
                            showBanner = shouldShow || installedUpdate != null,
                            installedUpdate = installedUpdate,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback && !remoteNewer) {
                                noUpdateFeedback(channel)
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
                            showBanner = it.installedUpdate != null,
                            showUnknownSourcesDialog = false,
                            errorMessage = null,
                            feedbackMessage = if (showNoUpdateFeedback) {
                                if (error is NoEligibleUpdateException) {
                                    noUpdateFeedback(channel)
                                } else {
                                    error.message ?: context.getString(R.string.update_error_check_failed)
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String =
        if (channel == UpdateChannel.STABLE && VersionUtils.isPrerelease(BuildConfig.VERSION_NAME)) {
            context.getString(R.string.update_waiting_for_stable)
        } else {
            context.getString(R.string.update_latest_version)
        }

    fun dismissBanner() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                installedUpdate = null
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
            syncBackgroundJobs()
            if (enabled && changed && !BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = false, showNoUpdateFeedback = false)
            }
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        val state = _uiState.value
        if (state.updateChannel == channel) return

        updateCheckJob?.cancel()
        UpdateJobService.cancelIdleInstall(context)
        _uiState.update {
            it.copy(
                updateChannel = channel,
                isChecking = false,
                update = null,
                isUpdateAvailable = false,
                isDownloading = false,
                downloadProgress = null,
                downloadedApkPath = null,
                showBanner = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
                feedbackMessage = null
            )
        }
        viewModelScope.launch {
            downloadStore.clear()
            updatePreferences.setUpdateChannel(channel)
            if (!BuildConfig.IS_DEBUG_BUILD) {
                checkForUpdates(force = true, showNoUpdateFeedback = false)
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

            val result = withContext(Dispatchers.IO) {
                downloadStore.download(update) { downloaded, total ->
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ApkInstaller.install(context, apkFile, background = false) }
                .onFailure { ApkInstaller.launchSystemInstaller(context, apkFile) }
        }
    }

    // Shows the "updated" banner once after the idle job installed a release, then forgets it.
    private suspend fun consumeAutoInstalledUpdate() {
        val tag = updatePreferences.autoInstalledTag.first() ?: return
        // Still older than the installed version means the install has not happened yet.
        if (VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return
        updatePreferences.setAutoInstalledTag(null)
        if (VersionUtils.isRemoteNewer(BuildConfig.VERSION_NAME, tag)) return
        if (!_uiState.value.autoInstallNoticeEnabled) return
        val installed = AppUpdate(
            tag = tag,
            title = tag,
            notes = "",
            releaseUrl = null,
            assetName = "",
            assetUrl = "",
            assetSizeBytes = null
        )
        _uiState.update { it.copy(installedUpdate = installed, showBanner = true) }
    }

    fun setAutoInstallNoticeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallNoticeEnabled = enabled) }
        viewModelScope.launch { updatePreferences.setAutoInstallNoticeEnabled(enabled) }
    }

    fun setAutoInstallEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoInstallEnabled = enabled) }
        viewModelScope.launch {
            updatePreferences.setAutoInstallEnabled(enabled)
            syncBackgroundJobs()
            if (enabled && downloadStore.pendingInstall() != null) {
                UpdateJobService.scheduleIdleInstall(context)
            }
        }
    }

    // Background checks run while the banner or automatic installs are on; the idle install job only with the latter.
    private fun syncBackgroundJobs() {
        if (BuildConfig.IS_DEBUG_BUILD) return
        val state = _uiState.value
        if (state.updateBannerEnabled || state.autoInstallEnabled) {
            UpdateJobService.schedulePeriodicCheck(context)
        } else {
            UpdateJobService.cancelPeriodicCheck(context)
        }
        if (!state.autoInstallEnabled) UpdateJobService.cancelIdleInstall(context)
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let(context::startActivity)
    }
}
