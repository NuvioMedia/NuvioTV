@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.ExtraLogoOption
import com.nuvio.tv.ui.components.ExtraLogoPicker

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.livetv.LiveTvAddPlaylistForm
import com.nuvio.tv.ui.util.rowsListDownRecovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    ACCOUNT,
    PROFILES,
    ANIME,
    EXTRA,
    APPEARANCE,
    LAYOUT,
    CONTENT_DISCOVERY,
    INTEGRATION,
    LIBRARY,
    PLAYBACK,
    LIVE_TV,
    ABOUT,
    ADVANCED,
    DEBUG
}

private enum class IntegrationSettingsSection {
    Hub,
    Debrid,
    Tmdb,
    MdbList,
    Tvdb,
    AnimeSkip,
    OpenSubtitles
}

private enum class LibrarySettingsSection {
    Hub,
    Sources,
    ConnectedServices
}

private enum class AnimeSettingsSection {
    Hub,
    ContentDiscovery,
    Layout,
    Integrations,
    Tmdb,
    MdbList,
    Tvdb,
    AnimeSkip,
    OpenSubtitles
}

private enum class ExtraSettingsSection {
    Hub,
    ContentDiscovery,
    Layout,
    Integrations,
    Tmdb,
    MdbList,
    Tvdb,
    AnimeSkip,
    OpenSubtitles
}

private val extraLogoOptions = listOf(
    ExtraLogoOption("Stella", Icons.Default.Star),
    ExtraLogoOption("Film", Icons.Default.Movie),
    ExtraLogoOption("TV", Icons.Default.LiveTv),
    ExtraLogoOption("Segnalibro", Icons.Default.Bookmark),
    ExtraLogoOption("Preferito", Icons.Default.Favorite),
    ExtraLogoOption("Fuoco", Icons.Default.Whatshot),
    ExtraLogoOption("Diamante", Icons.Default.Diamond),
    ExtraLogoOption("Fulmine", Icons.Default.Bolt),
    ExtraLogoOption("Razzo", Icons.Default.Rocket),
    ExtraLogoOption("Magia", Icons.Default.AutoAwesome),
    ExtraLogoOption("Play", Icons.Default.PlayArrow),
    ExtraLogoOption("Esplora", Icons.Default.Explore),
    ExtraLogoOption("Giochi", Icons.Default.VideogameAsset),
    ExtraLogoOption("Musica", Icons.Default.MusicNote),
    ExtraLogoOption("Palette", Icons.Default.Palette),
    ExtraLogoOption("Nuvola", Icons.Default.Cloud),
    ExtraLogoOption("Download", Icons.Default.FileDownload),
    ExtraLogoOption("Cast", Icons.Default.Cast),
    ExtraLogoOption("Estensione", Icons.Default.Extension),
    ExtraLogoOption("Cuffie", Icons.Default.Headphones)
)

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val subtitle: String
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
private const val SETTINGS_TAB_FOCUS_SELECT_DELAY_MS = 140L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

private sealed interface ExperienceModeLoadState {
    data object Loading : ExperienceModeLoadState
    data class Loaded(val mode: ExperienceMode?) : ExperienceModeLoadState
}

