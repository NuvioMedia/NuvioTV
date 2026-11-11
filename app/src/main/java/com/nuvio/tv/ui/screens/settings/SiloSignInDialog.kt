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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.silo.SiloProfile
import com.nuvio.tv.data.mediaserver.silo.SiloSignInProblem
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
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
        SiloSignInStep.PIN -> stringResource(R.string.silo_pin_title, state.pinFor?.name.orEmpty())
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
            SiloSignInStep.PIN -> SiloPinStep(state, onSubmit = flow::submitPin, onBack = flow::backToProfiles, onCancel = onDismiss)
            SiloSignInStep.SIGNING_IN -> SiloStatus(stringResource(R.string.silo_signing_in), loading = true)
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
    if (finding) SiloStatus(stringResource(R.string.silo_finding), loading = true) else SiloError(state)
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
        state.codePhase == SiloCodePhase.UNAVAILABLE -> SiloStatus(stringResource(R.string.silo_code_unavailable))
        state.codePhase == SiloCodePhase.DENIED -> SiloStatus(stringResource(R.string.silo_code_denied), error = true)
        state.codePhase == SiloCodePhase.EXPIRED -> {
            SiloStatus(stringResource(R.string.silo_code_expired))
            SiloError(state)
        }
        state.codePhase == SiloCodePhase.STARTING || code == null -> SiloStatus(stringResource(R.string.silo_code_starting), loading = true)
        else -> {
            val qr = remember(code.linkWithCode) { runCatching { QrCodeGenerator.generate(code.linkWithCode, 420, margin = 1) }.getOrNull() }
            Text(
                text = stringResource(R.string.silo_code_instructions),
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
                    text = code.shownCode,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = code.link.substringAfter("://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }
            SiloStatus(stringResource(R.string.silo_code_waiting), loading = true)
        }
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        if (server?.passwordSignIn == true) {
            SettingsDialogActionButton(text = stringResource(R.string.silo_use_password), onClick = flow::usePassword)
        }
        if (server?.codeSignIn == true && state.codePhase in setOf(SiloCodePhase.EXPIRED, SiloCodePhase.DENIED)) {
            SettingsDialogActionButton(text = stringResource(R.string.silo_new_code), onClick = flow::newCode, primary = true)
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        state.profiles.forEachIndexed { index, profile ->
            SettingsActionRow(
                title = profile.name,
                subtitle = profile.tags().takeIf { it.isNotEmpty() },
                onClick = { onPick(profile) },
                enabled = !state.busy,
                leadingIcon = Icons.Default.Person,
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier
            )
        }
    }
    SiloError(state)
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, enabled = !state.busy)
    }
}

@Composable
private fun SiloProfile.tags(): String = listOfNotNull(
    stringResource(R.string.silo_profile_kids).takeIf { isChild },
    stringResource(R.string.silo_profile_pin).takeIf { hasPin }
).joinToString(" · ")

@Composable
private fun SiloPinStep(state: SiloSignInState, onSubmit: (String) -> Unit, onBack: () -> Unit, onCancel: () -> Unit) {
    var pin by remember(state.pinFor?.id) { mutableStateOf("") }
    val submit = { if (!state.busy && pin.isNotBlank()) onSubmit(pin) }
    LaunchedEffect(state.pinWrong) { if (state.pinWrong) pin = "" }
    InputField(
        value = pin,
        onValueChange = { value -> pin = value.filter(Char::isDigit).take(PIN_MAX_LENGTH) },
        placeholder = stringResource(R.string.silo_pin_hint),
        keyboardType = KeyboardType.NumberPassword,
        isPassword = true,
        imeAction = ImeAction.Done,
        onImeAction = submit,
        modifier = Modifier.fillMaxWidth()
    )
    if (state.pinWrong) SiloStatus(stringResource(R.string.silo_pin_wrong), error = true)
    SiloError(state)
    SettingsDialogActionRow {
        if (state.profiles.size > 1) {
            SettingsDialogActionButton(text = stringResource(R.string.action_back), onClick = onBack, enabled = !state.busy)
        } else {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, enabled = !state.busy)
        }
        SettingsDialogActionButton(
            text = stringResource(if (state.busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = submit,
            primary = true,
            enabled = !state.busy && pin.isNotBlank()
        )
    }
}

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
    SiloStatus(text, error = true)
}

@Composable
private fun SiloStatus(text: String, loading: Boolean = false, error: Boolean = false) {
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

private const val PIN_MAX_LENGTH = 8
