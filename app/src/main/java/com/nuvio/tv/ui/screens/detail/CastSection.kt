package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.nuvioCardDepth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaCastMember

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun CastSection(
    cast: List<MetaCastMember>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    title: String = "Cast",
    leadingCast: List<MetaCastMember> = emptyList(),
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restorePersonId: Int? = null,
    restoreFocusToken: Int = 0,
    lastFocusedPersonKey: String? = null,
    onLastFocusedPersonKeyChange: (String) -> Unit = {},
    onRestoreFocusHandled: () -> Unit = {},
    onCastMemberFocused: (MetaCastMember) -> Unit = {},
    onCastMemberClick: (MetaCastMember) -> Unit = {}
) {
    if (cast.isEmpty() && leadingCast.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var restoreTargetRequester by remember { mutableStateOf(firstItemFocusRequester) }
    val lastFocusedRequester = remember(lastFocusedPersonKey, leadingCast, cast) {
        resolveCastFocusRequester(
            key = lastFocusedPersonKey,
            leadingCast = leadingCast,
            cast = cast,
            firstItemFocusRequester = firstItemFocusRequester,
            itemFocusRequesters = itemFocusRequesters
        )
    }

    LaunchedEffect(cast, leadingCast) {
        val validKeys = buildSet {
            leadingCast.forEach { member ->
                add("leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}")
            }
            cast.forEach { member ->
                add("cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}")
            }
        }
        itemFocusRequesters.keys.retainAll(validKeys)
    }

    // Track whether a restore is pending so focusRestorer can use the correct fallback
    var restorePending by remember { mutableStateOf(false) }
    var holdRestoreScrollSuppress by remember { mutableStateOf(false) }
    var holdEnterRowScrollSuppress by remember { mutableStateOf(false) }
    var enterRowHoldToken by remember { mutableIntStateOf(0) }
    var rowHasFocus by remember { mutableStateOf(false) }
    var sectionVerticallyOnScreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val suppressRestoreScroll = holdRestoreScrollSuppress ||
        holdEnterRowScrollSuppress ||
        (restoreFocusToken > 0 && restorePersonId != null)
    val restoreNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    val restoreItemModifier = if (suppressRestoreScroll) {
        Modifier.bringIntoViewResponder(restoreNoScrollResponder)
    } else {
        Modifier
    }

    // Only react to restoreFocusToken changes (triggered on ON_RESUME).
    // restorePersonId/cast lists are read inside but not used as keys to avoid
    // triggering scroll at the moment of click (before navigation happens).
    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken <= 0 || restorePersonId == null) {
            restorePending = false
            holdRestoreScrollSuppress = false
            return@LaunchedEffect
        }
        val leadingIndex = leadingCast.indexOfFirst { it.tmdbId == restorePersonId }
        val castIndex = cast.indexOfFirst { it.tmdbId == restorePersonId }
        if (leadingIndex < 0 && castIndex < 0) {
            restorePending = false
            holdRestoreScrollSuppress = false
            return@LaunchedEffect
        }
        val targetRequester = when {
            leadingIndex == 0 -> firstItemFocusRequester
            leadingIndex >= 0 -> {
                val member = leadingCast[leadingIndex]
                val key = "leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                itemFocusRequesters.getOrPut(key) { FocusRequester() }
            }
            castIndex == 0 && leadingCast.isEmpty() -> firstItemFocusRequester
            else -> {
                val member = cast[castIndex]
                val key = "cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                itemFocusRequesters.getOrPut(key) { FocusRequester() }
            }
        }
        restoreTargetRequester = targetRequester
        restorePending = true
        holdRestoreScrollSuppress = true
        targetRequester.requestFocusAfterFrames()
        repeat(2) { withFrameNanos { } }
        holdRestoreScrollSuppress = false
        onRestoreFocusHandled()
    }

    LaunchedEffect(enterRowHoldToken) {
        if (enterRowHoldToken == 0) return@LaunchedEffect
        holdEnterRowScrollSuppress = true
        repeat(2) { withFrameNanos { } }
        holdEnterRowScrollSuppress = false
    }

    val itemWidth = 150.dp
    val cardSize = 100.dp
    val hasTitle = title.isNotBlank()
    val currentUpFocusRequester by rememberUpdatedState(upFocusRequester)
    val currentDownFocusRequester by rememberUpdatedState(downFocusRequester)

    val itemFocusPropertiesModifier = if (currentUpFocusRequester != null || currentDownFocusRequester != null) {
        Modifier.focusProperties {
            if (currentUpFocusRequester != null) up = currentUpFocusRequester!!
            if (currentDownFocusRequester != null) down = currentDownFocusRequester!!
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 20.dp else NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val next = bounds.top >= -1f &&
                    bounds.bottom <= view.height + 1f &&
                    bounds.height > 1f
                if (next != sectionVerticallyOnScreen) {
                    sectionVerticallyOnScreen = next
                }
            }
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .onFocusChanged { state ->
                    val entered = state.hasFocus && !rowHasFocus
                    rowHasFocus = state.hasFocus
                    if (entered && !restorePending && sectionVerticallyOnScreen) {
                        holdEnterRowScrollSuppress = true
                        enterRowHoldToken += 1
                    }
                }
                .focusRestorer {
                    if (restorePending) restoreTargetRequester else lastFocusedRequester
                }
                .focusGroup(),
            state = listState,
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            val standardGap = NuvioTheme.spacing.sm
            val deadSpace = itemWidth - cardSize

            if (leadingCast.isNotEmpty()) {
                itemsIndexed(
                    items = leadingCast,
                    key = { index, member ->
                        "leading|" + index + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                    }
                ) { index, member ->
                    val isLastLeading = member == leadingCast.last()
                    val endPadding = if (isLastLeading && cast.isNotEmpty()) NuvioTheme.spacing.none else standardGap
                    val isRestoreTarget = member.tmdbId == restorePersonId
                    val isFirstItem = index == 0
                    val focusKey = "leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                    val focusRequester = if (isFirstItem) {
                        firstItemFocusRequester
                    } else {
                        remember(focusKey) { itemFocusRequesters.getOrPut(focusKey) { FocusRequester() } }
                    }

                    Box(modifier = restoreItemModifier.padding(end = endPadding)) {
                        CastMemberItem(
                            member = member,
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .then(itemFocusPropertiesModifier),
                            itemWidth = itemWidth,
                            cardSize = cardSize,
                            onFocused = {
                                onLastFocusedPersonKeyChange(focusKey)
                                onCastMemberFocused(member)
                                if (isRestoreTarget && restoreFocusToken > 0) {
                                    restorePending = false
                                }
                            },
                            onClick = { onCastMemberClick(member) }
                        )
                    }
                }
            }

            if (leadingCast.isNotEmpty() && cast.isNotEmpty()) {
                item(key = "role_divider") {
                    Box(
                        modifier = Modifier.height(cardSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(NuvioTheme.spacing.hairline)
                                .height(72.dp)
                                .offset(x = -deadSpace / 2)
                                .background(NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.9f))
                        )
                    }
                }
            }

            itemsIndexed(
                items = cast,
                key = { index, member ->
                    index.toString() + "|" + (member.tmdbId?.toString() ?: member.name) + "|" + (member.character ?: "") + "|" + (member.photo ?: "")
                }
            ) { index, member ->
                val isRestoreTarget = member.tmdbId == restorePersonId
                val isFirstCastItem = index == 0 && leadingCast.isEmpty()
                val focusKey = "cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
                val focusRequester = if (isFirstCastItem) {
                    firstItemFocusRequester
                } else {
                    remember(focusKey) { itemFocusRequesters.getOrPut(focusKey) { FocusRequester() } }
                }

                Box(modifier = restoreItemModifier.padding(end = standardGap)) {
                    CastMemberItem(
                        member = member,
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .then(itemFocusPropertiesModifier),
                        itemWidth = itemWidth,
                        cardSize = cardSize,
                        onFocused = {
                            onLastFocusedPersonKeyChange(focusKey)
                            onCastMemberFocused(member)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                restorePending = false
                            }
                        },
                        onClick = { onCastMemberClick(member) }
                    )
                }
            }
        }
    }
}

