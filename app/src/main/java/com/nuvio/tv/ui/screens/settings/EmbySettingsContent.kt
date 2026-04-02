@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun EmbySettingsContent(
    viewModel: EmbySettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_emby_header),
            subtitle = stringResource(R.string.settings_emby_header_subtitle)
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
                if (authState.isConnected) {
                    item(key = "emby_connected_status") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_emby_connected),
                            subtitle = authState.serverUrl,
                            onClick = { },
                            enabled = false,
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }

                    item(key = "emby_disconnect") {
                        Button(
                            onClick = { viewModel.disconnect() },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundElevated,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_emby_disconnect))
                        }
                    }
                } else {
                    item(key = "emby_server_url") {
                        SettingsTextField(
                            value = uiState.serverUrl,
                            onValueChange = { viewModel.updateServerUrl(it) },
                            placeholder = stringResource(R.string.settings_emby_server_url),
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }

                    item(key = "emby_api_key") {
                        SettingsTextField(
                            value = uiState.apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            placeholder = stringResource(R.string.settings_emby_api_key),
                            isPassword = true
                        )
                    }

                    item(key = "emby_test_connection") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.testConnection() },
                                enabled = !uiState.isTesting,
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundElevated,
                                    contentColor = NuvioColors.TextPrimary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.Primary
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    if (uiState.isTesting) stringResource(R.string.settings_emby_testing)
                                    else stringResource(R.string.settings_emby_test_connection)
                                )
                            }

                            if (uiState.testResult != null) {
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = uiState.testResult!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (uiState.isTestSuccess) NuvioColors.Primary else NuvioColors.Error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false
) {
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        onClick = { inputFocusRequester.requestFocus() },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(SettingsPillRadius)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onKeyEvent { event ->
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { keyboardController?.hide() }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                cursorBrush = SolidColor(
                    if (isInputFocused) NuvioColors.Primary
                    else Color.Transparent
                ),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}
