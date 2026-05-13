package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.ui.components.P2pConsentDialog
import kotlinx.coroutines.launch

@Composable
fun EssentialPlaybackSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: PlaybackSettingsViewModel = hiltViewModel()
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = null)
    val torrentSettings by viewModel.torrentSettingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
    var showAudioLanguageDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    val settings = playerSettings

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                SettingsDetailHeader(
                    title = stringResource(R.string.essential_playback_header_title),
                    subtitle = stringResource(R.string.essential_playback_header_subtitle)
                )
            }
            item(key = "playback_basics") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.essential_playback_basics)) {
                    SettingsActionRow(
                        title = stringResource(R.string.essential_stream_selection),
                        subtitle = stringResource(R.string.essential_stream_selection_subtitle),
                        value = when (settings?.streamAutoPlayMode) {
                            StreamAutoPlayMode.FIRST_STREAM -> stringResource(R.string.stream_auto_play_first_stream)
                            StreamAutoPlayMode.MANUAL -> stringResource(R.string.stream_auto_play_manual_short)
                            StreamAutoPlayMode.REGEX_MATCH -> stringResource(R.string.stream_auto_play_smart_match)
                            null -> ""
                        },
                        trailingIcon = Icons.Default.PlayArrow,
                        onClick = {
                            val current = settings?.streamAutoPlayMode ?: StreamAutoPlayMode.MANUAL
                            val next = if (current == StreamAutoPlayMode.MANUAL) {
                                StreamAutoPlayMode.FIRST_STREAM
                            } else {
                                StreamAutoPlayMode.MANUAL
                            }
                            coroutineScope.launch { viewModel.setStreamAutoPlayMode(next) }
                        },
                        enabled = settings != null,
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.essential_autoplay_next_episode),
                        subtitle = stringResource(R.string.essential_autoplay_next_episode_subtitle),
                        checked = settings?.streamAutoPlayNextEpisodeEnabled == true,
                        onToggle = {
                            val current = settings ?: return@SettingsToggleRow
                            coroutineScope.launch {
                                viewModel.setStreamAutoPlayNextEpisodeEnabled(!current.streamAutoPlayNextEpisodeEnabled)
                            }
                        },
                        enabled = settings != null
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.essential_p2p_streams),
                        subtitle = stringResource(R.string.essential_p2p_streams_subtitle),
                        checked = torrentSettings?.p2pEnabled == true,
                        onToggle = {
                            val current = torrentSettings ?: return@SettingsToggleRow
                            if (current.p2pEnabled) {
                                viewModel.setP2pEnabled(false)
                            } else {
                                showP2pConsentDialog = true
                            }
                        },
                        enabled = torrentSettings != null
                    )
                }
            }
            item(key = "subtitles_audio") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.essential_subtitles_and_audio)) {
                    SettingsActionRow(
                        title = stringResource(R.string.essential_subtitle_language),
                        subtitle = stringResource(R.string.essential_subtitle_language_subtitle),
                        value = settings?.subtitleStyle?.preferredLanguage.orEmpty(),
                        trailingIcon = Icons.Default.VideoSettings,
                        onClick = { showSubtitleLanguageDialog = true },
                        enabled = settings != null
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.essential_audio_language),
                        subtitle = stringResource(R.string.essential_audio_language_subtitle),
                        value = settings?.preferredAudioLanguage.orEmpty(),
                        trailingIcon = Icons.Default.VideoSettings,
                        onClick = { showAudioLanguageDialog = true },
                        enabled = settings != null
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.audio_decoder_priority),
                        subtitle = stringResource(R.string.audio_decoder_controls),
                        value = when (settings?.decoderPriority) {
                            0 -> stringResource(R.string.audio_decoder_device_only)
                            1 -> stringResource(R.string.audio_decoder_prefer_device)
                            2 -> stringResource(R.string.audio_decoder_prefer_app)
                            else -> ""
                        },
                        trailingIcon = Icons.Default.Tune,
                        onClick = { showDecoderPriorityDialog = true },
                        enabled = settings != null
                    )
                }
            }
        }
        SettingsVerticalScrollIndicators(state = listState)
    }

    if (showSubtitleLanguageDialog && settings != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_subtitle_language),
            selectedLanguage = settings.subtitleStyle.preferredLanguage,
            showNoneOption = false,
            onLanguageSelected = { language ->
                if (language != null) {
                    coroutineScope.launch { viewModel.setSubtitlePreferredLanguage(language) }
                }
                showSubtitleLanguageDialog = false
            },
            onDismiss = { showSubtitleLanguageDialog = false }
        )
    }
    if (showAudioLanguageDialog && settings != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_audio_language),
            selectedLanguage = settings.preferredAudioLanguage,
            showNoneOption = false,
            extraOptions = listOf(
                AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_media_default),
                AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = { language ->
                if (language != null) {
                    coroutineScope.launch { viewModel.setPreferredAudioLanguage(language) }
                }
                showAudioLanguageDialog = false
            },
            onDismiss = { showAudioLanguageDialog = false }
        )
    }
    if (showDecoderPriorityDialog && settings != null) {
        DecoderPriorityDialog(
            selectedPriority = settings.decoderPriority,
            onPrioritySelected = { priority ->
                coroutineScope.launch { viewModel.setDecoderPriority(priority) }
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false }
        )
    }
    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                viewModel.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false }
        )
    }
}
