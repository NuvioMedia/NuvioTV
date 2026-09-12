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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val updateChannel: UpdateChannel = UpdateChannel.STABLE
)

private data class UpdateIdentity(
    val channel: UpdateChannel, val tag: String, val name: String, val url: String, val size: Long?
)
private fun AppUpdate.identity(channel: UpdateChannel) = UpdateIdentity(channel, tag, assetName, assetUrl, assetSizeBytes)
private data class DownloadAttempt(val identity: UpdateIdentity, val id: String = UUID.randomUUID().toString())
private data class DownloadedUpdate(val identity: UpdateIdentity, val file: File)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private var updateCheckJob: Job? = null
    private var checkGeneration = 0L
    private var settingsRevision = 0L
    private var bannerRevision = 0L
    private val channelPreferenceMutex = Mutex()
    private var downloadJob: Job? = null
    @Volatile private var downloadAttempt: DownloadAttempt? = null
    private var downloaded: DownloadedUpdate? = null

    init {
        val initialChannelRevision = settingsRevision
        val initialBannerRevision = bannerRevision
        viewModelScope.launch {
            val enabled = updatePreferences.updateBannerEnabled.first()
            val channel = updatePreferences.getOrInitializeUpdateChannel()
            _uiState.update { it.copy(
                updateBannerEnabled = if (initialBannerRevision == bannerRevision) enabled else it.updateBannerEnabled,
                updateChannel = if (initialChannelRevision == settingsRevision) channel else it.updateChannel
            ) }
            if (_uiState.value.updateBannerEnabled && !BuildConfig.IS_DEBUG_BUILD) checkForUpdates(false, false)
        }
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (downloadAttempt != null || (!force && !_uiState.value.updateBannerEnabled)) return
        updateCheckJob?.cancel()
        val generation = ++checkGeneration
        val channel = _uiState.value.updateChannel
        updateCheckJob = viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, feedbackMessage = null) }
            val dismissedTag = updatePreferences.ignoredTag.first()
            val result = updateRepository.getLatestUpdate(channel)
            currentCoroutineContext().ensureActive()
            if (generation != checkGeneration || channel != _uiState.value.updateChannel) return@launch
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())
            currentCoroutineContext().ensureActive()
            if (generation != checkGeneration || channel != _uiState.value.updateChannel) return@launch
            result.onSuccess { update ->
                val newer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                val identity = update.identity(channel)
                if (!newer || downloaded?.identity != identity) discardDownloaded()
                _uiState.update { state ->
                    state.copy(
                        isChecking = false, update = update.takeIf { newer }, isUpdateAvailable = newer,
                        downloadedApkPath = downloaded?.file?.absolutePath,
                        showBanner = UpdateBannerPolicy.shouldShow(newer, force, state.updateBannerEnabled, dismissedTag, update.tag),
                        showUnknownSourcesDialog = false, errorMessage = null,
                        feedbackMessage = if (showNoUpdateFeedback && !newer) noUpdateFeedback(channel) else null
                    )
                }
            }.onFailure { error ->
                // A failed metadata refresh must not discard a valid ready-to-install artifact.
                _uiState.update {
                    it.copy(isChecking = false, feedbackMessage = if (showNoUpdateFeedback) {
                        if (error is NoEligibleUpdateException) noUpdateFeedback(channel)
                        else context.getString(R.string.update_error_check_failed)
                    } else null)
                }
            }
        }
    }

    private fun noUpdateFeedback(channel: UpdateChannel): String =
        if (channel == UpdateChannel.STABLE && VersionUtils.isPrerelease(BuildConfig.VERSION_NAME)) {
            context.getString(R.string.update_waiting_for_stable)
        } else context.getString(R.string.update_latest_version)

    fun dismissBanner() {
        if (downloadAttempt != null) {
            cancelDownload()
            return
        }
        val tag = _uiState.value.update?.tag
        _uiState.update { it.copy(showBanner = false, showUnknownSourcesDialog = false, errorMessage = null) }
        if (tag != null) viewModelScope.launch { updatePreferences.setIgnoredTag(tag) }
    }

    private fun cancelDownload() {
        downloadAttempt = null
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(isDownloading = false, downloadProgress = null) }
    }

    private fun discardDownloaded() {
        downloaded?.file?.delete()
        downloaded = null
    }

    fun dismissUnknownSourcesDialog() { _uiState.update { it.copy(showUnknownSourcesDialog = false) } }
    fun consumeFeedbackMessage() { _uiState.update { it.copy(feedbackMessage = null) } }

    fun setUpdateBannerEnabled(enabled: Boolean) {
        bannerRevision++
        val changed = _uiState.value.updateBannerEnabled != enabled
        // Notification preferences must not hide Cancel for an explicitly started transfer.
        val keepTransferControls = downloadAttempt != null
        _uiState.update { it.copy(updateBannerEnabled = enabled,
            showBanner = if (enabled || keepTransferControls) it.showBanner else false,
            showUnknownSourcesDialog = if (enabled || keepTransferControls) it.showUnknownSourcesDialog else false) }
        viewModelScope.launch {
            updatePreferences.setUpdateBannerEnabled(enabled)
            if (enabled && changed && !BuildConfig.IS_DEBUG_BUILD) checkForUpdates(false, false)
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (_uiState.value.updateChannel == channel) return
        val revision = ++settingsRevision
        ++checkGeneration
        updateCheckJob?.cancel()
        cancelDownload()
        discardDownloaded()
        _uiState.update { it.copy(updateChannel = channel, isChecking = false, update = null,
            isUpdateAvailable = false, downloadedApkPath = null, showBanner = false,
            showUnknownSourcesDialog = false, errorMessage = null, feedbackMessage = null) }
        viewModelScope.launch {
            channelPreferenceMutex.withLock {
                if (revision == settingsRevision) updatePreferences.setUpdateChannel(channel)
            }
            if (revision == settingsRevision && !BuildConfig.IS_DEBUG_BUILD) checkForUpdates(true, false)
        }
    }

    fun downloadUpdate() {
        val state = _uiState.value
        val update = state.update ?: return
        if (downloadAttempt != null || downloadJob?.isActive == true) return
        if (downloaded?.identity == update.identity(state.updateChannel)) {
            installUpdateOrRequestPermission()
            return
        }
        ++checkGeneration
        updateCheckJob?.cancel()
        discardDownloaded()
        val attempt = DownloadAttempt(update.identity(state.updateChannel))
        downloadAttempt = attempt
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destination = File(File(context.cacheDir, "updates"), "${attempt.id}-$safeName")
        _uiState.update { it.copy(isChecking = false, isDownloading = true, downloadProgress = 0f,
            downloadedApkPath = null, showUnknownSourcesDialog = false, errorMessage = null, showBanner = true) }
        downloadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = apkDownloader.download(update.assetUrl, destination, update.assetSizeBytes) { count, total ->
                    _uiState.update {
                        if (downloadAttempt != attempt) it else it.copy(downloadProgress = total?.takeIf { value -> value > 0 }
                            ?.let { value -> (count.toFloat() / value).coerceIn(0f, 1f) })
                    }
                }
                if (downloadAttempt != attempt || !currentCoroutineContext().isActive) {
                    result.getOrNull()?.delete()
                    return@launch
                }
                result.onSuccess { file ->
                    downloaded = DownloadedUpdate(attempt.identity, file)
                    _uiState.update { it.copy(downloadedApkPath = file.absolutePath, errorMessage = null) }
                    installUpdateOrRequestPermission()
                }.onFailure {
                    _uiState.update { it.copy(downloadedApkPath = null,
                        errorMessage = context.getString(R.string.update_error_download_failed), showBanner = true) }
                }
            } finally {
                if (downloadAttempt == attempt) {
                    downloadAttempt = null
                    downloadJob = null
                    _uiState.update { it.copy(isDownloading = false, downloadProgress = null) }
                }
            }
        }.also { it.start() }
    }

    fun installUpdateOrRequestPermission() {
        val state = _uiState.value
        val artifact = downloaded ?: return
        if (artifact.identity != state.update?.identity(state.updateChannel)) return
        if (!artifact.file.isFile) {
            discardDownloaded()
            _uiState.update { it.copy(downloadedApkPath = null, errorMessage = context.getString(R.string.update_error_apk_missing), showBanner = true) }
            return
        }
        ApkInstaller.canRequestPackageInstalls(context).fold(
            onSuccess = { allowed ->
                if (!allowed) {
                    _uiState.update { it.copy(showUnknownSourcesDialog = true, showBanner = true) }
                } else {
                    _uiState.update { it.copy(showUnknownSourcesDialog = false, errorMessage = null) }
                    ApkInstaller.launchInstall(context, artifact.file).onFailure { showInstallerError(R.string.update_error_install_failed) }
                }
            },
            onFailure = { showInstallerError(R.string.update_error_install_failed) }
        )
    }

    private fun showInstallerError(message: Int) {
        _uiState.update { it.copy(errorMessage = context.getString(message), showBanner = true, showUnknownSourcesDialog = false) }
    }

    fun openUnknownSourcesSettings() {
        val artifact = downloaded ?: return
        if (artifact.identity != _uiState.value.update?.identity(_uiState.value.updateChannel)) return
        ApkInstaller.openUnknownSourcesSettings(context).onFailure { showInstallerError(R.string.update_error_settings_failed) }
    }
}
