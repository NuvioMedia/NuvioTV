package com.nuvio.tv.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonGlow
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape

// androidx.tv.material3's Card/Surface/Button wire onClick exclusively through D-pad
// ENTER/CENTER key handling (SurfaceClickableUtils.tvClickable = handleDPadEnter().focusable()
// .semantics{}) - confirmed via the library's own public source: no Modifier.clickable,
// Modifier.combinedClickable, or pointerInput anywhere in that chain, in any released version.
// That means these components carry no pointer-input modifier of their own, so they are
// completely invisible to Compose's pointer hit-test tree: a tap never reaches them, and no
// touch-driven focus change or click ever happens for them, only real D-pad key presses do.
//
// There is no centralized way to patch this from outside (no CompositionLocal or callback hook
// the library reads for gesture strategy), so these thin wrappers add one, in exactly one place:
// a Modifier.pointerInput that detects a real tap, moves Compose focus to this exact instance via
// its own FocusRequester, and invokes the same onClick/onLongClick the D-pad path already uses -
// leaving 100% of the D-pad behavior, styling, and animation of the real component untouched.
// Call sites only need to import these instead of the androidx.tv.material3 originals.

private fun Modifier.tvTouchToClick(
    focusRequester: FocusRequester,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = if (!enabled) this else this.pointerInput(onClick, onLongClick) {
    detectTapGestures(
        onTap = {
            focusRequester.requestFocus()
            onClick()
        },
        onLongPress = onLongClick?.let { longClick ->
            { _: Offset ->
                focusRequester.requestFocus()
                longClick()
            }
        }
    )
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: CardShape = CardDefaults.shape(),
    colors: CardColors = CardDefaults.colors(),
    scale: CardScale = CardDefaults.scale(),
    border: CardBorder = CardDefaults.border(),
    glow: CardGlow = CardDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    androidx.tv.material3.Card(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .tvTouchToClick(focusRequester, enabled = true, onClick = onClick, onLongClick = onLongClick),
        onLongClick = onLongClick,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(),
    glow: ButtonGlow = ButtonDefaults.glow(),
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    border: ButtonBorder = ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .tvTouchToClick(focusRequester, enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        tonalElevation = tonalElevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}
