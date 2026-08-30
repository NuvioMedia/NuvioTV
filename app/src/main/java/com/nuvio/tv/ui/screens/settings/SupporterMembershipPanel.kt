@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.domain.model.MembershipOverview
import com.nuvio.tv.domain.model.MembershipOverviewState
import com.nuvio.tv.ui.components.badgeStyle
import com.nuvio.tv.ui.components.rememberMemberBadgeGradientBrush
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PatreonMembershipUrl = "https://www.patreon.com/settings/memberships"

private val MembershipCardColor = Color(0xFF07080B)
private val MembershipPrimaryText = Color(0xFFF6F7F9)
private val MembershipSecondaryText = Color.White.copy(alpha = 0.65f)
private val MembershipTertiaryText = Color.White.copy(alpha = 0.52f)

@Composable
internal fun SupporterMembershipPanel(
    state: MembershipOverviewState,
    supportUrl: String,
    actionFocusRequester: FocusRequester,
    backFocusRequester: FocusRequester,
    showQr: Boolean,
    onShowQr: () -> Unit,
    onHideQr: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manageMembership = state.overview?.subscriptionActive == true
    val actionUrl = if (manageMembership) PatreonMembershipUrl else supportUrl
    val qrBitmap = remember(actionUrl) {
        runCatching { QrCodeGenerator.generate(actionUrl, 420) }.getOrNull()
    }
    val rotation = animateFloatAsState(
        targetValue = if (showQr) 180f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "supporterMembershipFlip"
    ).value
    val hasShownQr = remember { mutableStateOf(false) }

    LaunchedEffect(showQr) {
        if (showQr) {
            hasShownQr.value = true
            backFocusRequester.requestFocusAfterFrames()
        } else if (hasShownQr.value) {
            actionFocusRequester.requestFocusAfterFrames()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(MembershipCardColor)
            .border(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.1f), RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = NuvioTheme.spacing.xxl)
    ) {
        MembershipPanelFront(
            state = state,
            actionFocusRequester = actionFocusRequester,
            manageMembership = manageMembership,
            isVisible = !showQr,
            onShowQr = onShowQr,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 18f * density
                    alpha = if (rotation <= 90f) 1f else 0f
                }
        )

        MembershipPanelBack(
            qrBitmap = qrBitmap,
            manageMembership = manageMembership,
            backFocusRequester = backFocusRequester,
            isVisible = showQr,
            onHideQr = onHideQr,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation - 180f
                    cameraDistance = 18f * density
                    alpha = if (rotation > 90f) 1f else 0f
                }
        )
    }
}

@Composable
private fun MembershipPanelFront(
    state: MembershipOverviewState,
    actionFocusRequester: FocusRequester,
    manageMembership: Boolean,
    isVisible: Boolean,
    onShowQr: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overview = state.overview
    val showPrimaryAction = !state.isLoading && overview != null
    val showRefresh = !state.isLoading && when {
        overview == null -> true
        state.hasError -> true
        overview.subscriptionActive -> true
        overview.providerConnected -> true
        overview.hasActiveGrant -> true
        else -> overview.active
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            when {
                state.isLoading -> MembershipLoadingContent()
                overview == null -> MembershipLoadErrorContent()
                overview.subscriptionActive -> ActiveSubscriptionContent(overview)
                overview.providerConnected && !overview.hasActiveGrant -> ConnectedMembershipContent()
                overview.hasActiveGrant || overview.active -> GrantMembershipContent(overview)
                else -> NonMemberContent()
            }

            if (state.hasError && overview != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.supporter_membership_unable_load),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9E9E)
                )
            }
        }

        if (showRefresh || showPrimaryAction) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showRefresh) {
                    MembershipRefreshButton(
                        isRefreshing = state.isRefreshing,
                        focusRequester = actionFocusRequester.takeIf { !showPrimaryAction },
                        downFocusRequester = actionFocusRequester.takeIf { showPrimaryAction },
                        isVisible = isVisible,
                        onRefresh = onRefresh
                    )
                }
                if (showPrimaryAction) {
                    MembershipPrimaryButton(
                        label = stringResource(
                            if (manageMembership) {
                                R.string.supporter_membership_manage
                            } else {
                                R.string.supporter_membership_view
                            }
                        ),
                        focusRequester = actionFocusRequester,
                        isVisible = isVisible,
                        onClick = onShowQr
                    )
                }
            }
        }
    }
}

