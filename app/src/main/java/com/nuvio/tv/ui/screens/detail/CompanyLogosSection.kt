@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CompanyLogosSection(
    title: String,
    companies: List<MetaCompany>,
    listState: LazyListState,
    onCompanyClick: (MetaCompany) -> Unit = {},
    restoreCompanyId: Int? = null,
    restoreFocusToken: Int = 0,
    lastFocusedCompanyId: Int? = null,
    onLastFocusedCompanyIdChange: (Int) -> Unit = {},
    onRestoreFocusHandled: () -> Unit = {},
    onCompanyFocused: (revealOverflowPx: Float) -> Unit = {},
    upFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    allowPageScroll: () -> Boolean = { false },
    windowResetKey: String? = null
) {
    if (companies.isEmpty()) return

    val companyWindowIds = remember(companies) { companies.map { companyWindowId(it) } }
    listState.keepDetailRowWindow(
        itemIds = companyWindowIds,
        lazyKeyAt = { index ->
            companies.getOrNull(index)?.let { companyLazyKey(title, index, it) }
        },
        resetKey = windowResetKey
    )
    val firstItemFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val lastFocusedRequester = remember(lastFocusedCompanyId, companies, restoreCompanyId) {
        when {
            lastFocusedCompanyId == null -> firstItemFocusRequester
            lastFocusedCompanyId == restoreCompanyId -> restoreFocusRequester
            lastFocusedCompanyId == companies.firstOrNull()?.tmdbId -> firstItemFocusRequester
            else -> itemFocusRequesters.getOrPut(lastFocusedCompanyId) { FocusRequester() }
        }
    }
    LaunchedEffect(companies) {
        val validIds = companies.mapNotNullTo(mutableSetOf()) { it.tmdbId }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    var holdRestoreScrollSuppress by remember { mutableStateOf(false) }
    var revealOverflowPx by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    val density = LocalDensity.current
    val revealPaddingPx = remember(density) { with(density) { NuvioTheme.spacing.md.toPx() } }
    val allowPageScrollState = rememberUpdatedState(allowPageScroll)
    val suppressRestoreScroll =
        holdRestoreScrollSuppress || (restoreCompanyId != null && restoreFocusToken > 0)
    var restorePending by remember(restoreCompanyId, restoreFocusToken) {
        mutableStateOf(suppressRestoreScroll)
    }
    var placedFocused by remember(restoreCompanyId, restoreFocusToken) { mutableStateOf(false) }
    val restoreNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    val stayVerticalResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                return if (allowPageScrollState.value()) {
                    localRect
                } else {
                    Rect(localRect.left, 0f, localRect.right, 0f)
                }
            }

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    LaunchedEffect(restoreCompanyId, restoreFocusToken) {
        if (restoreFocusToken <= 0 || restoreCompanyId == null) return@LaunchedEffect
        holdRestoreScrollSuppress = true
        restoreFocusRequester.requestFocusAfterFrames(frames = 0)
        holdRestoreScrollSuppress = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                revealOverflowPx = (bounds.bottom - view.height + revealPaddingPx).coerceAtLeast(0f)
            }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer {
                    when {
                        restorePending -> restoreFocusRequester
                        else -> lastFocusedRequester
                    }
                }
                .focusGroup(),
            state = listState,
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(
                items = companies,
                key = { index, company -> companyLazyKey(title, index, company) }
            ) { index, company ->
                val companyId = company.tmdbId
                val isRestoreTarget =
                    restoreFocusToken > 0 && companyId != null && companyId == restoreCompanyId
                val isFirstItem = index == 0
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstItem -> firstItemFocusRequester
                    companyId != null -> remember(companyId) {
                        itemFocusRequesters.getOrPut(companyId) { FocusRequester() }
                    }
                    else -> null
                }
                Box(
                    modifier = Modifier
                        .bringIntoViewResponder(
                            if (suppressRestoreScroll) restoreNoScrollResponder else stayVerticalResponder
                        )
                        .then(
                            if (isRestoreTarget && suppressRestoreScroll) {
                                Modifier.onPlaced {
                                    if (placedFocused) return@onPlaced
                                    placedFocused = true
                                    focusRequester?.let { runCatching { it.requestFocus() } }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    CompanyLogoCard(
                        company = company,
                        focusRequester = focusRequester,
                        upFocusRequester = upFocusRequester,
                        onFocused = {
                            companyId?.let(onLastFocusedCompanyIdChange)
                            onCompanyFocused(if (suppressRestoreScroll) 0f else revealOverflowPx)
                            if (isRestoreTarget) {
                                restorePending = false
                                onRestoreFocusHandled()
                            }
                        },
                        onClick = { onCompanyClick(company) }
                    )
                }
            }
        }
    }
}

private fun companyWindowId(company: MetaCompany): String =
    "${company.tmdbId ?: company.name}:${company.name}"

private fun companyLazyKey(title: String, index: Int, company: MetaCompany): String =
    "$title-$index-${company.name}-${company.logo.orEmpty()}"

@Composable
private fun CompanyLogoCard(
    company: MetaCompany,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidthPx = remember(density) { with(density) { 140.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { NuvioTheme.spacing.huge.roundToPx() } }
    val logoModel = remember(context, company.logo, logoWidthPx, logoHeightPx) {
        company.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var logoLoadFailed by remember(company.logo) { mutableStateOf(false) }

    Card(
        onClick = {
            if (company.tmdbId != null) {
                onClick()
            }
        },
        modifier = Modifier
            .width(140.dp)
            .height(NuvioTheme.spacing.huge)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )
            .onPreviewKeyEvent { event ->
                if (
                    upFocusRequester != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp
                ) {
                    upFocusRequester.requestFocus()
                } else {
                    false
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
        colors = CardDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.huge)
                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                .background(Color.White),
        contentAlignment = Alignment.Center
        ) {
            if (logoModel != null && !logoLoadFailed) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = company.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = company.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg)
                )
            }
        }
    }
}
