@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Card
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"
private const val SubtitleFocusTag = "SubtitleFocus"

private val OverlayTextColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

private val OverlayOutlineColors = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

private const val RailFadeDurationMs = 120

@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val builtInLabel = stringResource(R.string.subtitle_built_in)
    val forcedLabel = stringResource(R.string.sub_forced_lang)
    var persistedStyleFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val sessionPreferredLanguage = remember(visible) { subtitleStyle.preferredLanguage }
    val sessionSecondaryPreferredLanguage = remember(visible) { subtitleStyle.secondaryPreferredLanguage }
    val sessionSelectedInternalIndex = remember(visible) { selectedInternalIndex }
    val sessionInternalTracks = remember(visible) { internalTracks.map(TrackInfo::copy) }
    val sessionAddonSubtitles = remember(visible) { addonSubtitles.map(Subtitle::copy) }
    val sessionSelectedAddonSubtitle = remember(visible) { selectedAddonSubtitle?.copy() }
    val sessionInstalledSubtitleAddonOrder = remember(visible) { installedSubtitleAddonOrder.toList() }
    val sessionIsLoadingAddons = remember(visible) { isLoadingAddons }
    val languageItems = remember(visible) {
        buildSubtitleLanguageRailItems(
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            preferredLanguage = sessionPreferredLanguage,
            secondaryPreferredLanguage = sessionSecondaryPreferredLanguage,
            noneLabel = noneLabel
        )
    }
    val sessionInitialLanguageKey = remember(visible) {
        selectedSubtitleLanguageKey(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = sessionSelectedInternalIndex,
            selectedAddonSubtitle = sessionSelectedAddonSubtitle
        )
    }
    val sessionInitialSelectedOptionId = remember(visible) {
        selectedSubtitleOptionId(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = sessionSelectedInternalIndex,
            selectedAddonSubtitle = sessionSelectedAddonSubtitle
        )
    }
    fun buildSessionOptions(languageKey: String, activeSelectedOptionId: String?): List<SubtitleOptionRailItem> {
        return buildSubtitleOptionRailItems(
            selectedLanguageKey = languageKey,
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            installedAddonOrder = sessionInstalledSubtitleAddonOrder,
            selectedOptionId = activeSelectedOptionId,
            builtInLabel = builtInLabel,
            forcedLabel = forcedLabel
        )
    }

    val sessionInitialSubtitleOptions = remember(
        visible,
        sessionInitialLanguageKey,
        sessionInitialSelectedOptionId
    ) {
        buildSessionOptions(sessionInitialLanguageKey, sessionInitialSelectedOptionId)
    }
    val sessionInitialOptionTargetId = remember(visible) {
        sessionInitialSelectedOptionId
            ?.takeIf { id -> sessionInitialSubtitleOptions.any { it.id == id } }
            ?: sessionInitialSubtitleOptions.firstOrNull()?.id
    }
    var selectedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey)
    }
    var selectedOptionId by remember(visible) {
        mutableStateOf(sessionInitialSelectedOptionId)
    }
    val subtitleOptions = remember(
        selectedLanguageKey,
        selectedOptionId,
        sessionInternalTracks,
        sessionAddonSubtitles,
        sessionInstalledSubtitleAddonOrder
    ) {
        buildSessionOptions(selectedLanguageKey, selectedOptionId)
    }
    var lastFocusedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey.takeIf { key -> languageItems.any { it.key == key } })
    }
    var optionFocusMemory by remember(visible) {
        mutableStateOf<Map<String, String>>(
            sessionInitialOptionTargetId
                ?.let { mapOf(sessionInitialLanguageKey to it) }
                ?: emptyMap()
        )
    }
    var optionEntryLanguageKey by remember(visible) { mutableStateOf(sessionInitialLanguageKey) }
    var styleEntryOptionId by remember(visible) { mutableStateOf(sessionInitialOptionTargetId) }
    var activeRail by remember(visible) { mutableStateOf<OverlayFocusRail?>(null) }
    var activeOptionFocusId by remember(visible) { mutableStateOf<String?>(sessionInitialOptionTargetId) }
    var activeStyleFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var lastStyleFocusKey by remember(visible) { mutableStateOf(persistedStyleFocusKey) }
    var revealStyleRail by remember(visible) {
        mutableStateOf(sessionInitialSelectedOptionId != null)
    }
    var languageFocusToken by remember(visible) { mutableStateOf(0) }
    var optionFocusToken by remember(visible) { mutableStateOf(0) }
    var styleFocusToken by remember(visible) { mutableStateOf(0) }
    var pendingLanguageFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusId by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusLanguageKey by remember(visible) { mutableStateOf<String?>(null) }
    var pendingStyleFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    val overlaySessionKey = remember(visible) { Any() }
    val languageInitialVisibleIndex = remember(visible, languageItems, sessionInitialLanguageKey) {
        preferredVisibleStartIndex(languageItems.indexOfFirst { it.key == sessionInitialLanguageKey })
    }
    val optionInitialVisibleIndex = remember(visible, sessionInitialSubtitleOptions, sessionInitialSelectedOptionId) {
        preferredVisibleStartIndex(sessionInitialSubtitleOptions.indexOfFirst { it.id == sessionInitialSelectedOptionId })
    }
    val languageListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = languageInitialVisibleIndex)
    }
    val optionListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = optionInitialVisibleIndex)
    }
    val styleListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = 0)
    }
    val languageItemRequesters = rememberFocusRequesterMap(languageItems.map { it.key })
    val optionItemRequesters = rememberFocusRequesterMap(subtitleOptions.map { it.id })
    val styleRequesters = rememberStyleFocusRequesters()
    val optionRailVisible = selectedLanguageKey != SubtitleOffLanguageKey
    val styleRailVisible = optionRailVisible && (revealStyleRail || selectedOptionId != null)
    val languageTargetKey: String? = remember(languageItems, lastFocusedLanguageKey, selectedLanguageKey) {
        lastFocusedLanguageKey?.takeIf { key -> languageItems.any { it.key == key } }
            ?: selectedLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull()?.key
    }
    val optionTargetId: String? = remember(subtitleOptions, optionFocusMemory, selectedLanguageKey, selectedOptionId) {
        selectedOptionId?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: optionFocusMemory[selectedLanguageKey]?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: subtitleOptions.firstOrNull()?.id
    }
    val styleTargetKey = remember(lastStyleFocusKey) {
        lastStyleFocusKey ?: StyleFocusKey.DelaySet
    }

    fun requestLanguageFocus(targetKey: String?) {
        val resolvedKey = targetKey
            ?.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull()?.key
            ?: return
        pendingLanguageFocusKey = resolvedKey
        languageFocusToken += 1
    }

    fun requestOptionFocus(
        targetId: String?,
        languageKey: String = selectedLanguageKey,
        reason: String
    ) {
        val resolvedId = targetId ?: subtitleOptions.firstOrNull()?.id
            ?: return
        if (pendingOptionFocusId == resolvedId && pendingOptionFocusLanguageKey == languageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=duplicate_pending source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        if (
            activeRail == OverlayFocusRail.OPTION &&
            languageKey == selectedLanguageKey &&
            activeOptionFocusId == resolvedId
        ) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=already_focused source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        pendingOptionFocusId = resolvedId
        pendingOptionFocusLanguageKey = languageKey
        Log.d(
            SubtitleFocusTag,
            "option_restore_schedule source=$reason language=$languageKey id=$resolvedId"
        )
        optionFocusToken += 1
    }

    fun requestStyleFocus(targetKey: String?, reason: String) {
        val requestedKey = targetKey ?: StyleFocusKey.DelaySet
        val resolvedKey = when {
            requestedKey.startsWith("${StyleFocusKey.OutlineColorPrefix}:") && !subtitleStyle.outlineEnabled -> {
                StyleFocusKey.OutlineToggle
            }
            else -> requestedKey
        }.takeIf { key -> styleRequesters.containsKey(key) } ?: StyleFocusKey.DelaySet
        if (
            pendingStyleFocusKey == resolvedKey &&
            activeRail == OverlayFocusRail.STYLE &&
            activeStyleFocusKey == resolvedKey
        ) {
            Log.d(
                SubtitleFocusTag,
                "style_focus_skip reason=already_focused source=$reason key=$resolvedKey"
            )
            return
        }
        pendingStyleFocusKey = resolvedKey
        Log.d(
            SubtitleFocusTag,
            "style_focus_schedule source=$reason key=$resolvedKey"
        )
        styleFocusToken += 1
    }

    fun moveFocusToLanguageRail() {
        requestLanguageFocus(optionEntryLanguageKey)
    }

    fun moveFocusToOptionRail() {
        optionEntryLanguageKey = lastFocusedLanguageKey ?: selectedLanguageKey
        requestOptionFocus(
            targetId = optionTargetId,
            languageKey = selectedLanguageKey,
            reason = "language_to_option"
        )
    }

    fun moveFocusBackToOptionRail() {
        val targetId = styleEntryOptionId?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: optionTargetId
        requestOptionFocus(
            targetId = targetId,
            languageKey = selectedLanguageKey,
            reason = "style_to_option"
        )
    }

    fun moveFocusToStyleRail() {
        styleEntryOptionId = optionFocusMemory[selectedLanguageKey]?.takeIf { id ->
            subtitleOptions.any { it.id == id }
        } ?: selectedOptionId?.takeIf { id ->
            subtitleOptions.any { it.id == id }
        } ?: subtitleOptions.firstOrNull()?.id
        revealStyleRail = true
        requestStyleFocus(targetKey = styleTargetKey, reason = "option_to_style")
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 76.dp)
    ) {
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            if (sessionInitialLanguageKey != SubtitleOffLanguageKey && sessionInitialSelectedOptionId != null) {
                Log.d(
                    SubtitleFocusTag,
                    "overlay_open focus=option selectedLanguage=$sessionInitialLanguageKey selectedOption=$sessionInitialSelectedOptionId showStyle=true"
                )
                requestOptionFocus(
                    targetId = sessionInitialSelectedOptionId,
                    languageKey = sessionInitialLanguageKey,
                    reason = "overlay_open"
                )
            } else {
                Log.d(
                    SubtitleFocusTag,
                    "overlay_open focus=language selectedLanguage=$sessionInitialLanguageKey showOption=${sessionInitialLanguageKey != SubtitleOffLanguageKey} showStyle=${sessionInitialSelectedOptionId != null}"
                )
                requestLanguageFocus(sessionInitialLanguageKey)
            }
        }

        LaunchedEffect(visible, sessionInitialLanguageKey, languageItems) {
            if (!visible) return@LaunchedEffect
            val targetIndex = languageItems.indexOfFirst { it.key == sessionInitialLanguageKey }
            if (targetIndex >= 0) {
                languageListState.scrollItemIntoView(targetIndex)
            }
        }

        LaunchedEffect(visible, selectedOptionId) {
            if (visible && selectedOptionId != null) {
                revealStyleRail = true
            }
        }

        LaunchedEffect(visible, styleRailVisible, styleFocusToken) {
            if (!visible || !styleRailVisible || styleFocusToken <= 0) return@LaunchedEffect
            val targetKey = pendingStyleFocusKey ?: return@LaunchedEffect
            val requester = styleRequesters[targetKey] ?: run {
                pendingStyleFocusKey = null
                return@LaunchedEffect
            }
            val targetIndex = styleListIndexForFocusKey(targetKey)
            repeat(8) { attempt ->
                styleListState.scrollItemIntoView(targetIndex)
                Log.d(
                    SubtitleFocusTag,
                    "style_focus_request attempt=$attempt key=$targetKey activeRail=$activeRail activeStyleKey=$activeStyleFocusKey"
                )
                requester.requestFocusAfterFrames(frames = if (attempt == 0) 2 else 1)
                if (activeRail == OverlayFocusRail.STYLE && activeStyleFocusKey == targetKey) {
                    Log.d(
                        SubtitleFocusTag,
                        "style_focus_complete attempt=$attempt key=$targetKey"
                    )
                    pendingStyleFocusKey = null
                    return@LaunchedEffect
                }
            }
            Log.d(
                SubtitleFocusTag,
                "style_focus_timeout key=$targetKey activeStyleKey=$activeStyleFocusKey"
            )
            pendingStyleFocusKey = null
        }

        Column(verticalArrangement = Arrangement.Bottom) {
            Text(
                text = stringResource(R.string.subtitle_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SubtitleLanguageRail(
                    items = languageItems,
                    selectedLanguageKey = selectedLanguageKey,
                    listState = languageListState,
                    itemFocusRequesters = languageItemRequesters,
                    focusTargetKey = pendingLanguageFocusKey,
                    focusToken = languageFocusToken,
                    onFocusRequestConsumed = {
                        pendingLanguageFocusKey = null
                    },
                    onMoveRight = if (optionRailVisible && subtitleOptions.isNotEmpty()) ::moveFocusToOptionRail else null,
                    onLanguageSelected = { languageKey ->
                        Log.d(
                            SubtitleFocusTag,
                            "language_select key=$languageKey previous=$selectedLanguageKey"
                        )
                        selectedLanguageKey = languageKey
                        lastFocusedLanguageKey = languageKey
                        optionEntryLanguageKey = languageKey
                        activeRail = OverlayFocusRail.LANGUAGE
                        if (languageKey == SubtitleOffLanguageKey) {
                            selectedOptionId = null
                            revealStyleRail = false
                            onDisableSubtitles()
                        } else {
                            val nextOptions = buildSessionOptions(languageKey, selectedOptionId)
                            val nextSelectedId = selectedOptionId?.takeIf { id ->
                                nextOptions.any { it.id == id }
                            }
                            selectedOptionId = nextSelectedId
                            val nextTargetId = nextSelectedId
                                ?: optionFocusMemory[languageKey]?.takeIf { id ->
                                    nextOptions.any { it.id == id }
                                }
                                ?: nextOptions.firstOrNull()?.id
                            if (nextTargetId != null) {
                                optionFocusMemory = optionFocusMemory + (languageKey to nextTargetId)
                            }
                            revealStyleRail = nextSelectedId != null
                            requestOptionFocus(
                                targetId = nextTargetId,
                                languageKey = languageKey,
                                reason = "language_select"
                            )
                        }
                    },
                    onLanguageFocused = { key ->
                        lastFocusedLanguageKey = key
                        optionEntryLanguageKey = key
                        activeRail = OverlayFocusRail.LANGUAGE
                        activeStyleFocusKey = null
                    }
                )

                RailFadeIn(visible = optionRailVisible) {
                    SubtitleOptionsRail(
                        selectedLanguageKey = selectedLanguageKey,
                        options = subtitleOptions,
                        isLoadingAddons = sessionIsLoadingAddons,
                        listState = optionListState,
                        itemFocusRequesters = optionItemRequesters,
                        focusTargetId = pendingOptionFocusId,
                        focusLanguageKey = pendingOptionFocusLanguageKey,
                        focusToken = optionFocusToken,
                        onFocusRequestConsumed = {
                            pendingOptionFocusId = null
                            pendingOptionFocusLanguageKey = null
                        },
                        onOptionFocused = {
                            optionFocusMemory = optionFocusMemory + (selectedLanguageKey to it)
                            styleEntryOptionId = it
                            activeOptionFocusId = it
                            activeRail = OverlayFocusRail.OPTION
                            activeStyleFocusKey = null
                        },
                        onMoveLeft = ::moveFocusToLanguageRail,
                        onMoveRight = ::moveFocusToStyleRail,
                        onInternalTrackSelected = { optionId, trackIndex ->
                            selectedOptionId = optionId
                            optionFocusMemory = optionFocusMemory + (selectedLanguageKey to optionId)
                            styleEntryOptionId = optionId
                            activeOptionFocusId = optionId
                            activeRail = OverlayFocusRail.OPTION
                            onInternalTrackSelected(trackIndex)
                            revealStyleRail = true
                        },
                        onAddonSubtitleSelected = { optionId, subtitle ->
                            selectedOptionId = optionId
                            optionFocusMemory = optionFocusMemory + (selectedLanguageKey to optionId)
                            styleEntryOptionId = optionId
                            activeOptionFocusId = optionId
                            activeRail = OverlayFocusRail.OPTION
                            onAddonSubtitleSelected(subtitle)
                            revealStyleRail = true
                        }
                    )
                }

                RailFadeIn(visible = styleRailVisible) {
                    SubtitleStyleRail(
                        subtitleStyle = subtitleStyle,
                        subtitleDelayMs = subtitleDelayMs,
                        listState = styleListState,
                        onMoveLeft = ::moveFocusBackToOptionRail,
                        focusRequesters = styleRequesters,
                        onStyleFocused = {
                            activeRail = OverlayFocusRail.STYLE
                            activeStyleFocusKey = it
                            pendingStyleFocusKey = null
                            lastStyleFocusKey = it
                            persistedStyleFocusKey = it
                        },
                        onEvent = onEvent
                    )
                }
            }
        }
    }
}

