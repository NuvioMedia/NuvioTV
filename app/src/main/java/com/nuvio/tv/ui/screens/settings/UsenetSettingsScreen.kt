package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun UsenetSettingsContent(
    initialFocusRequester: FocusRequester,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
    sourcesViewModel: UsenetSourcesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sources by sourcesViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

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
                        initialFocusRequester = initialFocusRequester)
                    if (sources.error) Text(stringResource(R.string.usenet_sources_save_error))
                }
            }
            item(key = "usenet_settings") {
                UsenetSettingsCard(
                    configuration = uiState.usenet,
                    update = { viewModel.onEvent(AdvancedSettingsEvent.SetUsenet(it)) },
                    initialFocusRequester = if (sources.loaded) null else initialFocusRequester
                )
            }
            usenetDiagnosticsCardItems()
        }
        SettingsVerticalScrollIndicators(state = listState)
    }
}
