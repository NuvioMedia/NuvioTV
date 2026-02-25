package com.nuvio.tv.ui.screens.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors

private object ProfileSelectionSpacing {
    val ScreenPaddingHorizontal = 56.dp
    val ScreenPaddingVertical = 48.dp
    val LogoWidth = 190.dp
    val LogoHeight = 44.dp
    val LogoToHeading = 28.dp
    val HeadingToSubheading = 12.dp
    val GridItemGap = 28.dp
    val CardWidth = 152.dp
    val CardPaddingHorizontal = 10.dp
    val CardPaddingVertical = 8.dp
    val AvatarContainer = 126.dp
    val AvatarToName = 12.dp
    val NameToMeta = 8.dp
    val MetaSlotHeight = 16.dp
}

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    var focusedAvatarColor by remember { mutableStateOf(Color(0xFF1E88E5)) }

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty()) {
            focusedAvatarColor = parseProfileColor(profiles.first().avatarColorHex)
        }
    }

    val animatedAvatarColor by animateColorAsState(
        targetValue = focusedAvatarColor,
        animationSpec = tween(durationMillis = 520),
        label = "focusedAvatarColor"
    )
    val gradientTop = lerp(NuvioColors.BackgroundElevated, animatedAvatarColor, 0.3f)
    val gradientMid = lerp(NuvioColors.Background, animatedAvatarColor, 0.14f)
    val halfFadeStrong = animatedAvatarColor.copy(alpha = 0.26f)
    val halfFadeSoft = animatedAvatarColor.copy(alpha = 0.08f)
    val backgroundColor = NuvioColors.Background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val verticalGradient = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to gradientTop,
                        0.42f to gradientMid,
                        1f to backgroundColor
                    )
                )
                val horizontalGradient = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to halfFadeStrong,
                        0.45f to halfFadeSoft,
                        0.72f to Color.Transparent,
                        1f to Color.Transparent
                    )
                )
                onDrawBehind {
                    drawRect(color = backgroundColor, size = size)
                    drawRect(brush = verticalGradient, size = size)
                    drawRect(brush = horizontalGradient, size = size)
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal,
                    vertical = ProfileSelectionSpacing.ScreenPaddingVertical
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_wordmark),
                contentDescription = "NuvioTV",
                modifier = Modifier
                    .width(ProfileSelectionSpacing.LogoWidth)
                    .height(ProfileSelectionSpacing.LogoHeight),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(ProfileSelectionSpacing.LogoToHeading))

            Text(
                text = stringResource(R.string.profile_selection_title),
                color = NuvioColors.TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(ProfileSelectionSpacing.HeadingToSubheading))

            Text(
                text = stringResource(R.string.profile_selection_subtitle),
                color = NuvioColors.TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))

            ProfileGrid(
                profiles = profiles,
                onProfileFocused = { colorHex ->
                    focusedAvatarColor = parseProfileColor(colorHex)
                },
                onProfileSelected = { id ->
                    viewModel.selectProfile(id, onComplete = onProfileSelected)
                }
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Text(
                text = stringResource(R.string.profile_selection_hint),
                color = NuvioColors.TextTertiary.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    onProfileFocused: (String) -> Unit,
    onProfileSelected: (Int) -> Unit
) {
    val focusRequesters = remember(profiles.size) {
        List(profiles.size) { FocusRequester() }
    }

    LaunchedEffect(profiles.size) {
        repeat(2) { withFrameNanos { } }
        if (focusRequesters.isNotEmpty()) {
            runCatching { focusRequesters.first().requestFocus() }
        }
    }

    if (profiles.isEmpty()) {
        Text(
            text = stringResource(R.string.profile_selection_empty),
            color = NuvioColors.TextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProfileSelectionSpacing.GridItemGap),
                verticalAlignment = Alignment.Top
            ) {
                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        profile = profile,
                        focusRequester = focusRequesters[index],
                        onFocused = { onProfileFocused(profile.avatarColorHex) },
                        onClick = { onProfileSelected(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "profileItemScale"
    )
    val avatarSize by animateDpAsState(
        targetValue = if (isFocused) 102.dp else 96.dp,
        animationSpec = tween(durationMillis = 150),
        label = "profileAvatarSize"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 140),
        label = "profileRingWidth"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.Secondary else NuvioColors.Border.copy(alpha = 0.75f),
        animationSpec = tween(durationMillis = 140),
        label = "profileRingColor"
    )
    val nameColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
        animationSpec = tween(durationMillis = 120),
        label = "profileNameColor"
    )
    val nameWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium

    Column(
        modifier = Modifier
            .width(ProfileSelectionSpacing.CardWidth)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ProfileSelectionSpacing.AvatarContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isFocused) 122.dp else 114.dp)
                    .clip(CircleShape)
                    .border(
                        width = ringWidth,
                        color = ringColor,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = avatarSize
                )
            }

            if (profile.isPrimary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 1.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB300), CircleShape)
                        .border(
                            width = 2.dp,
                            color = NuvioColors.Background,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2605",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.AvatarToName))

        Text(
            text = profile.name,
            color = nameColor,
            fontSize = 17.sp,
            fontWeight = nameWeight,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))

        Box(
            modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            if (profile.isPrimary) {
                Text(
                    text = stringResource(R.string.profile_selection_primary_badge),
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

private fun parseProfileColor(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
}