@Composable
private fun rememberSettingsSectionSpecs() = listOf(
    SettingsSectionSpec(
        category = SettingsCategory.ACCOUNT,
        title = stringResource(R.string.settings_account),
        icon = Icons.Default.Person,
        subtitle = stringResource(R.string.settings_account_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.PROFILES,
        title = stringResource(R.string.settings_profiles),
        icon = Icons.Default.People,
        subtitle = stringResource(R.string.settings_profiles_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.ANIME,
        title = stringResource(R.string.nav_anime),
        icon = Icons.Default.FilterDrama,
        subtitle = stringResource(R.string.settings_anime_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.EXTRA,
        title = stringResource(R.string.nav_extra),
        icon = Icons.Default.Star,
        subtitle = stringResource(R.string.extra_settings_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.APPEARANCE,
        title = stringResource(R.string.appearance_title),
        icon = Icons.Default.Palette,
        subtitle = stringResource(R.string.appearance_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.LAYOUT,
        title = stringResource(R.string.settings_layout),
        icon = Icons.Default.GridView,
        subtitle = stringResource(R.string.settings_layout_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.CONTENT_DISCOVERY,
        title = stringResource(R.string.settings_content_discovery),
        icon = Icons.Default.Explore,
        subtitle = stringResource(R.string.settings_content_discovery_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.INTEGRATION,
        title = stringResource(R.string.settings_integration),
        icon = Icons.Default.Link,
        subtitle = "",

    ),
    SettingsSectionSpec(
        category = SettingsCategory.LIBRARY,
        title = stringResource(R.string.settings_library_title),
        icon = Icons.Default.Sync,
        subtitle = stringResource(R.string.settings_library_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLAYBACK,
        title = stringResource(R.string.settings_playback),
        icon = Icons.Rounded.PlayArrow,
        subtitle = stringResource(R.string.settings_playback_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.LIVE_TV,
        title = stringResource(R.string.nav_live_tv),
        icon = Icons.Default.LiveTv,
        subtitle = stringResource(R.string.settings_live_tv_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.ABOUT,
        title = stringResource(R.string.about_title),
        icon = Icons.Default.Info,
        subtitle = stringResource(R.string.settings_about_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.ADVANCED,
        title = stringResource(R.string.settings_advanced),
        icon = Icons.Default.Build,
        subtitle = stringResource(R.string.settings_advanced_subtitle),

    ),
    SettingsSectionSpec(
        category = SettingsCategory.DEBUG,
        title = stringResource(R.string.settings_debug),
        icon = Icons.Default.BugReport,
        subtitle = stringResource(R.string.settings_debug_subtitle),

    )
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToAddons: () -> Unit = {},
    onNavigateToAnimeAddons: () -> Unit = {},
    onNavigateToExtraAddons: () -> Unit = {},
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
            SettingsCategory.PROFILES to FocusRequester(),
            SettingsCategory.ANIME to FocusRequester(),
            SettingsCategory.EXTRA to FocusRequester(),
            SettingsCategory.LAYOUT to FocusRequester(),
            SettingsCategory.CONTENT_DISCOVERY to FocusRequester(),
            SettingsCategory.INTEGRATION to FocusRequester(),
            SettingsCategory.LIBRARY to FocusRequester(),
            SettingsCategory.PLAYBACK to FocusRequester(),
            SettingsCategory.LIVE_TV to FocusRequester(),
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
    val integrationTvdbFocusRequester = remember { FocusRequester() }
    val integrationAnimeSkipFocusRequester = remember { FocusRequester() }
    val integrationOpenSubtitlesFocusRequester = remember { FocusRequester() }
    val libraryHubFocusRequester = remember { FocusRequester() }
    val librarySourcesFocusRequester = remember { FocusRequester() }
    val libraryConnectedServicesFocusRequester = remember { FocusRequester() }
    val animeHubFocusRequester = remember { FocusRequester() }
    val animeContentDiscoveryFocusRequester = remember { FocusRequester() }
    val animeLayoutFocusRequester = remember { FocusRequester() }
    val animeIntegrationsFocusRequester = remember { FocusRequester() }
    val animeTmdbFocusRequester = remember { FocusRequester() }
    val animeMdbListFocusRequester = remember { FocusRequester() }
    val animeTvdbFocusRequester = remember { FocusRequester() }
    val animeAnimeSkipFocusRequester = remember { FocusRequester() }
    val extraHubFocusRequester = remember { FocusRequester() }
    val extraContentDiscoveryFocusRequester = remember { FocusRequester() }
    val extraIntegrationsFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var librarySection by remember { mutableStateOf(LibrarySettingsSection.Hub) }
    var animeSection by remember { mutableStateOf(AnimeSettingsSection.Hub) }
    var extraSection by remember { mutableStateOf(ExtraSettingsSection.Hub) }
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

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SettingsCategory.ANIME) {
            animeSection = AnimeSettingsSection.Hub
        }
        if (selectedCategory == SettingsCategory.EXTRA) {
            extraSection = ExtraSettingsSection.Hub
        }
        if (selectedCategory == SettingsCategory.INTEGRATION) {
            integrationSection = IntegrationSettingsSection.Hub
        }
        if (selectedCategory == SettingsCategory.LIBRARY) {
            librarySection = LibrarySettingsSection.Hub
        }
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
            // Sfondo opaco (prima solo il padding era trasparente): copre la
            // schermata uscente finché questa non è pronta, così non si vedono
            // due barre sovrapposte durante il cambio di rotta (issue #11).
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.xxl,
                end = NuvioTheme.spacing.xxl,
                // Niente più barra sopra Impostazioni: il menu tab torna in
                // alto, allineato con la barra sezioni delle altre schermate.
                top = NuvioTheme.spacing.sm,
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
                if (section.category == SettingsCategory.INTEGRATION) {
                    integrationSection = IntegrationSettingsSection.Hub
                }
                allowDetailAutofocus = true
                selectedCategory = section.category
                pendingContentFocusCategory = section.category
                pendingContentFocusRequestId += 1L
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
                                        focusedTabCategory = section.category
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
                                if (state.hasFocus && !allowDetailAutofocus) {
                                    railFocusRequesters[selectedCategory]?.let { requester ->
                                        runCatching { requester.requestFocus() }
                                    }
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
                                integrationTvdbFocusRequester = integrationTvdbFocusRequester,
integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                                openSubtitlesFocusRequester = integrationOpenSubtitlesFocusRequester,
                                librarySection = librarySection,
                                onSelectLibrarySection = { librarySection = it },
                                libraryHubFocusRequester = libraryHubFocusRequester,
                                librarySourcesFocusRequester = librarySourcesFocusRequester,
                                libraryConnectedServicesFocusRequester = libraryConnectedServicesFocusRequester,
                                animeSection = animeSection,
                                onSelectAnimeSection = { animeSection = it },
                                animeHubFocusRequester = animeHubFocusRequester,
                                animeContentDiscoveryFocusRequester = animeContentDiscoveryFocusRequester,
                                animeLayoutFocusRequester = animeLayoutFocusRequester,
                                animeIntegrationsFocusRequester = animeIntegrationsFocusRequester,
                                animeTmdbFocusRequester = animeTmdbFocusRequester,
                                animeMdbListFocusRequester = animeMdbListFocusRequester,
                                animeTvdbFocusRequester = animeTvdbFocusRequester,
                                animeAnimeSkipFocusRequester = animeAnimeSkipFocusRequester,
                                extraSection = extraSection,
                                onSelectExtraSection = { extraSection = it },
                                extraHubFocusRequester = extraHubFocusRequester,
                                extraContentDiscoveryFocusRequester = extraContentDiscoveryFocusRequester,
                                extraIntegrationsFocusRequester = extraIntegrationsFocusRequester,
                                onNavigateToManageProfiles = onNavigateToManageProfiles,
                                onNavigateToAddons = onNavigateToAddons,
                                onNavigateToAnimeAddons = onNavigateToAnimeAddons,
                                onNavigateToExtraAddons = onNavigateToExtraAddons,
                                onNavigateToPlugins = onNavigateToPlugins,
                                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                                onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                                onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
                                onCategoryChange = { selectedCategory = it }
                            )
                        }
                    }
                }
            } else {
            val isZenRailGlide = NuvioTheme.settingsUiStyle == SettingsUiStyle.ZEN
            var railCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var focusedRailBounds by remember { mutableStateOf<Rect?>(null) }
            val density = LocalDensity.current
            // Fix GIÙ nel dettaglio: si muove il focus e si verifica dove è
            // finito invece di affidarsi al focus search geometrico (che da
            // una riga di dettaglio aggancia le icone del rail laterale).
            val detailContentFocusRequester = LocalContentFocusRequester.current
            val detailFocusScope = rememberCoroutineScope()
            var detailFocusInside by remember { mutableStateOf(false) }

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
                        .focusRequester(detailContentFocusRequester)
                        .focusRestorer()
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
                            } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                // GIÙ non deve mai consegnare il focus al rail laterale:
                                // si muove e, se il focus è uscito dal dettaglio, si rientra
                                // sulla riga attiva (focusRestorer) prima che il rail lo tenga.
                                // allowDetailAutofocus va aperto prima del restore, altrimenti
                                // il guard onFocusChanged sotto rimanderebbe il focus al rail.
                                detailFocusScope.launch {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    repeat(3) { withFrameNanos { } }
                                    if (!detailFocusInside) {
                                        allowDetailAutofocus = true
                                        val restored = runCatching {
                                            detailContentFocusRequester.requestFocusAfterFrames()
                                        }.getOrDefault(false)
                                        if (!restored) {
                                            runCatching {
                                                contentFocusRequesters[selectedCategory]?.requestFocus()
                                            }
                                        }
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            detailFocusInside = state.hasFocus
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
                        integrationTvdbFocusRequester = integrationTvdbFocusRequester,
                        integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                        openSubtitlesFocusRequester = integrationOpenSubtitlesFocusRequester,
                        librarySection = librarySection,
                        onSelectLibrarySection = { librarySection = it },
                        libraryHubFocusRequester = libraryHubFocusRequester,
                        librarySourcesFocusRequester = librarySourcesFocusRequester,
                        libraryConnectedServicesFocusRequester = libraryConnectedServicesFocusRequester,
                        animeSection = animeSection,
                        onSelectAnimeSection = { animeSection = it },
                        animeHubFocusRequester = animeHubFocusRequester,
                        animeContentDiscoveryFocusRequester = animeContentDiscoveryFocusRequester,
                        animeLayoutFocusRequester = animeLayoutFocusRequester,
                        animeIntegrationsFocusRequester = animeIntegrationsFocusRequester,
                        animeTmdbFocusRequester = animeTmdbFocusRequester,
                        animeMdbListFocusRequester = animeMdbListFocusRequester,
                        animeTvdbFocusRequester = animeTvdbFocusRequester,
                        animeAnimeSkipFocusRequester = animeAnimeSkipFocusRequester,
                        extraSection = extraSection,
                        onSelectExtraSection = { extraSection = it },
                        extraHubFocusRequester = extraHubFocusRequester,
                        extraContentDiscoveryFocusRequester = extraContentDiscoveryFocusRequester,
                        extraIntegrationsFocusRequester = extraIntegrationsFocusRequester,
                        onNavigateToManageProfiles = onNavigateToManageProfiles,
                        onNavigateToAddons = onNavigateToAddons,
                        onNavigateToAnimeAddons = onNavigateToAnimeAddons,
                        onNavigateToExtraAddons = onNavigateToExtraAddons,
                        onNavigateToPlugins = onNavigateToPlugins,
                        onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                        onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                        onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
                        onCategoryChange = { selectedCategory = it }
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
    integrationTvdbFocusRequester: FocusRequester,
    integrationAnimeSkipFocusRequester: FocusRequester,
    librarySection: LibrarySettingsSection,
    onSelectLibrarySection: (LibrarySettingsSection) -> Unit,
    libraryHubFocusRequester: FocusRequester,
    librarySourcesFocusRequester: FocusRequester,
    libraryConnectedServicesFocusRequester: FocusRequester,
    animeSection: AnimeSettingsSection,
    onSelectAnimeSection: (AnimeSettingsSection) -> Unit,
    animeHubFocusRequester: FocusRequester,
    animeContentDiscoveryFocusRequester: FocusRequester,
    animeLayoutFocusRequester: FocusRequester,
    animeIntegrationsFocusRequester: FocusRequester,
    animeTmdbFocusRequester: FocusRequester,
    animeMdbListFocusRequester: FocusRequester,
    animeTvdbFocusRequester: FocusRequester,
    animeAnimeSkipFocusRequester: FocusRequester,
    openSubtitlesFocusRequester: FocusRequester,
    extraSection: ExtraSettingsSection,
    onSelectExtraSection: (ExtraSettingsSection) -> Unit,
    extraHubFocusRequester: FocusRequester,
    extraContentDiscoveryFocusRequester: FocusRequester,
    extraIntegrationsFocusRequester: FocusRequester,
    onNavigateToManageProfiles: () -> Unit,
    onNavigateToAddons: () -> Unit,
    onNavigateToAnimeAddons: () -> Unit,
    onNavigateToExtraAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToAuthQrSignIn: () -> Unit,
    onNavigateToSupportersContributors: () -> Unit,
    onNavigateToLicensesAttributions: () -> Unit,
    onCategoryChange: (SettingsCategory) -> Unit
) {
    when (selectedCategory) {
        SettingsCategory.PROFILES -> ProfileSettingsContent(
            onManageProfiles = onNavigateToManageProfiles,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.PROFILES]
            } else {
                null
            }
        )
        SettingsCategory.ANIME -> AnimeSettingsContent(            selectedSection = animeSection,
            onSelectSection = onSelectAnimeSection,
            onNavigateToAnimeAddons = onNavigateToAnimeAddons,
            onNavigateToPlugins = onNavigateToPlugins,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ANIME]
            } else {
                null
            },
            hubFocusRequester = animeHubFocusRequester,
            contentDiscoveryFocusRequester = animeContentDiscoveryFocusRequester,
            layoutFocusRequester = animeLayoutFocusRequester,
            integrationsFocusRequester = animeIntegrationsFocusRequester,
            tmdbFocusRequester = animeTmdbFocusRequester,
            mdbListFocusRequester = animeMdbListFocusRequester,
            tvdbFocusRequester = animeTvdbFocusRequester,
            animeSkipFocusRequester = animeAnimeSkipFocusRequester,
            openSubtitlesFocusRequester = openSubtitlesFocusRequester,
            isEssentialMode = isEssentialMode,
            autoFocusEnabled = allowDetailAutofocus
        )
        SettingsCategory.EXTRA -> ExtraSettingsContent(
            selectedSection = extraSection,
            onSelectSection = onSelectExtraSection,
            onNavigateToExtraAddons = onNavigateToExtraAddons,
            onNavigateToPlugins = onNavigateToPlugins,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.EXTRA]
            } else {
                null
            },
            hubFocusRequester = extraHubFocusRequester,
            contentDiscoveryFocusRequester = extraContentDiscoveryFocusRequester,
            integrationsFocusRequester = extraIntegrationsFocusRequester,
            isEssentialMode = isEssentialMode,
            autoFocusEnabled = allowDetailAutofocus
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
            tvdbFocusRequester = integrationTvdbFocusRequester,
            animeSkipFocusRequester = integrationAnimeSkipFocusRequester,
            openSubtitlesFocusRequester = openSubtitlesFocusRequester,
            autoFocusEnabled = allowDetailAutofocus
        )
        SettingsCategory.LIBRARY -> LibrarySettingsHubContent(
            selectedSection = librarySection,
            onSelectSection = onSelectLibrarySection,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.LIBRARY]
            } else {
                null
            },
            hubFocusRequester = libraryHubFocusRequester,
            sourcesFocusRequester = librarySourcesFocusRequester,
            connectedServicesFocusRequester = libraryConnectedServicesFocusRequester,
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
        SettingsCategory.LIVE_TV -> LiveTvSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.LIVE_TV]
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
private fun LibrarySettingsHubContent(
    selectedSection: LibrarySettingsSection,
    onSelectSection: (LibrarySettingsSection) -> Unit,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    sourcesFocusRequester: FocusRequester,
    connectedServicesFocusRequester: FocusRequester,
    autoFocusEnabled: Boolean
) {
    BackHandler(enabled = selectedSection != LibrarySettingsSection.Hub) {
        onSelectSection(LibrarySettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            LibrarySettingsSection.Hub -> hubEntryFocusRequester
            LibrarySettingsSection.Sources -> sourcesFocusRequester
            LibrarySettingsSection.ConnectedServices -> connectedServicesFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        LibrarySettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_library_title),
                    subtitle = stringResource(R.string.settings_library_description)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val libraryHubState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = libraryHubState,
                            modifier = Modifier.rowsListDownRecovery(libraryHubState),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "library_hub_sources") {
                                SettingsActionRow(
                                    title = stringResource(R.string.tracking_sources_title),
                                    subtitle = stringResource(R.string.tracking_sources_subtitle),
                                    onClick = { onSelectSection(LibrarySettingsSection.Sources) },
                                    leadingIcon = Icons.Default.GridView,
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                            item(key = "library_hub_connected_services") {
                                SettingsActionRow(
                                    title = stringResource(R.string.settings_connected_services_title),
                                    subtitle = stringResource(R.string.settings_connected_services_subtitle),
                                    onClick = { onSelectSection(LibrarySettingsSection.ConnectedServices) },
                                    leadingIcon = Icons.Default.Link
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = libraryHubState)
                    }
                }
            }
        }

        LibrarySettingsSection.Sources -> {
            LibrarySettingsContent(
                initialFocusRequester = sourcesFocusRequester
            )
        }

        LibrarySettingsSection.ConnectedServices -> {
            ConnectedServicesSettingsContent(
                initialFocusRequester = connectedServicesFocusRequester,
                autoFocusEnabled = autoFocusEnabled
            )
        }
    }
}

