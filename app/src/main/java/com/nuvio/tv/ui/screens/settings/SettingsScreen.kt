@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.SettingsUiStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    EXPERIENCE,
    ACCOUNT,
    PROFILES,
    APPEARANCE,
    LAYOUT,
    CONTENT_DISCOVERY,
    INTEGRATION,
    PLAYBACK,
    ADVANCED,
    TRACKING,
    ABOUT,
    DEBUG
}

private enum class IntegrationSettingsSection {
    Hub,
    Debrid,
    Tmdb,
    MdbList,
    AnimeSkip
}

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val subtitle: String,
    val destination: SettingsSectionDestination
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
// The rail gets the same treatment as the options pane: bring the item into view, then keep
// asking for a short while as it settles.
private const val SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS = 200L

/**
 * Stands for "no attempt handed focus to the rail container". Attempts are numbered from one, so
 * this has to sit outside that range: at zero it matches the number the counter starts on, and the
 * first time the rail takes focus the mark reads as set and the restoration is skipped.
 */
private const val NO_RAIL_FALLBACK = -1L
private const val SETTINGS_TAB_FOCUS_SELECT_DELAY_MS = 140L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

// The focus window has two parts: SETTINGS_DETAIL_FOCUS_DELAY_MS before the first request, then
// this long retrying once per frame, so a category has both added together before focus falls
// back to a directional move. This part is taken from the enter animation so the two cannot
// drift apart, and is timed rather than counted in frames since a frame count assumes a
// refresh rate. The worst case wait before the fallback is SETTINGS_DETAIL_FOCUS_DELAY_MS plus
// this window, not this window alone.
private const val SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS = SETTINGS_DETAIL_ANIM_IN_DURATION_MS

private sealed interface ExperienceModeLoadState {
    data object Loading : ExperienceModeLoadState
    data class Loaded(val mode: ExperienceMode?) : ExperienceModeLoadState
}

