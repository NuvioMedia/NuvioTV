@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    onNavigateToAuthQrSignIn: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            is AuthState.SignedOut -> {
                item {
                    Text(
                        text = "Sync your library, watch progress, addons, and plugins across devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
                item {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = "Sign In with QR",
                        subtitle = "Scan a QR code and complete email login on your phone",
                        onClick = onNavigateToAuthQrSignIn
                    )
                }
            }

            is AuthState.FullAccount -> {
                item { StatusCard(label = "Signed in", value = authState.email) }

                val overview = uiState.syncOverview
                if (overview != null) {
                    item { SyncOverviewCard(overview) }
                } else if (uiState.isSyncOverviewLoading) {
                    item { SyncOverviewLoadingCard() }
                }

                item { SignOutSettingsButton(onClick = { viewModel.signOut() }) }
            }

            is AuthState.Anonymous -> {
                item { StatusCard(label = "Anonymous", value = "Upgrade with QR to link an email") }

                val overview = uiState.syncOverview
                if (overview != null) {
                    item { SyncOverviewCard(overview) }
                } else if (uiState.isSyncOverviewLoading) {
                    item { SyncOverviewLoadingCard() }
                }

                item {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = "Upgrade with QR",
                        subtitle = "Scan a QR code and sign in from your phone",
                        onClick = onNavigateToAuthQrSignIn
                    )
                }
                item { SignOutSettingsButton(onClick = { viewModel.signOut() }) }
            }
        }
    }
}

@Composable
private fun SyncOverviewCard(overview: SyncOverview) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Totals row — layout matches ProfileSyncRow columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioColors.BackgroundElevated,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.Secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(100.dp)
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStatValue(overview.totalAddons, "addons")
                    ProfileStatValue(overview.totalPlugins, "plugins")
                    ProfileStatValue(overview.totalLibrary, "library")
                    ProfileStatValue(overview.totalWatchProgress, "progress")
                    ProfileStatValue(overview.totalWatchedItems, "watched")
                }
            }

            // Per-profile breakdown
            if (overview.perProfile.isNotEmpty()) {
                overview.perProfile.forEach { profile ->
                    ProfileSyncRow(profile)
                }
            }
        }
    }
}

@Composable
private fun SyncStatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.Secondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 9.sp,
            color = NuvioColors.TextTertiary
        )
    }
}

@Composable
private fun ProfileSyncRow(profile: ProfileSyncStats) {
    val color = runCatching { Color(android.graphics.Color.parseColor(profile.avatarColorHex)) }
        .getOrDefault(Color(0xFF1E88E5))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundElevated,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.profileName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = profile.profileName,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(70.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileStatValue(profile.addons, "addons")
            ProfileStatValue(profile.plugins, "plugins")
            ProfileStatValue(profile.library, "library")
            ProfileStatValue(profile.watchProgress, "progress")
            ProfileStatValue(profile.watchedItems, "watched")
        }
    }
}

@Composable
private fun ProfileStatValue(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = if (count > 0) NuvioColors.TextPrimary else NuvioColors.TextTertiary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            fontSize = 8.sp,
            color = NuvioColors.TextTertiary
        )
    }
}

@Composable
private fun SyncOverviewLoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.BackgroundCard,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading sync data...",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary
        )
    }
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
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
                shape = RoundedCornerShape(8.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isFocused) NuvioColors.Primary else NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.Secondary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = NuvioColors.Secondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioColors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SignOutSettingsButton(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = Color(0xFFC62828).copy(alpha = 0.12f),
            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.25f)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(0xFFF44336).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Sign Out",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
