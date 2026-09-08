@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import com.nuvio.tv.domain.model.VpnConnectionState
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun VpnSettingsContent(
    viewModel: VpnSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfigDialog by remember { mutableStateOf(false) }

    val statusSubtitle = when (uiState.connectionState) {
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.vpn_status_disconnected)
        VpnConnectionState.CONNECTING -> stringResource(R.string.vpn_status_connecting)
        VpnConnectionState.CONNECTED -> stringResource(R.string.vpn_status_connected)
        VpnConnectionState.ERROR -> vpnErrorMessage(uiState.errorMessage)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.vpn_title),
            subtitle = stringResource(R.string.settings_vpn_subtitle)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "vpn_config") {
                        SettingsActionRow(
                            title = stringResource(R.string.vpn_config_title),
                            subtitle = stringResource(R.string.vpn_config_subtitle),
                            value = if (uiState.config.isBlank()) {
                                stringResource(R.string.vpn_not_set)
                            } else {
                                stringResource(R.string.vpn_set)
                            },
                            onClick = { showConfigDialog = true },
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                    item(key = "vpn_toggle") {
                        SettingsToggleRow(
                            title = stringResource(R.string.vpn_toggle_title),
                            subtitle = statusSubtitle,
                            checked = uiState.connectionState == VpnConnectionState.CONNECTED ||
                                uiState.connectionState == VpnConnectionState.CONNECTING,
                            onToggle = {
                                if (uiState.connectionState == VpnConnectionState.CONNECTED ||
                                    uiState.connectionState == VpnConnectionState.CONNECTING
                                ) {
                                    viewModel.disconnect()
                                } else {
                                    viewModel.connect()
                                }
                            },
                            enabled = uiState.config.isNotBlank()
                        )
                    }
                    item(key = "vpn_check_ip") {
                        val ipSubtitle = when {
                            uiState.isCheckingIp -> stringResource(R.string.vpn_checking_ip)
                            uiState.baselineIp != null && uiState.currentIp != null -> {
                                val changed = uiState.baselineIp != uiState.currentIp
                                val statusRes = if (changed) R.string.vpn_ip_changed else R.string.vpn_ip_unchanged
                                stringResource(
                                    R.string.vpn_ip_comparison,
                                    uiState.baselineIp ?: "",
                                    uiState.currentIp ?: "",
                                    stringResource(statusRes)
                                )
                            }
                            uiState.currentIp != null -> stringResource(R.string.vpn_ip_current_only, uiState.currentIp ?: "")
                            else -> stringResource(R.string.vpn_check_ip_subtitle)
                        }
                        SettingsActionRow(
                            title = stringResource(R.string.vpn_check_ip_title),
                            subtitle = ipSubtitle,
                            onClick = { viewModel.checkCurrentIp() },
                            enabled = !uiState.isCheckingIp
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showConfigDialog) {
        VpnConfigDialog(
            currentValue = uiState.config,
            onSave = { viewModel.saveConfig(it); showConfigDialog = false },
            onClear = { viewModel.saveConfig(""); showConfigDialog = false },
            onDismiss = { showConfigDialog = false }
        )
    }
}

@Composable
private fun vpnErrorMessage(code: String?): String = when (code) {
    "no_config" -> stringResource(R.string.vpn_error_no_config)
    "VPN_NOT_AUTHORIZED", "permission_denied" -> stringResource(R.string.vpn_error_permission_denied)
    "unsupported" -> stringResource(R.string.vpn_error_unsupported)
    null -> stringResource(R.string.vpn_error_generic)
    else -> stringResource(R.string.vpn_error_invalid_config)
}

@Composable
private fun VpnConfigDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(TextFieldValue(currentValue)) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val cancelButtonFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.vpn_dialog_title), subtitle = stringResource(R.string.vpn_dialog_subtitle), width = 760.dp) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
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
            Box(modifier = Modifier.padding(14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                            when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    val cursor = value.selection.end
                                    val onLastLine = !value.text.substring(cursor).contains('\n')
                                    if (onLastLine) {
                                        // Compose's directional focus search (moveFocus) can fail to
                                        // find the button row below a tall multi-line field - jump to
                                        // an explicit target instead of relying on geometric search.
                                        cancelButtonFocusRequester.requestFocus()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    val cursor = value.selection.end
                                    val onFirstLine = !value.text.substring(0, cursor).contains('\n')
                                    if (onFirstLine) {
                                        focusManager.moveFocus(FocusDirection.Up)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (value.text.isBlank()) {
                            Text(
                                text = stringResource(R.string.vpn_dialog_placeholder),
                                style = MaterialTheme.typography.bodySmall,
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
            ) { Text(stringResource(R.string.action_cancel)) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_clear)) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave(value.text) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
