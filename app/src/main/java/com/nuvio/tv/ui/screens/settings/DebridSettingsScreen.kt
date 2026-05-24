@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridDeviceAuthorization
import com.nuvio.tv.core.debrid.DebridDeviceAuthorizationTokenResult
import com.nuvio.tv.core.debrid.DebridProvider
import com.nuvio.tv.core.debrid.DebridProviderAuthMethod
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeApiKeyDialog by remember { mutableStateOf<String?>(null) }
    var activeDeviceAuthDialog by remember { mutableStateOf<String?>(null) }
    var activeStreamPicker by remember { mutableStateOf<DebridStreamPicker?>(null) }
    var showResolverPicker by remember { mutableStateOf(false) }
    var showPrepareCountDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activeResolverProvider = uiState.activeResolverProvider

    LaunchedEffect(uiState.serverError) {
        val error = uiState.serverError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.debrid_title),
            subtitle = stringResource(R.string.debrid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val state = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "debrid_notice") {
                        DebridInfoText(text = stringResource(R.string.debrid_experimental_notice))
                    }

                    item(key = "debrid_cloud_library") {
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_cloud_library),
                            subtitle = stringResource(R.string.debrid_cloud_library_description),
                            checked = uiState.canUseCloudLibrary,
                            onToggle = { viewModel.setCloudLibraryEnabled(!uiState.cloudLibraryEnabled) },
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            enabled = uiState.hasCloudLibraryProvider
                        )
                    }

                    item(key = "debrid_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.debrid_enable_title),
                            subtitle = stringResource(R.string.debrid_enable_subtitle),
                            checked = uiState.canResolvePlayableLinks,
                            onToggle = { viewModel.onEvent(DebridSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                            enabled = uiState.hasResolverProvider
                        )
                    }

                    if (uiState.canResolvePlayableLinks && uiState.resolverProviders.size > 1 && activeResolverProvider != null) {
                        item(key = "debrid_resolve_with") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_resolve_with),
                                subtitle = stringResource(R.string.debrid_resolve_with_description),
                                value = activeResolverProvider.displayName,
                                onClick = { showResolverPicker = true },
                                enabled = true
                            )
                        }
                    }

                    if (!uiState.hasResolverProvider) {
                        item(key = "debrid_add_key_first") {
                            DebridInfoText(text = stringResource(R.string.debrid_add_key_first))
                        }
                    }

                    item(key = "debrid_account_section") {
                        DebridSectionLabel(text = stringResource(R.string.debrid_section_account))
                    }

                    DebridProviders.visible().forEach { provider ->
                        item(key = "debrid_${provider.id}_api_key") {
                            SettingsActionRow(
                                title = provider.displayName,
                                subtitle = if (provider.authMethod == DebridProviderAuthMethod.DeviceCode) {
                                    stringResource(R.string.debrid_provider_device_description, provider.displayName)
                                } else {
                                    stringResource(R.string.debrid_provider_description, provider.displayName)
                                },
                                value = providerCredentialStatus(
                                    provider = provider,
                                    credential = uiState.apiKeyFor(provider.id),
                                    notSetLabel = stringResource(R.string.debrid_not_set),
                                    connectedLabel = stringResource(R.string.debrid_connected)
                                ),
                                onClick = {
                                    when (provider.authMethod) {
                                        DebridProviderAuthMethod.DeviceCode -> activeDeviceAuthDialog = provider.id
                                        DebridProviderAuthMethod.ApiKey -> activeApiKeyDialog = provider.id
                                    }
                                },
                                enabled = true
                            )
                        }
                    }

                    if (uiState.canResolvePlayableLinks) {
                        item(key = "debrid_instant_section") {
                            DebridSectionLabel(text = stringResource(R.string.debrid_section_instant_playback))
                        }

                        item(key = "debrid_prepare_links") {
                            val prepareEnabled = uiState.instantPlaybackPreparationLimit > 0
                            SettingsToggleRow(
                                title = stringResource(R.string.debrid_prepare_instant_playback),
                                subtitle = stringResource(R.string.debrid_prepare_instant_playback_description),
                                checked = prepareEnabled,
                                onToggle = { viewModel.setInstantPlaybackPreparationEnabled(!prepareEnabled) },
                                enabled = true
                            )
                        }

                        if (uiState.instantPlaybackPreparationLimit > 0) {
                            item(key = "debrid_prepare_count") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_prepare_stream_count),
                                    subtitle = null,
                                    value = prepareCountLabel(uiState.instantPlaybackPreparationLimit),
                                    onClick = { showPrepareCountDialog = true },
                                    enabled = true
                                )
                            }
                        }
                    }

                    item(key = "debrid_formatting_section") {
                        DebridSectionLabel(text = stringResource(R.string.debrid_section_formatting))
                    }

                    item(key = "debrid_formatter") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_title),
                            subtitle = stringResource(R.string.debrid_formatter_subtitle),
                            value = stringResource(R.string.debrid_formatter_configure),
                            onClick = { viewModel.startFormatterQrMode() },
                            enabled = uiState.enabled
                        )
                    }

                    item(key = "debrid_formatter_reset") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_formatter_reset_title),
                            subtitle = stringResource(R.string.debrid_formatter_reset_subtitle),
                            value = stringResource(R.string.layout_reset_default),
                            onClick = { viewModel.resetFormatterTemplates() },
                            enabled = uiState.enabled
                        )
                    }

                    if (uiState.canResolvePlayableLinks) {
                        item(key = "debrid_filters_section") {
                            DebridSectionLabel(text = stringResource(R.string.debrid_section_filters))
                        }

                        item(key = "debrid_max_results") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_stream_max_results_title),
                                subtitle = stringResource(R.string.debrid_stream_max_results_subtitle),
                                value = streamMaxResultsLabel(uiState.streamPreferences.maxResults),
                                onClick = { activeStreamPicker = DebridStreamPicker.MAX_RESULTS },
                                enabled = true
                            )
                        }

                        item(key = "debrid_sort_mode") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_stream_sort_title),
                                subtitle = stringResource(R.string.debrid_stream_sort_subtitle),
                                value = sortProfileLabel(uiState.streamPreferences.sortCriteria),
                                onClick = { activeStreamPicker = DebridStreamPicker.SORT_MODE },
                                enabled = true
                            )
                        }

                        item(key = "debrid_per_resolution_limit") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_stream_per_resolution_limit_title),
                                subtitle = stringResource(R.string.debrid_stream_per_resolution_limit_subtitle),
                                value = streamMaxResultsLabel(uiState.streamPreferences.maxPerResolution),
                                onClick = { activeStreamPicker = DebridStreamPicker.MAX_PER_RESOLUTION },
                                enabled = true
                            )
                        }

                        item(key = "debrid_per_quality_limit") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_stream_per_quality_limit_title),
                                subtitle = stringResource(R.string.debrid_stream_per_quality_limit_subtitle),
                                value = streamMaxResultsLabel(uiState.streamPreferences.maxPerQuality),
                                onClick = { activeStreamPicker = DebridStreamPicker.MAX_PER_QUALITY },
                                enabled = true
                            )
                        }

                        item(key = "debrid_size_range") {
                            SettingsActionRow(
                                title = stringResource(R.string.debrid_stream_size_range_title),
                                subtitle = stringResource(R.string.debrid_stream_size_range_subtitle),
                                value = sizeRangeLabel(uiState.streamPreferences),
                                onClick = { activeStreamPicker = DebridStreamPicker.SIZE_RANGE },
                                enabled = true
                            )
                        }

                        debridRuleRows(uiState.streamPreferences) { picker, title, subtitle, value ->
                            item(key = "debrid_rule_${picker.name}") {
                                SettingsActionRow(
                                    title = title,
                                    subtitle = subtitle,
                                    value = value,
                                    onClick = { activeStreamPicker = picker },
                                    enabled = true
                                )
                            }
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = state)
            }
        }
    }

    activeApiKeyDialog?.let { providerId ->
        DebridProviders.byId(providerId)?.let { provider ->
            DebridApiKeyDialog(
                title = stringResource(R.string.debrid_api_key_dialog_title, provider.displayName),
                subtitle = stringResource(R.string.debrid_api_key_dialog_subtitle, provider.displayName),
                placeholder = stringResource(R.string.debrid_api_key_dialog_placeholder, provider.displayName),
                currentValue = uiState.apiKeyFor(provider.id),
                viewModel = viewModel,
                onSave = { value, onSaved -> viewModel.validateAndSaveProviderApiKey(provider.id, value, onSaved) },
                onSaved = { activeApiKeyDialog = null },
                onClear = {
                    viewModel.validateAndSaveProviderApiKey(provider.id, "") {}
                    activeApiKeyDialog = null
                },
                onDismiss = { activeApiKeyDialog = null }
            )
        }
    }

    activeDeviceAuthDialog?.let { providerId ->
        DebridProviders.byId(providerId)?.let { provider ->
            DebridDeviceAuthDialog(
                provider = provider,
                currentValue = uiState.apiKeyFor(provider.id),
                viewModel = viewModel,
                onConnected = { token -> viewModel.saveProviderCredential(provider.id, token) },
                onDisconnect = { viewModel.saveProviderCredential(provider.id, "") },
                onDismiss = { activeDeviceAuthDialog = null }
            )
        }
    }

    if (showResolverPicker) {
        DebridResolverProviderDialog(
            providers = uiState.resolverProviders,
            selectedProviderId = uiState.activeResolverProvider?.id,
            onSelected = { providerId ->
                viewModel.setPreferredResolverProviderId(providerId)
                showResolverPicker = false
            },
            onDismiss = { showResolverPicker = false }
        )
    }

    when (activeStreamPicker) {
        DebridStreamPicker.MAX_RESULTS -> DebridMaxResultsDialog(
            selectedValue = uiState.streamPreferences.maxResults,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(maxResults = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.MAX_PER_RESOLUTION -> DebridMaxResultsDialog(
            selectedValue = uiState.streamPreferences.maxPerResolution,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(maxPerResolution = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.MAX_PER_QUALITY -> DebridMaxResultsDialog(
            selectedValue = uiState.streamPreferences.maxPerQuality,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(maxPerQuality = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.SORT_MODE -> DebridSortModeDialog(
            selectedValue = sortProfileFor(uiState.streamPreferences.sortCriteria),
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(sortCriteria = sortCriteriaForProfile(value)))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.SIZE_RANGE -> DebridSizeRangeDialog(
            selectedValue = uiState.streamPreferences.sizeMinGb to uiState.streamPreferences.sizeMaxGb,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(sizeMinGb = value.first, sizeMaxGb = value.second))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_RESOLUTIONS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_resolutions_preferred),
            selectedValues = uiState.streamPreferences.preferredResolutions,
            values = DebridStreamResolution.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredResolutions = value.ifEmpty { DebridStreamResolution.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_RESOLUTIONS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_resolutions_required),
            selectedValues = uiState.streamPreferences.requiredResolutions,
            values = DebridStreamResolution.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredResolutions = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_RESOLUTIONS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_resolutions_excluded),
            selectedValues = uiState.streamPreferences.excludedResolutions,
            values = DebridStreamResolution.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedResolutions = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_QUALITIES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_qualities_preferred),
            selectedValues = uiState.streamPreferences.preferredQualities,
            values = DebridStreamQuality.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredQualities = value.ifEmpty { DebridStreamQuality.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_QUALITIES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_qualities_required),
            selectedValues = uiState.streamPreferences.requiredQualities,
            values = DebridStreamQuality.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredQualities = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_QUALITIES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_qualities_excluded),
            selectedValues = uiState.streamPreferences.excludedQualities,
            values = DebridStreamQuality.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedQualities = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_VISUAL_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_visual_tags_preferred),
            selectedValues = uiState.streamPreferences.preferredVisualTags,
            values = DebridStreamVisualTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredVisualTags = value.ifEmpty { DebridStreamVisualTag.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_VISUAL_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_visual_tags_required),
            selectedValues = uiState.streamPreferences.requiredVisualTags,
            values = DebridStreamVisualTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredVisualTags = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_VISUAL_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_visual_tags_excluded),
            selectedValues = uiState.streamPreferences.excludedVisualTags,
            values = DebridStreamVisualTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedVisualTags = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_AUDIO_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_audio_tags_preferred),
            selectedValues = uiState.streamPreferences.preferredAudioTags,
            values = DebridStreamAudioTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredAudioTags = value.ifEmpty { DebridStreamAudioTag.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_AUDIO_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_audio_tags_required),
            selectedValues = uiState.streamPreferences.requiredAudioTags,
            values = DebridStreamAudioTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredAudioTags = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_AUDIO_TAGS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_audio_tags_excluded),
            selectedValues = uiState.streamPreferences.excludedAudioTags,
            values = DebridStreamAudioTag.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedAudioTags = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_AUDIO_CHANNELS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_channels_preferred),
            selectedValues = uiState.streamPreferences.preferredAudioChannels,
            values = DebridStreamAudioChannel.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredAudioChannels = value.ifEmpty { DebridStreamAudioChannel.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_AUDIO_CHANNELS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_channels_required),
            selectedValues = uiState.streamPreferences.requiredAudioChannels,
            values = DebridStreamAudioChannel.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredAudioChannels = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_AUDIO_CHANNELS -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_channels_excluded),
            selectedValues = uiState.streamPreferences.excludedAudioChannels,
            values = DebridStreamAudioChannel.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedAudioChannels = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_ENCODES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_encodes_preferred),
            selectedValues = uiState.streamPreferences.preferredEncodes,
            values = DebridStreamEncode.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredEncodes = value.ifEmpty { DebridStreamEncode.defaultOrder }))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_ENCODES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_encodes_required),
            selectedValues = uiState.streamPreferences.requiredEncodes,
            values = DebridStreamEncode.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredEncodes = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_ENCODES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_encodes_excluded),
            selectedValues = uiState.streamPreferences.excludedEncodes,
            values = DebridStreamEncode.defaultOrder,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedEncodes = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.PREFERRED_LANGUAGES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_languages_preferred),
            selectedValues = uiState.streamPreferences.preferredLanguages,
            values = DebridStreamLanguage.entries,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(preferredLanguages = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_LANGUAGES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_languages_required),
            selectedValues = uiState.streamPreferences.requiredLanguages,
            values = DebridStreamLanguage.entries,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredLanguages = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_LANGUAGES -> DebridMultiChoiceDialog(
            title = stringResource(R.string.debrid_stream_languages_excluded),
            selectedValues = uiState.streamPreferences.excludedLanguages,
            values = DebridStreamLanguage.entries,
            label = { it.label },
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedLanguages = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.REQUIRED_RELEASE_GROUPS -> DebridTextListDialog(
            title = stringResource(R.string.debrid_stream_release_groups_required),
            selectedValues = uiState.streamPreferences.requiredReleaseGroups,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(requiredReleaseGroups = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        DebridStreamPicker.EXCLUDED_RELEASE_GROUPS -> DebridTextListDialog(
            title = stringResource(R.string.debrid_stream_release_groups_excluded),
            selectedValues = uiState.streamPreferences.excludedReleaseGroups,
            onSelected = { value ->
                viewModel.setStreamPreferences(uiState.streamPreferences.copy(excludedReleaseGroups = value))
                activeStreamPicker = null
            },
            onDismiss = { activeStreamPicker = null }
        )
        null -> Unit
    }

    if (showPrepareCountDialog) {
        DebridPrepareCountDialog(
            selectedLimit = uiState.instantPlaybackPreparationLimit,
            onLimitSelected = { limit ->
                viewModel.setInstantPlaybackPreparationLimit(limit)
                showPrepareCountDialog = false
            },
            onDismiss = { showPrepareCountDialog = false }
        )
    }

    if (uiState.isFormatterQrModeActive) {
        QrCodeOverlay(
            qrBitmap = uiState.formatterQrCodeBitmap,
            serverUrl = uiState.formatterServerUrl,
            instruction = stringResource(R.string.debrid_formatter_qr_instruction),
            onClose = { viewModel.stopFormatterQrMode() }
        )
    }
}

@Composable
private fun DebridInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun DebridSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioColors.TextPrimary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
    )
}

