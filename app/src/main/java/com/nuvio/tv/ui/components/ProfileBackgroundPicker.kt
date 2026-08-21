package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun ProfileBackgroundPicker(
    backgrounds: List<ProfileBackgroundCatalogItem>,
    selectedBackgroundId: String?,
    customBackgroundUrl: String?,
    selectedBackgroundUrl: String?,
    standardBackgroundColor: Color,
    onStandardBackgroundSelected: () -> Unit,
    onBackgroundSelected: (ProfileBackgroundCatalogItem) -> Unit,
    onCustomBackgroundSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        ProfileBackgroundItem(
            label = stringResource(R.string.profile_background_normal),
            isSelected = selectedBackgroundId == null && selectedBackgroundUrl == null,
            onClick = onStandardBackgroundSelected
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                lerp(NuvioTheme.colors.BackgroundElevated, standardBackgroundColor, 0.3f),
                                lerp(NuvioTheme.colors.Background, standardBackgroundColor, 0.14f),
                                NuvioTheme.colors.Background
                            )
                        )
                    )
            )
        }
        customBackgroundUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            ProfileBackgroundItem(
                label = stringResource(R.string.profile_background_custom),
                isSelected = selectedBackgroundId == null && imageUrl == selectedBackgroundUrl,
                onClick = onCustomBackgroundSelected
            ) {
                CustomProfileBackgroundImage(
                    imageUrl = imageUrl,
                    contentDescription = stringResource(R.string.profile_background_custom),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        backgrounds.forEach { background ->
            ProfileBackgroundItem(
                label = background.displayName,
                isSelected = selectedBackgroundUrl == null && background.id == selectedBackgroundId,
                onClick = { onBackgroundSelected(background) }
            ) {
                ProfileBackgroundImage(
                    background = background,
                    contentDescription = background.displayName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ProfileBackgroundItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "profileBackgroundScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "profileBackgroundBorder"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioTheme.colors.TextSecondary,
        animationSpec = tween(120),
        label = "profileBackgroundLabel"
    )
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .width(152.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .border(
                    border = if (isSelected || isFocused) {
                        NuvioTheme.focusRing.border(borderWidth)
                    } else {
                        BorderStroke(borderWidth, NuvioTheme.colors.Border)
                    },
                    shape = shape
                )
        ) {
            content()
        }

        Text(
            text = label,
            color = labelColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ProfileBackgroundImage(
    background: ProfileBackgroundCatalogItem,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(background.imageFile)
            .memoryCacheKey("profile-background-${background.id}-v${background.assetVersion}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .allowRgb565(false)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

@Composable
fun CustomProfileBackgroundImage(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey("custom-profile-background-$imageUrl")
            .diskCacheKey("custom-profile-background-$imageUrl")
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowRgb565(false)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}
