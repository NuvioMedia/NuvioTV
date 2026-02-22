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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.ui.screens.plugin.PluginScreenContent
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToTrakt: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsState()
    val sectionSpecs = remember {
        listOf(
            SettingsSectionSpec(
                category = SettingsCategory.ACCOUNT,
                title = "Cuenta",
                icon = Icons.Default.Person,
                subtitle = "Estado de cuenta y sincronización.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.PROFILES,
                title = "Perfiles",
                icon = Icons.Default.People,
                subtitle = "Gestionar perfiles de usuario.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.APPEARANCE,
                title = "Apariencia",
                icon = Icons.Default.Palette,
                subtitle = "Ajustes de tema y color.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.LAYOUT,
                title = "Diseño",
                icon = Icons.Default.GridView,
                subtitle = "Estructura de Inicio y estilos de póster.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.PLUGINS,
                title = "Plugins",
                icon = Icons.Default.Build,
                subtitle = "Repositorios y proveedores.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.INTEGRATION,
                title = "Integración",
                icon = Icons.Default.Link,
                subtitle = "Controles de TMDB y MDBList.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.PLAYBACK,
                title = "Reproducción",
                icon = Icons.Default.Settings,
                subtitle = "Reproductor, subtítulos y auto-reproducción.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.TRAKT,
                title = "Trakt",
                rawIconRes = R.raw.trakt_tv_glyph,
                subtitle = "Abrir pantalla de conexión de Trakt.",
                destination = SettingsSectionDestination.External
            ),
            SettingsSectionSpec(
                category = SettingsCategory.ABOUT,
                title = "Acerca de",
                icon = Icons.Default.Info,
                subtitle = "Versión y políticas.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.DEBUG,
                title = "Depuración",
                icon = Icons.Default.BugReport,
                subtitle = "Herramientas de desarrollo.",
                destination = SettingsSectionDestination.Inline
            )
        )
    }

    val visibleSections = remember(sectionSpecs, isPrimaryProfileActive) {
        sectionSpecs.filter { section ->
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

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

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
                    items(visibleSections) { section ->
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
                                    coroutineScope.launch {
                                        // Wait for detail content to settle before requesting first content focus.
                                        delay(120)
                                        val requester = contentFocusRequesters[section.category]
                                        val requested = if (requester != null) {
                                            runCatching {
                                                requester.requestFocus()
                                            }.isSuccess
                                        } else {
                                            false
                                        }
                                        if (!requested) {
                                            focusManager.moveFocus(FocusDirection.Right)
                                        }
                                    }
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
                                animationSpec = tween(200)
                            ) + fadeIn(animationSpec = tween(200)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -direction * (fullWidth / 6) },
                                        animationSpec = tween(180)
                                    ) + fadeOut(animationSpec = tween(180))
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
    val pluginUiState by pluginViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = "Plugins",
            subtitle = "Gestiona repositorios, proveedores y estados de plugins."
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
    val accountUiState by accountViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = "Cuenta",
            subtitle = "Estado de cuenta y sincronización."
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
                    title = "Integraciones",
                    subtitle = "Elige los ajustes de TMDB o MDBList"
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
                                subtitle = "Controles de enriquecimiento de metadatos",
                                onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) },
                                modifier = Modifier.focusRequester(hubEntryFocusRequester)
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = "MDBList",
                                subtitle = "Proveedores externos de calificaciones",
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