@Composable
private fun AnimeSettingsContent(
    selectedSection: AnimeSettingsSection,
    onSelectSection: (AnimeSettingsSection) -> Unit,
    onNavigateToAnimeAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    contentDiscoveryFocusRequester: FocusRequester,
    layoutFocusRequester: FocusRequester,
    integrationsFocusRequester: FocusRequester,
    tmdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester,
    tvdbFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    openSubtitlesFocusRequester: FocusRequester,
    isEssentialMode: Boolean,
    autoFocusEnabled: Boolean
) {
    BackHandler(enabled = selectedSection != AnimeSettingsSection.Hub) {
        onSelectSection(AnimeSettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            AnimeSettingsSection.Hub -> hubEntryFocusRequester
            AnimeSettingsSection.ContentDiscovery -> contentDiscoveryFocusRequester
            AnimeSettingsSection.Layout -> layoutFocusRequester
            AnimeSettingsSection.Integrations -> integrationsFocusRequester
            AnimeSettingsSection.Tmdb -> tmdbFocusRequester
            AnimeSettingsSection.MdbList -> mdbListFocusRequester
            AnimeSettingsSection.Tvdb -> tvdbFocusRequester
            AnimeSettingsSection.AnimeSkip -> animeSkipFocusRequester
            AnimeSettingsSection.OpenSubtitles -> openSubtitlesFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        AnimeSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.nav_anime),
                    subtitle = stringResource(R.string.settings_anime_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val animeHubState = rememberLazyListState()
                    val layoutSettingsViewModel: LayoutSettingsViewModel = hiltViewModel()
                    val layoutUiState by layoutSettingsViewModel.uiState.collectAsStateWithLifecycle()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = animeHubState,
                            modifier = Modifier.rowsListDownRecovery(animeHubState),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "anime_hub_tab_visibility") {
                                ToggleSettingsItem(
                                    icon = Icons.Default.Visibility,
                                    title = stringResource(R.string.anime_settings_show_tab_title),
                                    subtitle = stringResource(R.string.anime_settings_show_tab_sub),
                                    isChecked = layoutUiState.animeTabVisible,
                                    onCheckedChange = {
                                        layoutSettingsViewModel.onEvent(
                                            LayoutSettingsEvent.SetAnimeTabVisible(it)
                                        )
                                    }
                                )
                            }
                            item(key = "anime_hub_content_discovery") {
                                SettingsActionRow(
                                    title = stringResource(R.string.settings_anime_content_discovery_title),
                                    subtitle = stringResource(R.string.settings_anime_content_discovery_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.ContentDiscovery) },
                                    leadingIcon = Icons.Default.Explore,
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                            item(key = "anime_hub_layout") {
                                SettingsActionRow(
                                    title = stringResource(R.string.settings_anime_layout_title),
                                    subtitle = stringResource(R.string.settings_anime_layout_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.Layout) },
                                    leadingIcon = Icons.Default.GridView
                                )
                            }
                            item(key = "anime_hub_integrations") {
                                SettingsActionRow(
                                    title = stringResource(R.string.settings_anime_integrations_title),
                                    subtitle = stringResource(R.string.settings_anime_integrations_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.Integrations) },
                                    leadingIcon = Icons.Default.Link
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = animeHubState)
                    }
                }
            }
        }

        AnimeSettingsSection.ContentDiscovery -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_anime_content_discovery_title),
                    subtitle = stringResource(R.string.settings_anime_content_discovery_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsActionRow(
                            title = stringResource(R.string.anime_settings_addons_title),
                            subtitle = stringResource(R.string.anime_settings_addons_subtitle),
                            onClick = onNavigateToAnimeAddons,
                            leadingIcon = Icons.Default.Extension,
                            modifier = Modifier.focusRequester(contentDiscoveryFocusRequester)
                        )
                        if (AppFeaturePolicy.pluginsEnabled && !isEssentialMode) {
                            SettingsActionRow(
                                title = stringResource(R.string.plugin_title),
                                subtitle = stringResource(R.string.anime_settings_plugins_subtitle),
                                onClick = onNavigateToPlugins,
                                leadingIcon = Icons.Default.Build
                            )
                        }
                    }
                }
            }
        }

        AnimeSettingsSection.Layout -> {
            LayoutSettingsContent(
                viewModel = hiltViewModel<AnimeLayoutSettingsViewModel>(),
                initialFocusRequester = layoutFocusRequester,
                headerTitleRes = R.string.settings_anime_layout_title,
                headerSubtitleRes = R.string.settings_anime_layout_subtitle,
                animeMode = true,
                essentialMode = isEssentialMode
            )
        }

        AnimeSettingsSection.Integrations -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_anime_integrations_title),
                    subtitle = stringResource(R.string.settings_anime_integrations_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val animeIntegrationsState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = animeIntegrationsState,
                            modifier = Modifier.rowsListDownRecovery(animeIntegrationsState),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            item(key = "anime_integration_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.Tmdb) }
                                )
                            }
                            item(key = "anime_integration_mdblist") {
                                SettingsActionRow(
                                    title = "MDBList",
                                    subtitle = stringResource(R.string.settings_mdblist_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.MdbList) }
                                )
                            }
                            item(key = "anime_integration_tvdb") {
                                SettingsActionRow(
                                    title = "TVDB",
                                    subtitle = stringResource(R.string.settings_tvdb_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.Tvdb) }
                                )
                            }
                            item(key = "anime_integration_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.AnimeSkip) }
                                )
                            }
                            item(key = "anime_integration_opensubtitles") {
                                SettingsActionRow(
                                    title = "OpenSubtitles",
                                    subtitle = stringResource(R.string.settings_opensubtitles_subtitle),
                                    onClick = { onSelectSection(AnimeSettingsSection.OpenSubtitles) }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = animeIntegrationsState)
                    }
                }
            }
        }

        AnimeSettingsSection.Tmdb -> {
            AnimeTmdbSettingsContent(
                initialFocusRequester = tmdbFocusRequester
            )
        }

        AnimeSettingsSection.MdbList -> {
            AnimeMDBListSettingsContent(
                initialFocusRequester = mdbListFocusRequester
            )
        }

        AnimeSettingsSection.Tvdb -> {
            AnimeTvdbSettingsContent(
                initialFocusRequester = tvdbFocusRequester
            )
        }

        AnimeSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }

        AnimeSettingsSection.OpenSubtitles -> {
            OpenSubtitlesSettingsContent(
                initialFocusRequester = openSubtitlesFocusRequester
            )
        }
    }
}

