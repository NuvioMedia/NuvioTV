@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.annotation.RawRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.components.NuvioDialog
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
    var showCommentsDialog by remember { mutableStateOf(false) }
    var showWatchProgressDialog by remember { mutableStateOf(false) }
    var showLibrarySourceDialog by remember { mutableStateOf(false) }
    var showMoreLikeThisSourceDialog by remember { mutableStateOf(false) }
    val strAllHistory = stringResource(R.string.trakt_all_history)
    val strDaysFormat = stringResource(R.string.trakt_days_format)
    val strWatchProgressTrakt = stringResource(R.string.trakt_watch_progress_source_trakt)
    val strWatchProgressNuvio = stringResource(R.string.trakt_watch_progress_source_nuvio)
    val strSettingOn = stringResource(R.string.trakt_setting_on)
    val strSettingOff = stringResource(R.string.trakt_setting_off)
    val cwWindowFormatter: (Int) -> String = { days ->
        formatContinueWatchingWindow(days, strAllHistory) { strDaysFormat.format(it) }
    }
    val watchProgressFormatter: (WatchProgressSource) -> String = { source ->
        when (source) {
            WatchProgressSource.TRAKT -> strWatchProgressTrakt
            WatchProgressSource.NUVIO_SYNC -> strWatchProgressNuvio
        }
    }
    val strLibrarySourceTrakt = stringResource(R.string.trakt_library_source_trakt)
    val strLibrarySourceNuvio = stringResource(R.string.trakt_library_source_nuvio)
    val librarySourceFormatter: (LibrarySourceMode) -> String = { mode ->
        when (mode) {
            LibrarySourceMode.TRAKT -> strLibrarySourceTrakt
            LibrarySourceMode.LOCAL -> strLibrarySourceNuvio
        }
    }
    val enabledFormatter: (Boolean) -> String = { enabled ->
        if (enabled) strSettingOn else strSettingOff
    }
    val strMoreLikeThisTrakt = stringResource(R.string.trakt_more_like_this_source_trakt)
    val strMoreLikeThisTmdb = stringResource(R.string.trakt_more_like_this_source_tmdb)
    val moreLikeThisSourceFormatter: (MoreLikeThisSourcePreference) -> String = { source ->
        when (source) {
            MoreLikeThisSourcePreference.TRAKT -> strMoreLikeThisTrakt
            MoreLikeThisSourcePreference.TMDB -> strMoreLikeThisTmdb
        }
    }
    val continueWatchingDayOptions = remember {
        listOf(
            14,
            30,
            60,
            90,
            180,
            365,
            TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL
        )
    }

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
    val traktLogoSize = 96.dp
    val traktLogoPainter = rememberRawSvgPainter(R.raw.trakt_tv_favicon, traktLogoSize)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = 28.dp),
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
                contentDescription = stringResource(R.string.cd_trakt_logo),
                modifier = Modifier.size(traktLogoSize),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            Text(
                text = "Trakt",
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.trakt_description),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            if (uiState.mode == TraktConnectionMode.CONNECTED) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                Text(
                    text = stringResource(R.string.trakt_connected_as, uiState.username ?: stringResource(R.string.trakt_user_fallback)),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF7CFF9B)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            val expiresAt = uiState.deviceCodeExpiresAtMillis
            val remaining = expiresAt?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L
            val contentScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.trakt_account_login),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                        Button(
                            onClick = { viewModel.onCancelDeviceFlow() },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }

                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Text(
                        text = stringResource(R.string.trakt_awaiting_instruction),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = userCode ?: "-",
                        color = NuvioTheme.colors.Primary,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_trakt_qr),
                            modifier = Modifier.size(180.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(
                        text = stringResource(R.string.trakt_code_expires, formatDuration(remaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                } else if (uiState.mode == TraktConnectionMode.CONNECTED) {
                    uiState.tokenExpiresAtMillis?.let { expiresAtMillis ->
                        Text(
                            text = stringResource(R.string.trakt_token_refreshes, formatDuration((expiresAtMillis - nowMillis).coerceAtLeast(0L))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                    Button(
                        onClick = { showDisconnectConfirm = true },
                        modifier = Modifier.focusRequester(primaryFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.trakt_disconnect))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TraktConnectedStatsStrip(
                        stats = uiState.connectedStats,
                        isLoading = uiState.isStatsLoading
                    )
                } else {
                    Text(
                        text = stringResource(R.string.trakt_login_instruction),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Button(
                        onClick = { viewModel.onConnectClick() },
                        enabled = uiState.credentialsConfigured && !uiState.isLoading,
                        modifier = Modifier.focusRequester(primaryFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(stringResource(R.string.trakt_login))
                    }
                    if (!uiState.credentialsConfigured) {
                        Text(
                            text = stringResource(R.string.trakt_missing_credentials),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFB74D)
                        )
                    }
                }

                if (uiState.mode == TraktConnectionMode.CONNECTED) {
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_library_source_title),
                        subtitle = stringResource(R.string.trakt_library_source_subtitle),
                        value = librarySourceFormatter(uiState.librarySourceMode),
                        onClick = { showLibrarySourceDialog = true }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_watch_progress_title),
                        subtitle = stringResource(R.string.trakt_watch_progress_subtitle),
                        value = watchProgressFormatter(uiState.watchProgressSource),
                        onClick = { showWatchProgressDialog = true }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_continue_watching_window),
                        subtitle = stringResource(R.string.trakt_continue_watching_subtitle),
                        value = cwWindowFormatter(uiState.continueWatchingDaysCap),
                        onClick = { showDaysCapDialog = true }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_comments_title),
                        subtitle = stringResource(R.string.trakt_comments_subtitle),
                        value = enabledFormatter(uiState.showMetaComments),
                        onClick = { showCommentsDialog = true }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_more_like_this_source_title),
                        subtitle = stringResource(R.string.trakt_more_like_this_source_subtitle),
                        value = moreLikeThisSourceFormatter(uiState.moreLikeThisSource),
                        onClick = { showMoreLikeThisSourceDialog = true }
                    )
                }

                if (uiState.mode != TraktConnectionMode.CONNECTED) {
                    uiState.statusMessage?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
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
            }
            SettingsVerticalScrollIndicators(state = contentScrollState)
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Button(
                        onClick = { viewModel.onRetryPolling() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        Text(stringResource(R.string.trakt_retry))
                    }
                }
                Button(
                    onClick = onBackPress,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.trakt_back))
                }
            }
        }
    }

    if (showWatchProgressDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_watch_progress_dialog_title),
            subtitle = stringResource(R.string.trakt_watch_progress_dialog_subtitle),
            options = listOf(
                SettingsPickerOption(WatchProgressSource.TRAKT, stringResource(R.string.trakt_watch_progress_source_trakt)),
                SettingsPickerOption(WatchProgressSource.NUVIO_SYNC, stringResource(R.string.trakt_watch_progress_source_nuvio))
            ),
            selectedValue = uiState.watchProgressSource,
            onOptionSelected = { source ->
                viewModel.onWatchProgressSourceSelected(source)
                showWatchProgressDialog = false
            },
            onDismiss = { showWatchProgressDialog = false },
            width = 620.dp,
            maxHeight = 320.dp
        )
    }

    if (showLibrarySourceDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_library_source_dialog_title),
            subtitle = stringResource(R.string.trakt_library_source_dialog_subtitle),
            options = listOf(
                SettingsPickerOption(LibrarySourceMode.TRAKT, stringResource(R.string.trakt_library_source_trakt)),
                SettingsPickerOption(LibrarySourceMode.LOCAL, stringResource(R.string.trakt_library_source_nuvio))
            ),
            selectedValue = uiState.librarySourceMode,
            onOptionSelected = { mode ->
                viewModel.onLibrarySourceModeSelected(mode)
                showLibrarySourceDialog = false
            },
            onDismiss = { showLibrarySourceDialog = false },
            width = 620.dp,
            maxHeight = 320.dp
        )
    }

    if (showDaysCapDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_cw_window_title),
            subtitle = stringResource(R.string.trakt_cw_window_subtitle),
            options = continueWatchingDayOptions.map { days ->
                SettingsPickerOption(days, cwWindowFormatter(days))
            },
            selectedValue = uiState.continueWatchingDaysCap,
            onOptionSelected = { days ->
                viewModel.onContinueWatchingDaysCapSelected(days)
                showDaysCapDialog = false
            },
            onDismiss = { showDaysCapDialog = false },
            width = 620.dp,
            maxHeight = 360.dp
        )
    }

    if (showCommentsDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_comments_dialog_title),
            subtitle = stringResource(R.string.trakt_comments_dialog_subtitle),
            options = listOf(
                SettingsPickerOption(true, stringResource(R.string.trakt_setting_on)),
                SettingsPickerOption(false, stringResource(R.string.trakt_setting_off))
            ),
            selectedValue = uiState.showMetaComments,
            onOptionSelected = { show ->
                viewModel.onShowMetaCommentsChanged(show)
                showCommentsDialog = false
            },
            onDismiss = { showCommentsDialog = false },
            width = 620.dp,
            maxHeight = 320.dp
        )
    }

    if (showMoreLikeThisSourceDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_more_like_this_source_dialog_title),
            subtitle = stringResource(R.string.trakt_more_like_this_source_dialog_subtitle),
            options = listOf(
                SettingsPickerOption(MoreLikeThisSourcePreference.TRAKT, stringResource(R.string.trakt_more_like_this_source_trakt)),
                SettingsPickerOption(MoreLikeThisSourcePreference.TMDB, stringResource(R.string.trakt_more_like_this_source_tmdb))
            ),
            selectedValue = uiState.moreLikeThisSource,
            onOptionSelected = { source ->
                viewModel.onMoreLikeThisSourceSelected(source)
                showMoreLikeThisSourceDialog = false
            },
            onDismiss = { showMoreLikeThisSourceDialog = false },
            width = 620.dp,
            maxHeight = 320.dp
        )
    }

    if (showDisconnectConfirm) {
        NuvioDialog(
            onDismiss = { showDisconnectConfirm = false },
            title = stringResource(R.string.trakt_disconnect_title),
            subtitle = stringResource(R.string.trakt_disconnect_subtitle),
            width = 520.dp,
            suppressFirstKeyUp = false
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        viewModel.onDisconnectClick()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.trakt_disconnect))
                }
                Button(
                    onClick = { showDisconnectConfirm = false },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
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
    val labels = listOf(
        stringResource(R.string.trakt_stat_movies),
        stringResource(R.string.trakt_stat_shows),
        stringResource(R.string.trakt_stat_episodes),
        stringResource(R.string.trakt_stat_watched_hours)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.trakt_cached_label),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
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
                            .width(NuvioTheme.spacing.hairline)
                            .height(44.dp)
                            .background(NuvioTheme.colors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
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
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes iconRes: Int, targetSize: Dp): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { targetSize.roundToPx() }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
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

private fun formatContinueWatchingWindow(days: Int, allHistoryLabel: String, daysFormat: (Int) -> String): String {
    return if (days == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
        allHistoryLabel
    } else {
        daysFormat(days)
    }
}
