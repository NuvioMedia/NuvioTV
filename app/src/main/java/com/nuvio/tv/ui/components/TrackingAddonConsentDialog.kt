package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun TrackingAddonConsentDialog(
    addonName: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    var accepted by rememberSaveable(addonName) { mutableStateOf(false) }
    val checkboxFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        checkboxFocusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AmbientDialogEffect(onDismiss)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.addon_tracking_consent_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.addon_tracking_consent_body, addonName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                val checkboxState = stringResource(
                    if (accepted) R.string.addon_tracking_consent_checked else R.string.addon_tracking_consent_unchecked
                )
                Card(
                    onClick = { accepted = !accepted },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(checkboxFocusRequester)
                        .semantics {
                            role = Role.Checkbox
                            stateDescription = checkboxState
                        },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (accepted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (accepted) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                        )
                        Text(
                            text = stringResource(R.string.addon_tracking_consent_checkbox),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_tracking_consent_cancel),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    var installFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = { if (accepted) onInstall() },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { installFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = if (accepted) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundElevated
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_tracking_consent_install),
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                !accepted -> NuvioTheme.colors.TextTertiary
                                installFocused -> NuvioTheme.colors.OnSecondary
                                else -> NuvioTheme.colors.TextPrimary
                            },
                            modifier = Modifier
                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