@Composable
private fun ExtraSettingsContent(
    selectedSection: ExtraSettingsSection,
    onSelectSection: (ExtraSettingsSection) -> Unit,
    onNavigateToExtraAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    contentDiscoveryFocusRequester: FocusRequester,
    integrationsFocusRequester: FocusRequester,
    isEssentialMode: Boolean,
    autoFocusEnabled: Boolean
) {
    val extraTmdbFocusRequester = remember { FocusRequester() }
    val extraMdbListFocusRequester = remember { FocusRequester() }
    val extraTvdbFocusRequester = remember { FocusRequester() }
    val extraAnimeSkipFocusRequester = remember { FocusRequester() }
    val extraOpenSubtitlesFocusRequester = remember { FocusRequester() }
    val extraLayoutFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = selectedSection != ExtraSettingsSection.Hub) {
        onSelectSection(ExtraSettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            ExtraSettingsSection.Hub -> hubEntryFocusRequester
            ExtraSettingsSection.ContentDiscovery -> contentDiscoveryFocusRequester
            ExtraSettingsSection.Layout -> extraLayoutFocusRequester
            ExtraSettingsSection.Integrations -> integrationsFocusRequester
            ExtraSettingsSection.Tmdb -> extraTmdbFocusRequester
            ExtraSettingsSection.MdbList -> extraMdbListFocusRequester
            ExtraSettingsSection.Tvdb -> extraTvdbFocusRequester
            ExtraSettingsSection.AnimeSkip -> extraAnimeSkipFocusRequester
            ExtraSettingsSection.OpenSubtitles -> extraOpenSubtitlesFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        ExtraSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.nav_extra),
                    subtitle = stringResource(R.string.extra_settings_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val extraHubState = rememberLazyListState()
                    val extraHubViewModel: com.nuvio.tv.ui.screens.extra.ExtraHubViewModel = hiltViewModel()
                    val extraHubUiState by extraHubViewModel.uiState.collectAsStateWithLifecycle()
                    var showTabNameDialog by remember { mutableStateOf(false) }
                    var showLogoDialog by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = extraHubState,
                            modifier = Modifier.rowsListDownRecovery(extraHubState),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "extra_hub_tab_name") {
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_tab_name_title),
                                    subtitle = extraHubUiState.extraTabName,
                                    value = extraHubUiState.extraTabName,
                                    onClick = { showTabNameDialog = true },
                                    leadingIcon = Icons.Default.Edit,
                                    modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)
                                )
                            }
                            item(key = "extra_hub_tab_visibility") {
                                ToggleSettingsItem(
                                    icon = Icons.Default.Visibility,
                                    title = stringResource(R.string.extra_settings_show_tab_title),
                                    subtitle = stringResource(R.string.extra_settings_show_tab_sub),
                                    isChecked = extraHubUiState.extraTabVisible,
                                    onCheckedChange = {
                                        extraHubViewModel.onEvent(
                                            com.nuvio.tv.ui.screens.extra.ExtraHubEvent.SetExtraTabVisible(it)
                                        )
                                    }
                                )
                            }
                            item(key = "extra_hub_logo") {
                                val currentIconName = extraLogoOptions.getOrNull(extraHubUiState.extraTabLogoIndex)?.name ?: ""
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_logo_title),
                                    subtitle = currentIconName,
                                    onClick = { showLogoDialog = true },
                                    leadingIcon = extraLogoOptions.getOrNull(extraHubUiState.extraTabLogoIndex)?.icon
                                )
                            }
                            item(key = "extra_hub_content_discovery") {
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_addons_title),
                                    subtitle = stringResource(R.string.extra_settings_addons_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.ContentDiscovery) },
                                    leadingIcon = Icons.Default.Explore,
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                            item(key = "extra_hub_layout") {
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_layout_title),
                                    subtitle = stringResource(R.string.extra_settings_layout_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.Layout) },
                                    leadingIcon = Icons.Default.GridView
                                )
                            }
                            item(key = "extra_hub_integrations") {
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_integrations_title),
                                    subtitle = stringResource(R.string.extra_settings_integrations_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.Integrations) },
                                    leadingIcon = Icons.Default.Link
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = extraHubState)
                    }
                    if (showTabNameDialog) {
                        ExtraTabNameDialog(
                            currentValue = extraHubUiState.extraTabName,
                            onSaved = { newName ->
                                extraHubViewModel.onEvent(
                                    com.nuvio.tv.ui.screens.extra.ExtraHubEvent.SetExtraTabName(newName)
                                )
                                showTabNameDialog = false
                            },
                            onDismiss = { showTabNameDialog = false }
                        )
                    }
                    if (showLogoDialog) {
                        ExtraLogoPickerDialog(
                            options = extraLogoOptions,
                            selectedIndex = extraHubUiState.extraTabLogoIndex,
                            onOptionSelected = { index ->
                                extraHubViewModel.onEvent(
                                    com.nuvio.tv.ui.screens.extra.ExtraHubEvent.SetExtraTabLogoIndex(index)
                                )
                                showLogoDialog = false
                            },
                            onDismiss = { showLogoDialog = false }
                        )
                    }
                }
            }
        }

        ExtraSettingsSection.ContentDiscovery -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.extra_settings_addons_title),
                    subtitle = stringResource(R.string.extra_settings_addons_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val contentDiscoveryState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = contentDiscoveryState,
                            modifier = Modifier.rowsListDownRecovery(contentDiscoveryState),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "extra_content_discovery_addons") {
                                SettingsActionRow(
                                    title = stringResource(R.string.extra_settings_inner_addons_title),
                                    subtitle = stringResource(R.string.extra_settings_inner_addons_subtitle),
                                    onClick = onNavigateToExtraAddons,
                                    leadingIcon = Icons.Default.Extension,
                                    modifier = Modifier.focusRequester(contentDiscoveryFocusRequester)
                                )
                            }
                            if (AppFeaturePolicy.pluginsEnabled && !isEssentialMode) {
                                item(key = "extra_content_discovery_plugins") {
                                    SettingsActionRow(
                                        title = stringResource(R.string.plugin_title),
                                        subtitle = stringResource(R.string.extra_settings_plugins_subtitle),
                                        onClick = onNavigateToPlugins,
                                        leadingIcon = Icons.Default.Build
                                    )
                                }
                            }
                        }
                        SettingsVerticalScrollIndicators(state = contentDiscoveryState)
                    }
                }
            }
        }

        ExtraSettingsSection.Layout -> {
            LayoutSettingsContent(
                viewModel = hiltViewModel<ExtraLayoutSettingsViewModel>(),
                headerTitleRes = R.string.extra_settings_layout_title,
                headerSubtitleRes = R.string.extra_settings_layout_subtitle,
                initialFocusRequester = extraLayoutFocusRequester,
                essentialMode = isEssentialMode
            )
        }

        ExtraSettingsSection.Integrations -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.extra_settings_integrations_title),
                    subtitle = stringResource(R.string.extra_settings_integrations_subtitle)
                )
                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val integrationsState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = integrationsState,
                            modifier = Modifier.rowsListDownRecovery(integrationsState),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "extra_integration_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_integration_tmdb_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.Tmdb) },
                                    modifier = Modifier.focusRequester(integrationsFocusRequester)
                                )
                            }
                            item(key = "extra_integration_mdblist") {
                                SettingsActionRow(
                                    title = "MDBList",
                                    subtitle = stringResource(R.string.settings_integration_mdblist_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.MdbList) }
                                )
                            }
                            item(key = "extra_integration_tvdb") {
                                SettingsActionRow(
                                    title = "TVDB",
                                    subtitle = stringResource(R.string.settings_integration_tvdb_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.Tvdb) }
                                )
                            }
                            item(key = "extra_integration_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_integration_anime_skip_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.AnimeSkip) }
                                )
                            }
                            item(key = "extra_integration_opensubtitles") {
                                SettingsActionRow(
                                    title = "OpenSubtitles",
                                    subtitle = stringResource(R.string.settings_integration_opensubtitles_subtitle),
                                    onClick = { onSelectSection(ExtraSettingsSection.OpenSubtitles) }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = integrationsState)
                    }
                }
            }
        }

        ExtraSettingsSection.Tmdb -> {
            ExtraTmdbSettingsContent(
                initialFocusRequester = extraTmdbFocusRequester
            )
        }

        ExtraSettingsSection.MdbList -> {
            ExtraMDBListSettingsContent(
                initialFocusRequester = extraMdbListFocusRequester
            )
        }

        ExtraSettingsSection.Tvdb -> {
            ExtraTvdbSettingsContent(
                initialFocusRequester = extraTvdbFocusRequester
            )
        }

        ExtraSettingsSection.AnimeSkip -> {
            ExtraAnimeSkipSettingsContent(
                initialFocusRequester = extraAnimeSkipFocusRequester
            )
        }

        ExtraSettingsSection.OpenSubtitles -> {
            ExtraOpenSubtitlesSettingsContent(
                initialFocusRequester = extraOpenSubtitlesFocusRequester
            )
        }
    }
}

