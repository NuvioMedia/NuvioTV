package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.util.rememberRawSvgPainter

@Composable
internal fun WeTrakrAccountDialog(
    state: WeTrakrTrackerUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val icon = rememberRawSvgPainter(R.raw.wetrakr_icon, 150.dp)
    if (state.isConnected) {
        val logo = rememberRawSvgPainter(R.raw.wetrakr_logo, 160.dp)
        ConnectedTrackingAccountDialog(TrackingDialogBrand.WETRAKR, logo, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.WETRAKR,
                logo = logo,
                logoContentDescription = stringResource(R.string.wetrakr_name),
                connectedLabel = stringResource(R.string.wetrakr_connected_as, state.username ?: stringResource(R.string.wetrakr_account_fallback)),
                connectedDescription = stringResource(R.string.wetrakr_tracking_description) + "\n" +
                    stringResource(R.string.wetrakr_attribution),
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
                providerName = stringResource(R.string.wetrakr_name),
                logo = icon,
                logoContentDescription = stringResource(R.string.wetrakr_name),
                logoLabel = stringResource(R.string.wetrakr_name),
                qrContentDescription = stringResource(R.string.wetrakr_qr_description),
                instruction = stringResource(R.string.wetrakr_awaiting_instruction),
                userCode = state.session?.userCode,
                displayUrl = state.session?.verificationUrl,
                qrUrl = state.session?.verificationUrlComplete,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.wetrakr_missing_client),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}