@Composable
private fun RailFadeIn(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    if (!visible) return

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RailFadeDurationMs,
                easing = FastOutLinearInEasing
            )
        )
    }

    Box(modifier = Modifier.graphicsLayer(alpha = alpha.value)) {
        content()
    }
}

@Composable
private fun SubtitleLanguageRail(
    items: List<SubtitleLanguageRailItem>,
    selectedLanguageKey: String,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onMoveRight: (() -> Unit)?,
    onLanguageSelected: (String) -> Unit,
    onLanguageFocused: (String) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val targetKey = focusTargetKey ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.key == targetKey }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        Log.d(
            SubtitleFocusTag,
            "language_restore_request key=$targetKey index=$targetIndex selected=$selectedLanguageKey firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        itemFocusRequesters[targetKey]?.requestFocusAfterFrames()
        Log.d(
            SubtitleFocusTag,
            "language_restore_complete key=$targetKey index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        onFocusRequestConsumed()
    }

    RailColumn(width = 200.dp, title = stringResource(R.string.subtitle_tab_languages)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            modifier = Modifier
                .heightIn(max = 720.dp)
        ) {
            items(items = items, key = { item -> item.key }) { item ->
                SubtitleLanguageCard(
                    item = item,
                    isSelected = item.key == selectedLanguageKey,
                    onClick = { onLanguageSelected(item.key) },
                    focusRequester = itemFocusRequesters[item.key],
                    onMoveRight = onMoveRight,
                    onFocused = { onLanguageFocused(item.key) }
                )
            }
        }
    }
}

