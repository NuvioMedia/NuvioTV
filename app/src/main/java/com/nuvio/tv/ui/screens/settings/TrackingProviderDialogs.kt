@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.simkl.SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@Composable
internal fun TraktAccountDialog(
    state: TraktUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = painterResource(R.drawable.trakt_logo_wordmark)
    if (state.mode == TraktConnectionMode.CONNECTED) {
        val glyph = rememberRawSvgPainter(R.raw.trakt_tv_favicon, 150.dp)
        ConnectedTrackingAccountDialog(
            brand = TrackingDialogBrand.TRAKT,
            glyph = glyph,
            onDismiss = onDismiss
        ) {
            ConnectedTrackingAccountContent(
                brand = TrackingDialogBrand.TRAKT,
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_trakt_logo),
                connectedLabel = stringResource(
                    R.string.trakt_connected_as,
                    state.username ?: stringResource(R.string.trakt_user_fallback)
                ),
                connectedDescription = stringResource(R.string.trakt_description),
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                isLoading = state.isLoading,
                onDisconnect = onDisconnect,
                tokenRefreshText = state.tokenExpiresAtMillis?.let { expiresAt ->
                    stringResource(
                        R.string.trakt_token_refreshes,
                        formatTrackingDuration((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                    )
                },
                stats = state.connectedStats,
                isStatsLoading = state.isStatsLoading
            )
        }
    } else {
        NuvioDialog(
            onDismiss = onDismiss,
            title = "",
            width = 720.dp,
            titleTextAlign = TextAlign.Center,
            suppressFirstKeyUp = false
        ) {
            val displayUrl = state.verificationUrl ?: TRAKT_ACTIVATION_URL
            val qrUrl = state.deviceUserCode?.let { "$TRAKT_ACTIVATION_URL/$it" } ?: displayUrl
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.trakt_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_trakt_logo),
                qrContentDescription = stringResource(R.string.cd_trakt_qr),
                instruction = stringResource(R.string.trakt_awaiting_instruction),
                userCode = state.deviceUserCode,
                displayUrl = displayUrl,
                qrUrl = qrUrl,
                expiresAtEpochMs = state.deviceCodeExpiresAtMillis,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.trakt_missing_credentials),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun SimklAccountDialog(
    state: SimklSettingsUiState,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logo = rememberRawSvgPainter(R.raw.simkl_tv_wordmark, 220.dp)
    var showSyncInfo by rememberSaveable { mutableStateOf(false) }
    var browserError by rememberSaveable { mutableStateOf(false) }
    if (state.mode == SimklConnectionMode.CONNECTED) {
        val glyph = rememberRawSvgPainter(R.raw.simkl_tv_glyph, 150.dp)
        ConnectedTrackingAccountDialog(
            brand = TrackingDialogBrand.SIMKL,
            glyph = glyph,
            onDismiss = {
                if (showSyncInfo) {
                    browserError = false
                    showSyncInfo = false
                } else {
                    onDismiss()
                }
            }
        ) {
            Crossfade(
                targetState = showSyncInfo,
                animationSpec = tween(NuvioMotion.tokens.durations.fast),
                label = "SimklSyncInfo"
            ) { infoVisible ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (infoVisible) {
                        SimklSyncInfoContent(
                            logo = logo,
                            browserError = browserError,
                            onOpenGuide = {
                                browserError = false
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(SIMKL_SYNC_GUIDE_URL)
                                        )
                                    )
                                }.onFailure {
                                    browserError = true
                                }
                            },
                            onBack = {
                                browserError = false
                                showSyncInfo = false
                            }
                        )
                    } else {
                        ConnectedTrackingAccountContent(
                            brand = TrackingDialogBrand.SIMKL,
                            logo = logo,
                            logoContentDescription = stringResource(R.string.cd_simkl_logo),
                            connectedLabel = stringResource(
                                R.string.simkl_connected_as,
                                state.username ?: stringResource(R.string.simkl_user_fallback)
                            ),
                            connectedDescription = stringResource(R.string.simkl_description),
                            statusMessage = state.statusMessage,
                            errorMessage = state.errorMessage,
                            isLoading = state.isLoading,
                            onSync = onSync,
                            onDisconnect = onDisconnect,
                            onVisit = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(SIMKL_WEBSITE_URL)
                                        )
                                    )
                                }
                            },
                            onInfo = {
                                browserError = false
                                showSyncInfo = true
                            }
                        )
                    }
                }
            }
        }
    } else {
        NuvioDialog(
            onDismiss = onDismiss,
            title = "",
            width = 720.dp,
            titleTextAlign = TextAlign.Center,
            suppressFirstKeyUp = false
        ) {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.simkl_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.cd_simkl_logo),
                qrContentDescription = stringResource(R.string.cd_simkl_qr),
                instruction = stringResource(R.string.simkl_awaiting_instruction),
                userCode = state.userCode,
                displayUrl = state.verificationUri,
                qrUrl = state.verificationUri,
                expiresAtEpochMs = state.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = state.credentialsConfigured,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = stringResource(R.string.simkl_missing_credentials),
                onStartConnection = onStartConnection,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun TrackingDeviceAuthContent(
    providerName: String,
    logo: Painter,
    logoContentDescription: String,
    qrContentDescription: String,
    instruction: String,
    userCode: String?,
    displayUrl: String?,
    qrUrl: String?,
    expiresAtEpochMs: Long?,
    isLoading: Boolean,
    isPolling: Boolean,
    credentialsConfigured: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    missingCredentialsMessage: String,
    onStartConnection: () -> Unit,
    onRetryPolling: () -> Unit,
    onDismiss: () -> Unit
) {
    TrackingProviderWordmark(logo, logoContentDescription)
    when {
        isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    NuvioTheme.spacing.md,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = stringResource(R.string.tracking_connecting_provider, providerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        !userCode.isNullOrBlank() && !qrUrl.isNullOrBlank() -> {
            val qrBitmap = remember(qrUrl) {
                runCatching { QrCodeGenerator.generate(qrUrl, 420, margin = 1) }.getOrNull()
            }
            val nowMillis by produceState(
                initialValue = System.currentTimeMillis(),
                key1 = expiresAtEpochMs
            ) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000L)
                }
            }
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = qrContentDescription,
                        modifier = Modifier.size(144.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!displayUrl.isNullOrBlank()) {
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                expiresAtEpochMs?.let { expiresAt ->
                    Text(
                        text = stringResource(
                            R.string.trakt_code_expires,
                            formatTrackingDuration((expiresAt - nowMillis).coerceAtLeast(0L))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        else -> {
            Text(
                text = errorMessage ?: if (credentialsConfigured) {
                    stringResource(R.string.tracking_status_disconnected)
                } else {
                    missingCredentialsMessage
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorMessage != null || !credentialsConfigured) {
                    NuvioTheme.colors.Error
                } else {
                    NuvioTheme.colors.TextSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    val visibleStatus = errorMessage ?: statusMessage
    if (!visibleStatus.isNullOrBlank() && (!userCode.isNullOrBlank() || isLoading)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                NuvioTheme.spacing.sm,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPolling && errorMessage == null) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
            }
            Text(
                text = visibleStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (errorMessage == null) {
                    NuvioTheme.colors.TextSecondary
                } else {
                    NuvioTheme.colors.Error
                },
                textAlign = TextAlign.Center
            )
        }
    }

    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss
        )
        if (!isLoading && userCode.isNullOrBlank()) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onStartConnection,
                primary = true,
                enabled = credentialsConfigured
            )
        } else if (!isLoading && !isPolling && errorMessage != null && !userCode.isNullOrBlank()) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetryPolling,
                primary = true
            )
        }
    }
}

