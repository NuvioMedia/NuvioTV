@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.supports
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MediaServerSettingsContent(
    connectionId: String,
    initialFocusRequester: FocusRequester?,
    onOpenServer: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val connection = uiState.connections.firstOrNull { it.id == connectionId }
    if (connection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val provider = viewModel.provider(connection)
    val providerName = provider?.displayName ?: connection.providerId
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = connection.name,
            subtitle = "${connection.statusText(providerName, uiState.failures[connection.id])} · ${connection.address}"
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "server_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.servers_enabled),
                            subtitle = stringResource(R.string.servers_enabled_description),
                            checked = connection.enabled,
                            onToggle = { viewModel.setEnabled(connection.id, !connection.enabled) },
                            modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                        )
                    }
                    if (provider?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true) {
                        item(key = "server_catalog_metadata") {
                            SettingsToggleRow(
                                title = stringResource(R.string.servers_catalog_metadata),
                                subtitle = stringResource(R.string.servers_catalog_metadata_description, providerName),
                                checked = connection.useCatalogMetadata,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setCatalogMetadata(connection.id, !connection.useCatalogMetadata) }
                            )
                        }
                    }
                    if (provider?.supports(ServerCapability.USER_STATE_READ) == true) {
                        item(key = "server_import_watch_state") {
                            SettingsToggleRow(
                                title = stringResource(R.string.servers_import_watch_state),
                                subtitle = stringResource(R.string.servers_import_watch_state_description, providerName),
                                checked = connection.importWatchState,
                                enabled = connection.enabled,
                                onToggle = { viewModel.setImportWatchState(connection.id, !connection.importWatchState) }
                            )
                        }
                    }
                    item(key = "server_libraries_label") {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                            ServerSectionLabel(stringResource(R.string.servers_libraries))
                            Text(
                                text = stringResource(
                                    if (connection.libraries.isEmpty()) R.string.servers_no_libraries else R.string.servers_libraries_description
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
                            )
                        }
                    }
                    items(connection.libraries, key = { "library_${it.id}" }) { library ->
                        SettingsToggleRow(
                            title = library.name,
                            subtitle = null,
                            checked = library.selected,
                            enabled = connection.enabled,
                            onToggle = { viewModel.setLibrarySelected(connection.id, library.id, !library.selected) }
                        )
                    }
                    item(key = "server_refresh") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_refresh_libraries),
                            subtitle = null,
                            value = if (refreshing == connection.id) "…" else null,
                            enabled = connection.enabled && refreshing == null,
                            leadingIcon = Icons.Default.Sync,
                            onClick = {
                                viewModel.refreshLibraries(connection.id) { failure ->
                                    Toast.makeText(context, context.getString(failure.messageRes()), Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    if (provider != null) {
                        item(key = "server_sign_in") {
                            SettingsActionRow(
                                title = stringResource(R.string.servers_sign_in_again),
                                subtitle = null,
                                leadingIcon = Icons.Default.Lock,
                                onClick = {
                                    signIn = ServerSignInRequest(provider, address = connection.address, username = connection.userName)
                                }
                            )
                        }
                    }
                    item(key = "server_remove") {
                        SettingsActionRow(
                            title = stringResource(R.string.servers_remove),
                            subtitle = null,
                            leadingIcon = Icons.Default.Delete,
                            onClick = { confirmRemove = true }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    signIn?.let { request ->
        ServerSignInDialog(
            request = request,
            viewModel = viewModel,
            onConnected = { connected ->
                signIn = null
                if (connected.id != connection.id) onOpenServer(connected.id)
            },
            onDismiss = { signIn = null }
        )
    }

    if (confirmRemove) {
        NuvioDialog(
            onDismiss = { confirmRemove = false },
            title = stringResource(R.string.servers_remove_confirm_title),
            subtitle = stringResource(R.string.servers_remove_confirm_message, connection.name)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmRemove = false }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_remove),
                    onClick = {
                        confirmRemove = false
                        viewModel.remove(connection.id)
                    },
                    primary = true
                )
            }
        }
    }
}
