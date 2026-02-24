@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import com.nuvio.tv.R
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun DebugSettingsContent(
    viewModel: DebugSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showErrorDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.debug_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.Secondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.debug_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Popup / Dialog Testing ──
            item(key = "debug_popup_header") {
                Text(
                    text = stringResource(R.string.debug_section_popup),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item(key = "debug_playback_error") {
                DebugActionCard(
                    title = stringResource(R.string.debug_playback_error_title),
                    subtitle = stringResource(R.string.debug_playback_error_subtitle),
                    onClick = { showErrorDialog = true }
                )
            }

            // ── Feature Toggles ──
            item(key = "debug_feature_toggles_header") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.debug_section_features),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item(key = "debug_toggle_account_tab") {
                DebugToggleCard(
                    title = stringResource(R.string.debug_account_tab_title),
                    subtitle = stringResource(R.string.debug_account_tab_subtitle),
                    checked = uiState.accountTabEnabled,
                    onToggle = { viewModel.onEvent(DebugSettingsEvent.ToggleAccountTab(it)) }
                )
            }

            item(key = "debug_toggle_sync_code") {
                DebugToggleCard(
                    title = stringResource(R.string.debug_sync_code_title),
                    subtitle = stringResource(R.string.debug_sync_code_subtitle),
                    checked = uiState.syncCodeFeaturesEnabled,
                    onToggle = { viewModel.onEvent(DebugSettingsEvent.ToggleSyncCodeFeatures(it)) }
                )
            }

            // ── Manual Sign In ──
            item(key = "debug_account_header") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.debug_section_account),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item(key = "debug_sign_in_card") {
                DebugSignInCard(
                    isLoading = uiState.signInLoading,
                    result = uiState.signInResult,
                    onSignIn = { email, password ->
                        viewModel.onEvent(DebugSettingsEvent.SignIn(email, password))
                    }
                )
            }
        }
    }

    if (showErrorDialog) {
        NuvioDialog(
            onDismiss = { showErrorDialog = false },
            title = stringResource(R.string.debug_error_dialog_title),
            subtitle = stringResource(R.string.debug_error_dialog_subtitle)
        ) {
            DebugDialogButton(
                text = stringResource(R.string.debug_dismiss),
                onClick = { showErrorDialog = false }
            )
        }
    }
}

@Composable
private fun DebugToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        onClick = { onToggle(!checked) },
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = checked,
                onCheckedChange = { onToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NuvioColors.Secondary,
                    checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                    uncheckedThumbColor = NuvioColors.TextSecondary,
                    uncheckedTrackColor = NuvioColors.BackgroundCard
                )
            )
        }
    }
}

@Composable
private fun DebugActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }
    }
}

@Composable
private fun DebugDialogButton(
    text: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.0f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun DebugSignInCard(
    isLoading: Boolean,
    result: String?,
    onSignIn: (email: String, password: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.debug_manual_signin_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary
        )
        Text(
            text = stringResource(R.string.debug_manual_signin_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary
        )

        InputField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.debug_email_placeholder),
            keyboardType = KeyboardType.Email
        )

        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.debug_password_placeholder),
            isPassword = true
        )

        if (result != null) {
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("Failed")) NuvioColors.Error else NuvioColors.Secondary
            )
        }

        DebugDialogButton(
            text = if (isLoading) stringResource(R.string.debug_signing_in) else stringResource(R.string.debug_sign_in),
            onClick = {
                if (!isLoading && email.isNotBlank() && password.isNotBlank()) {
                    onSignIn(email.trim(), password)
                }
            }
        )
    }
}
