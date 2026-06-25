@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.SYNC_BACKEND_NUVIO_ID
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun DebugSyncBackendSwitchCard(
    uiState: AccountUiState,
    requireConfirmation: Boolean,
    modifier: Modifier = Modifier,
    onSwitchBackend: () -> Unit,
) {
    if (!uiState.debugBackendSwitchEnabled) return

    val isNuvioSelected = uiState.syncBackendId == SYNC_BACKEND_NUVIO_ID
    val targetBackendName = if (isNuvioSelected) "Hosted" else "Nuvio"
    var showConfirm by remember { mutableStateOf(false) }

    fun requestSwitch() {
        if (uiState.isDebugBackendSwitching) return
        if (requireConfirmation) {
            showConfirm = true
        } else {
            onSwitchBackend()
        }
    }

    Card(
        onClick = ::requestSwitch,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.sm),
            ),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.account_sync_backend_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                )
                Text(
                    text = uiState.syncBackendName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (uiState.isDebugBackendSwitching) {
                    Text(
                        text = stringResource(R.string.debug_backend_switching),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                } else if (!uiState.debugBackendSwitchError.isNullOrBlank()) {
                    Text(
                        text = uiState.debugBackendSwitchError,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Switch(
                checked = isNuvioSelected,
                onCheckedChange = { requestSwitch() },
                enabled = !uiState.isDebugBackendSwitching,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NuvioTheme.colors.Secondary,
                    checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                    uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                    uncheckedTrackColor = NuvioTheme.colors.BackgroundCard,
                ),
            )
        }
    }

    if (showConfirm) {
        NuvioDialog(
            onDismiss = { showConfirm = false },
            title = stringResource(R.string.debug_backend_switch_confirm_title),
            subtitle = stringResource(R.string.debug_backend_switch_confirm_message, targetBackendName),
            suppressFirstKeyUp = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { showConfirm = false },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        showConfirm = false
                        onSwitchBackend()
                    },
                    enabled = !uiState.isDebugBackendSwitching,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.FocusBackground,
                        contentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.action_switch))
                }
            }
        }
    }
}
