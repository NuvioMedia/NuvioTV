@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.omnio.tv.ui.screens.profile

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.omnio.tv.R
import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.AioSharingMode
import com.omnio.tv.ui.theme.OmnioColors

private val SectionShape = RoundedCornerShape(14.dp)

@Composable
internal fun ProfileAccountOptionsSection(
    isCreating: Boolean,
    showAddonOptions: Boolean,
    addonInitMode: ProfileAddonInitMode,
    onAddonInitModeChange: (ProfileAddonInitMode) -> Unit,
    showKidsOptions: Boolean,
    isKids: Boolean,
    onIsKidsChange: (Boolean) -> Unit,
    maxAgeRating: AgeRatingTier?,
    onMaxAgeRatingChange: (AgeRatingTier) -> Unit,
    aioSharing: AioSharingMode,
    onAioSharingChange: (AioSharingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showAddonOptions && !showKidsOptions) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_account_options_title),
            color = OmnioColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        if (showAddonOptions) {
            ProfileToggleRow(
                title = stringResource(R.string.profile_addon_mirror_title),
                subtitle = stringResource(R.string.profile_addon_mirror_subtitle),
                checked = addonInitMode == ProfileAddonInitMode.LIVE_MIRROR,
                onCheckedChange = { mirroring ->
                    onAddonInitModeChange(
                        if (mirroring) ProfileAddonInitMode.LIVE_MIRROR else ProfileAddonInitMode.FRESH
                    )
                }
            )

            if (isCreating && addonInitMode != ProfileAddonInitMode.LIVE_MIRROR) {
                ProfileChoiceRow(
                    title = stringResource(R.string.profile_addon_copy_title),
                    subtitle = stringResource(R.string.profile_addon_copy_subtitle),
                    selected = addonInitMode == ProfileAddonInitMode.COPY_FROM_MAIN,
                    selectedLabel = stringResource(R.string.profile_addon_copy_selected),
                    idleLabel = stringResource(R.string.profile_addon_copy_idle),
                    onSelectedChange = { selected ->
                        onAddonInitModeChange(
                            if (selected) ProfileAddonInitMode.COPY_FROM_MAIN else ProfileAddonInitMode.FRESH
                        )
                    }
                )
            }
        }

        if (showKidsOptions) {
            ProfileToggleRow(
                title = stringResource(R.string.profile_kids_toggle_title),
                subtitle = stringResource(R.string.profile_kids_toggle_subtitle),
                checked = isKids,
                onCheckedChange = onIsKidsChange
            )

            if (isKids) {
                Text(
                    text = stringResource(R.string.profile_kids_max_rating_label),
                    color = OmnioColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                AgeRatingChipRow(
                    selected = maxAgeRating,
                    onSelected = onMaxAgeRatingChange
                )
                Text(
                    text = stringResource(R.string.profile_kids_exit_pin_hint),
                    color = OmnioColors.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // AIOMetadata sharing — applies to non-primary profiles. Determines
        // whether the profile keeps its own AIO config in lock-step with Main
        // (FULL_MIRROR), pulls only the API keys (KEYS_ONLY), or stays
        // independent. Kids profiles can't FULL_MIRROR (it would discard the
        // kid-tuned catalogs), so we hide that row when isKids is true.
        Text(
            text = stringResource(R.string.profile_aio_section_title),
            color = OmnioColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp)
        )

        if (!isKids) {
            ProfileToggleRow(
                title = stringResource(R.string.profile_aio_full_mirror_title),
                subtitle = stringResource(R.string.profile_aio_full_mirror_subtitle),
                checked = aioSharing == AioSharingMode.FULL_MIRROR,
                onCheckedChange = { picked ->
                    onAioSharingChange(
                        if (picked) AioSharingMode.FULL_MIRROR else AioSharingMode.INDEPENDENT
                    )
                }
            )
        }

        ProfileToggleRow(
            title = stringResource(R.string.profile_aio_keys_only_title),
            subtitle = if (isKids) {
                stringResource(R.string.profile_aio_keys_only_subtitle_kids)
            } else {
                stringResource(R.string.profile_aio_keys_only_subtitle)
            },
            checked = aioSharing == AioSharingMode.KEYS_ONLY ||
                (isKids && aioSharing != AioSharingMode.INDEPENDENT),
            onCheckedChange = { picked ->
                onAioSharingChange(
                    if (picked) AioSharingMode.KEYS_ONLY else AioSharingMode.INDEPENDENT
                )
            }
        )
    }
}

@Composable
private fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    OptionRowFrame(
        modifier = modifier,
        selected = checked,
        onClick = { onCheckedChange(!checked) }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OmnioColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = OmnioColors.TextTertiary,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        ToggleIndicator(checked = checked)
    }
}

@Composable
private fun ProfileChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    selectedLabel: String,
    idleLabel: String,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    OptionRowFrame(
        modifier = modifier,
        selected = selected,
        onClick = { onSelectedChange(!selected) }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OmnioColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = OmnioColors.TextTertiary,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (selected) selectedLabel else idleLabel,
            color = if (selected) OmnioColors.FocusRing else OmnioColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OptionRowFrame(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val border by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "rowBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusRing
            selected -> OmnioColors.Secondary
            else -> OmnioColors.Border
        },
        animationSpec = tween(120),
        label = "rowBorderColor"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusBackground
            selected -> Color.White.copy(alpha = 0.06f)
            else -> Color.White.copy(alpha = 0.03f)
        },
        animationSpec = tween(120),
        label = "rowBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(bgColor)
            .border(border, borderColor, SectionShape)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP && isProfileSelectKeyInternal(native.keyCode)) {
                    onClick()
                    true
                } else false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun ToggleIndicator(checked: Boolean) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) OmnioColors.Secondary else Color.White.copy(alpha = 0.18f),
        animationSpec = tween(120),
        label = "toggleTrack"
    )
    Box(
        modifier = Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(trackColor)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .width(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
        )
    }
}

@Composable
private fun AgeRatingChipRow(
    selected: AgeRatingTier?,
    onSelected: (AgeRatingTier) -> Unit
) {
    val choices = listOf(
        AgeRatingTier.G,
        AgeRatingTier.PG,
        AgeRatingTier.PG_13,
        AgeRatingTier.TV_14,
        AgeRatingTier.R
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { tier ->
            AgeRatingChip(
                label = tier.label,
                selected = selected == tier,
                onClick = { onSelected(tier) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AgeRatingChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusRing
            selected -> OmnioColors.Secondary
            else -> OmnioColors.Border
        },
        animationSpec = tween(120),
        label = "chipBorder"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusBackground
            selected -> OmnioColors.Secondary
            else -> Color.White.copy(alpha = 0.04f)
        },
        animationSpec = tween(120),
        label = "chipBg"
    )
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP && isProfileSelectKeyInternal(native.keyCode)) {
                    onClick()
                    true
                } else false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected && !isFocused) Color.Black else OmnioColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun isProfileSelectKeyInternal(keyCode: Int): Boolean = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_SPACE,
    AndroidKeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}
