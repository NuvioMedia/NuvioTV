@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import com.nuvio.tv.R
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_title),
            subtitle = stringResource(R.string.mdblist_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "mdblist_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_enable_title),
                        subtitle = stringResource(R.string.mdblist_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "mdblist_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_api_key_title),
                        subtitle = stringResource(R.string.mdblist_api_key_subtitle),
                        value = maskApiKey(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { showApiKeyDialog = true },
                        enabled = uiState.enabled
                    )
                }

                item(key = "mdblist_trakt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_trakt_title),
                        subtitle = stringResource(R.string.mdblist_trakt_subtitle),
                        checked = uiState.showTrakt,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTrakt(!uiState.showTrakt)) }
                    )
                }

                item(key = "mdblist_imdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_imdb_title),
                        subtitle = stringResource(R.string.mdblist_imdb_subtitle),
                        checked = uiState.showImdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleImdb(!uiState.showImdb)) }
                    )
                }

                item(key = "mdblist_tmdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tmdb_title),
                        subtitle = stringResource(R.string.mdblist_tmdb_subtitle),
                        checked = uiState.showTmdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTmdb(!uiState.showTmdb)) }
                    )
                }

                item(key = "mdblist_letterboxd") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_letterboxd_title),
                        subtitle = stringResource(R.string.mdblist_letterboxd_subtitle),
                        checked = uiState.showLetterboxd,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleLetterboxd(!uiState.showLetterboxd)) }
                    )
                }

                item(key = "mdblist_tomatoes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tomatoes_title),
                        subtitle = stringResource(R.string.mdblist_tomatoes_subtitle),
                        checked = uiState.showTomatoes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTomatoes(!uiState.showTomatoes)) }
                    )
                }

                item(key = "mdblist_audience") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_audience_title),
                        subtitle = stringResource(R.string.mdblist_audience_subtitle),
                        checked = uiState.showAudience,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleAudience(!uiState.showAudience)) }
                    )
                }

                item(key = "mdblist_metacritic") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_metacritic_title),
                        subtitle = stringResource(R.string.mdblist_metacritic_subtitle),
                        checked = uiState.showMetacritic,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleMetacritic(!uiState.showMetacritic)) }
                    )
                }
            }
        }
    }

    if (showApiKeyDialog) {
        MDBListApiKeyDialog(
            currentValue = uiState.apiKey,
            onSave = { value ->
                viewModel.onEvent(MDBListSettingsEvent.SetApiKey(value))
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.onEvent(MDBListSettingsEvent.SetApiKey(""))
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun MDBListApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.mdblist_dialog_title),
        subtitle = stringResource(R.string.mdblist_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
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
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.mdblist_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value.trim()) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
