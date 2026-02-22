@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    // Solo solicitar el foco cuando termina la carga (no al cambiar el filtro de addons)
    LaunchedEffect(uiState.isLoadingSourceStreams) {
        if (!uiState.isLoadingSourceStreams && uiState.sourceFilteredStreams.isNotEmpty()) {
            try {
                streamsFocusRequester.requestFocus()
            } catch (_: Exception) {
                // El focus requester podría no estar listo todavía
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(NuvioColors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fuentes",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(
                        text = "Recargar",
                        onClick = onReload,
                        isPrimary = false
                    )
                    DialogButton(
                        text = "Cerrar",
                        onClick = onClose,
                        isPrimary = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Información del contenido actual
            Text(
                text = buildString {
                    if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        append("T${uiState.currentSeason} E${uiState.currentEpisode}")
                        if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                            append(" • ${uiState.currentEpisodeTitle}")
                        }
                    } else {
                        append(uiState.title)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = !uiState.isLoadingSourceStreams && uiState.sourceAvailableAddons.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    onAddonSelected = onAddonFilterSelected
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null -> {
                    Text(
                        text = uiState.sourceStreamsError ?: "Error al cargar los enlaces",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                uiState.sourceFilteredStreams.isEmpty() -> {
                    Text(
                        text = "No se encontraron enlaces",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        items(uiState.sourceFilteredStreams) { stream ->
                            StreamItem(
                                stream = stream,
                                focusRequester = streamsFocusRequester,
                                requestInitialFocus = stream == uiState.sourceFilteredStreams.firstOrNull(),
                                onClick = { onStreamSelected(stream) }
                            )
                        }
                    }
                }
            }
        }
    }
}