@Composable
private fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.nav_live_tv),
            subtitle = stringResource(R.string.settings_live_tv_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val layoutSettingsViewModel: LayoutSettingsViewModel = hiltViewModel()
            val layoutUiState by layoutSettingsViewModel.uiState.collectAsStateWithLifecycle()
            val liveTvViewModel: com.nuvio.tv.ui.screens.livetv.LiveTvViewModel = hiltViewModel()
            val liveTvUiState by liveTvViewModel.uiState.collectAsStateWithLifecycle()
            val liveTvHubState = rememberLazyListState()
            val fallbackFocusRequester = remember { FocusRequester() }
            var addPlaylistExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = liveTvHubState,
                    modifier = Modifier.rowsListDownRecovery(liveTvHubState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "live_tv_hub_tab_visibility") {
                        ToggleSettingsItem(
                            icon = Icons.Default.Visibility,
                            title = stringResource(R.string.settings_live_tv_show_tab_title),
                            subtitle = stringResource(R.string.settings_live_tv_show_tab_sub),
                            isChecked = layoutUiState.liveTvTabVisible,
                            onCheckedChange = {
                                layoutSettingsViewModel.onEvent(
                                    LayoutSettingsEvent.SetLiveTvTabVisible(it)
                                )
                            }
                        )
                    }
                    item(key = "live_tv_hub_add_playlist_header") {
                        SettingsActionRow(
                            title = stringResource(R.string.live_tv_add_playlist),
                            subtitle = stringResource(R.string.live_tv_add_subtitle),
                            value = if (addPlaylistExpanded) {
                                stringResource(R.string.playback_afr_open)
                            } else {
                                stringResource(R.string.playback_afr_closed)
                            },
                            onClick = { addPlaylistExpanded = !addPlaylistExpanded },
                            leadingIcon = Icons.Default.LiveTv,
                            trailingIcon = if (addPlaylistExpanded) {
                                Icons.Default.ExpandMore
                            } else {
                                Icons.Default.ChevronRight
                            },
                            modifier = Modifier.focusRequester(initialFocusRequester ?: fallbackFocusRequester)
                        )
                    }
                    if (addPlaylistExpanded) {
                        item(key = "live_tv_hub_add_playlist_form") {
                            LiveTvAddPlaylistForm(
                                isBusy = liveTvUiState.isAdding,
                                errorMessage = liveTvUiState.addError,
                                onAddM3u = { url ->
                                    liveTvViewModel.addPlaylist(url) {
                                        addPlaylistExpanded = false
                                    }
                                },
                                onAddXtream = { server, username, password ->
                                    liveTvViewModel.addXtreamPlaylist(server, username, password) {
                                        addPlaylistExpanded = false
                                    }
                                }
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = liveTvHubState)
            }
        }
    }
}