@Composable
private fun prepareCountLabel(limit: Int): String {
    return if (limit == 1) {
        stringResource(R.string.debrid_prepare_count_one)
    } else {
        stringResource(R.string.debrid_prepare_count_many, limit)
    }
}

@Composable
private fun DebridPrepareCountDialog(
    selectedLimit: Int,
    onLimitSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(1, 2, 3, 5)

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_prepare_stream_count),
        subtitle = stringResource(R.string.debrid_prepare_stream_count_warning),
        options = options.map { limit ->
            SettingsPickerOption(limit, prepareCountLabel(limit))
        },
        selectedValue = selectedLimit,
        onOptionSelected = onLimitSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun DebridResolverProviderDialog(
    providers: List<DebridProvider>,
    selectedProviderId: String?,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = selectedProviderId ?: providers.firstOrNull()?.id.orEmpty()

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_resolve_with),
        subtitle = stringResource(R.string.debrid_resolve_with_description),
        options = providers.map { provider ->
            SettingsPickerOption(provider.id, provider.displayName)
        },
        selectedValue = selected,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun DebridMaxResultsDialog(
    selectedValue: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 5, 10, 20, 50)

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_stream_max_results_title),
        options = options.map { value ->
            SettingsPickerOption(value, streamMaxResultsLabel(value))
        },
        selectedValue = selectedValue,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 360.dp
    )
}

