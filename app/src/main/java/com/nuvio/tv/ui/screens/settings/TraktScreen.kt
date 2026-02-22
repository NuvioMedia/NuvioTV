@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.RawRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
fun TraktScreen(
    viewModel: TraktViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val primaryFocusRequester = remember { FocusRequester() }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showDaysCapDialog by remember { mutableStateOf(false) }
    val continueWatchingDayOptions = remember { listOf(14, 30, 60, 90, 180, 365) }

    BackHandler { onBackPress() }

    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = uiState.mode) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(uiState.mode) {
        primaryFocusRequester.requestFocus()
    }

    val userCode = uiState.deviceUserCode
    val qrBitmap = remember(userCode) {
        userCode?.let {
            runCatching { QrCodeGenerator.generate("https://trakt.tv/activate/$it", 420) }.getOrNull()
        }
    }
    val traktLogoPainter = rememberRawSvgPainter(R.raw.trakt_tv_favicon)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = traktLogoPainter,
                contentDescription = "Trakt Logo",
                modifier = Modifier.size(96.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Trakt",
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sync your watchlist, watch progress, continue watching, scrobbles, and personal lists with Trakt.",
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary
            )
            if (uiState.mode == TraktConnectionMode.CONNECTED) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Connected as ${uiState.username ?: "Trakt user"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF7CFF9B)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .border(1.dp, NuvioColors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .background(NuvioColors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val expiresAt = uiState.deviceCodeExpiresAtMillis
            val remaining = expiresAt?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Account Login",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Button(
                        onClick = { viewModel.onCancelDeviceFlow() },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }

            if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                Text(
                    text = "Go to trakt.tv/activate and enter this code:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
                Text(
                    text = userCode ?: "-",
                    color = NuvioColors.Primary,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Trakt activation QR",
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = "Code expires in ${formatDuration(remaining)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            } else if (uiState.mode == TraktConnectionMode.CONNECTED) {
                uiState.tokenExpiresAtMillis?.let { expiresAtMillis ->
                    Text(
                        text = "Trakt access token refreshes in ${formatDuration((expiresAtMillis - nowMillis).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
                Button(
                    onClick = { showDisconnectConfirm = true },
                    modifier = Modifier.focusRequester(primaryFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Disconnect")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TraktConnectedStatsStrip(
                    stats = uiState.connectedStats,
                    isLoading = uiState.isStatsLoading
                )
            } else {
                Text(
                    text = "Press Login to start Trakt device authentication. A QR code will appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
                Button(
                    onClick = { viewModel.onConnectClick() },
                    enabled = uiState.credentialsConfigured && !uiState.isLoading,
                    modifier = Modifier.focusRequester(primaryFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Login")
                }
                if (!uiState.credentialsConfigured) {
                    Text(
                        text = "Missing TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in local.properties.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB74D)
                    )
                }
            }

            if (uiState.mode == TraktConnectionMode.CONNECTED) {
                SettingsActionRow(
                    title = "Continue Watching Window",
                    subtitle = "Days of Trakt progress considered for continue watching",
                    value = "${uiState.continueWatchingDaysCap} days",
                    onClick = { showDaysCapDialog = true }
                )
            }

            if (uiState.mode != TraktConnectionMode.CONNECTED) {
                uiState.statusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFF6E6E)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Button(
                        onClick = { viewModel.onRetryPolling() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        Text("Retry")
                    }
                }
                Button(
                    onClick = onBackPress,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Back")
                }
            }
        }
    }

    if (showDaysCapDialog) {
        Dialog(onDismissRequest = { showDaysCapDialog = false }) {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                    .border(1.dp, NuvioColors.Border, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Continue Watching Window",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "Choose how many days of Trakt activity should appear in continue watching.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )

                continueWatchingDayOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowOptions.forEach { days ->
                            val selected = uiState.continueWatchingDaysCap == days
                            Button(
                                onClick = {
                                    viewModel.onContinueWatchingDaysCapSelected(days)
                                    showDaysCapDialog = false
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) NuvioColors.Primary else NuvioColors.BackgroundCard,
                                    contentColor = if (selected) Color.Black else NuvioColors.TextPrimary
                                )
                            ) {
                                Text("$days days")
                            }
                        }
                        if (rowOptions.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showDaysCapDialog = false },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        Dialog(onDismissRequest = { showDisconnectConfirm = false }) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                    .border(1.dp, NuvioColors.Border, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Disconnect Trakt?",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "This will disconnect your Trakt account from Nuvio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            showDisconnectConfirm = false
                            viewModel.onDisconnectClick()
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Disconnect")
                    }
                    Button(
                        onClick = { showDisconnectConfirm = false },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun TraktConnectedStatsStrip(
    stats: TraktProgressService.TraktCachedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("...", "...", "...", "...")
    } else {
        listOf(
            stats?.moviesWatched?.toString() ?: "-",
            stats?.showsWatched?.toString() ?: "-",
            stats?.episodesWatched?.toString() ?: "-",
            stats?.totalWatchedHours?.let { "${it}h" } ?: "-"
        )
    }
    val labels = listOf("Movies", "Shows", "Episodes", "Watched Hours")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Cached",
            style = MaterialTheme.typography.labelMedium,
            color = NuvioColors.TextTertiary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(values.size) { index ->
                TraktStatItem(
                    value = values[index],
                    label = labels[index],
                    modifier = Modifier.weight(1f)
                )
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(NuvioColors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
    }
}

@Composable
private fun TraktStatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val request = remember(iconRes, context) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

private fun formatDuration(valueMs: Long): String {
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
