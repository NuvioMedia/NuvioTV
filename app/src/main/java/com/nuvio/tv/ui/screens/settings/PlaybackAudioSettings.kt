@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.trailerAndAudioSettingsItems(
    playerSettings: PlayerSettings,
    trailerSettings: TrailerSettings,
    onShowAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onSetTrailerEnabled: (Boolean) -> Unit,
    onSetTrailerDelaySeconds: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetMapDV7ToHevc: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item {
        Text(
            text = "Tráiler",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.PlayCircle,
            title = "Reproducción automática de tráilers",
            subtitle = "Reproducir tráilers automáticamente en la pantalla de detalles tras un periodo de inactividad",
            isChecked = trailerSettings.enabled,
            onCheckedChange = onSetTrailerEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    if (trailerSettings.enabled) {
        item {
            SliderSettingsItem(
                icon = Icons.Default.Timer,
                title = "Retraso del tráiler",
                value = trailerSettings.delaySeconds,
                valueText = "${trailerSettings.delaySeconds}s",
                minValue = 3,
                maxValue = 15,
                step = 1,
                onValueChange = onSetTrailerDelaySeconds,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }

    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Audio",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "El paso directo de audio (passthrough para TrueHD, DTS, AC-3, etc.) es automático. Al conectar a un receptor AV o barra de sonido compatible por HDMI, el audio sin pérdida se envía tal cual sin decodificar.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        val audioLangName = when (playerSettings.preferredAudioLanguage) {
            AudioLanguageOption.DEFAULT -> "Predeterminado (archivo multimedia)"
            AudioLanguageOption.DEVICE -> "Idioma del dispositivo"
            else -> AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.preferredAudioLanguage
            }?.name ?: playerSettings.preferredAudioLanguage
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = "Idioma de audio preferido",
            subtitle = audioLangName,
            onClick = onShowAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Speed,
            title = "Omitir silencios",
            subtitle = "Omitir las partes silenciosas del audio durante la reproducción",
            isChecked = playerSettings.skipSilence,
            onCheckedChange = onSetSkipSilence,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Audio Avanzado",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "Estos ajustes pueden causar problemas en algunos dispositivos. Cámbialos solo si sabes lo que haces.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        val decoderName = when (playerSettings.decoderPriority) {
            0 -> "Solo decodificadores del dispositivo"
            1 -> "Preferir decodificadores del dispositivo"
            2 -> "Preferir decodificadores de la app (FFmpeg)"
            else -> "Preferir decodificadores del dispositivo"
        }

        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = "Prioridad del decodificador",
            subtitle = decoderName,
            onClick = onShowDecoderPriorityDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.VolumeUp,
            title = "Reproducción tunelizada (Tunneled Playback)",
            subtitle = "Sincronización de audio/video a nivel de hardware. Puede mejorar la reproducción en algunos dispositivos Android TV",
            isChecked = playerSettings.tunnelingEnabled,
            onCheckedChange = onSetTunnelingEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = "Conversión de DV7 → HEVC",
            subtitle = "Asignar el perfil 7 de Dolby Vision a HEVC estándar para dispositivos sin soporte de hardware para DV",
            isChecked = playerSettings.mapDV7ToHevc,
            onCheckedChange = onSetMapDV7ToHevc,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }
}

@Composable
internal fun AudioSettingsDialogs(
    showAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    selectedLanguage: String,
    selectedPriority: Int,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit
) {
    if (showAudioLanguageDialog) {
        AudioLanguageSelectionDialog(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = {
                onSetPreferredAudioLanguage(it)
                onDismissAudioLanguageDialog()
            },
            onDismiss = onDismissAudioLanguageDialog
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = selectedPriority,
            onPrioritySelected = {
                onSetDecoderPriority(it)
                onDismissDecoderPriorityDialog()
            },
            onDismiss = onDismissDecoderPriorityDialog
        )
    }
}

@Composable
private fun AudioLanguageSelectionDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val specialOptions = listOf(
        AudioLanguageOption.DEFAULT to "Predeterminado (archivo multimedia)",
        AudioLanguageOption.DEVICE to "Idioma del dispositivo"
    )
    val allOptions = specialOptions + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code to it.name }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Idioma de audio preferido",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allOptions.size) { index ->
                        val (code, name) = allOptions[index]
                        val isSelected = code == selectedLanguage
                        var isFocused by remember { mutableStateOf(false) }

                        Card(
                            onClick = { onLanguageSelected(code) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                .onFocusChanged { isFocused = it.isFocused },
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seleccionado",
                                        tint = NuvioColors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(0, "Solo decodificadores del dispositivo", "Usar solo los decodificadores de hardware integrados. Es lo más compatible, pero puede que no soporte todos los formatos."),
        Triple(1, "Preferir decodificadores del dispositivo", "Usar decodificadores de hardware cuando estén disponibles, y FFmpeg como alternativa. Recomendado para la mayoría de dispositivos."),
        Triple(2, "Preferir decodificadores de la app (FFmpeg)", "Usar decodificadores FFmpeg cuando estén disponibles. Mejor soporte de formatos, pero mayor uso de CPU.")
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Prioridad del decodificador",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Controla si se usan decodificadores de hardware o de software (FFmpeg) para audio y video",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options.size) { index ->
                        val (priority, title, description) = options[index]
                        val isSelected = priority == selectedPriority

                        Card(
                            onClick = { onPrioritySelected(priority) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        color = NuvioColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seleccionado",
                                        tint = NuvioColors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}