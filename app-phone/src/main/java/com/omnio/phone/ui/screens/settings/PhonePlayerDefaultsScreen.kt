package com.omnio.phone.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnio.phone.R
import com.omnio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES

private enum class PickerType { SUBTITLE_PRIMARY, SUBTITLE_SECONDARY, AUDIO_PRIMARY, AUDIO_SECONDARY }

private data class LanguageOption(
    val code: String?,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePlayerDefaultsScreen(
    onBack: () -> Unit,
    viewModel: PhonePlayerDefaultsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var activePicker by remember { mutableStateOf<PickerType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_defaults_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(text = stringResource(R.string.player_defaults_subtitle_section))
            ValueRow(
                title = stringResource(R.string.player_defaults_subtitle_primary),
                value = labelForSubtitle(state.preferredSubtitleLanguage),
                onClick = { activePicker = PickerType.SUBTITLE_PRIMARY }
            )
            ValueRow(
                title = stringResource(R.string.player_defaults_subtitle_secondary),
                value = state.secondarySubtitleLanguage?.let(::labelForSubtitle)
                    ?: stringResource(R.string.player_defaults_none),
                onClick = { activePicker = PickerType.SUBTITLE_SECONDARY }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            SectionHeader(text = stringResource(R.string.player_defaults_audio_section))
            ValueRow(
                title = stringResource(R.string.player_defaults_audio_primary),
                value = labelForAudio(state.preferredAudioLanguage),
                onClick = { activePicker = PickerType.AUDIO_PRIMARY }
            )
            ValueRow(
                title = stringResource(R.string.player_defaults_audio_secondary),
                value = state.secondaryAudioLanguage?.let(::labelForAudio)
                    ?: stringResource(R.string.player_defaults_none),
                onClick = { activePicker = PickerType.AUDIO_SECONDARY }
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    activePicker?.let { type ->
        LanguagePickerDialog(
            type = type,
            currentCode = when (type) {
                PickerType.SUBTITLE_PRIMARY -> state.preferredSubtitleLanguage
                PickerType.SUBTITLE_SECONDARY -> state.secondarySubtitleLanguage
                PickerType.AUDIO_PRIMARY -> state.preferredAudioLanguage
                PickerType.AUDIO_SECONDARY -> state.secondaryAudioLanguage
            },
            onSelect = { code ->
                when (type) {
                    PickerType.SUBTITLE_PRIMARY -> viewModel.setPreferredSubtitleLanguage(
                        code ?: "en"
                    )
                    PickerType.SUBTITLE_SECONDARY -> viewModel.setSecondarySubtitleLanguage(code)
                    PickerType.AUDIO_PRIMARY -> viewModel.setPreferredAudioLanguage(
                        code ?: PhonePlayerDefaultsViewModel.AUDIO_DEVICE_CODE
                    )
                    PickerType.AUDIO_SECONDARY -> viewModel.setSecondaryAudioLanguage(code)
                }
                activePicker = null
            },
            onDismiss = { activePicker = null }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    type: PickerType,
    currentCode: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = optionsFor(type)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleFor(type)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.code ?: "__null__" }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.code) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.code == currentCode,
                            onClick = { onSelect(option.code) }
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun titleFor(type: PickerType): String = when (type) {
    PickerType.SUBTITLE_PRIMARY -> stringResource(R.string.player_defaults_subtitle_primary)
    PickerType.SUBTITLE_SECONDARY -> stringResource(R.string.player_defaults_subtitle_secondary)
    PickerType.AUDIO_PRIMARY -> stringResource(R.string.player_defaults_audio_primary)
    PickerType.AUDIO_SECONDARY -> stringResource(R.string.player_defaults_audio_secondary)
}

@Composable
private fun optionsFor(type: PickerType): List<LanguageOption> {
    val none = stringResource(R.string.player_defaults_none)
    val deviceDefault = stringResource(R.string.player_defaults_device_default)
    val original = stringResource(R.string.player_defaults_original)
    val forced = stringResource(R.string.player_defaults_forced)
    val languageItems = AVAILABLE_SUBTITLE_LANGUAGES.map { LanguageOption(it.code, it.name) }

    return when (type) {
        PickerType.SUBTITLE_PRIMARY -> listOf(
            LanguageOption(PhonePlayerDefaultsViewModel.SUBTITLE_FORCED_CODE, forced)
        ) + languageItems
        PickerType.SUBTITLE_SECONDARY -> listOf(LanguageOption(null, none)) + languageItems
        PickerType.AUDIO_PRIMARY -> listOf(
            LanguageOption(PhonePlayerDefaultsViewModel.AUDIO_DEVICE_CODE, deviceDefault),
            LanguageOption(PhonePlayerDefaultsViewModel.AUDIO_DEFAULT_CODE, original)
        ) + languageItems
        PickerType.AUDIO_SECONDARY -> listOf(LanguageOption(null, none)) + languageItems
    }
}

private fun labelForSubtitle(code: String): String = when (code) {
    PhonePlayerDefaultsViewModel.SUBTITLE_FORCED_CODE -> "Forced subtitles only"
    else -> AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }
        ?.name ?: code
}

private fun labelForAudio(code: String): String = when (code) {
    PhonePlayerDefaultsViewModel.AUDIO_DEVICE_CODE -> "System default"
    PhonePlayerDefaultsViewModel.AUDIO_DEFAULT_CODE -> "Original audio"
    else -> AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }
        ?.name ?: code
}
