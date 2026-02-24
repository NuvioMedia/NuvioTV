@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors

private const val SHOW_SYNC_CODE_FEATURES = false

@Composable
fun AccountScreen(
    onNavigateToAuthSignIn: () -> Unit = {},
    onNavigateToSyncGenerate: () -> Unit = {},
    onNavigateToSyncClaim: () -> Unit = {},
    onBackPress: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.authState) {
        if (uiState.authState is AuthState.FullAccount) {
            viewModel.loadLinkedDevices()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp),
        contentPadding = PaddingValues(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.account_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.account_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }
            }

            is AuthState.SignedOut -> {
                item {
                    Text(
                        text = stringResource(R.string.account_sign_in_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
                item {
                    AccountActionCard(
                        icon = Icons.Default.Person,
                        title = "Sign In / Create Account",
                        description = "Use email and password to create or sign into your account.",
                        onClick = onNavigateToAuthSignIn
                    )
                }
                if (SHOW_SYNC_CODE_FEATURES) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.account_sync_code_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.account_sync_code_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )
                    }
                    item {
                        AccountActionCard(
                            icon = Icons.Default.VpnKey,
                            title = "Generate Sync Code",
                            description = "Create a code on this device so other devices can link to it.",
                            onClick = onNavigateToSyncGenerate
                        )
                    }
                    item {
                        AccountActionCard(
                            icon = Icons.Default.Sync,
                            title = "Enter Sync Code",
                            description = "Link this device to another device using a sync code.",
                            onClick = onNavigateToSyncClaim
                        )
                    }
                }
            }

            is AuthState.FullAccount -> {
                item {
                    AccountInfoCard(
                        label = "Signed in as",
                        value = authState.email
                    )
                }
                item {
                    LinkedDevicesSection(
                        devices = uiState.linkedDevices,
                        onUnlink = { viewModel.unlinkDevice(it) }
                    )
                }
                if (SHOW_SYNC_CODE_FEATURES) {
                    item {
                        AccountActionCard(
                            icon = Icons.Default.VpnKey,
                            title = "Generate Sync Code",
                            description = "Create a code so other devices can sync with this account.",
                            onClick = onNavigateToSyncGenerate
                        )
                    }
                }
                item {
                    SignOutButton(onClick = { viewModel.signOut() })
                }
            }

        }
    }
}

@Composable
private fun AccountActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.TextPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = NuvioColors.Secondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AccountInfoCard(label: String, value: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LinkedDevicesSection(
    devices: List<com.nuvio.tv.data.remote.supabase.SupabaseLinkedDevice>,
    onUnlink: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.account_linked_devices, devices.size),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.account_no_linked_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextTertiary
            )
        } else {
            devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = NuvioColors.BackgroundCard,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = device.deviceName ?: "Unknown Device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onUnlink(device.deviceUserId) },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFFC62828).copy(alpha = 0.2f),
                            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.4f),
                            contentColor = Color(0xFFF44336),
                            focusedContentColor = Color(0xFFF44336)
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = "Unlink",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.account_unlink), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SignOutButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFFC62828).copy(alpha = 0.15f),
            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.3f),
            contentColor = Color(0xFFF44336),
            focusedContentColor = Color(0xFFF44336)
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