@Composable
private fun SubtitleOptionsRail(
    selectedLanguageKey: String,
    options: List<SubtitleOptionRailItem>,
    isLoadingAddons: Boolean,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetId: String?,
    focusLanguageKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onOptionFocused: (String) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onInternalTrackSelected: (String, Int) -> Unit,
    onAddonSubtitleSelected: (String, Subtitle) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val requestLanguageKey = focusLanguageKey
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        if (requestLanguageKey != selectedLanguageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_drop reason=language_mismatch requestLanguage=$requestLanguageKey selectedLanguage=$selectedLanguageKey target=$focusTargetId"
            )
            onFocusRequestConsumed()
            return@LaunchedEffect
        }
        val targetId = focusTargetId
            ?.takeIf { id -> options.any { it.id == id } }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        val targetIndex = options.indexOfFirst { it.id == targetId }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        Log.d(
            SubtitleFocusTag,
            "option_restore_request language=$selectedLanguageKey id=$targetId index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        itemFocusRequesters[targetId]?.requestFocusAfterFrames()
        Log.d(
            SubtitleFocusTag,
            "option_restore_complete language=$selectedLanguageKey id=$targetId index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        onFocusRequestConsumed()
    }

    RailColumn(width = 300.dp, title = stringResource(R.string.subtitle_dialog_title)) {
        when {
            selectedLanguageKey == SubtitleOffLanguageKey -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_none))
            }

            options.isEmpty() && isLoadingAddons -> {
                OverlayLoadingCard(text = stringResource(R.string.subtitle_loading_addon))
            }

            options.isEmpty() -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_no_addon))
            }

            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    modifier = Modifier
                        .heightIn(max = 720.dp)
                ) {
                    items(items = options, key = { option -> option.id }) { option ->
                        SubtitleOptionCard(
                            item = option,
                            focusRequester = itemFocusRequesters[option.id],
                            onMoveLeft = onMoveLeft,
                            onMoveRight = onMoveRight,
                            onFocused = { onOptionFocused(option.id) },
                            onClick = {
                                when (option.kind) {
                                    SubtitleOptionKind.INTERNAL -> {
                                        option.internalTrackIndex?.let { trackIndex ->
                                            onInternalTrackSelected(option.id, trackIndex)
                                        }
                                    }

                                    SubtitleOptionKind.ADDON -> {
                                        option.addonSubtitle?.let { subtitle ->
                                            onAddonSubtitleSelected(option.id, subtitle)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleStyleRail(
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    listState: LazyListState,
    onMoveLeft: () -> Unit,
    focusRequesters: Map<String, FocusRequester>,
    onStyleFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    RailColumn(width = 280.dp, title = stringResource(R.string.subtitle_style_title)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier
                .heightIn(max = 720.dp)
        ) {
            item {
                Card(
                    onClick = { onEvent(PlayerEvent.OnShowSubtitleDelayOverlay) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.DelaySet]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.DelaySet) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_tab_delay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = formatSubtitleDelay(subtitleDelayMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_font_size)) {
                    StepperRow(
                        value = "${subtitleStyle.size}%",
                        onDecrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) },
                        onIncrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeIncrease],
                        decrementFocusKey = StyleFocusKey.FontSizeDecrease,
                        incrementFocusKey = StyleFocusKey.FontSizeIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_bold)) {
                    ToggleChip(
                        label = if (subtitleStyle.bold) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                        isEnabled = subtitleStyle.bold,
                        onMoveLeft = onMoveLeft,
                        focusRequester = focusRequesters[StyleFocusKey.Bold],
                        focusKey = StyleFocusKey.Bold,
                        onFocused = onStyleFocused,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_text_color)) {
                    ColorChipRow(
                        colors = OverlayTextColors,
                        selectedColor = subtitleStyle.textColor,
                        onMoveLeft = onMoveLeft,
                        focusRequesters = focusRequesters,
                        focusKeyPrefix = StyleFocusKey.TextColorPrefix,
                        onFocused = onStyleFocused,
                        onColorSelected = { color -> onEvent(PlayerEvent.OnSetSubtitleTextColor(color)) }
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_outline)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToggleChip(
                            label = if (subtitleStyle.outlineEnabled) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                            isEnabled = subtitleStyle.outlineEnabled,
                            onMoveLeft = onMoveLeft,
                            focusRequester = focusRequesters[StyleFocusKey.OutlineToggle],
                            focusKey = StyleFocusKey.OutlineToggle,
                            onFocused = onStyleFocused,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                        )
                        ColorChipRow(
                            colors = OverlayOutlineColors,
                            selectedColor = subtitleStyle.outlineColor,
                            enabled = subtitleStyle.outlineEnabled,
                            onMoveLeft = onMoveLeft,
                            focusRequesters = focusRequesters,
                            focusKeyPrefix = StyleFocusKey.OutlineColorPrefix,
                            onFocused = onStyleFocused,
                            onColorSelected = { color ->
                                if (!subtitleStyle.outlineEnabled) {
                                    onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                                }
                                onEvent(PlayerEvent.OnSetSubtitleOutlineColor(color))
                            }
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_bottom_offset)) {
                    StepperRow(
                        value = subtitleStyle.verticalOffset.toString(),
                        onDecrease = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) },
                        onIncrease = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.OffsetDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.OffsetIncrease],
                        decrementFocusKey = StyleFocusKey.OffsetDecrease,
                        incrementFocusKey = StyleFocusKey.OffsetIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                Card(
                    onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    modifier = Modifier
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.Reset]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.Reset) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_reset_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RailColumn(
    width: androidx.compose.ui.unit.Dp,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioColors.TextTertiary
        )
        content()
    }
}

@Composable
private fun SubtitleLanguageCard(
    item: SubtitleLanguageRailItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onMoveRight: (() -> Unit)?,
    onFocused: () -> Unit
) {
    val textColor = if (isSelected) NuvioColors.OnSecondary else Color.White
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveToOptionsKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveToOptionsKey -> {
                        val moveRight = onMoveRight ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveRight()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .onFocusChanged {
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "language_focused key=${item.key} label=${item.label}"
                    )
                    onFocused()
                }
            },
        colors = overlayCardColors(selected = isSelected),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.count > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
                CountBadge(count = item.count, selected = isSelected)
            }
        }
    }
}

