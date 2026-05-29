package com.nuvio.tv.ui.components

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: Dp = 520.dp,
    titleTextAlign: TextAlign = TextAlign.Start,
    suppressFirstKeyUp: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var isReady by remember { mutableStateOf(!suppressFirstKeyUp) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(320.dp)

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                .border(1.dp, NuvioColors.Border, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            isReady = true
                        }
                        if (!isReady) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = titleTextAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }

                content()
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
