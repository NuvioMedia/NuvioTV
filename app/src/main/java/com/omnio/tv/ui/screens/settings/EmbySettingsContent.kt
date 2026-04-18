@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.omnio.tv.ui.screens.settings

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.omnio.tv.ui.components.OmnioDialog
import com.omnio.tv.ui.theme.OmnioColors

@Composable
fun EmbySettingsContent(
    viewModel: EmbySettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()

    var showServerDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_emby_title),
            subtitle = stringResource(R.string.settings_emby_subtitle)
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
                if (credentials.isConfigured) {
                    item(key = "emby_connected_status") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_emby_connected),
                            subtitle = credentials.serverUrl,
                            enabled = false,
                            onClick = {},
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }

                    item(key = "emby_disconnect") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_emby_disconnect),
                            subtitle = stringResource(R.string.settings_emby_disconnect_subtitle),
                            onClick = { viewModel.disconnect() }
                        )
                    }
                } else {
                    item(key = "emby_server_url") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_emby_server_url),
                            subtitle = uiState.serverUrl.ifBlank { stringResource(R.string.mdblist_not_set) },
                            onClick = { showServerDialog = true },
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }

                    item(key = "emby_api_key") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_emby_api_key),
                            subtitle = maskSecret(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                            onClick = { showApiKeyDialog = true }
                        )
                    }

                    item(key = "emby_test_connection") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.testConnection() },
                                enabled = !uiState.isTesting,
                                colors = ButtonDefaults.colors(
                                    containerColor = OmnioColors.BackgroundElevated,
                                    contentColor = OmnioColors.TextPrimary,
                                    focusedContainerColor = OmnioColors.FocusBackground,
                                    focusedContentColor = OmnioColors.Primary
                                )
                            ) {
                                Text(
                                    text = if (uiState.isTesting) {
                                        stringResource(R.string.settings_emby_testing)
                                    } else {
                                        stringResource(R.string.settings_emby_test_connection)
                                    }
                                )
                            }

                            val result = uiState.testResult
                            if (!result.isNullOrBlank()) {
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (uiState.isTestSuccess) OmnioColors.Primary else OmnioColors.Error,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showServerDialog) {
        EmbyTextInputDialog(
            title = stringResource(R.string.settings_emby_server_url),
            subtitle = stringResource(R.string.settings_emby_server_url_hint),
            placeholder = stringResource(R.string.settings_emby_server_url_placeholder),
            currentValue = uiState.serverUrl,
            onSave = {
                viewModel.updateServerUrl(it)
                showServerDialog = false
            },
            onClear = {
                viewModel.updateServerUrl("")
                showServerDialog = false
            },
            onDismiss = { showServerDialog = false }
        )
    }

    if (showApiKeyDialog) {
        EmbyTextInputDialog(
            title = stringResource(R.string.settings_emby_api_key),
            subtitle = stringResource(R.string.settings_emby_api_key_hint),
            placeholder = stringResource(R.string.settings_emby_api_key_placeholder),
            currentValue = uiState.apiKey,
            isSecret = true,
            onSave = {
                viewModel.updateApiKey(it)
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.updateApiKey("")
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun EmbyTextInputDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    currentValue: String,
    isSecret: Boolean = false,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    OmnioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
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
                    visualTransformation = if (isSecret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
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
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = OmnioColors.BackgroundElevated,
                    contentColor = OmnioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value.trim()) },
                colors = ButtonDefaults.colors(
                    containerColor = OmnioColors.BackgroundCard,
                    contentColor = OmnioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

private fun maskSecret(value: String, notSetLabel: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