@Composable
private fun ConnectedTrackingAccountDialog(
    brand: TrackingDialogBrand,
    glyph: Painter,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        suppressFirstKeyUp = false,
        containerBrush = brand.cardBrush(),
        containerBorderColor = Color.White.copy(alpha = 0.2f),
        containerBorderWidth = 0.5.dp,
        containerCornerRadius = 24.dp,
        contentPadding = 20.dp,
        contentSpacing = 14.dp,
        backgroundContent = {
            Image(
                painter = glyph,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(150.dp)
                    .alpha(0.08f),
                contentScale = ContentScale.Fit
            )
        },
        content = content
    )
}

@Composable
private fun ConnectedTrackingAccountContent(
    brand: TrackingDialogBrand,
    logo: Painter,
    logoContentDescription: String,
    connectedLabel: String,
    connectedDescription: String,
    statusMessage: String?,
    errorMessage: String?,
    isLoading: Boolean,
    onDisconnect: () -> Unit,
    onSync: (() -> Unit)? = null,
    tokenRefreshText: String? = null,
    stats: TraktProgressService.TraktCachedStats? = null,
    isStatsLoading: Boolean = false,
    onVisit: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null
) {
    TrackingConnectedWordmark(
        brand = brand,
        logo = logo,
        contentDescription = logoContentDescription
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = connectedLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = connectedDescription,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.76f)
        )
        if (!tokenRefreshText.isNullOrBlank()) {
            Text(
                text = tokenRefreshText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
    if (stats != null || isStatsLoading) {
        TraktConnectedStatsStrip(stats, isStatsLoading)
    }
    if (onSync != null) {
        TrackingBrandPrimaryButton(
            label = stringResource(R.string.simkl_sync_now),
            loading = isLoading,
            enabled = !isLoading,
            onClick = onSync
        )
    }
    val visibleStatus = errorMessage ?: statusMessage
    if (!visibleStatus.isNullOrBlank()) {
        TrackingBrandMessage(
            text = visibleStatus,
            isError = errorMessage != null
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val hasMultipleActions = onVisit != null || onInfo != null
        if (onVisit != null) {
            TrackingBrandFooterButton(
                text = stringResource(R.string.simkl_visit),
                onClick = onVisit,
                modifier = if (hasMultipleActions) Modifier.weight(0.95f) else Modifier,
                fillContentWidth = hasMultipleActions
            )
        }
        TrackingBrandFooterButton(
            text = stringResource(R.string.trakt_disconnect),
            onClick = onDisconnect,
            enabled = !isLoading,
            modifier = if (hasMultipleActions) Modifier.weight(1.05f) else Modifier,
            fillContentWidth = hasMultipleActions
        )
        if (onInfo != null) {
            TrackingBrandFooterButton(
                text = stringResource(R.string.simkl_sync_info_action),
                onClick = onInfo,
                modifier = Modifier.weight(1.55f),
                fillContentWidth = true
            )
        }
    }
}

@Composable
private fun SimklSyncInfoContent(
    logo: Painter,
    browserError: Boolean,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit
) {
    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { backFocusRequester.requestFocus() }
    }

    TrackingConnectedWordmark(
        brand = TrackingDialogBrand.SIMKL,
        logo = logo,
        contentDescription = stringResource(R.string.cd_simkl_logo)
    )
    Text(
        text = stringResource(R.string.simkl_sync_info_title),
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(
            R.string.simkl_sync_info_description,
            SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f)
    )
    Text(
        text = stringResource(R.string.simkl_sync_info_activity),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f)
    )
    Text(
        text = stringResource(R.string.simkl_sync_info_manual),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f)
    )
    Text(
        text = stringResource(R.string.simkl_sync_info_library_statuses),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f)
    )
    if (browserError) {
        TrackingBrandMessage(
            text = stringResource(R.string.error_open_browser_failed),
            isError = true
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackingBrandFooterButton(
            text = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier
                .weight(0.8f)
                .focusRequester(backFocusRequester),
            fillContentWidth = true
        )
        TrackingBrandFooterButton(
            text = stringResource(R.string.simkl_sync_info_docs),
            onClick = onOpenGuide,
            modifier = Modifier.weight(1.8f),
            fillContentWidth = true
        )
    }
}

