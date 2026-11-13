@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.nuvio.tv.ui.theme.NuvioTheme

private const val MPV_USER_CONFIG_PLACEHOLDER = "deband=yes\nscale=spline36\ncache-secs=120"

/** Lines that set an option: not blank, not a `#` comment and not a `[profile]` header. */
internal fun mpvUserConfigOptionCount(config: String): Int =
    config.lineSequence()
        .map { it.trim() }
        .count { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") }

@Composable
internal fun MpvUserConfigDialog(
    initialConfig: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val fieldShape = RoundedCornerShape(10.dp)
    val buttonShape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playback_mpv_user_config_title),
        subtitle = stringResource(R.string.playback_mpv_user_config_dialog_sub),
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
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
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                        shape = fieldShape
                    ),
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = fieldShape
                    )
                ),
                shape = CardDefaults.shape(fieldShape),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                    BasicTextField(
                        value = config,
                        onValueChange = { config = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .focusRequester(inputFocusRequester)
                            // A multi-line field keeps D-pad focus for cursor moves; down leaves it
                            // for the buttons, and the keyboard still moves the cursor.
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                                    return@onPreviewKeyEvent false
                                }
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    runCatching { saveFocusRequester.requestFocus() }
                                }
                                true
                            }
                            .onKeyEvent { keyEvent ->
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                            },
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii
                        ),
                        textStyle = textStyle.copy(color = NuvioTheme.colors.TextPrimary),
                        cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                        decorationBox = { innerTextField ->
                            if (config.isEmpty()) {
                                Text(
                                    text = MPV_USER_CONFIG_PLACEHOLDER,
                                    style = textStyle,
                                    color = NuvioTheme.colors.TextTertiary
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
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = buttonShape
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                Button(
                    onClick = { config = "" },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = buttonShape
                ) {
                    Text(stringResource(R.string.action_clear))
                }
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                Button(
                    onClick = { onSave(config) },
                    modifier = Modifier.focusRequester(saveFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = buttonShape
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