@Composable
private fun MembershipLoadingContent() {
    Text(
        text = stringResource(R.string.supporter_membership_loading),
        style = MaterialTheme.typography.headlineSmall,
        color = MembershipPrimaryText,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun MembershipLoadErrorContent() {
    Text(
        text = stringResource(R.string.supporter_membership_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MembershipPrimaryText,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.supporter_membership_unable_load),
        style = MaterialTheme.typography.bodyMedium,
        color = MembershipSecondaryText
    )
}

@Composable
private fun NonMemberContent() {
    Text(
        text = stringResource(R.string.supporter_membership_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MembershipPrimaryText,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.supporter_membership_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MembershipSecondaryText
    )
}

@Composable
private fun ConnectedMembershipContent() {
    Text(
        text = stringResource(R.string.supporter_membership_connected_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MembershipPrimaryText,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.supporter_membership_connected_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MembershipSecondaryText
    )
}

@Composable
private fun ActiveSubscriptionContent(overview: MembershipOverview) {
    val tier = overview.membershipLevel ?: overview.tier ?: MemberTier.SUPPORTER
    val supporterSince = overview.supporterSince?.let(::formatMembershipDate)

    MembershipTierTitle(tier = tier)
    supporterSince?.let { date ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.supporter_membership_supporter_since, date),
            style = MaterialTheme.typography.bodyMedium,
            color = MembershipTertiaryText
        )
    }
}

@Composable
private fun GrantMembershipContent(overview: MembershipOverview) {
    val tier = overview.grantTier ?: overview.tier ?: MemberTier.SUPPORTER
    val supporterSince = overview.supporterSince?.let(::formatMembershipDate)
    MembershipTierTitle(tier = tier)
    supporterSince?.let { date ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.supporter_membership_supporter_since, date),
            style = MaterialTheme.typography.bodyMedium,
            color = MembershipTertiaryText
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembershipTierTitle(
    tier: MemberTier
) {
    val badgeStyle = remember(tier) { tier.badgeStyle() }
    val tierSize = remember(tier) { mutableStateOf(IntSize.Zero) }
    val tierBrush = rememberMemberBadgeGradientBrush(
        style = badgeStyle,
        size = tierSize.value
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.supporter_membership_you_are),
            style = MaterialTheme.typography.headlineSmall,
            color = MembershipPrimaryText,
            fontWeight = FontWeight.SemiBold
        )
        Row {
            Text(
                text = tier.displayName(),
                style = MaterialTheme.typography.headlineSmall.copy(brush = tierBrush),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.onSizeChanged { tierSize.value = it }
            )
            Text(
                text = ".",
                style = MaterialTheme.typography.headlineSmall,
                color = MembershipPrimaryText,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = stringResource(R.string.supporter_membership_thank_you),
            style = MaterialTheme.typography.headlineSmall,
            color = MembershipPrimaryText,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MemberTier.displayName(): String = when (this) {
    MemberTier.SUPPORTER -> stringResource(R.string.supporter_membership_tier_supporter)
    MemberTier.SUPPORTER_PLUS -> stringResource(R.string.supporter_membership_tier_supporter_plus)
}

@Composable
private fun MembershipRefreshButton(
    isRefreshing: Boolean,
    focusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    isVisible: Boolean,
    onRefresh: () -> Unit
) {
    Button(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusProperties {
                canFocus = isVisible
                if (downFocusRequester != null) down = downFocusRequester
            },
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            contentColor = MembershipSecondaryText,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(
            text = stringResource(
                if (isRefreshing) {
                    R.string.supporter_membership_refreshing
                } else {
                    R.string.supporter_membership_refresh
                }
            ),
            modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MembershipPrimaryButton(
    label: String,
    focusRequester: FocusRequester,
    isVisible: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties { canFocus = isVisible }
            .fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = MembershipPrimaryText,
            focusedContainerColor = NuvioTheme.colors.Primary,
            contentColor = MembershipCardColor,
            focusedContentColor = NuvioTheme.colors.OnPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MembershipPanelBack(
    qrBitmap: Bitmap?,
    manageMembership: Boolean,
    backFocusRequester: FocusRequester,
    isVisible: Boolean,
    onHideQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(
                if (manageMembership) {
                    R.string.supporter_membership_scan_manage
                } else {
                    R.string.supporter_membership_scan_support
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MembershipPrimaryText,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (manageMembership) {
                    R.string.supporter_membership_scan_manage_description
                } else {
                    R.string.supporter_membership_scan_support_description
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MembershipSecondaryText,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(22.dp))
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_membership_qr),
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(NuvioTheme.spacing.xl))
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onHideQr,
            modifier = Modifier
                .focusRequester(backFocusRequester)
                .focusProperties { canFocus = isVisible }
                .fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContentColor = NuvioTheme.colors.Primary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(50))
        ) {
            Text(
                text = stringResource(R.string.supporters_contributors_back_button),
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatMembershipDate(rawDate: String): String = runCatching {
    val date = LocalDate.parse(rawDate.substringBefore('T'))
    date.format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.getDefault()))
}.getOrDefault(rawDate)