@Composable
private fun DebridSortModeDialog(
    selectedValue: DebridSortProfile,
    onSelected: (DebridSortProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        DebridSortProfile.DEFAULT,
        DebridSortProfile.LARGEST,
        DebridSortProfile.SMALLEST,
        DebridSortProfile.AUDIO,
        DebridSortProfile.LANGUAGE
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_stream_sort_title),
        options = options.map { value ->
            SettingsPickerOption(value, sortProfileLabel(value))
        },
        selectedValue = selectedValue,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 360.dp
    )
}

@Composable
private fun DebridSizeRangeDialog(
    selectedValue: Pair<Int, Int>,
    onSelected: (Pair<Int, Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to 0,
        0 to 5,
        0 to 10,
        5 to 20,
        10 to 50,
        20 to 100
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.debrid_stream_size_range_title),
        options = options.map { value ->
            SettingsPickerOption(value, sizeRangeLabel(value.first, value.second))
        },
        selectedValue = selectedValue,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun <T> DebridMultiChoiceDialog(
    title: String,
    selectedValues: List<T>,
    values: List<T>,
    label: (T) -> String,
    onSelected: (List<T>) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsMultiChoiceDialog(
        title = title,
        options = values.map { value -> SettingsPickerOption(value, label(value)) },
        selectedValues = selectedValues,
        onValuesSelected = onSelected,
        onDismiss = onDismiss,
        width = 560.dp,
        maxHeight = 420.dp
    )
}

@Composable
private fun DebridTextListDialog(
    title: String,
    selectedValues: List<String>,
    onSelected: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(selectedValues) { mutableStateOf(selectedValues.joinToString("\n")) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onSelected(value.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.distinct())
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.debrid_stream_release_groups_input_subtitle),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if ((native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                native.action == KeyEvent.ACTION_DOWN
                            ) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(NuvioColors.Primary)
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_clear),
                onClick = { value = "" }
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                onClick = { submit() },
                primary = true
            )
        }
    }
}

