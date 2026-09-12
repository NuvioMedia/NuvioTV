package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun MdbListAccountDialog(
    state: MdbListTrackerUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = rememberRawSvgPainter(R.raw.mdblist_logo, 150.dp)
    if (state.isConnected) {
        ConnectedTrackingAccountDialog(TrackingDialogBrand.MDBLIST, logo, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.MDBLIST,
                logo = logo,
                logoContentDescription = stringResource(R.string.mdblist_name),
                connectedLabel = stringResource(R.string.mdblist_connected_as, state.username ?: stringResource(R.string.mdblist_account_fallback)),
                connectedDescription = stringResource(R.string.mdblist_tracking_description),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading || state.isSyncing,
                onDisconnect = onDisconnect,
                onSync = onSync
            )
        }
    } else {
        NuvioDialog(onDismiss = onDismiss, title = "", width = 720.dp, titleTextAlign = TextAlign.Center, suppressFirstKeyUp = false) {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.mdblist_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.mdblist_name),
                logoLabel = stringResource(R.string.mdblist_name),
                qrContentDescription = stringResource(R.string.mdblist_qr_description),
                instruction = stringResource(R.string.mdblist_awaiting_instruction),
                userCode = state.session?.userCode,
                displayUrl = state.session?.verificationUri,
                qrUrl = state.session?.verificationUriComplete,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.mdblist_missing_client),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}