@Composable
private fun rememberSettingsSectionSpecs() = listOf(
    SettingsSectionSpec(
        category = SettingsCategory.EXPERIENCE,
        title = stringResource(R.string.settings_experience),
        icon = Icons.Default.Tune,
        subtitle = stringResource(R.string.settings_experience_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ACCOUNT,
        title = stringResource(R.string.settings_account),
        icon = Icons.Default.Person,
        subtitle = stringResource(R.string.settings_account_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PROFILES,
        title = stringResource(R.string.settings_profiles),
        icon = Icons.Default.People,
        subtitle = stringResource(R.string.settings_profiles_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.APPEARANCE,
        title = stringResource(R.string.appearance_title),
        icon = Icons.Default.Palette,
        subtitle = stringResource(R.string.appearance_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.LAYOUT,
        title = stringResource(R.string.settings_layout),
        icon = Icons.Default.GridView,
        subtitle = stringResource(R.string.settings_layout_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.CONTENT_DISCOVERY,
        title = stringResource(R.string.settings_content_discovery),
        icon = Icons.Default.Explore,
        subtitle = stringResource(R.string.settings_content_discovery_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.INTEGRATION,
        title = stringResource(R.string.settings_integration),
        icon = Icons.Default.Link,
        subtitle = "",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLAYBACK,
        title = stringResource(R.string.settings_playback),
        icon = Icons.Rounded.PlayArrow,
        subtitle = stringResource(R.string.settings_playback_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.TRACKING,
        title = stringResource(R.string.settings_tracking_title),
        icon = Icons.Default.Sync,
        subtitle = stringResource(R.string.settings_tracking_subtitle),
        destination = SettingsSectionDestination.External
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ABOUT,
        title = stringResource(R.string.about_title),
        icon = Icons.Default.Info,
        subtitle = stringResource(R.string.settings_about_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ADVANCED,
        title = stringResource(R.string.settings_advanced),
        icon = Icons.Default.Build,
        subtitle = stringResource(R.string.settings_advanced_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.DEBUG,
        title = stringResource(R.string.settings_debug),
        icon = Icons.Default.BugReport,
        subtitle = stringResource(R.string.settings_debug_subtitle),
        destination = SettingsSectionDestination.Inline
    )
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToTracking: () -> Unit = {},
    onNavigateToAddons: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel(),
    experienceModeViewModel: ExperienceModeSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()
    val experienceModeState by remember(experienceModeViewModel) {
        experienceModeViewModel.mode.map<ExperienceMode?, ExperienceModeLoadState> {
            ExperienceModeLoadState.Loaded(it)
        }
    }.collectAsStateWithLifecycle(initialValue = ExperienceModeLoadState.Loading)
    val loadedExperienceMode = (experienceModeState as? ExperienceModeLoadState.Loaded)?.mode
    val experienceModeLoaded = experienceModeState is ExperienceModeLoadState.Loaded

    if (!experienceModeLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
        )
        return
    }

    val isEssentialMode = loadedExperienceMode == ExperienceMode.ESSENTIAL

    val allSectionSpecs = rememberSettingsSectionSpecs()
    val visibleSections = remember(isPrimaryProfileActive, isEssentialMode, allSectionSpecs) {
        allSectionSpecs.filter { section ->
            when (section.category) {
                SettingsCategory.EXPERIENCE -> false
                SettingsCategory.DEBUG -> BuildConfig.IS_DEBUG_BUILD && !isEssentialMode
                SettingsCategory.PROFILES -> isPrimaryProfileActive
                SettingsCategory.ACCOUNT -> isPrimaryProfileActive
                SettingsCategory.LAYOUT -> true
                SettingsCategory.CONTENT_DISCOVERY -> true
                SettingsCategory.INTEGRATION -> true
                SettingsCategory.ADVANCED -> true
                else -> true
            }
        }
    }

    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val isHorizonStyle = NuvioTheme.settingsUiStyle == SettingsUiStyle.HORIZON
    var selectedCategory by rememberSaveable {
        mutableStateOf(
            visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        )
    }
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
        mapOf(
            SettingsCategory.APPEARANCE to FocusRequester(),
            SettingsCategory.EXPERIENCE to FocusRequester(),
            SettingsCategory.PROFILES to FocusRequester(),
            SettingsCategory.LAYOUT to FocusRequester(),
            SettingsCategory.CONTENT_DISCOVERY to FocusRequester(),
            SettingsCategory.INTEGRATION to FocusRequester(),
            SettingsCategory.PLAYBACK to FocusRequester(),
            SettingsCategory.ADVANCED to FocusRequester(),
            SettingsCategory.ABOUT to FocusRequester(),
            SettingsCategory.ACCOUNT to FocusRequester()
        )
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationDebridFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
    val integrationAnimeSkipFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }
    // Saveable so it survives a trip out to one of the screens a category opens. The pane bounces
    // focus back to the rail whenever it gains focus while this is false, so a plain remember made
    // every return from those screens land on the rail rather than where the user had been.
    var allowDetailAutofocus by rememberSaveable { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    // The rail item that last had focus. Not the same as the selected category: an external
    // category such as tracking never becomes the selected one, and moving along the rail without
    // opening anything does not change it either. Saveable so returning from the sidebar, or from
    // a screen a category opened, comes back to the item the user left from.
    var railFocusCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val railFocusCategory = railFocusCategoryName
        ?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
        // The name surviving is not enough. Which categories the rail shows depends on the profile
        // and on essential mode, so the one that was left can be gone by the time it is returned
        // to. Restoring to it would spend the whole retry window on an item that is not there.
        ?.takeIf { category -> visibleSections.any { it.category == category } }
        ?: selectedCategory
    val railListState = rememberLazyListState()
    val railScope = rememberCoroutineScope()

    // The rail is a lazy list, so an item that has scrolled out of view has no attached requester
    // and cannot take focus. Bring it back into view first, then ask, retrying while it composes.
    // Without this, restoring to a scrolled out item silently failed and focus was left to the
    // geometric search, which is how coming back from the sidebar landed on the wrong item.
    var railHadFocus by remember { mutableStateOf(false) }
    var railFocusJob by remember { mutableStateOf<Job?>(null) }
    // The attempt whose fallback handed focus to the rail container, or 0. The container gaining
    // focus is what triggers a restoration, so without this the fallback asks for the same
    // unavailable item again instead of ending the attempt. Numbered rather than a flag so a
    // newer attempt cannot inherit an older one's mark.
    var railFallbackAttempt by remember { mutableLongStateOf(NO_RAIL_FALLBACK) }
    // The category an attempt is currently working towards, so that item can end the attempt when
    // it takes focus by any route. Only that item: cancelling on any other is how an earlier
    // version let the rail's own choice of child kill a restoration and drift away from the user.
    var railRestoringCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // Identifies an attempt. Cancellation is cooperative, so a stopped attempt can still run as
    // far as its next suspension point, and without this its cleanup would clear state belonging
    // to the attempt that replaced it.
    var railFocusAttempt by remember { mutableLongStateOf(0L) }
    val focusRailCategory: (SettingsCategory) -> Unit = { category ->
        railFocusJob?.cancel()
        railFocusJob = null
        // Cleared here, not only by the attempt that set it. A cancelled attempt is forbidden from
        // clearing it once the number has moved on, and an attempt that lands straight away never
        // sets one, so without this a target could outlive every attempt behind it.
        railRestoringCategory = null
        railFocusAttempt += 1L
        val attempt = railFocusAttempt
        // Try straight away first, without a coroutine: a suspension point before the request
        // leaves a gap for the rail to settle on a child of its own, and that child then records
        // itself as the item to come back to.
        //
        // The rail's own focusRestorer covers the ordinary case, where the item being returned to
        // is still composed and is the one the rail last had focus on. This is for what it cannot
        // do: a target that is off screen and so has no node to restore to, and a target that is
        // not the child the rail last held, such as the category a Back press names. Only those
        // reach the scroll and retry below.
        val landed = railFocusRequesters[category]
            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
        if (!landed) {
            // Set before launching, so the attempt advertises its target from the start.
            railRestoringCategory = category
            railFocusJob = railScope.launch {
                try {
                    val index = visibleSections.indexOfFirst { it.category == category }
                    // Only when it is not on screen at all. An item that is even partly on screen
                    // can usually take focus as it is, and scrollToItem would drag it to the start
                    // of the viewport and move the whole rail under the user for nothing.
                    // Visibility is not the same as having an attached requester, which is what
                    // the retry below is for.
                    if (index >= 0 && !railListState.isItemVisible(index)) {
                        runCatching { railListState.scrollToItem(index) }
                    }
                    val deadline =
                        System.nanoTime() + SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
                    var focused = false
                    while (!focused && attempt == railFocusAttempt && System.nanoTime() < deadline) {
                        focused = railFocusRequesters[category]
                            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
                        if (!focused) withFrameNanos { }
                    }
                    // Only when the rail does not already hold focus. If it does, focus is
                    // somewhere sensible in it already, and asking the container again would
                    // neither move anything nor produce the transition that clears the mark.
                    if (!focused && !railHadFocus && attempt == railFocusAttempt) {
                        val handedOver = runCatching {
                            railContainerFocusRequester.requestFocus()
                        }.getOrDefault(false)
                        if (handedOver) railFallbackAttempt = attempt
                    }
                } finally {
                    // Also on cancellation, so a stopped attempt does not leave its target or its
                    // job behind, and only if this is still the current attempt.
                    if (attempt == railFocusAttempt) {
                        railRestoringCategory = null
                        railFocusJob = null
                    }
                }
            }
        }
    }

    // Back inside the options pane returns to the category rail before it leaves settings, the
    // way back inside a row returns to the start of that row. Anything the pane declares itself,
    // such as the integrations sub sections, composes later and so is asked first.
    BackHandler(enabled = detailHasFocus) {
        // Cleared up front rather than after the request. The rail reports its own focus a frame
        // later, and until it does a second quick press would be consumed here again instead of
        // leaving settings. The result of the request is deliberately not read: either way this
        // press was the step back, and with the flag already cleared the next one carries on out.
        detailHasFocus = false
        focusRailCategory(railFocusCategory)
    }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        // Categories such as plugins and addons open a destination of their own, so Settings leaves
        // composition and comes back with nothing asking for the options pane: the rail is simply
        // the first thing able to take focus. Landing there loses the user's place for a trip they
        // did not take through the rail. The saved flag says the pane was where they were, so aim
        // for it and leave the rail to the case where it really was the last thing focused.
        if (allowDetailAutofocus) {
            pendingContentFocusCategory = selectedCategory
            pendingContentFocusRequestId += 1L
        } else {
            runCatching { railContainerFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        // Asked once per frame until the pane takes focus or the window closes, rather than
        // betting on one fixed delay. The pane is not always laid out when the delay expires,
        // and the fallback below moves by direction, which lands wherever is nearest the rail
        // row the user came from, usually the middle of the options.
        var requested = false
        val deadline = System.nanoTime() + SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
        while (!requested && System.nanoTime() < deadline) {
            requested = contentFocusRequesters[category]
                ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
            if (!requested) withFrameNanos { }
        }
        if (!requested) {
            focusManager.moveFocus(if (isHorizonStyle) FocusDirection.Down else FocusDirection.Right)
        }
        pendingContentFocusCategory = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = NuvioTheme.spacing.xxl,
                end = NuvioTheme.spacing.xxl,
                top = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp,
                bottom = NuvioTheme.spacing.xl
            )
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {

            val onSectionClick: (SettingsSectionSpec) -> Unit = { section ->
                if (section.destination == SettingsSectionDestination.External) {
                    // These have no options of their own to come back to: the rail row is the
                    // whole category, and it is the rail the user is leaving from. Without this
                    // the flag keeps whatever an earlier visit to the pane left in it, and the
                    // return aims at the options of a category that was never opened.
                    allowDetailAutofocus = false
                    when (section.category) {
                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                        SettingsCategory.TRACKING -> onNavigateToTracking()
                        else -> Unit
                    }
                } else {
                    if (section.category == SettingsCategory.INTEGRATION) {
                        integrationSection = IntegrationSettingsSection.Hub
                    }
                    allowDetailAutofocus = true
                    selectedCategory = section.category
                    pendingContentFocusCategory = section.category
                    pendingContentFocusRequestId += 1L
                }
            }

            if (isHorizonStyle) {
                var topBarCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var focusedTabBounds by remember { mutableStateOf<Rect?>(null) }
                val density = LocalDensity.current

                var focusedTabCategory by remember { mutableStateOf<SettingsCategory?>(null) }
                val selectFocusedTab: (SettingsCategory) -> Unit = { category ->
                    if (selectedCategory != category) {
                        if (category == SettingsCategory.INTEGRATION) {
                            integrationSection = IntegrationSettingsSection.Hub
                        }
                        allowDetailAutofocus = false
                        selectedCategory = category
                    }
                }

                LaunchedEffect(focusedTabCategory) {
                    val category = focusedTabCategory ?: return@LaunchedEffect
                    delay(SETTINGS_TAB_FOCUS_SELECT_DELAY_MS)
                    selectFocusedTab(category)
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { topBarCoordinates = it }
                    ) {
                        focusedTabBounds?.let { bounds ->
                            val glideSpec = tween<Float>(durationMillis = 250, easing = FastOutSlowInEasing)
                            val pillLeft by animateFloatAsState(bounds.left, glideSpec, label = "pillLeft")
                            val pillTop by animateFloatAsState(bounds.top, glideSpec, label = "pillTop")
                            val pillWidth by animateFloatAsState(bounds.width, glideSpec, label = "pillWidth")
                            val pillHeight by animateFloatAsState(bounds.height, glideSpec, label = "pillHeight")
                            val pillAlpha by animateFloatAsState(
                                targetValue = if (railHadFocus) 1f else 0f,
                                animationSpec = tween(durationMillis = 200),
                                label = "pillAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopLeft)
                                    .absoluteOffset { IntOffset(pillLeft.roundToInt(), pillTop.roundToInt()) }
                                    .size(
                                        width = with(density) { pillWidth.toDp() },
                                        height = with(density) { pillHeight.toDp() }
                                    )
                                    .graphicsLayer { alpha = pillAlpha }
                                    .clip(RoundedCornerShape(SettingsPillRadius))
                                    .background(NuvioTheme.colors.Secondary)
                            )
                        }
                        LazyRow(
                            state = railListState,
                            modifier = Modifier
                                .focusRequester(railContainerFocusRequester)
                                // Lets the rail resolve its own focus enter to the item that was on it when
                                // it last lost focus. Without this the enter is resolved by stepping on from
                                // whatever holds focus, which lands an item further along and drags the rail
                                // with it. It agrees with focusRailCategory rather than competing with it:
                                // both name the item the rail was left on. It cannot restore an item that is
                                // no longer composed, which is what the retry there is for.
                                .focusRestorer()
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    val justGainedFocus = !railHadFocus && state.hasFocus
                                    railHadFocus = state.hasFocus
                                    if (justGainedFocus) {
                                        if (railFallbackAttempt == railFocusAttempt) {
                                            railFallbackAttempt = NO_RAIL_FALLBACK
                                        } else {
                                            focusRailCategory(railFocusCategory)
                                        }
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    // The user moving is the one signal that is unambiguously
                                    // theirs, unlike a focus change, which the rail also produces
                                    // on its own. Stop a restoration still retrying.
                                    if (event.type == KeyEventType.KeyDown && event.key.isDirection()) {
                                        // Retired by number as well as cancelled. The loops check the
                                        // attempt number rather than isActive, so bumping it is what
                                        // actually stops them; the cancel is just the quicker of the
                                        // two.
                                        railFocusAttempt += 1L
                                        railFocusJob?.cancel()
                                        railFocusJob = null
                                        railRestoringCategory = null
                                    }
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        focusedTabCategory?.let(selectFocusedTab)
                                        allowDetailAutofocus = true
                                    }
                                    false
                                },
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
                        ) {
                            items(
                                items = visibleSections,
                                key = { it.category }
                            ) { section ->
                                SettingsTopBarTab(
                                    title = section.title,
                                    icon = section.icon,
                                    rawIconRes = section.rawIconRes,
                                    isSelected = selectedCategory == section.category,
                                    focusRequester = railFocusRequesters[section.category],
                                    onClick = { onSectionClick(section) },
                                    onFocused = {
                                        val restoringTo = railRestoringCategory
                                        if (section.category == restoringTo) {
                                            // Cleared here rather than waiting for the attempt to
                                            // reach its next suspension point. The attempt is left
                                            // running: it ends on its own once the request lands, and
                                            // a directional press stops it early.
                                            railRestoringCategory = null
                                            railFocusCategoryName = section.category.name
                                        } else if (restoringTo == null) {
                                            railFocusCategoryName = section.category.name
                                        }
                                        if (section.destination == SettingsSectionDestination.Inline) {
                                            focusedTabCategory = section.category
                                        }
                                    },
                                    onFocusedTabPositioned = { tabCoordinates ->
                                        topBarCoordinates?.let { container ->
                                            focusedTabBounds = container.localBoundingBoxOf(tabCoordinates, clipBounds = false)
                                        }
                                    }
                                )
                            }
                        }
                        SettingsHorizontalScrollIndicators(state = railListState)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                detailHasFocus = state.hasFocus
                                if (state.hasFocus && !allowDetailAutofocus) {
                                    focusRailCategory(railFocusCategory)
                                }
                            }
                    ) {
                        AnimatedContent(
                            targetState = selectedCategory,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxHeight()
                                .widthIn(max = 880.dp)
                                .fillMaxWidth(),
                            transitionSpec = {
                                val order = visibleSections.map { it.category }
                                val forward = order.indexOf(targetState) >= order.indexOf(initialState)
                                val toStart = forward != isRtl
                                (slideInHorizontally(
                                    animationSpec = tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS, easing = FastOutSlowInEasing)
                                ) { fullWidth -> if (toStart) fullWidth / 4 else -fullWidth / 4 } +
                                    fadeIn(tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS, easing = FastOutSlowInEasing)
                                        ) { fullWidth -> if (toStart) -fullWidth / 4 else fullWidth / 4 } +
                                            fadeOut(tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS))
                                    )
                            },
                            label = "settingsDetailTransition"
                        ) { animatedCategory ->
                            SettingsDetailPane(
                                selectedCategory = animatedCategory,
                                isEssentialMode = isEssentialMode,
                                allowDetailAutofocus = allowDetailAutofocus,
                                contentFocusRequesters = contentFocusRequesters,
                                experienceModeViewModel = experienceModeViewModel,
                                integrationSection = integrationSection,
                                onSelectIntegrationSection = { integrationSection = it },
                                integrationHubFocusRequester = integrationHubFocusRequester,
                                integrationDebridFocusRequester = integrationDebridFocusRequester,
                                integrationTmdbFocusRequester = integrationTmdbFocusRequester,
                                integrationMdbListFocusRequester = integrationMdbListFocusRequester,
                                integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                                onNavigateToManageProfiles = onNavigateToManageProfiles,
                                onNavigateToAddons = onNavigateToAddons,
                                onNavigateToPlugins = onNavigateToPlugins,
                                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                                onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                                onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
                            )
                        }
                    }
                }
            } else {
            val isZenRailGlide = NuvioTheme.settingsUiStyle == SettingsUiStyle.ZEN
            var railCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var focusedRailBounds by remember { mutableStateOf<Rect?>(null) }
            val density = LocalDensity.current

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .onGloballyPositioned { railCoordinates = it }
                ) {
                    if (isZenRailGlide) {
                        focusedRailBounds?.let { bounds ->
                            val pillTop by animateFloatAsState(
                                targetValue = bounds.top,
                                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                label = "railPillTop"
                            )
                            val pillAlpha by animateFloatAsState(
                                targetValue = if (railHadFocus) 1f else 0f,
                                animationSpec = tween(durationMillis = 200),
                                label = "railPillAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopLeft)
                                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), pillTop.roundToInt()) }
                                    .size(
                                        width = with(density) { bounds.width.toDp() },
                                        height = with(density) { bounds.height.toDp() }
                                    )
                                    .graphicsLayer { alpha = pillAlpha }
                                    .clip(SettingsZenRowShape)
                                    .background(settingsFocusFillColor())
                            )
                        }
                    }
                    LazyColumn(
                        state = railListState,
                        modifier = Modifier
                            .focusRequester(railContainerFocusRequester)
                            // Lets the rail resolve its own focus enter to the item that was on it when
                            // it last lost focus. Without this the enter is resolved by stepping on from
                            // whatever holds focus, which lands an item further along and drags the rail
                            // with it. It agrees with focusRailCategory rather than competing with it:
                            // both name the item the rail was left on. It cannot restore an item that is
                            // no longer composed, which is what the retry there is for.
                            .focusRestorer()
                            .fillMaxSize()
                            .onFocusChanged { state ->
                                val justGainedFocus = !railHadFocus && state.hasFocus
                                railHadFocus = state.hasFocus
                                if (justGainedFocus) {
                                    if (railFallbackAttempt == railFocusAttempt) {
                                        railFallbackAttempt = NO_RAIL_FALLBACK
                                    } else {
                                        focusRailCategory(railFocusCategory)
                                    }
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                // The user moving is the one signal that is unambiguously theirs,
                                // unlike a focus change, which the rail also produces on its own.
                                // Stop a restoration still retrying.
                                if (event.type == KeyEventType.KeyDown && event.key.isDirection()) {
                                    // Retired by number as well as cancelled. The loops check the
                                    // attempt number rather than isActive, so bumping it is what
                                    // actually stops them; the cancel is just the quicker of the
                                    // two.
                                    railFocusAttempt += 1L
                                    railFocusJob?.cancel()
                                    railFocusJob = null
                                    railRestoringCategory = null
                                }
                                val toDetailKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (event.type == KeyEventType.KeyDown && event.key == toDetailKey) {
                                    allowDetailAutofocus = true
                                    false
                                } else {
                                    false
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        items(
                            items = visibleSections,
                            key = { it.category }
                        ) { section ->
                            SettingsRailButton(
                                onFocused = {
                                    val restoringTo = railRestoringCategory
                                    if (section.category == restoringTo) {
                                        // Cleared here rather than waiting for the attempt to
                                        // reach its next suspension point. The attempt is left
                                        // running: it ends on its own once the request lands, and
                                        // a directional press stops it early.
                                        railRestoringCategory = null
                                        railFocusCategoryName = section.category.name
                                    } else if (restoringTo == null) {
                                        railFocusCategoryName = section.category.name
                                    }
                                },
                                title = section.title,
                                icon = section.icon,
                                rawIconRes = section.rawIconRes,
                                isSelected = selectedCategory == section.category,
                                focusRequester = railFocusRequesters[section.category],
                                onClick = { onSectionClick(section) },
                                onFocusedItemPositioned = if (isZenRailGlide) {
                                    { itemCoordinates ->
                                        railCoordinates?.let { container ->
                                            focusedRailBounds = container.localBoundingBoxOf(itemCoordinates, clipBounds = false)
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    SettingsVerticalScrollIndicators(state = railListState)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { event ->
                            val toRailKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                            if (event.type == KeyEventType.KeyDown && event.key == toRailKey) {
                                val movedLeft = focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)
                                if (!movedLeft) {
                                    allowDetailAutofocus = false
                                    focusRailCategory(railFocusCategory)
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            detailHasFocus = state.hasFocus
                            if (state.hasFocus && !allowDetailAutofocus) {
                                focusRailCategory(railFocusCategory)
                            }
                        }
                ) {
                    SettingsDetailPane(
                        selectedCategory = selectedCategory,
                        isEssentialMode = isEssentialMode,
                        allowDetailAutofocus = allowDetailAutofocus,
                        contentFocusRequesters = contentFocusRequesters,
                        experienceModeViewModel = experienceModeViewModel,
                        integrationSection = integrationSection,
                        onSelectIntegrationSection = { integrationSection = it },
                        integrationHubFocusRequester = integrationHubFocusRequester,
                        integrationDebridFocusRequester = integrationDebridFocusRequester,
                        integrationTmdbFocusRequester = integrationTmdbFocusRequester,
                        integrationMdbListFocusRequester = integrationMdbListFocusRequester,
                        integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                        onNavigateToManageProfiles = onNavigateToManageProfiles,
                        onNavigateToAddons = onNavigateToAddons,
                        onNavigateToPlugins = onNavigateToPlugins,
                        onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                        onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                        onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SettingsDetailPane(
    selectedCategory: SettingsCategory,
    isEssentialMode: Boolean,
    allowDetailAutofocus: Boolean,
    contentFocusRequesters: Map<SettingsCategory, FocusRequester>,
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    integrationSection: IntegrationSettingsSection,
    onSelectIntegrationSection: (IntegrationSettingsSection) -> Unit,
    integrationHubFocusRequester: FocusRequester,
    integrationDebridFocusRequester: FocusRequester,
    integrationTmdbFocusRequester: FocusRequester,
    integrationMdbListFocusRequester: FocusRequester,
    integrationAnimeSkipFocusRequester: FocusRequester,
    onNavigateToManageProfiles: () -> Unit,
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToAuthQrSignIn: () -> Unit,
    onNavigateToSupportersContributors: () -> Unit,
    onNavigateToLicensesAttributions: () -> Unit
) {
    when (selectedCategory) {
        SettingsCategory.EXPERIENCE -> EssentialAdvancedSettingsContent(
            experienceModeViewModel = experienceModeViewModel,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.EXPERIENCE]
            } else {
                null
            }
        )
        SettingsCategory.PROFILES -> ProfileSettingsContent(
            onManageProfiles = onNavigateToManageProfiles,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.PROFILES]
            } else {
                null
            }
        )
        SettingsCategory.APPEARANCE -> ThemeSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.APPEARANCE]
            } else {
                null
            }
        )
        SettingsCategory.LAYOUT -> LayoutSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.LAYOUT]
            } else {
                null
            },
            essentialMode = isEssentialMode
        )
        SettingsCategory.PLAYBACK -> if (isEssentialMode) {
            EssentialPlaybackSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        } else {
            PlaybackSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        }
        SettingsCategory.ADVANCED -> if (isEssentialMode) {
            EssentialAdvancedSettingsContent(
                experienceModeViewModel = experienceModeViewModel,
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                }
            )
        } else {
            AdvancedSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                },
                experienceModeViewModel = experienceModeViewModel
            )
        }
        SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
            selectedSection = integrationSection,
            onSelectSection = onSelectIntegrationSection,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.INTEGRATION]
            } else {
                null
            },
            hubFocusRequester = integrationHubFocusRequester,
            debridFocusRequester = integrationDebridFocusRequester,
            tmdbFocusRequester = integrationTmdbFocusRequester,
            mdbListFocusRequester = integrationMdbListFocusRequester,
            animeSkipFocusRequester = integrationAnimeSkipFocusRequester,
            autoFocusEnabled = allowDetailAutofocus
        )
        SettingsCategory.ABOUT -> AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ABOUT]
            } else {
                null
            }
        )
        SettingsCategory.CONTENT_DISCOVERY -> ContentDiscoverySettingsContent(
            onNavigateToAddons = onNavigateToAddons,
            onNavigateToPlugins = onNavigateToPlugins,
            showPlugins = AppFeaturePolicy.pluginsEnabled && !isEssentialMode,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.CONTENT_DISCOVERY]
            } else {
                null
            }
        )
        SettingsCategory.ACCOUNT -> AccountSettingsInline(
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ACCOUNT]
            } else {
                null
            }
        )
        SettingsCategory.DEBUG -> DebugSettingsContent()
        SettingsCategory.TRACKING -> Unit
    }
}

