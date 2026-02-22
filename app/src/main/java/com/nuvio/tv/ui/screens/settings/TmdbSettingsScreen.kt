@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES

@Composable
fun TmdbSettingsScreen(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = "TMDB Enrichment",
        subtitle = "Choose which metadata fields should come from TMDB"
    ) {
        TmdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "TMDB Enrichment",
            subtitle = "Choose which metadata fields should come from TMDB"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SettingsToggleRow(
                        title = "Enable TMDB Enrichment",
                        subtitle = "Use TMDB as a metadata source to enhance addon data",
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item {
                    val languageName = AVAILABLE_SUBTITLE_LANGUAGES
                        .find { it.code == uiState.language }
                        ?.name
                        ?: uiState.language.uppercase()
                    SettingsActionRow(
                        title = "Language",
                        subtitle = "TMDB metadata language for title, logo, and enabled fields",
                        value = languageName,
                        enabled = uiState.enabled,
                        onClick = { showLanguageDialog = true }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Artwork",
                        subtitle = "Logo and backdrop images from TMDB",
                        checked = uiState.useArtwork,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Basic Info",
                        subtitle = "Description, genres, and rating from TMDB",
                        checked = uiState.useBasicInfo,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Details",
                        subtitle = "Runtime, release date, country, and language from TMDB",
                        checked = uiState.useDetails,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDetails(!uiState.useDetails)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Credits",
                        subtitle = "Cast with photos, director, and writer from TMDB",
                        checked = uiState.useCredits,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Productions",
                        subtitle = "Production companies from TMDB",
                        checked = uiState.useProductions,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleProductions(!uiState.useProductions)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Networks",
                        subtitle = "Networks with logos from TMDB",
                        checked = uiState.useNetworks,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleNetworks(!uiState.useNetworks)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Episodes",
                        subtitle = "Episode titles, overviews, thumbnails, and runtime from TMDB",
                        checked = uiState.useEpisodes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "More Like This",
                        subtitle = "TMDB recommendation backdrops on detail page",
                        checked = uiState.useMoreLikeThis,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleMoreLikeThis(!uiState.useMoreLikeThis)
                            )
                        }
                    )
                }

            }
        }
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = "TMDB Language",
            selectedLanguage = uiState.language,
            showNoneOption = false,
            onLanguageSelected = { language ->
                viewModel.onEvent(TmdbSettingsEvent.SetLanguage(language ?: "en"))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}