@Composable
private fun TrackingConnectedWordmark(
    brand: TrackingDialogBrand,
    logo: Painter,
    contentDescription: String
) {
    Image(
        painter = logo,
        contentDescription = contentDescription,
        modifier = when (brand) {
            TrackingDialogBrand.TRAKT -> Modifier
                .width(86.dp)
                .height(38.dp)
            TrackingDialogBrand.SIMKL -> Modifier
                .width(124.dp)
                .height(30.dp)
        },
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart
    )
}

@Composable
private fun TrackingBrandPrimaryButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White,
            contentColor = TrackingBrandButtonContent,
            focusedContentColor = TrackingBrandButtonContent,
            disabledContainerColor = Color.White.copy(alpha = 0.34f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TrackingBrandButtonContent
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TrackingBrandFooterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillContentWidth: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White.copy(alpha = 0.84f),
            focusedContentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = if (fillContentWidth) Modifier.fillMaxWidth() else Modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrackingBrandMessage(
    text: String,
    isError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isError) {
                    TrackingBrandError.copy(alpha = 0.14f)
                } else {
                    Color.White.copy(alpha = 0.1f)
                }
            )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                TrackingBrandError
            } else {
                Color.White.copy(alpha = 0.82f)
            }
        )
    }
}

@Composable
private fun TrackingProviderWordmark(
    logo: Painter,
    contentDescription: String
) {
    Image(
        painter = logo,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        contentScale = ContentScale.Fit
    )
}

