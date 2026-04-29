@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.omnio.tv.ui.screens.settings

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.omnio.tv.domain.model.TraktSharingMode
import com.omnio.tv.core.uishared.OmnioColors

@Composable
internal fun TraktSharingModeSelector(
    selected: TraktSharingMode,
    onSelected: (TraktSharingMode) -> Unit,
    mainProfileUsername: String?,
    modifier: Modifier = Modifier
) {
    val mainName = mainProfileUsername?.takeIf { it.isNotBlank() }
    val sharedRwSubtitle = if (mainName != null) {
        stringResource(R.string.trakt_sharing_shared_rw_subtitle, mainName)
    } else {
        stringResource(R.string.trakt_sharing_shared_rw_subtitle_default)
    }
    val sharedReadOnlySubtitle = if (mainName != null) {
        stringResource(R.string.trakt_sharing_shared_readonly_subtitle, mainName)
    } else {
        stringResource(R.string.trakt_sharing_shared_readonly_subtitle_default)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.trakt_sharing_section_title),
            color = OmnioColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        TraktSharingChoiceRow(
            title = stringResource(R.string.trakt_sharing_own_title),
            subtitle = stringResource(R.string.trakt_sharing_own_subtitle),
            selected = selected == TraktSharingMode.OWN,
            onClick = { onSelected(TraktSharingMode.OWN) }
        )
        TraktSharingChoiceRow(
            title = stringResource(R.string.trakt_sharing_shared_rw_title),
            subtitle = sharedRwSubtitle,
            selected = selected == TraktSharingMode.SHARED_RW,
            onClick = { onSelected(TraktSharingMode.SHARED_RW) }
        )
        TraktSharingChoiceRow(
            title = stringResource(R.string.trakt_sharing_shared_readonly_title),
            subtitle = sharedReadOnlySubtitle,
            selected = selected == TraktSharingMode.SHARED_READ_ONLY,
            onClick = { onSelected(TraktSharingMode.SHARED_READ_ONLY) }
        )
    }
}

@Composable
private fun TraktSharingChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "shareBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusRing
            selected -> OmnioColors.Secondary
            else -> OmnioColors.Border
        },
        animationSpec = tween(120),
        label = "shareBorderColor"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> OmnioColors.FocusBackground
            selected -> Color.White.copy(alpha = 0.06f)
            else -> Color.White.copy(alpha = 0.03f)
        },
        animationSpec = tween(120),
        label = "shareBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelectKey(native.keyCode)) {
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioDot(selected = selected)
        Column(modifier = Modifier.padding(start = 12.dp)) {
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
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val ringColor by animateColorAsState(
        targetValue = if (selected) OmnioColors.Secondary else OmnioColors.Border,
        animationSpec = tween(120),
        label = "radioRing"
    )
    Row(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(2.dp, ringColor, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (selected) {
            Row(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(OmnioColors.Secondary)
            ) {}
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_SPACE,
    AndroidKeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}
