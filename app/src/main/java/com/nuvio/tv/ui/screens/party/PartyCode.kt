@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.party

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PARTY_CODE_ALPHABET
import com.nuvio.tv.core.party.PARTY_CODE_LENGTH
import com.nuvio.tv.ui.screens.settings.settingsOptionRow
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun CodeSlots(
    code: String,
    modifier: Modifier = Modifier,
    activeIndex: Int? = null,
    textStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    slotHeight: Dp = 48.dp,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            repeat(PARTY_CODE_LENGTH) { index ->
                CodeSlot(
                    char = code.getOrNull(index),
                    active = index == activeIndex,
                    textStyle = textStyle,
                    modifier = Modifier
                        .weight(1f)
                        .height(slotHeight),
                )
            }
        }
    }
}

@Composable
private fun CodeSlot(
    char: Char?,
    active: Boolean,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val lineColor by animateColorAsState(
        targetValue = when {
            active -> NuvioTheme.colors.Secondary
            char != null -> NuvioTheme.colors.TextSecondary
            else -> NuvioTheme.colors.Border
        },
        animationSpec = tween(180),
        label = "codeSlotLine",
    )
    val lineHeight by animateDpAsState(if (active) NuvioTheme.strokes.heavy else NuvioTheme.strokes.medium, tween(180), label = "codeSlotLineHeight")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), initialScale = 0.5f)) togetherWith
                        fadeOut(tween(90))
                },
                label = "codeSlotChar",
            ) { value ->
                if (value != null) {
                    Text(
                        text = value.toString(),
                        style = textStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                } else if (active) {
                    Caret()
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeight)
                .clip(CircleShape)
                .background(lineColor),
        )
    }
}

@Composable
private fun Caret() {
    val transition = rememberInfiniteTransition(label = "codeCaret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "codeCaretAlpha",
    )
    Box(
        modifier = Modifier
            .width(NuvioTheme.strokes.medium)
            .height(26.dp)
            .alpha(alpha)
            .background(NuvioTheme.colors.Secondary),
    )
}

@Composable
internal fun CodeKeypad(
    enabled: Boolean,
    canSubmit: Boolean,
    busy: Boolean,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
    firstKeyFocusRequester: FocusRequester,
    submitFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (!enabled || native.isCtrlPressed || native.isAltPressed || native.isMetaPressed) return@onPreviewKeyEvent false
            val character = native.unicodeChar.toChar().uppercaseChar()
            when {
                character in PARTY_CODE_ALPHABET -> {
                    if (native.action == KeyEvent.ACTION_DOWN) onKey(character)
                    true
                }
                native.keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (native.action == KeyEvent.ACTION_DOWN) onDelete()
                    true
                }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Column(
            modifier = Modifier.settingsOptionRow(firstKeyFocusRequester),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            PARTY_CODE_ALPHABET.chunked(KEYPAD_COLUMNS).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    row.forEachIndexed { columnIndex, character ->
                        KeypadButton(
                            onClick = { onKey(character) },
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(firstKeyFocusRequester)
                                    else Modifier
                                ),
                        ) {
                            Text(
                                text = character.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            KeypadButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = stringResource(R.string.action_delete),
                    modifier = Modifier.size(20.dp),
                )
            }
            KeypadButton(
                onClick = onSubmit,
                enabled = enabled && canSubmit && !busy,
                primary = true,
                modifier = Modifier
                    .weight(3f)
                    .focusRequester(submitFocusRequester),
            ) {
                Text(
                    text = stringResource(R.string.party_join_action),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(44.dp)
            .focusProperties { canFocus = enabled },
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private const val KEYPAD_COLUMNS = 8
