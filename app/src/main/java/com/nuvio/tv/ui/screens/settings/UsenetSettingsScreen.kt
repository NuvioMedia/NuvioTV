package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R

@Composable
internal fun UsenetSettingsContent(
    initialFocusRequester: FocusRequester,
    viewModel: AdvancedSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            item(key = "usenet_settings") {
                UsenetSettingsCard(
                    configuration = uiState.usenet,
                    update = { viewModel.onEvent(AdvancedSettingsEvent.SetUsenet(it)) },
                    initialFocusRequester = initialFocusRequester
                )
            }
            usenetDiagnosticsCardItems()
        }
        SettingsVerticalScrollIndicators(state = listState)
    }
}