@Composable
private fun streamMaxResultsLabel(value: Int): String {
    return if (value <= 0) {
        stringResource(R.string.debrid_stream_max_results_all)
    } else {
        stringResource(R.string.debrid_stream_max_results_count, value)
    }
}

private fun sortProfileLabel(value: DebridSortProfile): String {
    return when (value) {
        DebridSortProfile.DEFAULT -> "Default"
        DebridSortProfile.LARGEST -> "Largest first"
        DebridSortProfile.SMALLEST -> "Smallest first"
        DebridSortProfile.AUDIO -> "Best audio first"
        DebridSortProfile.LANGUAGE -> "Language first"
    }
}

private fun LazyListScope.debridRuleRows(
    preferences: DebridStreamPreferences,
    row: LazyListScope.(DebridStreamPicker, String, String?, String) -> Unit
) {
    row(DebridStreamPicker.PREFERRED_RESOLUTIONS, "Preferred resolutions", "Sort selected resolutions first, in default order.", selectionCountLabel(preferences.preferredResolutions))
    row(DebridStreamPicker.REQUIRED_RESOLUTIONS, "Required resolutions", "Only show selected resolutions.", selectionCountLabel(preferences.requiredResolutions))
    row(DebridStreamPicker.EXCLUDED_RESOLUTIONS, "Excluded resolutions", "Hide selected resolutions.", selectionCountLabel(preferences.excludedResolutions))
    row(DebridStreamPicker.PREFERRED_QUALITIES, "Preferred qualities", "Sort selected qualities first, in default order.", selectionCountLabel(preferences.preferredQualities))
    row(DebridStreamPicker.REQUIRED_QUALITIES, "Required qualities", "Only show selected source qualities.", selectionCountLabel(preferences.requiredQualities))
    row(DebridStreamPicker.EXCLUDED_QUALITIES, "Excluded qualities", "Hide selected source qualities.", selectionCountLabel(preferences.excludedQualities))
    row(DebridStreamPicker.PREFERRED_VISUAL_TAGS, "Preferred visual tags", "Sort DV, HDR, 10bit, IMAX and similar tags.", selectionCountLabel(preferences.preferredVisualTags))
    row(DebridStreamPicker.REQUIRED_VISUAL_TAGS, "Required visual tags", "Require DV, HDR, 10bit, IMAX, SDR and similar tags.", selectionCountLabel(preferences.requiredVisualTags))
    row(DebridStreamPicker.EXCLUDED_VISUAL_TAGS, "Excluded visual tags", "Hide DV, HDR, 10bit, 3D and similar tags.", selectionCountLabel(preferences.excludedVisualTags))
    row(DebridStreamPicker.PREFERRED_AUDIO_TAGS, "Preferred audio tags", "Sort Atmos, TrueHD, DTS, AAC and similar tags.", selectionCountLabel(preferences.preferredAudioTags))
    row(DebridStreamPicker.REQUIRED_AUDIO_TAGS, "Required audio tags", "Require Atmos, TrueHD, DTS, AAC and similar tags.", selectionCountLabel(preferences.requiredAudioTags))
    row(DebridStreamPicker.EXCLUDED_AUDIO_TAGS, "Excluded audio tags", "Hide selected audio tags.", selectionCountLabel(preferences.excludedAudioTags))
    row(DebridStreamPicker.PREFERRED_AUDIO_CHANNELS, "Preferred channels", "Sort preferred channel layouts first.", selectionCountLabel(preferences.preferredAudioChannels))
    row(DebridStreamPicker.REQUIRED_AUDIO_CHANNELS, "Required channels", "Only show selected channel layouts.", selectionCountLabel(preferences.requiredAudioChannels))
    row(DebridStreamPicker.EXCLUDED_AUDIO_CHANNELS, "Excluded channels", "Hide selected channel layouts.", selectionCountLabel(preferences.excludedAudioChannels))
    row(DebridStreamPicker.PREFERRED_ENCODES, "Preferred encodes", "Sort AV1, HEVC, AVC and similar encodes.", selectionCountLabel(preferences.preferredEncodes))
    row(DebridStreamPicker.REQUIRED_ENCODES, "Required encodes", "Require AV1, HEVC, AVC and similar encodes.", selectionCountLabel(preferences.requiredEncodes))
    row(DebridStreamPicker.EXCLUDED_ENCODES, "Excluded encodes", "Hide selected encodes.", selectionCountLabel(preferences.excludedEncodes))
    row(DebridStreamPicker.PREFERRED_LANGUAGES, "Preferred languages", "Sort preferred audio languages first.", selectionCountLabel(preferences.preferredLanguages))
    row(DebridStreamPicker.REQUIRED_LANGUAGES, "Required languages", "Only show streams with selected languages.", selectionCountLabel(preferences.requiredLanguages))
    row(DebridStreamPicker.EXCLUDED_LANGUAGES, "Excluded languages", "Hide streams where every language is excluded.", selectionCountLabel(preferences.excludedLanguages))
    row(DebridStreamPicker.REQUIRED_RELEASE_GROUPS, "Required release groups", "Only show selected release groups.", selectionCountLabel(preferences.requiredReleaseGroups))
    row(DebridStreamPicker.EXCLUDED_RELEASE_GROUPS, "Excluded release groups", "Hide selected release groups.", selectionCountLabel(preferences.excludedReleaseGroups))
}