@Composable
private fun SubtitleOptionCard(
    item: SubtitleOptionRailItem,
    focusRequester: FocusRequester?,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val titleColor = if (item.isSelected) NuvioColors.OnSecondary else Color.White
    val metaColor = if (item.isSelected) {
        NuvioColors.OnSecondary.copy(alpha = 0.72f)
    } else {
        NuvioColors.TextTertiary
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    val moveRightKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                onMoveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    moveRightKey -> {
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                onMoveRight()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .onFocusChanged {
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "option_focused id=${item.id} title=${item.title} selected=${item.isSelected}"
                    )
                    onFocused()
                }
            },
        colors = overlayCardColors(selected = item.isSelected),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourceChip(label = item.sourceLabel, selected = item.isSelected)
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor
                )
                if (!item.meta.isNullOrBlank()) {
                    Text(
                        text = item.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = metaColor
                    )
                }
            }
            if (item.isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioColors.OnSecondary
                )
            }
        }
    }
}

@Composable
private fun CountBadge(
    count: Int,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    NuvioColors.Secondary.copy(alpha = 0.85f)
                },
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NuvioColors.OnSecondary else NuvioColors.OnSecondary
        )
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (selected) {
                    NuvioColors.OnSecondary.copy(alpha = 0.14f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                RoundedCornerShape(999.dp)
            )
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = NuvioColors.OnSecondary.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                NuvioColors.OnSecondary.copy(alpha = 0.9f)
            } else {
                Color.White.copy(alpha = 0.78f)
            }
        )
    }
}

