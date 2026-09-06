package com.nuvio.tv.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.createFocusRingStyle

@Preview(widthDp = 960, heightDp = 540, uiMode = Configuration.UI_MODE_TYPE_TELEVISION)
@Composable
fun CustomThemeEditorPreview() {
    ThemeEditorPreviewFrame(
        title = stringResource(R.string.custom_theme_title),
        subtitle = stringResource(R.string.custom_theme_subtitle),
        width = 840.dp
    ) {
        CustomThemeEditor(CustomThemeColors.Default, onSave = {}, onDismiss = {})
    }
}

@Preview(widthDp = 960, heightDp = 540, uiMode = Configuration.UI_MODE_TYPE_TELEVISION)
@Composable
fun HexColorEditorPreview() {
    ThemeEditorPreviewFrame(
        title = stringResource(R.string.custom_theme_hex_title),
        subtitle = stringResource(R.string.custom_theme_hex_hint),
        width = 420.dp
    ) {
        HexColorEditor(0xB75AFF, onConfirm = {}, onDismiss = {})
    }
}

@Composable
private fun ThemeEditorPreviewFrame(
    title: String,
    subtitle: String,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    NuvioTheme(appTheme = AppTheme.ROSE_GOLD) {
        CompositionLocalProvider(LocalNuvioFocusRingStyle provides createFocusRingStyle(ThemeColors.White)) {
            Box(
                Modifier.fillMaxSize().background(NuvioTheme.colors.Background),
                contentAlignment = Alignment.Center
            ) {
                val shape = RoundedCornerShape(NuvioTheme.radii.xl)
                Column(
                    Modifier
                        .width(width)
                        .background(NuvioTheme.colors.BackgroundElevated, shape)
                        .border(1.dp, NuvioTheme.colors.Border, shape)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                    content()
                }
            }
        }
    }
}
