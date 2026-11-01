package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay

internal enum class UsenetSettingsSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val icon: ImageVector
) {
    PROVIDERS(R.string.usenet_section_providers, R.string.usenet_section_none, Icons.Default.Dns),
    INDEXERS(R.string.usenet_section_indexers, R.string.usenet_section_none, Icons.Default.Search),
    SEARCH(R.string.usenet_section_search, R.string.usenet_section_search_desc, Icons.Default.FilterList),
    PLAYBACK(R.string.usenet_section_playback, R.string.usenet_section_playback_desc, Icons.Default.Tune),
    DIAGNOSTICS(R.string.usenet_section_diagnostics, R.string.usenet_section_diagnostics_desc, Icons.Default.Insights)
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun UsenetSettingsContent(
    initialFocusRequester: FocusRequester,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
    sourcesViewModel: UsenetSourcesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sources by sourcesViewModel.uiState.collectAsStateWithLifecycle()
    val indexerStatuses by sourcesViewModel.indexerStatuses.collectAsStateWithLifecycle()
    val phoneSetup by sourcesViewModel.phoneSetup.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Opens what still has to be set up; everything else starts closed.
    var expandedNames by rememberSaveable(sources.profileId, sources.loaded) {
        mutableStateOf(buildList {
            if (sources.loaded && sources.configuration.providers.isEmpty()) add(UsenetSettingsSection.PROVIDERS.name)
            if (sources.loaded && sources.configuration.indexers.isEmpty()) add(UsenetSettingsSection.INDEXERS.name)
        })
    }
    val expanded = UsenetSettingsSection.entries.filterTo(mutableSetOf()) { it.name in expandedNames }
    // The entry focus is requested once; before the sources load it lands on the playback header.
    val startedUnloaded = remember { !sources.loaded }
    var playbackHasFocus by remember { mutableStateOf(false) }
    val toggle = { section: UsenetSettingsSection ->
        expandedNames = if (section.name in expandedNames) expandedNames - section.name else expandedNames + section.name
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header") {
                SettingsDetailHeader(
                    title = stringResource(R.string.usenet_title),
                    subtitle = stringResource(R.string.settings_usenet_subtitle)
                )
            }
            item(key = "usenet_sources") {
                key(sources.profileId) {
                    if (sources.loaded) UsenetSourcesCard(sources.configuration,
                        update = { sourcesViewModel.update(it, sources.profileId) },
                        testIndexer = sourcesViewModel::test,
                        testProvider = sourcesViewModel::testProvider,
                        setUpFromPhone = sourcesViewModel::startPhoneSetup,
                        phoneSetupOpen = phoneSetup != null,
                        indexerStatuses = indexerStatuses,
                        initialFocusRequester = initialFocusRequester,
                        expanded = expanded,
                        onToggleSection = toggle,
                        takeEntryFocus = { startedUnloaded && playbackHasFocus })
                    if (sources.error) Text(stringResource(R.string.usenet_sources_save_error))
                }
            }
            item(key = "usenet_settings") {
                UsenetSettingsCard(
                    configuration = uiState.usenet,
                    update = { viewModel.onEvent(AdvancedSettingsEvent.SetUsenet(it)) },
                    expanded = UsenetSettingsSection.PLAYBACK in expanded,
                    onToggle = { toggle(UsenetSettingsSection.PLAYBACK) },
                    initialFocusRequester = if (sources.loaded) null else initialFocusRequester,
                    modifier = Modifier.onFocusChanged { playbackHasFocus = it.hasFocus }
                )
            }
            item(key = "usenet_diagnostics") {
                // Only the header: the reports stay separate lazy items so the remote can reach each one.
                val open = UsenetSettingsSection.DIAGNOSTICS in expanded
                val section = UsenetSettingsSection.DIAGNOSTICS
                SettingsActionRow(
                    title = stringResource(section.title),
                    subtitle = stringResource(section.description),
                    value = stringResource(if (open) R.string.layout_open else R.string.layout_closed),
                    onClick = { toggle(section) },
                    leadingIcon = section.icon,
                    trailingIcon = if (open) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight
                )
            }
            if (UsenetSettingsSection.DIAGNOSTICS in expanded) usenetDiagnosticsCardItems()
        }
        SettingsVerticalScrollIndicators(state = listState)
        phoneSetup?.let { setup ->
            QrCodeOverlay(
                qrBitmap = setup.qrCode,
                serverUrl = setup.url,
                instruction = stringResource(R.string.usenet_phone_qr_instruction),
                onClose = sourcesViewModel::stopPhoneSetup,
                status = setup.lastSaved?.let { stringResource(R.string.usenet_phone_saved_on_tv, it) }
            )
        }
    }
}

@Composable
internal fun UsenetSettingsSection(
    section: UsenetSettingsSection,
    expanded: Set<UsenetSettingsSection>,
    onToggle: (UsenetSettingsSection) -> Unit,
    description: String = stringResource(section.description),
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) = SettingsCollapsibleSection(
    title = stringResource(section.title),
    description = description,
    icon = section.icon,
    expanded = section in expanded,
    onToggle = { onToggle(section) },
    focusRequester = focusRequester,
    content = content
)