@Composable
private fun AnimeTmdbSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    TmdbSettingsContent(
        viewModel = hiltViewModel<AnimeTmdbSettingsViewModel>(),
        initialFocusRequester = initialFocusRequester,
        showModernHomeToggle = false
    )
}

@Composable
private fun AnimeMDBListSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    MDBListSettingsContent(
        viewModel = hiltViewModel<AnimeMDBListSettingsViewModel>(),
        initialFocusRequester = initialFocusRequester
    )
}

@Composable
private fun ExtraTmdbSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    TmdbSettingsContent(
        viewModel = hiltViewModel<ExtraTmdbSettingsViewModel>(),
        initialFocusRequester = initialFocusRequester,
        showModernHomeToggle = false
    )
}

@Composable
private fun ExtraMDBListSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    MDBListSettingsContent(
        viewModel = hiltViewModel<ExtraMDBListSettingsViewModel>(),
        initialFocusRequester = initialFocusRequester
    )
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
    tvdbFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    openSubtitlesFocusRequester: FocusRequester,
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
            IntegrationSettingsSection.Tvdb -> tvdbFocusRequester
            IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
            IntegrationSettingsSection.OpenSubtitles -> openSubtitlesFocusRequester
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
                            modifier = Modifier.rowsListDownRecovery(integrationHubState),
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
                            item(key = "integration_hub_tvdb") {
                                SettingsActionRow(
                                    title = "TVDB",
                                    subtitle = stringResource(R.string.settings_tvdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tvdb) }
                                )
                            }
                            item(key = "integration_hub_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.AnimeSkip) }
                                )
                            }
                            item(key = "integration_hub_opensubtitles") {
                                SettingsActionRow(
                                    title = "OpenSubtitles",
                                    subtitle = stringResource(R.string.settings_opensubtitles_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.OpenSubtitles) }
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

        IntegrationSettingsSection.Tvdb -> {
            TvdbSettingsContent(
                initialFocusRequester = tvdbFocusRequester
            )
        }

        IntegrationSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }

        IntegrationSettingsSection.OpenSubtitles -> {
            OpenSubtitlesSettingsContent(
                initialFocusRequester = openSubtitlesFocusRequester
            )
        }
    }
}

@Composable
private fun ExtraTabNameDialog(
    currentValue: String,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(currentValue) }
    val inputFocusRequester = remember { FocusRequester() }
    var isInputFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { inputFocusRequester.requestFocus() }
    }

    com.nuvio.tv.ui.components.NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.extra_settings_tab_name_dialog_title),
        width = 520.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.extra_settings_tab_name_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSaved(value.ifBlank { "Extra" }) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun ExtraLogoPickerDialog(
    options: List<ExtraLogoOption>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingIndex by remember { mutableIntStateOf(selectedIndex) }
    val pendingOption = options.getOrNull(pendingIndex)

    com.nuvio.tv.ui.components.NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.extra_settings_logo_title),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            pendingOption?.let { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.name,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }

            ExtraLogoPicker(
                options = options,
                selectedIndex = pendingIndex,
                onOptionSelected = { pendingIndex = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) { Text(stringResource(R.string.action_cancel)) }
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                Button(
                    onClick = { onOptionSelected(pendingIndex) },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
