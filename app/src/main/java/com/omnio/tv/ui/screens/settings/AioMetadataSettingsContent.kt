@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.omnio.tv.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.omnio.tv.R
import com.omnio.tv.domain.model.AioMetadataProvider
import com.omnio.tv.ui.components.OmnioDialog
import com.omnio.tv.ui.theme.OmnioColors

@Composable
fun AioMetadataSettingsContent(
    viewModel: AioMetadataSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keyDialogProvider by remember { mutableStateOf<AioMetadataProvider?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.aio_metadata_title),
            subtitle = stringResource(R.string.aio_metadata_subtitle)
        )

        if (uiState.isPrimaryProfileBlocked) {
            PrimaryProfileBlockedBanner()
        }

        uiState.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = { viewModel.consumeError() })
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "aio_enable") {
                    SettingsToggleRow(
                        title = stringResource(R.string.aio_metadata_enable_title),
                        subtitle = stringResource(R.string.aio_metadata_enable_subtitle),
                        checked = uiState.enabled,
                        enabled = !uiState.isPrimaryProfileBlocked && !uiState.isMutating,
                        onToggle = { viewModel.onToggleEnabled() },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                if (uiState.hasConfig && uiState.manifestUrl.isNotBlank()) {
                    item(key = "aio_manifest_url") {
                        SettingsActionRow(
                            title = stringResource(R.string.aio_metadata_manifest_url_title),
                            subtitle = stringResource(R.string.aio_metadata_manifest_url_subtitle),
                            value = shortenUrl(uiState.manifestUrl),
                            onClick = { copyToClipboard(context, uiState.manifestUrl) }
                        )
                    }
                }

                if (uiState.configureUrl.isNotBlank()) {
                    item(key = "aio_manage_on_web") {
                        SettingsActionRow(
                            title = stringResource(R.string.aio_metadata_manage_on_web_title),
                            subtitle = stringResource(R.string.aio_metadata_manage_on_web_subtitle),
                            value = shortenUrl(uiState.configureUrl),
                            onClick = { copyToClipboard(context, uiState.configureUrl) }
                        )
                    }
                }

                items(
                    items = AioMetadataSettingsViewModel.KNOWN_PROVIDERS,
                    key = { provider -> "aio_provider_${provider.key}" }
                ) { provider ->
                    ProviderRow(
                        provider = provider,
                        enabled = uiState.providers[provider.key] ?: false,
                        keyValue = uiState.providerKeys[provider.key].orEmpty(),
                        isMutating = uiState.isMutating,
                        interactive = !uiState.isPrimaryProfileBlocked,
                        onToggle = { next ->
                            viewModel.onProviderEnabledChanged(provider.key, next)
                        },
                        onEditKey = { keyDialogProvider = provider }
                    )
                }
            }
        }
    }

    val dialogProvider = keyDialogProvider
    if (dialogProvider != null) {
        AioKeyInputDialog(
            provider = dialogProvider,
            currentValue = uiState.providerKeys[dialogProvider.key].orEmpty(),
            onSave = { value ->
                viewModel.onProviderKeyChanged(dialogProvider.key, value)
                keyDialogProvider = null
            },
            onClear = {
                viewModel.onProviderKeyChanged(dialogProvider.key, "")
                keyDialogProvider = null
            },
            onDismiss = { keyDialogProvider = null }
        )
    }
}

@Composable
private fun ProviderRow(
    provider: AioMetadataProvider,
    enabled: Boolean,
    keyValue: String,
    isMutating: Boolean,
    interactive: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditKey: () -> Unit,
) {
    val providerLabel = providerDisplayName(provider)
    val notSetLabel = stringResource(R.string.aio_metadata_not_set)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsToggleRow(
            title = providerLabel,
            subtitle = stringResource(R.string.aio_metadata_provider_enable_suffix),
            checked = enabled,
            enabled = interactive && !isMutating,
            onToggle = { onToggle(!enabled) }
        )
        if (provider.requiresApiKey) {
            SettingsActionRow(
                title = stringResource(keyLabelRes(provider)),
                subtitle = maskApiKey(keyValue, notSetLabel),
                enabled = interactive && !isMutating,
                onClick = onEditKey
            )
        }
    }
}

@Composable
private fun PrimaryProfileBlockedBanner() {
    Text(
        text = stringResource(R.string.aio_metadata_primary_profile_blocked),
        style = MaterialTheme.typography.bodyMedium,
        color = OmnioColors.Error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = OmnioColors.Error,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.colors(
                containerColor = OmnioColors.BackgroundElevated,
                contentColor = OmnioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun providerDisplayName(provider: AioMetadataProvider): String = when (provider) {
    AioMetadataProvider.TMDB -> "TMDB"
    AioMetadataProvider.TVDB -> "TVDB"
    AioMetadataProvider.FANART -> "Fanart.tv"
    AioMetadataProvider.MAL -> "MyAnimeList"
    AioMetadataProvider.ANILIST -> "AniList"
    AioMetadataProvider.KITSU -> "Kitsu"
}

private fun keyLabelRes(provider: AioMetadataProvider): Int = when (provider) {
    AioMetadataProvider.TMDB -> R.string.aio_metadata_provider_tmdb
    AioMetadataProvider.TVDB -> R.string.aio_metadata_provider_tvdb
    AioMetadataProvider.FANART -> R.string.aio_metadata_provider_fanart
    AioMetadataProvider.MAL -> R.string.aio_metadata_provider_mal
    AioMetadataProvider.ANILIST -> R.string.aio_metadata_provider_anilist
    AioMetadataProvider.KITSU -> R.string.aio_metadata_provider_kitsu
}

private fun maskApiKey(raw: String, notSetLabel: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}

private fun shortenUrl(url: String): String {
    if (url.length <= 48) return url
    return url.take(24) + "…" + url.takeLast(20)
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("AIOMetadata", value))
}

@Composable
private fun AioKeyInputDialog(
    provider: AioMetadataProvider,
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { runCatching { inputFocusRequester.requestFocus() } }

    OmnioDialog(
        onDismiss = onDismiss,
        title = stringResource(keyLabelRes(provider)),
        subtitle = stringResource(R.string.aio_metadata_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = OmnioColors.BackgroundElevated,
                focusedContainerColor = OmnioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, OmnioColors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, OmnioColors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = OmnioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) OmnioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.aio_metadata_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OmnioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = OmnioColors.BackgroundElevated,
                    contentColor = OmnioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.aio_metadata_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = OmnioColors.BackgroundElevated,
                    contentColor = OmnioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.aio_metadata_clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value.trim()) },
                colors = ButtonDefaults.colors(
                    containerColor = OmnioColors.BackgroundCard,
                    contentColor = OmnioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.aio_metadata_save))
            }
        }
    }
}
