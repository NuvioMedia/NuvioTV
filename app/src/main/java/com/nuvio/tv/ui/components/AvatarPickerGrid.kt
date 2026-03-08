package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.ui.theme.NuvioColors

private val PinnedAvatarCategories = listOf("anime", "animation", "tv", "movie", "gaming")

@Composable
fun AvatarPickerGrid(
    avatars: List<AvatarCatalogItem>,
    selectedAvatarId: String?,
    onAvatarSelected: (AvatarCatalogItem) -> Unit,
    onAvatarFocused: ((AvatarCatalogItem?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val categories = remember(avatars) {
        buildList {
            add("all")

            val normalizedCategories = avatars
                .mapNotNull { avatar -> avatar.category.trim().takeIf { it.isNotEmpty() } }
                .distinct()

            PinnedAvatarCategories.forEach { category ->
                if (normalizedCategories.any { it.equals(category, ignoreCase = true) }) {
                    add(category)
                }
            }

            normalizedCategories
                .filterNot { category ->
                    PinnedAvatarCategories.any { it.equals(category, ignoreCase = true) }
                }
                .sortedBy { it.lowercase() }
                .forEach(::add)
        }
    }
    var selectedCategory by remember { mutableStateOf("all") }
    val categoryRequesters = remember(categories) {
        categories.associateWith { FocusRequester() }
    }

    LaunchedEffect(categories) {
        if (selectedCategory !in categories) {
            selectedCategory = "all"
        }
    }

    val filteredAvatars = remember(avatars, selectedCategory) {
        if (selectedCategory == "all") avatars
        else avatars.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }
    val avatarRequesters = remember(filteredAvatars) {
        filteredAvatars.associate { it.id to FocusRequester() }
    }
    val selectedCategoryRequester = categoryRequesters.getValue(selectedCategory)
    val firstAvatarRequester = filteredAvatars.firstOrNull()?.let { avatarRequesters[it.id] }

    Column(modifier = modifier) {
        // Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            categories.forEach { category ->
                CategoryTab(
                    label = categoryLabel(category),
                    isSelected = selectedCategory == category,
                    focusRequester = categoryRequesters.getValue(category),
                    downFocusRequester = if (selectedCategory == category) firstAvatarRequester else null,
                    onClick = { selectedCategory = category }
                )
                if (category != categories.last()) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            val minCellWidth = 88.dp
            val horizontalSpacing = 12.dp
            val horizontalPadding = 16.dp
            val availableWidth = maxWidth - horizontalPadding
            val columnCount = max(
                1,
                floor(
                    (availableWidth.value + horizontalSpacing.value) /
                        (minCellWidth.value + horizontalSpacing.value)
                ).toInt()
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minCellWidth),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(filteredAvatars, key = { _, avatar -> avatar.id }) { index, avatar ->
                    AvatarGridItem(
                        avatar = avatar,
                        isSelected = avatar.id == selectedAvatarId,
                        focusRequester = avatarRequesters.getValue(avatar.id),
                        upFocusRequester = if (index < columnCount) selectedCategoryRequester else null,
                        onFocused = { focused -> if (focused) onAvatarFocused?.invoke(avatar) },
                        onClick = { onAvatarSelected(avatar) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTab(
    label: String,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected && isFocused -> NuvioColors.FocusBackground
            isSelected -> NuvioColors.Secondary.copy(alpha = 0.22f)
            isFocused -> NuvioColors.FocusBackground
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(150),
        label = "categoryBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected && isFocused -> NuvioColors.FocusRing
            isFocused -> NuvioColors.FocusRing
            isSelected -> NuvioColors.Secondary
            else -> NuvioColors.Border
        },
        animationSpec = tween(150),
        label = "categoryBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected && isFocused -> 2.dp
            isFocused -> 2.dp
            isSelected -> 1.dp
            else -> 1.dp
        },
        animationSpec = tween(150),
        label = "categoryBorderWidth"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioColors.TextSecondary,
        animationSpec = tween(150),
        label = "categoryText"
    )

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .then(
                if (downFocusRequester != null) {
                    Modifier.focusProperties { down = downFocusRequester }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun AvatarGridItem(
    avatar: AvatarCatalogItem,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(150),
        label = "avatarScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected -> 3.dp
            isFocused -> 2.dp
            else -> 0.dp
        },
        animationSpec = tween(120),
        label = "avatarBorder"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected || isFocused -> NuvioColors.FocusRing
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "avatarBorderColor"
    )

    Box(
        modifier = Modifier
            .requiredSize(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                onFocused(it.isFocused)
            }
            .clip(CircleShape)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatar.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = avatar.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun categoryLabel(category: String): String {
    return when (category) {
        "all" -> stringResource(R.string.profile_avatar_category_all)
        "anime" -> stringResource(R.string.profile_avatar_category_anime)
        "animation" -> stringResource(R.string.profile_avatar_category_animation)
        "movie" -> stringResource(R.string.profile_avatar_category_movie)
        "tv" -> stringResource(R.string.profile_avatar_category_tv)
        "gaming" -> stringResource(R.string.profile_avatar_category_gaming)
        else -> category.replaceFirstChar { it.uppercase() }
    }
}
