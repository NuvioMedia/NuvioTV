@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.silo.SiloProfile
import com.nuvio.tv.data.mediaserver.silo.SiloSignInProblem
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

/** Adding Silo: the server's address, then a code approved on another device (or the password), then a Silo profile. */
@Composable
internal fun SiloSignInDialog(
    request: ServerSignInRequest,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val flow = viewModel.siloSignIn
    val state by flow.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { flow.start(onConnected) }
    DisposableEffect(Unit) { onDispose { flow.close() } }
    val busy = state.busy || state.step == SiloSignInStep.SIGNING_IN
    val serverName = state.server?.name
    val title = when (state.step) {
        SiloSignInStep.PROFILES -> stringResource(R.string.silo_profiles_title)
        SiloSignInStep.PIN -> stringResource(R.string.servers_pin_title, state.pinFor?.name.orEmpty())
        else -> stringResource(R.string.servers_sign_in_title, "Silo")
    }
    val subtitle = when (state.step) {
        SiloSignInStep.ADDRESS, SiloSignInStep.FINDING -> stringResource(R.string.silo_address_subtitle)
        SiloSignInStep.CODE -> serverName?.let { stringResource(R.string.silo_code_subtitle, it) }
        SiloSignInStep.PASSWORD -> serverName?.let { stringResource(R.string.silo_password_subtitle, it) }
        else -> serverName
    }

    NuvioDialog(
        onDismiss = { if (!busy) onDismiss() },
        title = title,
        subtitle = subtitle,
        width = 640.dp
    ) {
        when (state.step) {
            SiloSignInStep.ADDRESS, SiloSignInStep.FINDING -> SiloAddressStep(request.address, state, onFind = flow::find, onCancel = onDismiss)
            SiloSignInStep.CODE -> SiloCodeStep(state, flow, onCancel = onDismiss)
            SiloSignInStep.PASSWORD -> SiloPasswordStep(state, flow)
            SiloSignInStep.PROFILES -> SiloProfilesStep(state, onPick = flow::pickProfile, onCancel = onDismiss)
            SiloSignInStep.PIN -> {
                val onlyProfile = state.profiles.size <= 1
                ServerSignInPin(
                    key = state.pinFor?.id,
                    hint = stringResource(R.string.servers_pin_hint),
                    maxLength = PIN_MAX_LENGTH,
                    wrongText = if (state.pinWrong) stringResource(R.string.servers_pin_wrong) else null,
                    busy = state.busy,
                    backText = stringResource(if (onlyProfile) R.string.action_cancel else R.string.action_back),
                    onBack = if (onlyProfile) onDismiss else flow::backToProfiles,
                    onSubmit = flow::submitPin,
                    error = { SiloError(state) }
                )
            }
            SiloSignInStep.SIGNING_IN -> ServerSignInStatus(stringResource(R.string.servers_signing_in), loading = true)
            SiloSignInStep.FAILED -> {
                SiloError(state, fallback = true)
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                    SettingsDialogActionButton(text = stringResource(R.string.action_retry), onClick = flow::backToAddress, primary = true)
                }
            }
        }
    }
}

