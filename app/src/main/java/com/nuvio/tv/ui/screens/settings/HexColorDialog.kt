package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.formatHexColor
import com.nuvio.tv.domain.model.parseHexColor
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun HexColorDialog(
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.custom_theme_hex_title),
        subtitle = stringResource(R.string.custom_theme_hex_hint),
        width = 420.dp,
        contentPadding = 20.dp,
        contentSpacing = 12.dp
    ) {
        HexColorEditor(initialColor, onConfirm, onDismiss)
    }
}

@Composable
internal fun HexColorEditor(
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var digits by remember { mutableStateOf(formatHexColor(initialColor).removePrefix("#")) }
    var replaceOnInput by remember { mutableStateOf(true) }
    val firstKeyFocusRequester = remember { FocusRequester() }
    val clearFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val color = parseHexColor(digits)

    LaunchedEffect(Unit) { firstKeyFocusRequester.requestFocusAfterFrames() }

    fun enter(character: Char) {
        if (replaceOnInput) {
            digits = character.toString()
            replaceOnInput = false
        } else if (digits.length < 6) {
            digits += character
        }
    }

    fun delete() {
        digits = if (replaceOnInput) "" else digits.dropLast(1)
        replaceOnInput = false
    }

    Column(
        modifier = Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.isCtrlPressed || native.isAltPressed || native.isMetaPressed) return@onPreviewKeyEvent false
            val character = native.unicodeChar.toChar().uppercaseChar()
            when {
                character in "0123456789ABCDEF" -> {
                    if (native.action == KeyEvent.ACTION_DOWN) enter(character)
                    true
                }
                native.keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (native.action == KeyEvent.ACTION_DOWN) delete()
                    true
                }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NuvioTheme.colors.Background, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(Color((color ?: initialColor) or 0xFF000000.toInt()), RoundedCornerShape(8.dp))
            )
            Text(
                text = "#" + digits.padEnd(6, '–'),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.background(
                    if (replaceOnInput) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
            )
        }

        Column(
            modifier = Modifier.settingsOptionRow(firstKeyFocusRequester),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            "0123456789ABCDEF".chunked(4).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { columnIndex, character ->
                        Button(
                            onClick = { enter(character) },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .then(
                                    if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(firstKeyFocusRequester)
                                    else Modifier
                                )
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(character.toString(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.settingsOptionRow(clearFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { digits = ""; replaceOnInput = false },
                modifier = Modifier.weight(1f).height(36.dp).focusRequester(clearFocusRequester)
            ) { Text(stringResource(R.string.custom_theme_hex_clear)) }
            Button(onClick = ::delete, modifier = Modifier.weight(1f).height(36.dp)) {
                Text(stringResource(R.string.custom_theme_hex_delete))
            }
        }
        Row(
            modifier = Modifier.settingsOptionRow(cancelFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(36.dp).focusRequester(cancelFocusRequester)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = { color?.let(onConfirm) },
                enabled = color != null,
                modifier = Modifier.weight(1f).height(36.dp),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) { Text(stringResource(R.string.custom_theme_use_color)) }
        }
    }
}
