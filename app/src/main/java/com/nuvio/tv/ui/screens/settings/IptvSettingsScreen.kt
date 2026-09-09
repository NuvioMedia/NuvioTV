@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun IptvSettingsScreen(
    viewModel: IptvSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl)
    ) {
        IptvSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun IptvSettingsContent(
    viewModel: IptvSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showEpgDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.iptv_settings_title),
            subtitle = stringResource(R.string.iptv_settings_subtitle)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_playlist_url_title),
                    subtitle = stringResource(R.string.iptv_playlist_url_subtitle),
                    value = uiState.playlistUrl.ifBlank { stringResource(R.string.iptv_not_set) },
                    onClick = { showPlaylistDialog = true },
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
                SettingsActionRow(
                    title = stringResource(R.string.iptv_epg_url_title),
                    subtitle = stringResource(R.string.iptv_epg_url_subtitle),
                    value = uiState.epgUrl.ifBlank { stringResource(R.string.iptv_not_set) },
                    onClick = { showEpgDialog = true }
                )
                SettingsActionRow(
                    title = stringResource(R.string.iptv_test_title),
                    subtitle = uiState.testResultMessage ?: stringResource(R.string.iptv_test_subtitle),
                    onClick = { viewModel.testPlaylist() },
                    enabled = !uiState.isTesting && uiState.playlistUrl.isNotBlank()
                )
            }
        }
    }

    if (showPlaylistDialog) {
        IptvUrlDialog(
            title = stringResource(R.string.iptv_playlist_url_title),
            subtitle = stringResource(R.string.iptv_playlist_url_dialog_subtitle),
            placeholder = "https://example.com/playlist.m3u8",
            currentValue = uiState.playlistUrl,
            onSave = { viewModel.savePlaylistUrl(it); showPlaylistDialog = false },
            onClear = { viewModel.savePlaylistUrl(""); showPlaylistDialog = false },
            onDismiss = { showPlaylistDialog = false }
        )
    }
    if (showEpgDialog) {
        IptvUrlDialog(
            title = stringResource(R.string.iptv_epg_url_title),
            subtitle = stringResource(R.string.iptv_epg_url_dialog_subtitle),
            placeholder = "https://example.com/epg.xml.gz",
            currentValue = uiState.epgUrl,
            onSave = { viewModel.saveEpgUrl(it); showEpgDialog = false },
            onClear = { viewModel.saveEpgUrl(""); showEpgDialog = false },
            onDismiss = { showEpgDialog = false }
        )
    }
}

@Composable
private fun IptvUrlDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val cancelButtonFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = subtitle, width = 700.dp) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onPreviewKeyEvent { event ->
                            // Must be onPreviewKeyEvent (top-down, before BasicTextField's
                            // own internal key handling consumes DPAD up/down) - a same-node
                            // onKeyEvent modifier never actually fires for these keys.
                            // Confirmed live on-device.
                            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER -> true
                                // BasicTextField consumes DPAD up/down itself (no built-in
                                // vertical-navigation meaning in a single-line field), so it
                                // never reaches Compose's default focus search - move focus
                                // explicitly instead, otherwise D-pad users get stuck unable
                                // to reach the Save button below.
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    // moveFocus's directional search is unreliable from
                                    // inside a NuvioDialog (confirmed live on-device: it
                                    // silently fails to find the button row below, even
                                    // though the same call works fine in a plain in-line
                                    // screen) - jump to an explicit target instead.
                                    cancelButtonFocusRequester.requestFocus()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
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
                modifier = Modifier.focusRequester(cancelButtonFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
