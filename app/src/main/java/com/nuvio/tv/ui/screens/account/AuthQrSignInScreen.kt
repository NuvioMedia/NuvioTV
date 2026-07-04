@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

private val AuthTextPrimary = Color(0xFFF5F7F8)
private val AuthTextSecondary = Color(0xFF969CA3)
private val AuthTextMuted = Color(0xFF6E7178)
private val AuthPaneBackground = Color.White.copy(alpha = 0.022f)
private val AuthPaneBorder = Color.White.copy(alpha = 0.07f)
private val AuthSecondaryButtonBackground = Color.White.copy(alpha = 0.05f)
private val AuthSecondaryButtonBorder = Color.White.copy(alpha = 0.09f)

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
    var exitRequested by remember { mutableStateOf(false) }

    fun leaveAuthScreen() {
        exitRequested = true
        viewModel.clearQrLoginSession()
        onBackPress()
    }

    fun continueFromAuthScreen() {
        exitRequested = true
        if (onContinue != null && !isSignedIn) {
            viewModel.signOut()
        }
        viewModel.clearQrLoginSession()
        if (onContinue != null) {
            onContinue()
        } else {
            onBackPress()
        }
    }

    BackHandler {
        leaveAuthScreen()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(uiState.authState, isSignedIn, uiState.qrLoginCode, uiState.isLoading, uiState.error, exitRequested) {
        if (
            !exitRequested &&
            uiState.authState !is AuthState.Loading &&
            !isSignedIn &&
            uiState.qrLoginCode.isNullOrBlank() &&
            uiState.error.isNullOrBlank() &&
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
            exitRequested = true
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
            .background(Color.Black)
            .authGradientBackground()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            AuthQrBrandPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 56.dp, end = 56.dp),
                isSignedIn = isSignedIn,
                fullAccount = fullAccount
            )

            AuthQrLoginPane(
                modifier = Modifier
                    .width(460.dp)
                    .fillMaxHeight()
                    .background(AuthPaneBackground)
                    .drawBehind {
                        drawLine(
                            color = AuthPaneBorder,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    },
                uiState = uiState,
                isSignedIn = isSignedIn,
                isOnboardingMode = isOnboardingMode,
                remainingMillis = remainingMillis,
                onRefreshOrSignOut = {
                    if (isSignedIn) {
                        viewModel.signOut()
                    } else {
                        viewModel.startQrLogin()
                    }
                },
                onBackOrContinue = {
                    if (isOnboardingMode) {
                        continueFromAuthScreen()
                    } else {
                        leaveAuthScreen()
                    }
                }
            )
        }
    }
}

@Composable
private fun AuthQrBrandPanel(
    modifier: Modifier,
    isSignedIn: Boolean,
    fullAccount: AuthState.FullAccount?
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo_wordmark),
            contentDescription = stringResource(R.string.cd_nuvio),
            modifier = Modifier.height(60.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.auth_qr_tagline),
            modifier = Modifier.widthIn(max = 440.dp),
            style = MaterialTheme.typography.displayLarge.copy(
                color = AuthTextPrimary,
                fontSize = 40.sp,
                lineHeight = 45.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (isSignedIn) {
                stringResource(R.string.auth_qr_connected)
            } else {
                stringResource(R.string.auth_qr_phone_hint)
            },
            modifier = Modifier.widthIn(max = 400.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = AuthTextSecondary,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Normal
            )
        )
        if (isSignedIn && fullAccount != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = fullAccount.email,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF7CFF9B)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = fullAccount.userId,
                style = MaterialTheme.typography.bodySmall,
                color = AuthTextSecondary
            )
        }
    }
}

