@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
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
    TRAKT,
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
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

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
        category = SettingsCategory.TRAKT,
        title = "Trakt",
        rawIconRes = R.raw.trakt_tv_glyph,
        subtitle = stringResource(R.string.settings_trakt_subtitle),
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
    onNavigateToTrakt: () -> Unit = {},
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
    var allowDetailAutofocus by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        runCatching { railContainerFocusRequester.requestFocus() }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        val requester = contentFocusRequesters[category]
        val requested = if (requester != null) {
            runCatching { requester.requestFocus() }.isSuccess
        } else {
            false
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
            var railHadFocus by remember { mutableStateOf(false) }
            val railListState = rememberLazyListState()

            val onSectionClick: (SettingsSectionSpec) -> Unit = { section ->
                if (section.destination == SettingsSectionDestination.External) {
                    when (section.category) {
                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                        SettingsCategory.TRAKT -> onNavigateToTrakt()
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
                                    .offset { IntOffset(pillLeft.roundToInt(), pillTop.roundToInt()) }
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
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    val justGainedFocus = !railHadFocus && state.hasFocus
                                    railHadFocus = state.hasFocus
                                    if (justGainedFocus) {
                                        val requester = railFocusRequesters[selectedCategory]
                                        val requested = if (requester != null) {
                                            runCatching { requester.requestFocus() }.isSuccess
                                        } else {
                                            false
                                        }
                                        if (!requested) {
                                            focusManager.moveFocus(FocusDirection.Enter)
                                        }
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
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
                                if (state.hasFocus && !allowDetailAutofocus) {
                                    railFocusRequesters[selectedCategory]?.let { requester ->
                                        runCatching { requester.requestFocus() }
                                    }
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxHeight()
                                .widthIn(max = 880.dp)
                                .fillMaxWidth()
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
                                    .offset { IntOffset(bounds.left.roundToInt(), pillTop.roundToInt()) }
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
                            .fillMaxSize()
                            .onFocusChanged { state ->
                                val justGainedFocus = !railHadFocus && state.hasFocus
                                railHadFocus = state.hasFocus
                                if (justGainedFocus) {
                                    val requester = railFocusRequesters[selectedCategory]
                                    val requested = if (requester != null) {
                                        runCatching { requester.requestFocus() }.isSuccess
                                    } else {
                                        false
                                    }
                                    if (!requested) {
                                        focusManager.moveFocus(FocusDirection.Down)
                                    }
                                }
                            }
                            .onPreviewKeyEvent { event ->
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
                                    val requested = railFocusRequesters[selectedCategory]?.let { requester ->
                                        runCatching { requester.requestFocus() }.isSuccess
                                    } ?: false
                                    if (!requested) {
                                        runCatching { railContainerFocusRequester.requestFocus() }
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.hasFocus && !allowDetailAutofocus) {
                                railFocusRequesters[selectedCategory]?.let { requester ->
                                    runCatching { requester.requestFocus() }
                                }
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
        SettingsCategory.TRAKT -> Unit
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