private enum class TrackingDialogBrand {
    TRAKT,
    SIMKL
}

private fun TrackingDialogBrand.cardBrush(): Brush = when (this) {
    TrackingDialogBrand.TRAKT -> Brush.linearGradient(
        colors = listOf(Color(0xFF7D279B), Color(0xFFD61F56), Color(0xFFF22125))
    )
    TrackingDialogBrand.SIMKL -> Brush.linearGradient(
        colors = listOf(Color(0xFF050505), Color(0xFF292929), Color(0xFF111111))
    )
}

@Composable
private fun TraktConnectedStatsStrip(
    stats: TraktProgressService.TraktCachedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("…", "…", "…", "…")
    } else {
        listOf(
            stats?.moviesWatched?.toString() ?: "-",
            stats?.showsWatched?.toString() ?: "-",
            stats?.episodesWatched?.toString() ?: "-",
            stats?.totalWatchedHours?.let { "${it}h" } ?: "-"
        )
    }
    val labels = listOf(
        stringResource(R.string.trakt_stat_movies),
        stringResource(R.string.trakt_stat_shows),
        stringResource(R.string.trakt_stat_episodes),
        stringResource(R.string.trakt_stat_watched_hours)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.trakt_cached_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(Color.White.copy(alpha = 0.18f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            values.forEachIndexed { index, value ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = labels[index],
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.76f),
                        textAlign = TextAlign.Center
                    )
                }
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(NuvioTheme.spacing.hairline)
                            .height(44.dp)
                            .background(Color.White.copy(alpha = 0.18f))
                    )
                }
            }
        }
    }
}

internal fun formatTrackingDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000L).coerceAtLeast(0L)
    val days = TimeUnit.SECONDS.toDays(totalSeconds)
    val hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private const val TRAKT_ACTIVATION_URL = "https://trakt.tv/activate"
private const val SIMKL_WEBSITE_URL = "https://simkl.com"
private const val SIMKL_SYNC_GUIDE_URL = "https://api.simkl.org/guides/sync"
private val TrackingBrandButtonContent = Color(0xFF171717)
private val TrackingBrandError = Color(0xFFFFDAD6)
