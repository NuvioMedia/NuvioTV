@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.anilist.AniListConfig
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.util.rememberRawSvgPainter

@Composable
internal fun AniListAccountDialog(
    state: AniListTrackerUiState,
    onStartConnection: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logo = rememberRawSvgPainter(R.raw.anilist_logo, 150.dp)
    if (state.isConnected) {
        ConnectedTrackingAccountDialog(TrackingDialogBrand.ANILIST, logo, onDismiss) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.ANILIST,
                logo = logo,
                logoContentDescription = stringResource(R.string.anilist_name),
                connectedLabel = stringResource(R.string.anilist_connected_as, state.username ?: stringResource(R.string.anilist_account_fallback)),
                connectedDescription = stringResource(R.string.anilist_tracking_description),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading || state.isSyncing,
                onDisconnect = onDisconnect,
                onSync = onSync,
                onVisit = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AniListConfig.WEBSITE_URL)))
                    }
                }
            )
        }
    } else {
        NuvioDialog(onDismiss = onDismiss, title = "", width = 720.dp, titleTextAlign = TextAlign.Center, suppressFirstKeyUp = false) {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.anilist_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.anilist_name),
                logoLabel = stringResource(R.string.anilist_name),
                qrContentDescription = stringResource(R.string.anilist_qr_description),
                instruction = stringResource(R.string.anilist_qr_instruction),
                userCode = state.session?.pairingCode,
                displayUrl = state.session?.displayUrl,
                qrUrl = state.session?.loginUrl,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.session != null,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.anilist_missing_client),
                onStartConnection = onStartConnection,
                onRetryPolling = onStartConnection,
                onDismiss = onDismiss
            )
        }
    }
}
