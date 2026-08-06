package com.nuvio.tv.updater

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: Any? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = false
)

@HiltViewModel
class UpdateViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) = Unit

    fun dismissBanner() = Unit

    fun dismissUnknownSourcesDialog() = Unit

    fun consumeFeedbackMessage() = Unit

    fun setUpdateBannerEnabled(enabled: Boolean) = Unit

    fun downloadUpdate() = Unit

    fun installUpdateOrRequestPermission() = Unit

    fun openUnknownSourcesSettings() = Unit
}