private fun resolveCastFocusRequester(
    key: String?,
    leadingCast: List<MetaCastMember>,
    cast: List<MetaCastMember>,
    firstItemFocusRequester: FocusRequester,
    itemFocusRequesters: MutableMap<String, FocusRequester>
): FocusRequester {
    if (key.isNullOrEmpty()) return firstItemFocusRequester
    val firstLeadingKey = leadingCast.firstOrNull()?.let { member ->
        "leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
    }
    val firstCastKey = if (leadingCast.isEmpty()) {
        cast.firstOrNull()?.let { member ->
            "cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
        }
    } else {
        null
    }
    if (key == firstLeadingKey || key == firstCastKey) return firstItemFocusRequester
    val known = leadingCast.any { member ->
        key == "leading:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
    } || cast.any { member ->
        key == "cast:${member.tmdbId ?: member.name}:${member.character.orEmpty()}"
    }
    if (!known) return firstItemFocusRequester
    return itemFocusRequesters.getOrPut(key) { FocusRequester() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastMemberItem(
    member: MetaCastMember,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 150.dp,
    cardSize: Dp = 100.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val typography = MaterialTheme.typography
    val nameStyle = remember(typography) { typography.labelMedium }
    val characterStyle = remember(typography) { typography.labelSmall }
    val initialsStyle = remember(typography) { typography.titleLarge }
    val photo = member.photo
    val photoModel = remember(context, photo, cardSizePx) {
        photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    var isFocused by remember { mutableStateOf(false) }
    val cardDepthStyle = LocalCardDepthStyle.current

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            onClick = onClick,
            modifier = modifier
                .size(cardSize)
                .align(Alignment.Start)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                },
            shape = CardDefaults.shape(
                shape = CircleShape
            ),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = CircleShape
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .nuvioCardDepth(
                        shape = CircleShape,
                        surface = CardDepthSurface.CAST,
                        style = cardDepthStyle
                    ),
                contentAlignment = Alignment.Center
            ) {
                val currentBgColor = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.SurfaceVariant
                val bgPainter = remember(currentBgColor) { androidx.compose.ui.graphics.painter.ColorPainter(currentBgColor) }

                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize().background(currentBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.name.firstOrNull()?.uppercase() ?: "?",
                            style = initialsStyle,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = member.name,
            style = nameStyle,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val character = member.character
        if (!character.isNullOrBlank()) {
            val displayCharacter = when {
                character.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
                character.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
                character.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
                else -> character
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = displayCharacter,
                style = characterStyle,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
