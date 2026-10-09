@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components.input

import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

data class NuvioSelectOption(val label: String, val value: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NuvioSelectInput(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<NuvioSelectOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (NuvioSelectOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedOptionFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }
        var focused = requestFocus(selectedOptionFocusRequester)
        var attempt = 0
        while (!focused && attempt < 6) {
            kotlinx.coroutines.delay(32)
            focused = requestFocus(selectedOptionFocusRequester)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        kotlinx.coroutines.delay(48)
        if (requestFocus(selectedOptionFocusRequester)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { focusedOptionValue = null; onExpandedChange(false) },
            modifier = Modifier.width(with(density) { anchorSize.width.toDp() }).heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val textColor = if (isOptionFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
                val itemColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                DropdownMenuItem(
                    modifier = Modifier
                        .then(if (isSelected) Modifier.focusRequester(selectedOptionFocusRequester).bringIntoViewRequester(selectedBringIntoViewRequester) else Modifier)
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(itemColor, RoundedCornerShape(10.dp))
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = { Text(option.label, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(option); onExpandedChange(false) },
                    colors = MenuDefaults.itemColors(textColor = textColor, disabledTextColor = NuvioTheme.colors.TextDisabled)
                )
            }
        }
    }
}

private fun requestFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus() }.getOrDefault(false)
