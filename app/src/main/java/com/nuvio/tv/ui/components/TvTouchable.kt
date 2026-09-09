package com.nuvio.tv.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
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
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.Glow
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.SurfaceColors
import androidx.tv.material3.SurfaceDefaults

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

// Exposed (not private) so call sites that use a library composable we can't drop-in-replace -
// e.g. Tab, which calls androidx.tv.material3.Surface's selectable overload internally and so
// isn't reachable through the Card/Button/Surface/IconButton wrappers above - can apply the same
// tap-to-focus-then-click bridge directly on their own Modifier chain.
//
// Keyed on `enabled`/whether onLongClick is present (both structural, rarely-changing signals)
// rather than on the onClick/onLongClick lambda identity - many call sites pass a fresh lambda
// literal every recomposition, and keying pointerInput on that would restart the gesture
// detector coroutine constantly, risking a real in-flight tap being dropped mid-gesture by an
// an unrelated recomposition. rememberUpdatedState keeps onTap/onLongPress reading the latest
// lambda regardless, the same pattern Compose's own Modifier.clickable uses internally.
@Composable
internal fun Modifier.tvTouchToClick(
    focusRequester: FocusRequester,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val hasLongClick = onLongClick != null
    if (!enabled) return this
    return this.pointerInput(hasLongClick) {
        detectTapGestures(
            onTap = {
                // The composable that owns this FocusRequester can leave composition between a
                // tap's down and up (e.g. scrolled out of a LazyRow and recycled) - requestFocus
                // throws IllegalStateException if that already happened, which must not crash
                // the whole screen over a focus nicety when the click itself can still fire.
                runCatching { focusRequester.requestFocus() }
                currentOnClick()
            },
            onLongPress = if (hasLongClick) {
                {
                    runCatching { focusRequester.requestFocus() }
                    currentOnLongClick?.invoke()
                }
            } else null
        )
    }
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

// Non-interactive Surface has no onClick and is never part of the D-pad-only click gap - plain
// passthrough so files that use both Surface overloads only need one import.
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shape: Shape = SurfaceDefaults.shape,
    colors: SurfaceColors = SurfaceDefaults.colors(),
    border: Border = Border.None,
    glow: Glow = Glow.None,
    content: @Composable (BoxScope.() -> Unit),
) {
    androidx.tv.material3.Surface(
        modifier = modifier,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        border = border,
        glow = glow,
        content = content
    )
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (BoxScope.() -> Unit),
) {
    val focusRequester = remember { FocusRequester() }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .tvTouchToClick(focusRequester, enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        onLongClick = onLongClick,
        enabled = enabled,
        tonalElevation = tonalElevation,
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
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = IconButtonDefaults.scale(),
    glow: ButtonGlow = IconButtonDefaults.glow(),
    shape: ButtonShape = IconButtonDefaults.shape(),
    colors: ButtonColors = IconButtonDefaults.colors(),
    border: ButtonBorder = IconButtonDefaults.border(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    androidx.tv.material3.IconButton(
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
        border = border,
        interactionSource = interactionSource,
        content = content
    )
}