@Composable
private fun SiloAddressStep(initial: String, state: SiloSignInState, onFind: (String) -> Unit, onCancel: () -> Unit) {
    var address by remember { mutableStateOf(initial) }
    val finding = state.step == SiloSignInStep.FINDING
    val find = { if (!finding && address.isNotBlank()) onFind(address) }
    SiloField(label = stringResource(R.string.servers_address)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            InputField(
                value = address,
                onValueChange = { address = it },
                placeholder = stringResource(R.string.silo_address_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = find,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (finding) ServerSignInStatus(stringResource(R.string.silo_finding), loading = true) else SiloError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, enabled = !finding)
        SettingsDialogActionButton(
            text = stringResource(R.string.servers_connect),
            onClick = find,
            primary = true,
            enabled = !finding && address.isNotBlank()
        )
    }
}

@Composable
private fun ColumnScope.SiloCodeStep(state: SiloSignInState, flow: SiloSignInFlow, onCancel: () -> Unit) {
    val code = state.code
    val server = state.server
    when {
        state.codePhase == SiloCodePhase.UNAVAILABLE -> ServerSignInStatus(stringResource(R.string.silo_code_unavailable))
        state.codePhase == SiloCodePhase.DENIED -> ServerSignInStatus(stringResource(R.string.silo_code_denied), error = true)
        state.codePhase == SiloCodePhase.EXPIRED -> {
            ServerSignInStatus(stringResource(R.string.servers_code_expired))
            SiloError(state)
        }
        state.codePhase == SiloCodePhase.STARTING || code == null ->
            ServerSignInStatus(stringResource(R.string.servers_code_starting), loading = true)
        else -> ServerSignInCode(
            instructions = stringResource(R.string.silo_code_instructions),
            link = code.linkWithCode,
            code = code.shownCode,
            page = code.link.substringAfter("://"),
            waiting = stringResource(R.string.silo_code_waiting)
        )
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        if (server?.passwordSignIn == true) {
            SettingsDialogActionButton(text = stringResource(R.string.silo_use_password), onClick = flow::usePassword)
        }
        if (server?.codeSignIn == true && state.codePhase in setOf(SiloCodePhase.EXPIRED, SiloCodePhase.DENIED)) {
            SettingsDialogActionButton(text = stringResource(R.string.servers_new_code), onClick = flow::newCode, primary = true)
        }
    }
}

@Composable
private fun SiloPasswordStep(state: SiloSignInState, flow: SiloSignInFlow) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSignIn = !state.busy && username.isNotBlank() && password.isNotEmpty()
    val signIn = { if (canSignIn) flow.signInWithPassword(username, password) }
    SiloField(label = stringResource(R.string.servers_username)) {
        InputField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.servers_username),
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SiloField(label = stringResource(R.string.servers_password)) {
        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.servers_password),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = signIn,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SiloError(state)
    SettingsDialogActionRow {
        if (state.server?.codeSignIn == true) {
            SettingsDialogActionButton(text = stringResource(R.string.silo_use_code), onClick = flow::useCode, enabled = !state.busy)
        } else {
            SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = flow::backToAddress, enabled = !state.busy)
        }
        SettingsDialogActionButton(
            text = stringResource(if (state.busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = signIn,
            primary = true,
            enabled = canSignIn
        )
    }
}

@Composable
private fun ColumnScope.SiloProfilesStep(state: SiloSignInState, onPick: (SiloProfile) -> Unit, onCancel: () -> Unit) {
    ServerSignInPicker(state.profiles) { profile, modifier ->
        SettingsActionRow(
            title = profile.name,
            subtitle = profile.tags().takeIf { it.isNotEmpty() },
            onClick = { onPick(profile) },
            enabled = !state.busy,
            leadingIcon = Icons.Default.Person,
            modifier = modifier
        )
    }
    SiloError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, enabled = !state.busy)
    }
}

@Composable
private fun SiloProfile.tags(): String = listOfNotNull(
    stringResource(R.string.silo_profile_kids).takeIf { isChild },
    stringResource(R.string.servers_has_pin).takeIf { hasPin }
).joinToString(" · ")

@Composable
private fun SiloField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        field()
    }
}

/** The reason a step failed; with [fallback], a general line even when nothing more specific is known. */
@Composable
private fun SiloError(state: SiloSignInState, fallback: Boolean = false) {
    val text = when (state.problem) {
        SiloSignInProblem.NOT_SILO -> stringResource(R.string.silo_error_not_silo)
        SiloSignInProblem.TEMPORARY_PASSWORD -> stringResource(R.string.silo_error_temporary_password)
        SiloSignInProblem.PASSWORD_SIGN_IN_OFF -> stringResource(R.string.silo_error_password_off)
        SiloSignInProblem.TOO_MANY_TRIES -> stringResource(R.string.silo_error_too_many_tries)
        null -> when (state.error) {
            null -> if (fallback) stringResource(R.string.servers_error_failed) else return
            ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
            ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
            ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
            ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
            else -> stringResource(R.string.servers_error_failed)
        }
    }
    ServerSignInStatus(text, error = true)
}

private const val PIN_MAX_LENGTH = 8