private fun selectionCountLabel(values: List<*>): String {
    return if (values.isEmpty()) "Any" else "${values.size} selected"
}

private fun sizeRangeLabel(preferences: DebridStreamPreferences): String {
    return sizeRangeLabel(preferences.sizeMinGb, preferences.sizeMaxGb)
}

private fun sizeRangeLabel(minGb: Int, maxGb: Int): String {
    return when {
        minGb <= 0 && maxGb <= 0 -> "Any"
        minGb <= 0 -> "Up to ${maxGb}GB"
        maxGb <= 0 -> "${minGb}GB+"
        else -> "${minGb}-${maxGb}GB"
    }
}

private fun sortProfileFor(criteria: List<DebridStreamSortCriterion>): DebridSortProfile {
    val normalized = criteria.map { it.key to it.direction }
    return when {
        normalized == listOf(DebridStreamSortKey.SIZE to DebridStreamSortDirection.DESC) -> DebridSortProfile.LARGEST
        normalized == listOf(DebridStreamSortKey.SIZE to DebridStreamSortDirection.ASC) -> DebridSortProfile.SMALLEST
        normalized.take(2) == listOf(
            DebridStreamSortKey.AUDIO_TAG to DebridStreamSortDirection.DESC,
            DebridStreamSortKey.AUDIO_CHANNEL to DebridStreamSortDirection.DESC
        ) -> DebridSortProfile.AUDIO
        normalized.firstOrNull() == DebridStreamSortKey.LANGUAGE to DebridStreamSortDirection.DESC -> DebridSortProfile.LANGUAGE
        else -> DebridSortProfile.DEFAULT
    }
}