@Composable
private fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    showPlugins: Boolean,
    initialFocusRequester: FocusRequester?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_content_discovery),
            subtitle = stringResource(R.string.settings_content_discovery_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.settings_content_discovery_addons_subtitle),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.GridView,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
            if (showPlugins) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.settings_content_discovery_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Build
                )
            }
        }
    }
}

@Composable
private fun EssentialAdvancedSettingsContent(
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    initialFocusRequester: FocusRequester?
) {
    var showConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_advanced),
            subtitle = stringResource(R.string.experience_mode_switch_to_advanced_header_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.experience_mode_switch_to_advanced),
                subtitle = stringResource(R.string.experience_mode_switch_to_advanced_subtitle),
                value = stringResource(R.string.experience_mode_essential),
                onClick = { showConfirmation = true },
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }

    if (showConfirmation) {
        ExperienceModeConfirmationDialog(
            targetMode = ExperienceMode.ADVANCED,
            onConfirm = { experienceModeViewModel.setMode(ExperienceMode.ADVANCED) },
            onDismiss = { showConfirmation = false }
        )
    }
}

@Composable
private fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val accountViewModel: com.nuvio.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            com.nuvio.tv.ui.screens.account.AccountSettingsContent(
                uiState = accountUiState,
                viewModel = accountViewModel,
                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                initialFocusRequester = initialFocusRequester
            )
        }
    }
}

