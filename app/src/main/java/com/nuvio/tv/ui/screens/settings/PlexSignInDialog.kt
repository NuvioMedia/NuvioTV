@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.plex.PlexAccountClient
import com.nuvio.tv.data.mediaserver.plex.PlexHomeUser
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.plex.PlexServer
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

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
        PlexSignInStep.PIN -> stringResource(R.string.plex_pin_title, state.pinFor?.name.orEmpty())
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
            PlexSignInStep.PIN -> PlexPinStep(state, onSubmit = flow::submitPin, onBack = flow::back)
            PlexSignInStep.SIGNING_IN -> PlexStatus(stringResource(R.string.plex_signing_in), loading = true)
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
        state.codePhase == PlexCodePhase.EXPIRED -> PlexStatus(stringResource(R.string.plex_code_expired))
        state.codePhase == PlexCodePhase.STARTING || pin == null -> PlexStatus(stringResource(R.string.plex_code_starting), loading = true)
        else -> {
            val link = "https://${PlexAccountClient.LINK_PAGE}"
            val qr = remember(pin.code) { runCatching { QrCodeGenerator.generate("$link?pin=${pin.code}", 420, margin = 1) }.getOrNull() }
            Text(
                text = stringResource(R.string.plex_code_instructions, PlexAccountClient.LINK_PAGE),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(196.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = pin.code,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = PlexAccountClient.LINK_PAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }
            PlexStatus(stringResource(R.string.plex_code_waiting), loading = true)
        }
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        if (state.codePhase == PlexCodePhase.EXPIRED) {
            SettingsDialogActionButton(text = stringResource(R.string.plex_new_code), onClick = onNewCode, primary = true)
        }
    }
}

@Composable
private fun ColumnScope.PlexServersStep(state: PlexSignInState, onPick: (PlexServer) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    val servers = state.servers
    val focusIndex = if (state.serversLoading) -1 else servers.indexOfFirst { it.reach != PlexReach.OFFLINE }
    LaunchedEffect(focusIndex) { if (focusIndex >= 0) focus.requestFocusAfterFrames() }
    when {
        state.serversLoading && servers.isEmpty() -> PlexStatus(stringResource(R.string.plex_servers_finding), loading = true)
        servers.isEmpty() -> PlexStatus(stringResource(R.string.plex_servers_none))
        else -> Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            servers.forEachIndexed { index, server ->
                SettingsActionRow(
                    title = server.name,
                    subtitle = server.description(state.serversLoading),
                    value = if (state.serversLoading) null else server.reach.label(),
                    onClick = { onPick(server) },
                    enabled = !state.busy && !state.serversLoading && server.reach != PlexReach.OFFLINE,
                    leadingRawIconRes = R.raw.plex_logo,
                    modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier
                )
            }
            if (state.serversLoading) PlexStatus(stringResource(R.string.plex_servers_checking), loading = true)
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        state.users.forEachIndexed { index, user ->
            SettingsActionRow(
                title = user.name,
                subtitle = user.tags().takeIf { it.isNotEmpty() },
                onClick = { onPick(user) },
                enabled = !state.busy,
                leadingIcon = Icons.Default.Person,
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier
            )
        }
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
    stringResource(R.string.plex_user_pin).takeIf { hasPin }
).joinToString(" · ")

@Composable
private fun PlexPinStep(state: PlexSignInState, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var pin by remember(state.pinFor?.uuid) { mutableStateOf("") }
    val submit = { if (!state.busy && pin.isNotBlank()) onSubmit(pin) }
    LaunchedEffect(state.pinWrong) { if (state.pinWrong) pin = "" }
    InputField(
        value = pin,
        onValueChange = { value -> pin = value.filter(Char::isDigit).take(PIN_LENGTH) },
        placeholder = stringResource(R.string.plex_pin_hint),
        keyboardType = KeyboardType.NumberPassword,
        isPassword = true,
        imeAction = ImeAction.Done,
        onImeAction = submit,
        modifier = Modifier.fillMaxWidth()
    )
    if (state.pinWrong) PlexStatus(stringResource(R.string.plex_pin_wrong), error = true)
    PlexError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack, enabled = !state.busy)
        SettingsDialogActionButton(
            text = stringResource(if (state.busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = submit,
            primary = true,
            enabled = !state.busy && pin.isNotBlank()
        )
    }
}

@Composable
private fun PlexError(state: PlexSignInState) {
    val failure = state.error ?: return
    PlexStatus(stringResource(failure.messageRes()), error = true)
}

@Composable
private fun PlexStatus(text: String, loading: Boolean = false, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.lg))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private const val PIN_LENGTH = 4
