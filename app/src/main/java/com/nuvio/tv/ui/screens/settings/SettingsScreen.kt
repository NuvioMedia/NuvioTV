@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
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
import androidx.compose.ui.res.stringResource
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

@Composable
private fun rememberSettingsSectionSpecs() = listOf(
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
        category = SettingsCategory.PLUGINS,
        title = stringResource(R.string.settings_plugins),
        icon = Icons.Default.Build,
        subtitle = stringResource(R.string.settings_plugins_subtitle),
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
        icon = Icons.Default.Settings,
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
    onNavigateToAuthQrSignIn: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()

    val allSectionSpecs = rememberSettingsSectionSpecs()
    val visibleSections = remember(isPrimaryProfileActive, allSectionSpecs) {
        allSectionSpecs.filter { section ->
            when (section.category) {
                SettingsCategory.DEBUG -> BuildConfig.IS_DEBUG_BUILD
                SettingsCategory.PROFILES -> isPrimaryProfileActive
                SettingsCategory.ACCOUNT -> isPrimaryProfileActive
                SettingsCategory.TRAKT -> isPrimaryProfileActive
                else -> true
            }
        }
    }

    var selectedCategory by remember(visibleSections) {
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
                SettingsCategory.LAYOUT to FocusRequester(),
                SettingsCategory.INTEGRATION to FocusRequester(),
                SettingsCategory.PLAYBACK to FocusRequester(),
                SettingsCategory.ABOUT to FocusRequester()
            )
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
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
                        .focusRequester(railContainerFocusRequester)
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
                                allowDetailAutofocus = true
                                pendingContentFocusCategory = selectedCategory
                                pendingContentFocusRequestId += 1L
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
                                    allowDetailAutofocus = true
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
                        .onFocusChanged { state ->
                            if (state.hasFocus && !allowDetailAutofocus) {
                                railFocusRequesters[selectedCategory]?.let { requester ->
                                    runCatching { requester.requestFocus() }
                                }
                            }
                        }
                ) {
                    when (selectedCategory) {
                        SettingsCategory.PROFILES -> ProfileSettingsContent()
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
                            }
                        )
                        SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.PLAYBACK]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
                            selectedSection = integrationSection,
                            onSelectSection = { integrationSection = it },
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.INTEGRATION]
                            } else {
                                null
                            },
                            hubFocusRequester = integrationHubFocusRequester,
                            tmdbFocusRequester = integrationTmdbFocusRequester,
                            mdbListFocusRequester = integrationMdbListFocusRequester,
                            autoFocusEnabled = allowDetailAutofocus
                        )
                        SettingsCategory.ABOUT -> AboutSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.ABOUT]
                            } else {
                                null
                            }
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

@Composable
private fun PluginsSettingsContent() {
    val pluginViewModel: com.nuvio.tv.ui.screens.plugin.PluginViewModel = hiltViewModel()
    val pluginUiState by pluginViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_plugins),
            subtitle = stringResource(R.string.settings_plugins_section_subtitle)
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
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
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
    mdbListFocusRequester: FocusRequester,
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
                    title = stringResource(R.string.settings_integrations_section),
                    subtitle = stringResource(R.string.settings_integrations_section_subtitle)
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "integration_hub_tmdb") {
                            SettingsActionRow(
                                title = "TMDB",
                                subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) },
                                modifier = Modifier.focusRequester(hubEntryFocusRequester)
                            )
                        }
                        item(key = "integration_hub_mdblist") {
                            SettingsActionRow(
                                title = "MDBList",
                                subtitle = stringResource(R.string.settings_mdblist_subtitle),
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