@Composable
private fun IntegrationSettingsContent(
    selectedSection: IntegrationSettingsSection,
    onSelectSection: (IntegrationSettingsSection) -> Unit,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    debridFocusRequester: FocusRequester,
    tmdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    autoFocusEnabled: Boolean
) {
    BackHandler(enabled = selectedSection != IntegrationSettingsSection.Hub) {
        onSelectSection(IntegrationSettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            IntegrationSettingsSection.Hub -> hubEntryFocusRequester
            IntegrationSettingsSection.Debrid -> debridFocusRequester
            IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
            IntegrationSettingsSection.MdbList -> mdbListFocusRequester
            IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        IntegrationSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_integrations_section),
                    subtitle = stringResource(R.string.settings_integrations_section_subtitle)
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val integrationHubState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = integrationHubState,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "integration_hub_debrid") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_title),
                                    subtitle = stringResource(R.string.settings_debrid_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Debrid) },
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                            item(key = "integration_hub_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) }
                                )
                            }
                            item(key = "integration_hub_mdblist") {
                                SettingsActionRow(
                                    title = "MDBList",
                                    subtitle = stringResource(R.string.settings_mdblist_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.MdbList) }
                                )
                            }
                            item(key = "integration_hub_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.AnimeSkip) }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = integrationHubState)
                    }
                }
            }
        }

        IntegrationSettingsSection.Debrid -> {
            DebridSettingsContent(
                initialFocusRequester = debridFocusRequester
            )
        }

        IntegrationSettingsSection.Tmdb -> {
            TmdbSettingsContent(
                initialFocusRequester = tmdbFocusRequester
            )
        }

        IntegrationSettingsSection.MdbList -> {
            MDBListSettingsContent(
                initialFocusRequester = mdbListFocusRequester
            )
        }

        IntegrationSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }
    }
}

/**
 * Whether the item at [index] is on screen at all. Used to decide whether the rail has to be
 * scrolled before an item can be reached, not whether the item can take focus: being listed here
 * does not promise an attached requester, which is what the retry after the scroll is for.
 */
private fun LazyListState.isItemVisible(index: Int): Boolean =
    layoutInfo.visibleItemsInfo.any { it.index == index }

/** Whether [this] is one of the directional keys, so a press of it means the user is moving. */
private fun Key.isDirection(): Boolean =
    this == Key.DirectionUp ||
        this == Key.DirectionDown ||
        this == Key.DirectionLeft ||
        this == Key.DirectionRight
