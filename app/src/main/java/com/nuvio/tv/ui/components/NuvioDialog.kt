package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

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
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: Dp = 520.dp,
    titleTextAlign: TextAlign = TextAlign.Start,
    suppressFirstKeyUp: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var isReady by remember { mutableStateOf(!suppressFirstKeyUp) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - NuvioTheme.spacing.xxxl).coerceAtLeast(320.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth)
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(NuvioTheme.radii.xl))
                .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, RoundedCornerShape(NuvioTheme.radii.xl))
                .padding(NuvioTheme.spacing.xl)
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
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = titleTextAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
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