private fun sortProfileLabel(criteria: List<DebridStreamSortCriterion>): String {
    return sortProfileLabel(sortProfileFor(criteria))
}

private fun sortCriteriaForProfile(profile: DebridSortProfile): List<DebridStreamSortCriterion> {
    return when (profile) {
        DebridSortProfile.DEFAULT -> DebridStreamSortCriterion.defaultOrder
        DebridSortProfile.LARGEST -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
        DebridSortProfile.SMALLEST -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC))
        DebridSortProfile.AUDIO -> listOf(
            DebridStreamSortCriterion(DebridStreamSortKey.AUDIO_TAG, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.AUDIO_CHANNEL, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
        )
        DebridSortProfile.LANGUAGE -> listOf(
            DebridStreamSortCriterion(DebridStreamSortKey.LANGUAGE, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
            DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
        )
    }
}

private enum class DebridSortProfile {
    DEFAULT,
    LARGEST,
    SMALLEST,
    AUDIO,
    LANGUAGE
}

@Composable
private fun DebridDeviceAuthDialog(
    provider: DebridProvider,
    currentValue: String,
    viewModel: DebridSettingsViewModel,
    onConnected: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = currentValue.isNotBlank()
    var restartNonce by remember(provider.id) { mutableStateOf(0) }
    var session by remember(provider.id, restartNonce, isConnected) { mutableStateOf<DebridDeviceAuthorization?>(null) }
    var isStarting by remember(provider.id, restartNonce, isConnected) { mutableStateOf(!isConnected) }
    var isPolling by remember(provider.id, restartNonce, isConnected) { mutableStateOf(false) }
    var statusMessage by remember(provider.id, restartNonce, isConnected) { mutableStateOf<String?>(null) }

    val startingMessage = stringResource(R.string.debrid_device_auth_starting)
    val waitingMessage = stringResource(R.string.debrid_device_auth_waiting)
    val failedMessage = stringResource(R.string.debrid_device_auth_failed)
    val missingConfigurationMessage = stringResource(R.string.debrid_device_auth_missing_configuration)
    val expiredMessage = stringResource(R.string.debrid_device_auth_expired)

    LaunchedEffect(provider.id, restartNonce, isConnected) {
        if (isConnected) {
            isStarting = false
            isPolling = false
            statusMessage = null
            session = null
            return@LaunchedEffect
        }
        isStarting = true
        isPolling = false
        statusMessage = null
        val startResult = runCatching {
            viewModel.startDeviceAuthorization(provider.id)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
        session = startResult.getOrNull()
        isStarting = false
        statusMessage = if (session == null) {
            startResult.exceptionOrNull()?.message?.takeIf { it.contains("PREMIUMIZE_CLIENT_ID") }
                ?.let { missingConfigurationMessage }
                ?: failedMessage
        } else {
            waitingMessage
        }
    }

    LaunchedEffect(session?.deviceCode, restartNonce, isConnected) {
        if (isConnected) return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        while (true) {
            delay(activeSession.intervalSeconds.coerceAtLeast(1) * 1_000L)
            isPolling = true
            val result = runCatching {
                viewModel.redeemDeviceAuthorization(provider.id, activeSession.deviceCode)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (error.isCancelledHttpRequest()) {
                    DebridDeviceAuthorizationTokenResult.Pending
                } else {
                    DebridDeviceAuthorizationTokenResult.Failed(null)
                }
            }
            isPolling = false
            when (result) {
                is DebridDeviceAuthorizationTokenResult.Authorized -> {
                    onConnected(result.accessToken)
                    onDismiss()
                    return@LaunchedEffect
                }
                DebridDeviceAuthorizationTokenResult.Pending -> {
                    statusMessage = waitingMessage
                }
                DebridDeviceAuthorizationTokenResult.Expired -> {
                    statusMessage = expiredMessage
                    return@LaunchedEffect
                }
                is DebridDeviceAuthorizationTokenResult.Failed -> {
                    statusMessage = result.message.toDeviceAuthStatusMessage(failedMessage)
                    return@LaunchedEffect
                }
                DebridDeviceAuthorizationTokenResult.Unsupported -> {
                    statusMessage = failedMessage
                    return@LaunchedEffect
                }
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (isConnected) R.string.debrid_disconnect_provider else R.string.debrid_connect_provider,
            provider.displayName
        ),
        width = 620.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        if (isConnected) {
            Text(
                text = stringResource(R.string.debrid_device_auth_connected, provider.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (isStarting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    text = startingMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        } else {
            session?.let { activeSession ->
                val qrBitmap = remember(activeSession.friendlyVerificationUrl) {
                    runCatching { QrCodeGenerator.generate(activeSession.friendlyVerificationUrl, 420, margin = 1) }.getOrNull()
                }
                Text(
                    text = stringResource(R.string.debrid_device_auth_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(196.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                DebridDeviceAuthCodes(
                    userCode = activeSession.userCode,
                    verificationUrl = activeSession.friendlyVerificationUrl,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            statusMessage?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPolling) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message == failedMessage || message == expiredMessage || message == missingConfigurationMessage) {
                            NuvioColors.Error
                        } else {
                            NuvioColors.TextSecondary
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            if (isConnected) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.debrid_disconnect),
                    onClick = {
                        onDisconnect()
                        onDismiss()
                    },
                    primary = true
                )
            }
            if (!isConnected && !isStarting && session == null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { restartNonce += 1 }
                )
            }
        }
    }
}

@Composable
private fun DebridDeviceAuthCodes(
    userCode: String,
    verificationUrl: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = userCode,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = verificationUrl,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}

private fun Throwable.isCancelledHttpRequest(): Boolean {
    val text = listOfNotNull(message, toString())
        .joinToString(" ")
        .lowercase()
    return "code=-999" in text ||
        ("nsurlerrordomain" in text && ("cancelled" in text || "canceled" in text))
}

private fun String?.toDeviceAuthStatusMessage(fallback: String): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
    val lower = value.lowercase()
    return if (
        value.length > 180 ||
        "exception in http request" in lower ||
        "nsurlerrordomain" in lower ||
        "userinfo=" in lower
    ) {
        fallback
    } else {
        value
    }
}

@Composable
private fun DebridApiKeyDialog(
    title: String,
    subtitle: String,
    placeholder: String,
    currentValue: String,
    viewModel: DebridSettingsViewModel,
    onSave: (String, () -> Unit) -> Unit,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val submit = {
        if (!validating) {
            focusManager.clearFocus()
            keyboardController?.hide()
            onSave(value, onSaved)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when {
                                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    native.action == KeyEvent.ACTION_DOWN -> true
                                (native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                    native.action == KeyEvent.ACTION_DOWN -> {
                                    submit()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioColors.Primary else Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_clear),
                onClick = onClear
            )
            SettingsDialogActionButton(
                text = if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
                onClick = { submit() },
                primary = true,
                enabled = !validating
            )
        }
    }
}

private fun maskDebridApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "****" else "******${trimmed.takeLast(4)}"
}

private fun providerCredentialStatus(
    provider: DebridProvider,
    credential: String,
    notSetLabel: String,
    connectedLabel: String
): String =
    when (provider.authMethod) {
        DebridProviderAuthMethod.DeviceCode -> if (credential.isBlank()) notSetLabel else connectedLabel
        DebridProviderAuthMethod.ApiKey -> maskDebridApiKey(credential, notSetLabel)
    }

private enum class DebridStreamPicker {
    MAX_RESULTS,
    MAX_PER_RESOLUTION,
    MAX_PER_QUALITY,
    SORT_MODE,
    SIZE_RANGE,
    PREFERRED_RESOLUTIONS,
    REQUIRED_RESOLUTIONS,
    EXCLUDED_RESOLUTIONS,
    PREFERRED_QUALITIES,
    REQUIRED_QUALITIES,
    EXCLUDED_QUALITIES,
    PREFERRED_VISUAL_TAGS,
    REQUIRED_VISUAL_TAGS,
    EXCLUDED_VISUAL_TAGS,
    PREFERRED_AUDIO_TAGS,
    REQUIRED_AUDIO_TAGS,
    EXCLUDED_AUDIO_TAGS,
    PREFERRED_AUDIO_CHANNELS,
    REQUIRED_AUDIO_CHANNELS,
    EXCLUDED_AUDIO_CHANNELS,
    PREFERRED_ENCODES,
    REQUIRED_ENCODES,
    EXCLUDED_ENCODES,
    PREFERRED_LANGUAGES,
    REQUIRED_LANGUAGES,
    EXCLUDED_LANGUAGES,
    REQUIRED_RELEASE_GROUPS,
    EXCLUDED_RELEASE_GROUPS
}
