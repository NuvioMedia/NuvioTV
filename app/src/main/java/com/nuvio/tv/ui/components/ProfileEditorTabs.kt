package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

enum class ProfileEditorTab {
    Avatar,
    Background
}

@Composable
fun ProfileEditorTabs(
    selectedTab: ProfileEditorTab,
    showBackgroundTab: Boolean,
    onTabSelected: (ProfileEditorTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        ProfileEditorTabItem(
            label = stringResource(R.string.profile_editor_tab_avatar),
            isSelected = selectedTab == ProfileEditorTab.Avatar,
            onClick = { onTabSelected(ProfileEditorTab.Avatar) }
        )
        if (showBackgroundTab) {
            ProfileEditorTabItem(
                label = stringResource(R.string.profile_editor_tab_background),
                isSelected = selectedTab == ProfileEditorTab.Background,
                onClick = { onTabSelected(ProfileEditorTab.Background) }
            )
        }
    }
}

@Composable
private fun ProfileEditorTabItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.FocusBackground
            isSelected -> NuvioTheme.colors.Secondary.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(120),
        label = "profileEditorTabBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioTheme.colors.TextSecondary,
        animationSpec = tween(120),
        label = "profileEditorTabText"
    )
    val shape = RoundedCornerShape(20.dp)

    Text(
        text = label,
        color = textColor,
        fontSize = 13.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                border = if (isFocused) {
                    NuvioTheme.focusRing.border(2.dp)
                } else {
                    BorderStroke(1.dp, if (isSelected) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border)
                },
                shape = shape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm)
    )
}