@Composable
private fun OverlayLoadingCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextTertiary
            )
        }
    }
}

@Composable
private fun OverlayEmptyCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextTertiary
        )
    }
}

@Composable
private fun OverlaySectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        content()
    }
}

@Composable
private fun StepperRow(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    valueWidth: Dp = 84.dp,
    onMoveLeft: (() -> Unit)? = null,
    decrementFocusRequester: FocusRequester? = null,
    incrementFocusRequester: FocusRequester? = null,
    decrementFocusKey: String? = null,
    incrementFocusKey: String? = null,
    onFocusChanged: ((String) -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            onClick = onDecrease,
            onMoveLeft = onMoveLeft,
            focusRequester = decrementFocusRequester,
            focusKey = decrementFocusKey,
            onFocused = onFocusChanged
        )
        Box(
            modifier = Modifier
                .width(valueWidth)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            onClick = onIncrease,
            focusRequester = incrementFocusRequester,
            focusKey = incrementFocusKey,
            onFocused = onFocusChanged
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, NuvioColors.FocusRing, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = IconButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isEnabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    Card(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        } else {
            Modifier
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        },
        colors = overlayCardColors(selected = isEnabled),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) NuvioColors.OnSecondary else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ColorChipRow(
    colors: List<Color>,
    selectedColor: Int,
    enabled: Boolean = true,
    onMoveLeft: (() -> Unit)? = null,
    focusRequesters: Map<String, FocusRequester>,
    focusKeyPrefix: String,
    onFocused: ((String) -> Unit)? = null,
    onColorSelected: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(colors) { color ->
            val focusKey = "$focusKeyPrefix:${color.toArgb()}"
            ColorChip(
                color = if (enabled) color else color.copy(alpha = 0.35f),
                isSelected = color.toArgb() == selectedColor,
                enabled = enabled,
                onMoveLeft = if (color == colors.firstOrNull()) onMoveLeft else null,
                focusRequester = focusRequesters[focusKey],
                focusKey = focusKey,
                onFocused = onFocused,
                onClick = { onColorSelected(color.toArgb()) }
            )
        }
    }
}

