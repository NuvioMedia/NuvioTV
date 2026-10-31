@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.RawRes
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MediaServersSettingsContent(
    initialFocusRequester: FocusRequester?,
    onOpenServer: (String) -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    val connections = uiState.connections
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_media_servers),
            subtitle = stringResource(R.string.settings_media_servers_subtitle)
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
                    item(key = "servers_label") { ServerSectionLabel(stringResource(R.string.servers_section_connected)) }
                    if (connections.isEmpty()) {
                        item(key = "servers_empty") {
                            Text(
                                text = stringResource(R.string.servers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
                            )
                        }
                    }
                    items(connections, key = { "server_${it.id}" }) { connection ->
                        SettingsActionRow(
                            title = connection.name,
                            subtitle = connection.statusText(
                                providerName = viewModel.provider(connection)?.displayName ?: connection.providerId,
                                failure = uiState.failures[connection.id]
                            ),
                            onClick = { onOpenServer(connection.id) },
                            leadingIcon = Icons.Default.Dns,
                            modifier = if (connection == connections.first()) focusModifier else Modifier
                        )
                    }
                    item(key = "servers_add_label") { ServerSectionLabel(stringResource(R.string.servers_section_add)) }
                    items(viewModel.providers, key = { "provider_${it.id}" }) { provider ->
                        SettingsActionRow(
                            title = provider.displayName,
                            subtitle = stringResource(R.string.servers_add_description, provider.displayName),
                            onClick = { signIn = ServerSignInRequest(provider) },
                            leadingRawIconRes = serverLogoRes(provider.id),
                            modifier = if (connections.isEmpty() && provider == viewModel.providers.first()) focusModifier else Modifier
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
            onConnected = { connection ->
                signIn = null
                onOpenServer(connection.id)
            },
            onDismiss = { signIn = null }
        )
    }
}

@Composable
internal fun ServerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
    )
}

@Composable
internal fun ServerConnection.statusText(providerName: String, failure: ServerFailure?): String {
    val identity = stringResource(R.string.servers_signed_in_as, providerName, userName)
    return listOfNotNull(identity, statusLabel(failure)).joinToString(" · ")
}

@Composable
internal fun ServerConnection.statusLabel(failure: ServerFailure?): String? = when {
    !enabled -> stringResource(R.string.servers_status_disabled)
    failure == ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_status_auth)
    failure == ServerFailure.UNREACHABLE -> stringResource(R.string.servers_status_unreachable)
    else -> null
}

@RawRes
internal fun serverLogoRes(providerId: String): Int? = when (providerId) {
    "jellyfin" -> R.raw.jellyfin_logo
    "emby" -> R.raw.emby_logo
    else -> null
}
