package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.formatHexColor
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.theme.createFocusRingStyle
import com.nuvio.tv.ui.theme.toColorPalette

@Composable
internal fun CustomThemeDialog(
    initialColors: CustomThemeColors,
    onSave: (CustomThemeColors) -> Unit,
    onDismiss: () -> Unit
) {
    val editorFocusRing = remember { createFocusRingStyle(ThemeColors.White) }
    CompositionLocalProvider(LocalNuvioFocusRingStyle provides editorFocusRing) {
        NuvioDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.custom_theme_title),
            subtitle = stringResource(R.string.custom_theme_subtitle),
            width = 840.dp,
            usePlatformDefaultWidth = false,
            contentPadding = 20.dp,
            contentSpacing = 12.dp
        ) {
            CustomThemeEditor(initialColors, onSave, onDismiss)
        }
    }
}

@Composable
internal fun CustomThemeEditor(
    initialColors: CustomThemeColors,
    onSave: (CustomThemeColors) -> Unit,
    onDismiss: () -> Unit
) {
    var colors by remember { mutableStateOf(initialColors) }
    var selectedIndex by remember { mutableStateOf(0) }
    var showHexEditor by remember { mutableStateOf(false) }
    var restoreHexFocus by remember { mutableStateOf(false) }
    val firstColorFocusRequester = remember { FocusRequester() }
    val hexFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val selectedColor = colors.colors[selectedIndex]

    LaunchedEffect(Unit) { firstColorFocusRequester.requestFocusAfterFrames() }
    LaunchedEffect(showHexEditor) {
        if (!showHexEditor && restoreHexFocus) {
            restoreHexFocus = false
            hexFocusRequester.requestFocusAfterFrames()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier.weight(1.25f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.settingsOptionRow(firstColorFocusRequester),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colors.colors.forEachIndexed { index, color ->
                        ThemeColorSlot(
                            index = index,
                            color = color,
                            selected = index == selectedIndex,
                            onClick = { selectedIndex = index },
                            modifier = Modifier.weight(1f).then(
                                if (index == 0) Modifier.focusRequester(firstColorFocusRequester) else Modifier
                            )
                        )
                    }
                }

                ThemeColorPicker(
                    color = selectedColor,
                    colorIndex = selectedIndex,
                    onColorChanged = { colors = colors.withColor(selectedIndex, it) }
                )

                Card(
                    onClick = { restoreHexFocus = true; showHexEditor = true },
                    modifier = Modifier.fillMaxWidth().focusRequester(hexFocusRequester),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.Background,
                        focusedContainerColor = NuvioTheme.colors.Background
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    border = CardDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp))
                    ),
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.custom_theme_enter_hex),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatHexColor(selectedColor),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            CustomThemeSample(colors, modifier = Modifier.weight(1f).fillMaxHeight())
        }

        Row(
            modifier = Modifier.fillMaxWidth().settingsOptionRow(cancelFocusRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.custom_theme_remote_hint),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .height(36.dp)
                    .focusRequester(cancelFocusRequester)
                    .focusProperties { up = hexFocusRequester }
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = { onSave(colors) },
                modifier = Modifier.height(36.dp).focusProperties { up = hexFocusRequester },
                colors = ButtonDefaults.colors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) { Text(stringResource(R.string.custom_theme_save)) }
        }
    }

    if (showHexEditor) {
        HexColorDialog(
            initialColor = selectedColor,
            onConfirm = { colors = colors.withColor(selectedIndex, it); showHexEditor = false },
            onDismiss = { showHexEditor = false }
        )
    }
}

@Composable
private fun ThemeColorSlot(
    index: Int,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val label = stringResource(R.string.custom_theme_color_number, index + 1)
    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            contentDescription = "$label, ${formatHexColor(color)}"
        },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(22.dp).background(Color(color or 0xFF000000.toInt()), CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
private fun CustomThemeSample(colors: CustomThemeColors, modifier: Modifier = Modifier) {
    val palette = remember(colors) { colors.toColorPalette() }
    val brush = palette.accentBrush()
    val sampleShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .background(palette.background, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.custom_theme_preview),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(BorderStroke(2.dp, brush), sampleShape)
                .background(palette.backgroundElevated, sampleShape)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.custom_theme_preview_ring),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}
