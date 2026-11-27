@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.plex.PlexAccountClient
import com.nuvio.tv.data.mediaserver.plex.PlexHomeUser
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.plex.PlexServer
import com.nuvio.tv.ui.components.NuvioDialog

/** Adding Plex: a code at plex.tv/link, then a server, then a Plex Home user (each step only when there is a choice). */
@Composable
internal fun PlexSignInDialog(
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val flow = viewModel.plexSignIn
    val state by flow.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { flow.start(onConnected) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> flow.pause()
                Lifecycle.Event.ON_START -> flow.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            flow.close()
        }
    }
    val busy = state.busy || state.step == PlexSignInStep.SIGNING_IN
    val title = when (state.step) {
        PlexSignInStep.SERVERS -> stringResource(R.string.plex_servers_title)
        PlexSignInStep.USERS -> stringResource(R.string.plex_users_title)
        PlexSignInStep.PIN -> stringResource(R.string.servers_pin_title, state.pinFor?.name.orEmpty())
        else -> stringResource(R.string.servers_sign_in_title, "Plex")
    }
    val subtitle = when (state.step) {
        PlexSignInStep.CODE, PlexSignInStep.FAILED -> stringResource(R.string.plex_sign_in_subtitle)
        PlexSignInStep.SERVERS -> state.accountName?.let { stringResource(R.string.plex_servers_subtitle, it) }
        PlexSignInStep.USERS -> stringResource(R.string.plex_users_subtitle, state.server?.name.orEmpty())
        else -> state.server?.name
    }

    NuvioDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = title,
        subtitle = subtitle,
        width = 640.dp
    ) {
        when (state.step) {
            PlexSignInStep.CODE -> PlexCodeStep(state, onNewCode = flow::newCode, onCancel = onDismiss)
            PlexSignInStep.SERVERS -> PlexServersStep(state, onPick = flow::pickServer, onCancel = onDismiss)
            PlexSignInStep.USERS -> PlexUsersStep(state, onPick = flow::pickUser, onBack = flow::back)
            PlexSignInStep.PIN -> ServerSignInPin(
                key = state.pinFor?.uuid,
                hint = stringResource(R.string.servers_pin_hint),
                maxLength = PIN_LENGTH,
                wrongText = if (state.pinWrong) stringResource(R.string.servers_pin_wrong) else null,
                busy = state.busy,
                backText = stringResource(R.string.action_back),
                onBack = flow::back,
                onSubmit = flow::submitPin,
                error = { PlexError(state) }
            )
            PlexSignInStep.SIGNING_IN -> ServerSignInStatus(stringResource(R.string.servers_signing_in), loading = true)
            PlexSignInStep.FAILED -> {
                PlexError(state)
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                    SettingsDialogActionButton(text = stringResource(R.string.action_retry), onClick = flow::newCode, primary = true)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PlexCodeStep(state: PlexSignInState, onNewCode: () -> Unit, onCancel: () -> Unit) {
    val pin = state.pin
    when {
        state.codePhase == PlexCodePhase.EXPIRED -> ServerSignInStatus(stringResource(R.string.servers_code_expired))
        state.codePhase == PlexCodePhase.STARTING || pin == null ->
            ServerSignInStatus(stringResource(R.string.servers_code_starting), loading = true)
        else -> ServerSignInCode(
            instructions = stringResource(R.string.plex_code_instructions, PlexAccountClient.LINK_PAGE),
            link = "https://${PlexAccountClient.LINK_PAGE}?pin=${pin.code}",
            code = pin.code,
            page = PlexAccountClient.LINK_PAGE,
            waiting = stringResource(R.string.plex_code_waiting)
        )
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        if (state.codePhase == PlexCodePhase.EXPIRED) {
            SettingsDialogActionButton(text = stringResource(R.string.servers_new_code), onClick = onNewCode, primary = true)
        }
    }
}

@Composable
private fun ColumnScope.PlexServersStep(state: PlexSignInState, onPick: (PlexServer) -> Unit, onCancel: () -> Unit) {
    val servers = state.servers
    when {
        state.serversLoading && servers.isEmpty() -> ServerSignInStatus(stringResource(R.string.plex_servers_finding), loading = true)
        servers.isEmpty() -> ServerSignInStatus(stringResource(R.string.plex_servers_none))
        else -> ServerSignInPicker(
            items = servers,
            focusIndex = if (state.serversLoading) -1 else servers.indexOfFirst { it.reach != PlexReach.OFFLINE },
            footer = { if (state.serversLoading) ServerSignInStatus(stringResource(R.string.plex_servers_checking), loading = true) }
        ) { server, modifier ->
            SettingsActionRow(
                title = server.name,
                subtitle = server.description(state.serversLoading),
                value = if (state.serversLoading) null else server.reach.label(),
                onClick = { onPick(server) },
                enabled = !state.busy && !state.serversLoading && server.reach != PlexReach.OFFLINE,
                leadingRawIconRes = R.raw.plex_logo,
                modifier = modifier
            )
        }
    }
    PlexError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
    }
}

@Composable
private fun PlexServer.description(checking: Boolean): String {
    val owner = stringResource(if (owned) R.string.plex_server_owned else R.string.plex_server_shared)
    if (checking) return owner
    return when (reach) {
        PlexReach.RELAY -> "$owner · ${stringResource(R.string.plex_reach_relay_detail)}"
        PlexReach.OFFLINE -> "$owner · ${stringResource(R.string.plex_reach_offline_detail)}"
        else -> owner
    }
}

@Composable
private fun PlexReach.label(): String = stringResource(
    when (this) {
        PlexReach.LOCAL -> R.string.plex_reach_local
        PlexReach.REMOTE -> R.string.plex_reach_remote
        PlexReach.RELAY -> R.string.plex_reach_relay
        PlexReach.OFFLINE -> R.string.plex_reach_offline
    }
)

@Composable
private fun ColumnScope.PlexUsersStep(state: PlexSignInState, onPick: (PlexHomeUser) -> Unit, onBack: () -> Unit) {
    ServerSignInPicker(state.users) { user, modifier ->
        SettingsActionRow(
            title = user.name,
            subtitle = user.tags().takeIf { it.isNotEmpty() },
            onClick = { onPick(user) },
            enabled = !state.busy,
            leadingIcon = Icons.Default.Person,
            modifier = modifier
        )
    }
    PlexError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack, enabled = !state.busy)
    }
}

@Composable
private fun PlexHomeUser.tags(): String = listOfNotNull(
    stringResource(R.string.plex_user_admin).takeIf { admin },
    stringResource(R.string.plex_user_managed).takeIf { restricted && !admin },
    stringResource(R.string.servers_has_pin).takeIf { hasPin }
).joinToString(" · ")

@Composable
private fun PlexError(state: PlexSignInState) {
    val failure = state.error ?: return
    ServerSignInStatus(stringResource(failure.messageRes()), error = true)
}

private const val PIN_LENGTH = 4
