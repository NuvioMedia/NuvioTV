@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun LayoutSettingsScreen(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = "Ajustes de Diseño",
        subtitle = "Ajusta el diseño de inicio, visibilidad de contenido y pósters"
    ) {
        LayoutSettingsContent(viewModel = viewModel)
    }
}

private enum class LayoutSettingsSection {
    HOME_LAYOUT,
    HOME_CONTENT,
    DETAIL_PAGE,
    FOCUSED_POSTER,
    POSTER_CARD_STYLE
}

@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    var homeLayoutExpanded by rememberSaveable { mutableStateOf(false) }
    var homeContentExpanded by rememberSaveable { mutableStateOf(false) }
    var detailPageExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedPosterExpanded by rememberSaveable { mutableStateOf(false) }
    var posterCardStyleExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultHomeLayoutHeaderFocus = remember { FocusRequester() }
    val homeContentHeaderFocus = remember { FocusRequester() }
    val detailPageHeaderFocus = remember { FocusRequester() }
    val focusedPosterHeaderFocus = remember { FocusRequester() }
    val posterCardStyleHeaderFocus = remember { FocusRequester() }
    val homeLayoutHeaderFocus = initialFocusRequester ?: defaultHomeLayoutHeaderFocus

    var focusedSection by remember { mutableStateOf<LayoutSettingsSection?>(null) }

    LaunchedEffect(homeLayoutExpanded, focusedSection) {
        if (!homeLayoutExpanded && focusedSection == LayoutSettingsSection.HOME_LAYOUT) {
            homeLayoutHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(homeContentExpanded, focusedSection) {
        if (!homeContentExpanded && focusedSection == LayoutSettingsSection.HOME_CONTENT) {
            homeContentHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(detailPageExpanded, focusedSection) {
        if (!detailPageExpanded && focusedSection == LayoutSettingsSection.DETAIL_PAGE) {
            detailPageHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(focusedPosterExpanded, focusedSection) {
        if (!focusedPosterExpanded && focusedSection == LayoutSettingsSection.FOCUSED_POSTER) {
            focusedPosterHeaderFocus.requestFocus()
        }
    }
    LaunchedEffect(posterCardStyleExpanded, focusedSection) {
        if (!posterCardStyleExpanded && focusedSection == LayoutSettingsSection.POSTER_CARD_STYLE) {
            posterCardStyleHeaderFocus.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Ajustes de Diseño",
            subtitle = "Ajusta el diseño de inicio, visibilidad de contenido y pósters"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CollapsibleSectionCard(
                    title = "Diseño de Inicio",
                    description = "Elige la estructura y la sección destacada.",
                    expanded = homeLayoutExpanded,
                    onToggle = { homeLayoutExpanded = !homeLayoutExpanded },
                    focusRequester = homeLayoutHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LayoutCard(
                            layout = HomeLayout.MODERN,
                            isSelected = uiState.selectedLayout == HomeLayout.MODERN,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.MODERN))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.GRID,
                            isSelected = uiState.selectedLayout == HomeLayout.GRID,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.GRID))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                        LayoutCard(
                            layout = HomeLayout.CLASSIC,
                            isSelected = uiState.selectedLayout == HomeLayout.CLASSIC,
                            onClick = {
                                viewModel.onEvent(LayoutSettingsEvent.SelectLayout(HomeLayout.CLASSIC))
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.selectedLayout == HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = "Pósters Horizontales",
                            subtitle = "Alterna entre tarjetas verticales y horizontales para la vista Moderna.",
                            checked = uiState.modernLandscapePostersEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernLandscapePostersEnabled(
                                        !uiState.modernLandscapePostersEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                        CompactToggleRow(
                            title = "Mostrar Fila de Vista Previa",
                            subtitle = "Muestra una vista parcial de la fila inferior en el Inicio Moderno.",
                            checked = uiState.modernNextRowPreviewEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernNextRowPreviewEnabled(
                                        !uiState.modernNextRowPreviewEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                        )
                    }

                    if (uiState.heroSectionEnabled && uiState.availableCatalogs.isNotEmpty()) {
                        Text(
                            text = "Catálogos Destacados",
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioColors.TextSecondary
                        )
                        Text(
                            text = "Selecciona uno o más catálogos para el contenido principal.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary
                        )
                        LazyRow(
                            contentPadding = PaddingValues(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.availableCatalogs) { catalog ->
                                CatalogChip(
                                    catalogInfo = catalog,
                                    isSelected = catalog.key in uiState.heroCatalogKeys,
                                    onClick = {
                                        viewModel.onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key))
                                    },
                                    onFocused = { focusedSection = LayoutSettingsSection.HOME_LAYOUT }
                                )
                            }
                        }
                    }
                }
            }

            item {
                CollapsibleSectionCard(
                    title = "Contenido de Inicio",
                    description = "Controla lo que aparece en inicio y búsqueda.",
                    expanded = homeContentExpanded,
                    onToggle = { homeContentExpanded = !homeContentExpanded },
                    focusRequester = homeContentHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                ) {
                    if (!uiState.modernSidebarEnabled) {
                        CompactToggleRow(
                            title = "Colapsar Menú Lateral",
                            subtitle = "Oculta el menú lateral por defecto; se muestra al enfocarlo.",
                            checked = uiState.sidebarCollapsedByDefault,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    CompactToggleRow(
                        title = "Menú Lateral Moderno ON/OFF",
                        subtitle = "Activa la navegación lateral flotante.",
                        checked = uiState.modernSidebarEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetModernSidebarEnabled(!uiState.modernSidebarEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    if (uiState.modernSidebarEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        CompactToggleRow(
                            title = "Desenfoque de Menú Lateral Moderno",
                            subtitle = "Alterna el efecto de desenfoque en el menú lateral.",
                            checked = uiState.modernSidebarBlurEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetModernSidebarBlurEnabled(!uiState.modernSidebarBlurEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    CompactToggleRow(
                        title = "Mostrar Sección Destacada",
                        subtitle = "Muestra el carrusel principal en la parte superior de inicio.",
                        checked = uiState.heroSectionEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetHeroSectionEnabled(!uiState.heroSectionEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    CompactToggleRow(
                        title = "Mostrar Descubrir en Búsqueda",
                        subtitle = "Muestra la sección de exploración cuando la búsqueda está vacía.",
                        checked = uiState.searchDiscoverEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetSearchDiscoverEnabled(!uiState.searchDiscoverEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                    if (uiState.selectedLayout != HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = "Mostrar Etiquetas de Pósters",
                            subtitle = "Muestra los títulos debajo de los pósters en las filas y cuadrícula.",
                            checked = uiState.posterLabelsEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    if (uiState.selectedLayout != HomeLayout.MODERN) {
                        CompactToggleRow(
                            title = "Mostrar Nombre del Addon",
                            subtitle = "Muestra el nombre de la fuente debajo de los títulos del catálogo.",
                            checked = uiState.catalogAddonNameEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                        )
                    }
                    CompactToggleRow(
                        title = "Mostrar Tipo de Catálogo",
                        subtitle = "Muestra el sufijo del tipo junto al nombre del catálogo (Películas/Series).",
                        checked = uiState.catalogTypeSuffixEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.HOME_CONTENT }
                    )
                }
            }

            item {
                CollapsibleSectionCard(
                    title = "Página de Detalles",
                    description = "Ajustes para las pantallas de detalles y episodios.",
                    expanded = detailPageExpanded,
                    onToggle = { detailPageExpanded = !detailPageExpanded },
                    focusRequester = detailPageHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                ) {
                    CompactToggleRow(
                        title = "Desenfocar Episodios No Vistos",
                        subtitle = "Desenfoca las miniaturas de los episodios hasta que se vean para evitar spoilers.",
                        checked = uiState.blurUnwatchedEpisodes,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetBlurUnwatchedEpisodes(!uiState.blurUnwatchedEpisodes)
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = "Mostrar Botón de Tráiler",
                        subtitle = "Muestra el botón de tráiler en la página de detalles (solo si está disponible).",
                        checked = uiState.detailPageTrailerButtonEnabled,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(
                                    !uiState.detailPageTrailerButtonEnabled
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )

                    CompactToggleRow(
                        title = "Preferir metadatos de addon externo",
                        subtitle = "Usa metadatos de un addon externo en lugar del addon del catálogo.",
                        checked = uiState.preferExternalMetaAddonDetail,
                        onToggle = {
                            viewModel.onEvent(
                                LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(
                                    !uiState.preferExternalMetaAddonDetail
                                )
                            )
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.DETAIL_PAGE }
                    )
                }
            }

            item {
                CollapsibleSectionCard(
                    title = "Póster Enfocado",
                    description = "Comportamiento avanzado para las tarjetas al enfocarlas.",
                    expanded = focusedPosterExpanded,
                    onToggle = { focusedPosterExpanded = !focusedPosterExpanded },
                    focusRequester = focusedPosterHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                ) {
                    val isModern = uiState.selectedLayout == HomeLayout.MODERN
                    val isModernLandscape = isModern && uiState.modernLandscapePostersEnabled
                    val showAutoplayRow = uiState.focusedPosterBackdropExpandEnabled || isModernLandscape

                    if (!isModernLandscape) {
                        CompactToggleRow(
                            title = "Expandir Póster Enfocado a Fondo",
                            subtitle = "Expande el póster enfocado tras un breve retraso.",
                            checked = uiState.focusedPosterBackdropExpandEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled(
                                        !uiState.focusedPosterBackdropExpandEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (!isModernLandscape && uiState.focusedPosterBackdropExpandEnabled) {
                        SliderSettingsItem(
                            icon = Icons.Default.Timer,
                            title = "Retraso de Expansión de Fondo",
                            subtitle = "Cuánto tiempo esperar antes de expandir las tarjetas enfocadas.",
                            value = uiState.focusedPosterBackdropExpandDelaySeconds,
                            valueText = "${uiState.focusedPosterBackdropExpandDelaySeconds}s",
                            minValue = 1,
                            maxValue = 10,
                            step = 1,
                            onValueChange = { seconds ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds(seconds)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (showAutoplayRow) {
                        CompactToggleRow(
                            title = if (isModern) {
                                "Auto-reproducir Tráiler"
                            } else {
                                "Auto-reproducir Tráiler en Tarjeta Expandida"
                            },
                            subtitle = if (isModern) {
                                "Reproduce la vista previa del tráiler para el contenido enfocado."
                            } else {
                                "Reproduce el tráiler dentro de la tarjeta expandida cuando esté disponible."
                            },
                            checked = uiState.focusedPosterBackdropTrailerEnabled,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled(
                                        !uiState.focusedPosterBackdropTrailerEnabled
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (showAutoplayRow && uiState.focusedPosterBackdropTrailerEnabled) {
                        CompactToggleRow(
                            title = "Reproducir Tráiler Silenciado",
                            subtitle = if (isModern) {
                                "Silencia el audio del tráiler durante la auto-reproducción."
                            } else {
                                "Silencia el audio del tráiler en las tarjetas expandidas."
                            },
                            checked = uiState.focusedPosterBackdropTrailerMuted,
                            onToggle = {
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(
                                        !uiState.focusedPosterBackdropTrailerMuted
                                    )
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }

                    if (
                        isModern &&
                        showAutoplayRow &&
                        uiState.focusedPosterBackdropTrailerEnabled
                    ) {
                        ModernTrailerPlaybackTargetRow(
                            selectedTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
                            onTargetSelected = { target ->
                                viewModel.onEvent(
                                    LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget(target)
                                )
                            },
                            onFocused = { focusedSection = LayoutSettingsSection.FOCUSED_POSTER }
                        )
                    }
                }
            }

            item {
                CollapsibleSectionCard(
                    title = "Estilo de Tarjeta de Póster",
                    description = "Ajusta el ancho de la tarjeta y el radio del borde.",
                    expanded = posterCardStyleExpanded,
                    onToggle = { posterCardStyleExpanded = !posterCardStyleExpanded },
                    focusRequester = posterCardStyleHeaderFocus,
                    onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE }
                ) {
                    PosterCardStyleControls(
                        widthDp = uiState.posterCardWidthDp,
                        cornerRadiusDp = uiState.posterCardCornerRadiusDp,
                        onWidthSelected = { width ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardWidth(width))
                        },
                        onCornerRadiusSelected = { radius ->
                            viewModel.onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(radius))
                        },
                        onReset = {
                            viewModel.onEvent(LayoutSettingsEvent.ResetPosterCardStyle)
                        },
                        onFocused = { focusedSection = LayoutSettingsSection.POSTER_CARD_STYLE }
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsActionRow(
            title = title,
            subtitle = description,
            value = if (expanded) "Abierto" else "Cerrado",
            onClick = onToggle,
            trailingIcon = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            modifier = Modifier.focusRequester(focusRequester),
            onFocused = onFocused
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SettingsGroupCard {
                content()
            }
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsToggleRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onToggle = onToggle,
        onFocused = onFocused
    )
}

@Composable
private fun ModernTrailerPlaybackTargetRow(
    selectedTarget: FocusedPosterTrailerPlaybackTarget,
    onTargetSelected: (FocusedPosterTrailerPlaybackTarget) -> Unit,
    onFocused: () -> Unit
) {
    Text(
        text = "Ubicación de Reproducción de Tráiler",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioColors.TextSecondary
    )
    Text(
        text = "Elige dónde se reproduce la vista previa en el Inicio Moderno.",
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextTertiary
    )
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SettingsChoiceChip(
                label = "Tarjeta Expandida",
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
                },
                onFocused = onFocused
            )
        }
        item {
            SettingsChoiceChip(
                label = "Sección Destacada",
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
                },
                onFocused = onFocused
            )
        }
    }
}

@Composable
private fun LayoutCard(
    layout: HomeLayout,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                when (layout) {
                    HomeLayout.CLASSIC -> ClassicLayoutPreview(modifier = Modifier.fillMaxWidth())
                    HomeLayout.GRID -> GridLayoutPreview(modifier = Modifier.fillMaxWidth())
                    HomeLayout.MODERN -> ModernLayoutPreview(modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Seleccionado",
                        tint = NuvioColors.FocusRing,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = layout.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CatalogChip(
    catalogInfo: CatalogInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = catalogInfo.name,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

@Composable
private fun PosterCardStyleControls(
    widthDp: Int,
    cornerRadiusDp: Int,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onReset: () -> Unit,
    onFocused: () -> Unit
) {
    val widthOptions = listOf(
        PresetOption("Compacto", 104),
        PresetOption("Denso", 112),
        PresetOption("Estándar", 120),
        PresetOption("Equilibrado", 126),
        PresetOption("Cómodo", 134),
        PresetOption("Grande", 140)
    )
    val radiusOptions = listOf(
        PresetOption("Recto", 0),
        PresetOption("Sutil", 4),
        PresetOption("Clásico", 8),
        PresetOption("Redondeado", 12),
        PresetOption("Píldora", 16)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OptionRow(
            title = "Ancho",
            selectedValue = widthDp,
            options = widthOptions,
            onSelected = onWidthSelected,
            onFocused = onFocused
        )
        OptionRow(
            title = "Radio de Borde",
            selectedValue = cornerRadiusDp,
            options = radiusOptions,
            onSelected = onCornerRadiusSelected,
            onFocused = onFocused
        )

        Button(
            onClick = onReset,
            modifier = Modifier.onFocusChanged {
                if (it.isFocused) onFocused()
            },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.Background,
                focusedContainerColor = NuvioColors.Background
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(SettingsPillRadius)
                )
            )
        ) {
            Text(
                text = "Restablecer Valores por Defecto",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit,
    onFocused: () -> Unit
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: "Personalizado"

    Text(
        text = "$title ($selectedLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioColors.TextSecondary
    )

    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { option ->
            ValueChip(
                label = option.label,
                isSelected = option.value == selectedValue,
                onClick = { onSelected(option.value) },
                onFocused = onFocused
            )
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    SettingsChoiceChip(
        label = label,
        selected = isSelected,
        onClick = onClick,
        onFocused = onFocused
    )
}

private data class PresetOption(
    val label: String,
    val value: Int
)