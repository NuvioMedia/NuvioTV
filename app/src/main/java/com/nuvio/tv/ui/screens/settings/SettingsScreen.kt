@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.plugin.PluginScreenContent
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

internal enum class SettingsCategory {
    ACCOUNT,
    PROFILES,
    APPEARANCE,
    LAYOUT,
    PLUGINS,
    INTEGRATION,
    PLAYBACK,
    TRAKT,
    ABOUT,
    DEBUG
}

private enum class IntegrationSettingsSection {
    Hub,
    Tmdb,
    MdbList
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

private val SETTINGS_SECTION_SPECS = listOf(
    SettingsSectionSpec(
        category = SettingsCategory.ACCOUNT,
        title = "Account",
        icon = Icons.Default.Person,
        subtitle = "Account and sync status.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PROFILES,
        title = "Profiles",
        icon = Icons.Default.People,
        subtitle = "Manage user profiles.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.APPEARANCE,
        title = "Appearance",
        icon = Icons.Default.Palette,
        subtitle = "Theme and color tuning.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.LAYOUT,
        title = "Layout",
        icon = Icons.Default.GridView,
        subtitle = "Home structure and poster styles.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLUGINS,
        title = "Plugins",
        icon = Icons.Default.Build,
        subtitle = "Repositories and providers.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.INTEGRATION,
        title = "Integration",
        icon = Icons.Default.Link,
        subtitle = "TMDB and MDBList controls.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLAYBACK,
        title = "Playback",
        icon = Icons.Default.Settings,
        subtitle = "Player, subtitles, and auto-play.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.TRAKT,
        title = "Trakt",
        rawIconRes = R.raw.trakt_tv_glyph,
        subtitle = "Open Trakt connection screen.",
        destination = SettingsSectionDestination.External
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ABOUT,
        title = "About",
        icon = Icons.Default.Info,
        subtitle = "Version and policies.",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.DEBUG,
        title = "Debug",
        icon = Icons.Default.BugReport,
        subtitle = "Developer tools and feature flags.",
        destination = SettingsSectionDestination.Inline
    )
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToTrakt: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()

    val visibleSections = remember(isPrimaryProfileActive) {
        SETTINGS_SECTION_SPECS.filter { section ->
            when (section.category) {
                SettingsCategory.DEBUG -> BuildConfig.IS_DEBUG_BUILD
                SettingsCategory.PROFILES -> isPrimaryProfileActive
                SettingsCategory.ACCOUNT -> isPrimaryProfileActive
                SettingsCategory.TRAKT -> isPrimaryProfileActive
                else -> true
            }
        }
    }

    var selectedCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE) }
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
            mapOf(
                SettingsCategory.APPEARANCE to FocusRequester(),
                SettingsCategory.LAYOUT to FocusRequester(),
                SettingsCategory.INTEGRATION to FocusRequester(),
                SettingsCategory.PLAYBACK to FocusRequester(),
                SettingsCategory.ABOUT to FocusRequester()
            )
    }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        railFocusRequesters[selectedCategory]?.let { requester ->
            runCatching { requester.requestFocus() }
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
            focusManager.moveFocus(FocusDirection.Right)
        }
        pendingContentFocusCategory = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = if (showBuiltInHeader) 24.dp else 68.dp,
                bottom = 24.dp
            )
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var railHadFocus by remember { mutableStateOf(false) }

                LazyColumn(
                    modifier = Modifier
                        .width(282.dp)
                        .fillMaxHeight()
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
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                focusManager.moveFocus(FocusDirection.Right)
                                true
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
                            onClick = {
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
                                    selectedCategory = section.category
                                    pendingContentFocusCategory = section.category
                                    pendingContentFocusRequestId += 1L
                                }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    AnimatedContent(
                        targetState = selectedCategory,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> direction * (fullWidth / 6) },
                                animationSpec = tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS)
                            ) + fadeIn(animationSpec = tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -direction * (fullWidth / 6) },
                                        animationSpec = tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS)
                                    ) + fadeOut(animationSpec = tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS))
                                )
                        },
                        label = "settings_split_detail"
                    ) { category ->
                        when (category) {
                            SettingsCategory.PROFILES -> ProfileSettingsContent()
                            SettingsCategory.APPEARANCE -> ThemeSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.APPEARANCE]
                            )
                            SettingsCategory.LAYOUT -> LayoutSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.LAYOUT]
                            )
                            SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.PLAYBACK]
                            )
                            SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
                                selectedSection = integrationSection,
                                onSelectSection = { integrationSection = it },
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.INTEGRATION],
                                hubFocusRequester = integrationHubFocusRequester,
                                tmdbFocusRequester = integrationTmdbFocusRequester,
                                mdbListFocusRequester = integrationMdbListFocusRequester
                            )
                            SettingsCategory.ABOUT -> AboutSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.ABOUT]
                            )
                            SettingsCategory.PLUGINS -> PluginsSettingsContent()
                            SettingsCategory.ACCOUNT -> AccountSettingsInline(
                                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
                            )
                            SettingsCategory.DEBUG -> DebugSettingsContent()
                            SettingsCategory.TRAKT -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsSettingsContent() {
    val pluginViewModel: com.nuvio.tv.ui.screens.plugin.PluginViewModel = hiltViewModel()
    val pluginUiState by pluginViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = "Plugins",
            subtitle = "Manage repositories, providers, and plugin states."
        )
        SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopStart
            ) {
                PluginScreenContent(
                    uiState = pluginUiState,
                    viewModel = pluginViewModel,
                    showHeader = false
                )
            }
        }
    }
}

@Composable
private fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit
) {
    val accountViewModel: com.nuvio.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = "Account",
            subtitle = "Account and sync status."
        )
        SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
            com.nuvio.tv.ui.screens.account.AccountSettingsContent(
                uiState = accountUiState,
                viewModel = accountViewModel,
                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
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
    tmdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester
) {
    BackHandler(enabled = selectedSection != IntegrationSettingsSection.Hub) {
        onSelectSection(IntegrationSettingsSection.Hub)
    }
    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection) {
        val requester = when (selectedSection) {
            IntegrationSettingsSection.Hub -> hubEntryFocusRequester
            IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
            IntegrationSettingsSection.MdbList -> mdbListFocusRequester
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
                    title = "Integrations",
                    subtitle = "Choose TMDB or MDBList settings"
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            SettingsActionRow(
                                title = "TMDB",
                                subtitle = "Metadata enrichment controls",
                                onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) },
                                modifier = Modifier.focusRequester(hubEntryFocusRequester)
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = "MDBList",
                                subtitle = "External ratings providers",
                                onClick = { onSelectSection(IntegrationSettingsSection.MdbList) }
                            )
                        }
                    }
                }
            }
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
    }
}
