@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

@Composable
fun AuthQrSignInScreen(
    onBackPress: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val fullAccount = uiState.authState as? AuthState.FullAccount
    val isSignedIn = fullAccount != null
    val isOnboardingMode = onContinue != null
    val isApproved = remember(uiState.qrLoginStatus) {
        uiState.qrLoginStatus?.contains("approved", ignoreCase = true) == true
    }
    var onboardingTransitionHandled by remember(isOnboardingMode) { mutableStateOf(false) }

    BackHandler {
        viewModel.clearQrLoginSession()
        onBackPress()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(uiState.authState, isSignedIn, uiState.qrLoginCode, uiState.isLoading) {
        if (
            uiState.authState !is AuthState.Loading &&
            !isSignedIn &&
            uiState.qrLoginCode.isNullOrBlank() &&
            !uiState.isLoading
        ) {
            viewModel.startQrLogin()
        }
    }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn && !uiState.qrLoginCode.isNullOrBlank()) {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(isApproved, uiState.isLoading) {
        if (isApproved && !uiState.isLoading) {
            viewModel.exchangeQrLogin()
        }
    }

    LaunchedEffect(isOnboardingMode, isSignedIn) {
        if (!isOnboardingMode || onboardingTransitionHandled) return@LaunchedEffect
        if (isSignedIn) {
            onboardingTransitionHandled = true
            viewModel.clearQrLoginSession()
            onContinue.invoke()
        }
    }

    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = uiState.qrLoginCode) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingMillis = uiState.qrLoginExpiresAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = "Nuvio",
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(60.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.auth_qr_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isSignedIn) {
                        "Your account is connected on this TV."
                    } else {
                        "Use your phone to sign in with email/password. TV stays QR-only for faster login."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (isSignedIn) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = fullAccount.email,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF7CFF9B),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = fullAccount.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .border(1.dp, NuvioColors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .background(
                        NuvioColors.BackgroundElevated.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.auth_qr_account_login),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = if (isSignedIn) {
                        "Your synced data"
                    } else {
                        "Scan QR, approve in browser, then return here."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (isSignedIn && !isOnboardingMode) {
                    AccountConnectedStatsStrip(
                        stats = uiState.connectedStats,
                        isLoading = uiState.isStatsLoading
                    )
                } else if (isSignedIn && isOnboardingMode) {
                    StatusPill(
                        text = stringResource(R.string.auth_qr_finishing),
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary
                    )
                } else {
                    if (uiState.qrLoginBitmap != null) {
                        Image(
                            bitmap = uiState.qrLoginBitmap!!.asImageBitmap(),
                            contentDescription = "QR login code",
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(NuvioColors.BackgroundCard, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.isLoading) "Generating QR..." else "QR unavailable. Refresh to retry.",
                                color = NuvioColors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (!uiState.qrLoginCode.isNullOrBlank()) {
                        Text(
                            text = "Code: ${uiState.qrLoginCode}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (uiState.qrLoginExpiresAtMillis != null) {
                        Text(
                            text = stringResource(R.string.auth_qr_expires, formatDuration(remainingMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }

                    val statusText = uiState.error ?: uiState.qrLoginStatus
                    if (!statusText.isNullOrBlank()) {
                        StatusPill(
                            text = statusText,
                            containerColor = if (uiState.error != null) Color(0x33C62828) else NuvioColors.BackgroundCard,
                            contentColor = if (uiState.error != null) Color(0xFFFF6E6E) else NuvioColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (isSignedIn) {
                                viewModel.signOut()
                            } else {
                                viewModel.startQrLogin()
                            }
                        },
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = Color.White,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = Color.Black,
                            disabledContainerColor = NuvioColors.BackgroundCard.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(
                            when {
                                isSignedIn -> "Sign Out"
                                uiState.isLoading -> "Please wait..."
                                else -> "Refresh QR"
                            }
                        )
                    }
                    Button(
                        onClick = {
                            if (onContinue != null && !isSignedIn) {
                                viewModel.signOut()
                            }
                            viewModel.clearQrLoginSession()
                            if (onContinue != null) {
                                onContinue()
                            } else {
                                onBackPress()
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = Color.White,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = Color.Black
                        )
                    ) {
                        Text(
                            if (onContinue != null) {
                                if (isSignedIn) "Continue" else "Continue without account"
                            } else {
                                "Back"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NuvioColors.Border.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(containerColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentHeight()
        )
    }
}

@Composable
private fun AccountConnectedStatsStrip(
    stats: AccountConnectedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("...", "...", "...", "...")
    } else {
        listOf(
            (stats?.addons ?: 0).toString(),
            (stats?.plugins ?: 0).toString(),
            (stats?.library ?: 0).toString(),
            (stats?.watchProgress ?: 0).toString()
        )
    }
    val labels = listOf("Addons", "Plugins", "Library", "Watch Progress")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(values.size) { index ->
                AccountStatItem(
                    value = values[index],
                    label = labels[index],
                    modifier = Modifier.weight(1f)
                )
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .width(1.dp)
                            .background(NuvioColors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
    }
}

@Composable
private fun AccountStatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