@Composable
private fun AuthQrLoginPane(
    modifier: Modifier,
    uiState: AccountUiState,
    isSignedIn: Boolean,
    isOnboardingMode: Boolean,
    remainingMillis: Long,
    onRefreshOrSignOut: () -> Unit,
    onBackOrContinue: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.auth_qr_account_login),
            style = MaterialTheme.typography.headlineLarge.copy(
                color = AuthTextPrimary,
                fontSize = 30.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (isSignedIn) {
                stringResource(R.string.auth_qr_synced_data)
            } else {
                stringResource(R.string.auth_qr_scan_instruction)
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                color = AuthTextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))

        if (isSignedIn && !isOnboardingMode) {
            AccountConnectedStatsStrip(
                stats = uiState.connectedStats,
                isLoading = uiState.isStatsLoading
            )
        } else if (isSignedIn && isOnboardingMode) {
            StatusPill(
                text = stringResource(R.string.auth_qr_finishing),
                containerColor = AuthSecondaryButtonBackground,
                contentColor = AuthTextSecondary
            )
        } else {
            AuthQrCodeBlock(uiState = uiState, remainingMillis = remainingMillis)
        }

        Spacer(modifier = Modifier.height(28.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRefreshOrSignOut,
                enabled = !uiState.isLoading,
                colors = ButtonDefaults.colors(
                    containerColor = AuthSecondaryButtonBackground,
                    focusedContainerColor = Color.White,
                    contentColor = AuthTextPrimary,
                    focusedContentColor = Color.Black,
                    disabledContainerColor = AuthSecondaryButtonBackground.copy(alpha = 0.45f)
                ),
                border = ButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                        shape = RoundedCornerShape(16.dp)
                    )
                )
            ) {
                Text(
                    when {
                        isSignedIn -> stringResource(R.string.account_sign_out)
                        uiState.isLoading -> stringResource(R.string.auth_qr_please_wait)
                        else -> stringResource(R.string.auth_qr_refresh)
                    }
                )
            }
            Button(
                onClick = onBackOrContinue,
                colors = ButtonDefaults.colors(
                    containerColor = AuthSecondaryButtonBackground,
                    focusedContainerColor = Color.White,
                    contentColor = AuthTextPrimary,
                    focusedContentColor = Color.Black
                ),
                border = ButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuthSecondaryButtonBorder),
                        shape = RoundedCornerShape(16.dp)
                    )
                )
            ) {
                Text(
                    if (isOnboardingMode) {
                        if (isSignedIn) stringResource(R.string.auth_qr_continue) else stringResource(R.string.auth_qr_continue_without_account)
                    } else {
                        stringResource(R.string.auth_qr_back)
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthQrCodeBlock(
    uiState: AccountUiState,
    remainingMillis: Long
) {
    val qrBitmap = uiState.qrLoginBitmap
    if (qrBitmap != null) {
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_qr_login),
            modifier = Modifier
                .size(206.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(206.dp)
                .background(AuthSecondaryButtonBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AuthSecondaryButtonBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (uiState.isLoading) stringResource(R.string.auth_qr_generating) else stringResource(R.string.auth_qr_unavailable),
                color = AuthTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    AuthTermsAcknowledgement()

    val qrLoginCode = uiState.qrLoginCode
    if (!qrLoginCode.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.auth_qr_code_display, qrLoginCode),
            style = MaterialTheme.typography.bodyMedium,
            color = AuthTextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
    if (uiState.qrLoginExpiresAtMillis != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.auth_qr_expires, formatDuration(remainingMillis)),
            style = MaterialTheme.typography.bodySmall,
            color = AuthTextSecondary
        )
    }

    val statusText = uiState.error ?: uiState.qrLoginStatus
    if (!statusText.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(14.dp))
        if (uiState.error != null) {
            StatusPill(
                text = statusText,
                containerColor = Color(0x33C62828),
                contentColor = Color(0xFFFF6E6E)
            )
        } else {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = AuthTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AuthTermsAcknowledgement() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.auth_qr_terms_prefix),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AuthTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.auth_qr_terms_link),
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nuvio.tv/terms")))
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                color = AuthTextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
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
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), RoundedCornerShape(NuvioTheme.radii.md))
            .background(containerColor, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp),
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
    val labels = listOf(
        stringResource(R.string.account_stat_addons),
        stringResource(R.string.account_stat_plugins),
        stringResource(R.string.account_stat_library),
        stringResource(R.string.account_stat_progress)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
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
                            .width(NuvioTheme.spacing.hairline)
                            .background(NuvioTheme.colors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.hairline)
                .background(NuvioTheme.colors.Border.copy(alpha = 0.8f))
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
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun Modifier.authGradientBackground(): Modifier = drawWithCache {
    val angleRadians = 122.0 * PI / 180.0
    val directionX = sin(angleRadians).toFloat()
    val directionY = (-cos(angleRadians)).toFloat()
    val halfLength = (abs(size.width * directionX) + abs(size.height * directionY)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = Offset(
        x = center.x - directionX * halfLength,
        y = center.y - directionY * halfLength
    )
    val end = Offset(
        x = center.x + directionX * halfLength,
        y = center.y + directionY * halfLength
    )
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0xFF21113B),
            0.14f to Color(0xFF21113B),
            0.26f to Color(0xFF1A0E2F),
            0.36f to Color(0xFF130A23),
            0.48f to Color(0xFF0A060F),
            0.60f to Color(0xFF050408),
            0.70f to Color.Black,
            1f to Color.Black
        ),
        start = start,
        end = end
    )
    onDrawBehind {
        drawRect(brush = brush)
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