@Composable
private fun ColorChip(
    color: Color,
    isSelected: Boolean,
    enabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        modifier = Modifier
            .size(32.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, Color.White, CircleShape)
                    isFocused -> Modifier.border(2.dp, NuvioColors.FocusRing, CircleShape)
                    else -> Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        shape = CardDefaults.shape(CircleShape)
        ,
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {}
}

@Composable
private fun overlayCardColors(selected: Boolean) = CardDefaults.colors(
    containerColor = if (selected) NuvioColors.Secondary else Color.Transparent,
    focusedContainerColor = if (selected) NuvioColors.Secondary else Color.Transparent
)

@Composable
private fun overlayCardBorder() = CardDefaults.border(
    border = Border(
        border = BorderStroke(2.dp, Color.Transparent),
        shape = RoundedCornerShape(12.dp)
    ),
    focusedBorder = Border(
        border = BorderStroke(2.dp, NuvioColors.FocusRing),
        shape = RoundedCornerShape(12.dp)
    )
)

private object StyleFocusKey {
    const val FontSizeDecrease = "font_size_decrease"
    const val FontSizeIncrease = "font_size_increase"
    const val Bold = "bold"
    const val OutlineToggle = "outline_toggle"
    const val OffsetDecrease = "offset_decrease"
    const val OffsetIncrease = "offset_increase"
    const val DelaySet = "delay_set"
    const val Reset = "reset"
    const val TextColorPrefix = "text_color"
    const val OutlineColorPrefix = "outline_color"
}

