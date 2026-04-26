package com.nuvio.tv.updater

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: Any? = null,
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
class UpdateViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) = Unit

    fun dismissDialog() = Unit

    fun ignoreThisVersion() = Unit

    fun downloadUpdate() = Unit

    fun installUpdateOrRequestPermission() = Unit

    fun openUnknownSourcesSettings() = Unit
}
