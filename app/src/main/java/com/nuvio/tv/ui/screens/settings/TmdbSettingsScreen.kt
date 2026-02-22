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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES

@Composable
fun TmdbSettingsScreen(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = "Enriquecimiento TMDB",
        subtitle = "Elige qué campos de metadatos deben provenir de TMDB"
    ) {
        TmdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Enriquecimiento TMDB",
            subtitle = "Elige qué campos de metadatos deben provenir de TMDB"
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
                        title = "Activar Enriquecimiento TMDB",
                        subtitle = "Usa TMDB como fuente de metadatos para mejorar los datos de los addons",
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
                        title = "Idioma",
                        subtitle = "Idioma de metadatos de TMDB para títulos, logos y campos activos",
                        value = languageName,
                        enabled = uiState.enabled,
                        onClick = { showLanguageDialog = true }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Arte (Artwork)",
                        subtitle = "Logos e imágenes de fondo desde TMDB",
                        checked = uiState.useArtwork,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Información Básica",
                        subtitle = "Descripción, géneros y calificación desde TMDB",
                        checked = uiState.useBasicInfo,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Detalles",
                        subtitle = "Duración, fecha de estreno, país e idioma desde TMDB",
                        checked = uiState.useDetails,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDetails(!uiState.useDetails)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Créditos",
                        subtitle = "Reparto con fotos, director y guionista desde TMDB",
                        checked = uiState.useCredits,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Productoras",
                        subtitle = "Compañías productoras desde TMDB",
                        checked = uiState.useProductions,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleProductions(!uiState.useProductions)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Cadenas/Networks",
                        subtitle = "Cadenas de TV con logos desde TMDB",
                        checked = uiState.useNetworks,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleNetworks(!uiState.useNetworks)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Episodios",
                        subtitle = "Títulos de episodios, sinopsis, miniaturas y duración desde TMDB",
                        checked = uiState.useEpisodes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
                    )
                }

                item {
                    SettingsToggleRow(
                        title = "Más como esto",
                        subtitle = "Fondos de recomendaciones de TMDB en la página de detalles",
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
            title = "Idioma de TMDB",
            selectedLanguage = uiState.language,
            showNoneOption = false,
            onLanguageSelected = { language ->
                viewModel.onEvent(TmdbSettingsEvent.SetLanguage(language ?: "es"))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}