private enum class OverlayFocusRail {
    LANGUAGE,
    OPTION,
    STYLE
}

private fun styleListIndexForFocusKey(focusKey: String): Int {
    return when {
        focusKey == StyleFocusKey.DelaySet -> 0
        focusKey == StyleFocusKey.FontSizeDecrease || focusKey == StyleFocusKey.FontSizeIncrease -> 1
        focusKey == StyleFocusKey.Bold -> 2
        focusKey.startsWith("${StyleFocusKey.TextColorPrefix}:") -> 3
        focusKey == StyleFocusKey.OutlineToggle || focusKey.startsWith("${StyleFocusKey.OutlineColorPrefix}:") -> 4
        focusKey == StyleFocusKey.OffsetDecrease || focusKey == StyleFocusKey.OffsetIncrease -> 5
        focusKey == StyleFocusKey.Reset -> 6
        else -> 0
    }
}

@Composable
private fun rememberFocusRequesterMap(keys: List<String>): Map<String, FocusRequester> {
    return remember(keys) { keys.associateWith { FocusRequester() } }
}

@Composable
private fun rememberStyleFocusRequesters(): Map<String, FocusRequester> {
    return remember {
        listOf(
            StyleFocusKey.FontSizeDecrease,
            StyleFocusKey.FontSizeIncrease,
            StyleFocusKey.Bold,
            StyleFocusKey.OutlineToggle,
            StyleFocusKey.OffsetDecrease,
            StyleFocusKey.OffsetIncrease,
            StyleFocusKey.DelaySet,
            StyleFocusKey.Reset
        ).associateWith { FocusRequester() } +
            OverlayTextColors.associate { color ->
                "${StyleFocusKey.TextColorPrefix}:${color.toArgb()}" to FocusRequester()
            } +
            OverlayOutlineColors.associate { color ->
                "${StyleFocusKey.OutlineColorPrefix}:${color.toArgb()}" to FocusRequester()
            }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollItemIntoView(
    targetIndex: Int,
    contextItemsBefore: Int = 1
) {
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem((targetIndex - contextItemsBefore).coerceAtLeast(0))
}

private fun preferredVisibleStartIndex(targetIndex: Int): Int {
    if (targetIndex < 0) return 0
    return (targetIndex - 1).coerceAtLeast(0)
}

private data class SubtitleLanguageRailItem(
    val key: String,
    val label: String,
    val count: Int
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val isSelected: Boolean,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null
)

private fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    noneLabel: String
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    val sortedItems = counts.entries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, count) ->
            SubtitleLanguageRailItem(
                key = key,
                label = subtitleLanguageLabel(key),
                count = count
            )
        }

    return listOf(
        SubtitleLanguageRailItem(
            key = SubtitleOffLanguageKey,
            label = noneLabel,
            count = 0
        )
    ) + sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    installedAddonOrder: List<String>,
    selectedOptionId: String?,
    builtInLabel: String,
    forcedLabel: String
): List<SubtitleOptionRailItem> {
    if (selectedLanguageKey == SubtitleOffLanguageKey) return emptyList()

    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            SubtitleOptionRailItem(
                id = "internal:${track.index}",
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = builtInLabel,
                meta = listOfNotNull(
                    track.codec,
                    if (track.isForced) forcedLabel else null
                ).joinToString(" • ").ifBlank { null },
                isSelected = "internal:${track.index}" == selectedOptionId,
                internalTrackIndex = track.index
            )
        }

    val addonItems = addonSubtitles
        .withIndex()
        .filter { (_, subtitle) -> normalizeOverlayLanguageKey(subtitle.lang) == selectedLanguageKey }
        .sortedWith(
            compareBy(
                { (index, subtitle) -> addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE },
                { (index, _) -> index }
            )
        )
        .distinctBy { (_, subtitle) -> addonSubtitleOptionId(subtitle) }
        .map { (_, subtitle) ->
            val optionId = addonSubtitleOptionId(subtitle)
            SubtitleOptionRailItem(
                id = optionId,
                kind = SubtitleOptionKind.ADDON,
                title = Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)),
                sourceLabel = subtitle.addonName,
                meta = subtitle.id.takeIf { it.isNotBlank() && it != subtitle.lang },
                isSelected = optionId == selectedOptionId,
                addonSubtitle = subtitle
            )
        }

    return internalItems + addonItems
}

private fun selectedSubtitleLanguageKey(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun selectedSubtitleOptionId(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String? {
    selectedAddonSubtitle?.let { subtitle ->
        return addonSubtitleOptionId(subtitle)
    }

    internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { track ->
            return "internal:${track.index}"
        }

    internalTracks
        .firstOrNull { it.isSelected }
        ?.let { track ->
            return "internal:${track.index}"
        }

    return null
}

private fun addonSubtitleOptionId(subtitle: Subtitle): String {
    return "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}"
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String): String {
    return when (key) {
        SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none")
        SubtitleUnknownLanguageKey -> "Unknown"
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    else -> subtitleLanguageLabel(key).lowercase()
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return when {
        delayMs > 0 -> "+${delayMs}ms"
        delayMs < 0 -> "${delayMs}ms"
        else -> "0ms"
    }
}
