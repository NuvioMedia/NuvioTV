@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun FloppyAccountDialog(
    state: FloppyTrackerUiState,
    onConnect: (address: String, token: String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state.isConnected) {
        FloppyConnectedDialog(state, onDisconnect, onDismiss)
    } else {
        FloppyConnectDialog(state, onConnect, onDismiss)
    }
}

@Composable
private fun FloppyConnectedDialog(
    state: FloppyTrackerUiState,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.floppy_name),
        subtitle = stringResource(R.string.floppy_connected_to, state.baseUrl.orEmpty()),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Text(
            text = stringResource(R.string.floppy_connected_description),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        state.error?.let { FloppyErrorText(it) }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(R.string.trakt_disconnect),
                onClick = onDisconnect,
                primary = true
            )
        }
    }
}

@Composable
private fun FloppyConnectDialog(
    state: FloppyTrackerUiState,
    onConnect: (address: String, token: String) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val addressFocusRequester = remember { FocusRequester() }
    val tokenFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.floppy_dialog_title),
        subtitle = stringResource(R.string.floppy_dialog_subtitle),
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        FloppyTextField(
            value = address,
            onValueChange = { address = it },
            placeholder = stringResource(R.string.floppy_address_placeholder),
            keyboardType = KeyboardType.Uri,
            focusRequester = addressFocusRequester,
            onDone = { tokenFocusRequester.requestFocus() },
            imeAction = ImeAction.Next
        )
        FloppyTextField(
            value = token,
            onValueChange = { token = it },
            placeholder = stringResource(R.string.floppy_token_placeholder),
            keyboardType = KeyboardType.Password,
            focusRequester = tokenFocusRequester,
            onDone = { keyboardController?.hide() },
            imeAction = ImeAction.Done
        )
        state.error?.let { FloppyErrorText(it) }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = if (state.isLoading) stringResource(R.string.tracking_status_connecting) else stringResource(R.string.floppy_connect),
                onClick = { if (!state.isLoading) onConnect(address, token) },
                primary = true
            )
        }
    }

    LaunchedEffect(Unit) {
        runCatching { addressFocusRequester.requestFocus() }
    }
}

@Composable
private fun FloppyErrorText(error: FloppyConnectionError) {
    Text(
        text = stringResource(
            when (error) {
                FloppyConnectionError.INVALID_ADDRESS -> R.string.floppy_error_address
                FloppyConnectionError.MISSING_TOKEN -> R.string.floppy_error_token
                FloppyConnectionError.REJECTED -> R.string.floppy_error_rejected
                FloppyConnectionError.UNREACHABLE -> R.string.floppy_error_unreachable
                FloppyConnectionError.NOT_FLOPPY -> R.string.floppy_error_not_floppy
                FloppyConnectionError.SAVE_FAILED -> R.string.floppy_error_save
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.Error
    )
}

/** Same focus-ring text field as the MDBList key dialog: D-pad centre opens the keyboard. */
@Composable
private fun FloppyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    imeAction: ImeAction
) {
    var isInputFocused by remember { mutableStateOf(false) }
    Card(
        onClick = { focusRequester.requestFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioTheme.colors.TextPrimary,
                    textDirection = TextDirection.Content
                ),
                cursorBrush = SolidColor(
                    if (isInputFocused) NuvioTheme.colors.Primary
                    else androidx.compose.ui.graphics.Color.Transparent
                ),
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
}
