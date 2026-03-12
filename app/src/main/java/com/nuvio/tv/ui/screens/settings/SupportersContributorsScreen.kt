@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.repository.GitHubContributor
import com.nuvio.tv.data.repository.SupporterDonation
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DONATIONS_URL: String
    get() = BuildConfig.DONATIONS_BASE_URL
        .takeIf { it.isNotBlank() }
        ?: error("DONATIONS_BASE_URL is missing. Set it in local.properties or local.dev.properties.")
        .removeSuffix("/")

private val DONATE_URL: String
    get() = BuildConfig.DONATIONS_DONATE_URL
        .takeIf { it.isNotBlank() }
        ?: error("DONATIONS_DONATE_URL is missing. Set it in local.properties or local.dev.properties.")
        .removeSuffix("/")

@Composable
fun SupportersContributorsScreen(
    viewModel: SupportersContributorsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    var showDonateQr by remember { mutableStateOf(false) }
    val donateFocusRequester = remember { FocusRequester() }
    val backFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = showDonateQr) {
        showDonateQr = false
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabFocusRequesters = remember {
        SupportersContributorsTab.entries.associateWith { FocusRequester() }
    }
    val supporterFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val contributorFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var pendingSupporterRestoreKey by remember { mutableStateOf<String?>(null) }
    var pendingContributorRestoreKey by remember { mutableStateOf<String?>(null) }

    BackHandler {
        when {
            uiState.selectedContributor != null -> {
                pendingContributorRestoreKey = uiState.selectedContributor?.login
                viewModel.dismissContributorDetails()
            }
            uiState.selectedSupporter != null -> {
                pendingSupporterRestoreKey = uiState.selectedSupporter?.key
                viewModel.dismissSupporterDetails()
            }
            else -> onBackPress()
        }
    }

    LaunchedEffect(Unit) {
        tabFocusRequesters.getValue(SupportersContributorsTab.Contributors).requestFocusAfterFrames()
    }

    LaunchedEffect(uiState.supporters) {
        supporterFocusRequesters.keys.retainAll(uiState.supporters.map { it.key }.toSet())
    }

    LaunchedEffect(uiState.contributors) {
        contributorFocusRequesters.keys.retainAll(uiState.contributors.map { it.login }.toSet())
    }

    LaunchedEffect(uiState.selectedSupporter, pendingSupporterRestoreKey) {
        val key = pendingSupporterRestoreKey ?: return@LaunchedEffect
        if (uiState.selectedSupporter != null) return@LaunchedEffect
        supporterFocusRequesters[key]?.requestFocusAfterFrames()
        pendingSupporterRestoreKey = null
    }

    LaunchedEffect(uiState.selectedContributor, pendingContributorRestoreKey) {
        val key = pendingContributorRestoreKey ?: return@LaunchedEffect
        if (uiState.selectedContributor != null) return@LaunchedEffect
        contributorFocusRequesters[key]?.requestFocusAfterFrames()
        pendingContributorRestoreKey = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 36.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SupportersBrandColumn(
                modifier = Modifier.weight(0.42f),
                donateFocusRequester = donateFocusRequester,
                backFocusRequester = backFocusRequester,
                showDonateQr = showDonateQr,
                onShowDonateQr = { showDonateQr = true },
                onHideDonateQr = { showDonateQr = false }
            )

            SupportersContentPanel(
                uiState = uiState,
                leftFocusRequester = if (showDonateQr) backFocusRequester else donateFocusRequester,
                tabFocusRequesters = tabFocusRequesters,
                supporterFocusRequesters = supporterFocusRequesters,
                contributorFocusRequesters = contributorFocusRequesters,
                onSelectTab = viewModel::onSelectTab,
                onRetrySupporters = viewModel::retrySupporters,
                onRetryContributors = viewModel::retryContributors,
                onSupporterClick = viewModel::onSupporterSelected,
                onContributorClick = viewModel::onContributorSelected,
                modifier = Modifier.weight(0.58f)
            )
        }
    }

    uiState.selectedSupporter?.let { supporter ->
        SupporterDetailsDialog(
            supporter = supporter,
            onDismiss = {
                pendingSupporterRestoreKey = supporter.key
                viewModel.dismissSupporterDetails()
            }
        )
    }

    uiState.selectedContributor?.let { contributor ->
        ContributorDetailsDialog(
            contributor = contributor,
            onDismiss = {
                pendingContributorRestoreKey = contributor.login
                viewModel.dismissContributorDetails()
            }
        )
    }
}

@Composable
private fun SupportersBrandColumn(
    modifier: Modifier = Modifier,
    donateFocusRequester: FocusRequester,
    backFocusRequester: FocusRequester,
    showDonateQr: Boolean,
    onShowDonateQr: () -> Unit,
    onHideDonateQr: () -> Unit
) {
    var hasShownDonateQr by remember { mutableStateOf(false) }
    val qrBitmap = remember(DONATE_URL) {
        runCatching { QrCodeGenerator.generate(DONATE_URL, 420) }.getOrNull()
    }
    val rotation by animateFloatAsState(
        targetValue = if (showDonateQr) 180f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "supportersDonateFlip"
    )

    LaunchedEffect(showDonateQr) {
        if (showDonateQr) {
            hasShownDonateQr = true
            backFocusRequester.requestFocusAfterFrames()
        } else if (hasShownDonateQr) {
            donateFocusRequester.requestFocusAfterFrames()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(NuvioColors.BackgroundElevated)
            .border(1.dp, NuvioColors.Border, RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 32.dp)
    ) {
        SupportersBrandFront(
            donateFocusRequester = donateFocusRequester,
            onShowDonateQr = onShowDonateQr,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 18f * density
                    alpha = if (rotation <= 90f) 1f else 0f
                }
        )

        SupportersBrandBack(
            qrBitmap = qrBitmap,
            backFocusRequester = backFocusRequester,
            onHideDonateQr = onHideDonateQr,
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
private fun SupportersBrandFront(
    donateFocusRequester: FocusRequester,
    onShowDonateQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_wordmark),
                contentDescription = "NuvioTV",
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .height(86.dp),
                contentScale = ContentScale.Fit
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.supporters_contributors_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.supporters_contributors_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }

            Text(
                text = stringResource(R.string.supporters_contributors_supporters_copy),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary.copy(alpha = 0.92f)
            )

            Text(
                text = stringResource(R.string.supporters_contributors_donate_copy),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onShowDonateQr,
                modifier = Modifier
                    .focusRequester(donateFocusRequester)
                    .fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContentColor = NuvioColors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(
                    text = stringResource(R.string.supporters_contributors_donate_button),
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SupportersBrandBack(
    qrBitmap: Bitmap?,
    backFocusRequester: FocusRequester,
    onHideDonateQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.supporters_contributors_qr_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.supporters_contributors_qr_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(22.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Donation QR code",
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onHideDonateQr,
            modifier = Modifier
                .focusRequester(backFocusRequester)
                .fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.FocusBackground,
                contentColor = NuvioColors.TextPrimary,
                focusedContentColor = NuvioColors.Primary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(50))
        ) {
            Text(
                text = stringResource(R.string.supporters_contributors_back_button),
                modifier = Modifier.padding(vertical = 4.dp),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SupportersContentPanel(
    uiState: SupportersContributorsUiState,
    leftFocusRequester: FocusRequester,
    tabFocusRequesters: Map<SupportersContributorsTab, FocusRequester>,
    supporterFocusRequesters: MutableMap<String, FocusRequester>,
    contributorFocusRequesters: MutableMap<String, FocusRequester>,
    onSelectTab: (SupportersContributorsTab) -> Unit,
    onRetrySupporters: () -> Unit,
    onRetryContributors: () -> Unit,
    onSupporterClick: (SupporterDonation) -> Unit,
    onContributorClick: (GitHubContributor) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTabRequester = tabFocusRequesters.getValue(uiState.selectedTab)

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(NuvioColors.BackgroundElevated)
            .border(1.dp, NuvioColors.Border, RoundedCornerShape(28.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(selectedTabRequester),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SupportersTabButton(
                label = stringResource(R.string.supporters_tab),
                selected = uiState.selectedTab == SupportersContributorsTab.Supporters,
                leftFocusRequester = leftFocusRequester,
                focusRequester = tabFocusRequesters.getValue(SupportersContributorsTab.Supporters),
                onClick = { onSelectTab(SupportersContributorsTab.Supporters) }
            )
            SupportersTabButton(
                label = stringResource(R.string.contributors_tab),
                selected = uiState.selectedTab == SupportersContributorsTab.Contributors,
                leftFocusRequester = null,
                focusRequester = tabFocusRequesters.getValue(SupportersContributorsTab.Contributors),
                onClick = { onSelectTab(SupportersContributorsTab.Contributors) }
            )
        }

        when (uiState.selectedTab) {
            SupportersContributorsTab.Supporters -> SupportersTabContent(
                uiState = uiState,
                leftFocusRequester = leftFocusRequester,
                upFocusRequester = selectedTabRequester,
                supporterFocusRequesters = supporterFocusRequesters,
                onRetry = onRetrySupporters,
                onSupporterClick = onSupporterClick,
                modifier = Modifier.weight(1f)
            )
            SupportersContributorsTab.Contributors -> ContributorsTabContent(
                uiState = uiState,
                leftFocusRequester = leftFocusRequester,
                upFocusRequester = selectedTabRequester,
                contributorFocusRequesters = contributorFocusRequesters,
                onRetry = onRetryContributors,
                onContributorClick = onContributorClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SupportersTabContent(
    uiState: SupportersContributorsUiState,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    supporterFocusRequesters: MutableMap<String, FocusRequester>,
    onRetry: () -> Unit,
    onSupporterClick: (SupporterDonation) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(NuvioColors.Background)
            .border(1.dp, NuvioColors.Border, RoundedCornerShape(24.dp))
    ) {
        when {
            uiState.isSupportersLoading -> CenterStatusText(
                text = stringResource(R.string.supporters_loading),
                modifier = Modifier.fillMaxSize()
            )

            uiState.supportersErrorMessage != null -> TabErrorState(
                title = stringResource(R.string.supporters_error_title),
                message = uiState.supportersErrorMessage,
                leftFocusRequester = leftFocusRequester,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize()
            )

            uiState.hasLoadedSupporters && uiState.supporters.isEmpty() -> CenterStatusText(
                text = stringResource(R.string.supporters_empty),
                modifier = Modifier.fillMaxSize()
            )

            else -> {
                val firstRequester = uiState.supporters.firstOrNull()?.let { supporter ->
                    supporterFocusRequesters.getOrPut(supporter.key) { FocusRequester() }
                } ?: FocusRequester()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .focusRestorer(firstRequester),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)
                ) {
                    items(uiState.supporters, key = { it.key }) { supporter ->
                        val requester = remember(supporter.key) {
                            supporterFocusRequesters.getOrPut(supporter.key) { FocusRequester() }
                        }
                        val isFirstItem = supporter.key == uiState.supporters.firstOrNull()?.key
                        SupporterCard(
                            supporter = supporter,
                            focusRequester = requester,
                            leftFocusRequester = leftFocusRequester,
                            upFocusRequester = if (isFirstItem) upFocusRequester else null,
                            onClick = { onSupporterClick(supporter) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorsTabContent(
    uiState: SupportersContributorsUiState,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    contributorFocusRequesters: MutableMap<String, FocusRequester>,
    onRetry: () -> Unit,
    onContributorClick: (GitHubContributor) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(NuvioColors.Background)
            .border(1.dp, NuvioColors.Border, RoundedCornerShape(24.dp))
    ) {
        when {
            uiState.isContributorsLoading -> CenterStatusText(
                text = stringResource(R.string.contributors_loading),
                modifier = Modifier.fillMaxSize()
            )

            uiState.contributorsErrorMessage != null -> TabErrorState(
                title = stringResource(R.string.contributors_error_title),
                message = uiState.contributorsErrorMessage,
                leftFocusRequester = leftFocusRequester,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize()
            )

            uiState.hasLoadedContributors && uiState.contributors.isEmpty() -> CenterStatusText(
                text = stringResource(R.string.contributors_empty),
                modifier = Modifier.fillMaxSize()
            )

            else -> {
                val firstRequester = uiState.contributors.firstOrNull()?.let { contributor ->
                    contributorFocusRequesters.getOrPut(contributor.login) { FocusRequester() }
                } ?: FocusRequester()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .focusRestorer(firstRequester),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)
                ) {
                    items(uiState.contributors, key = { it.login }) { contributor ->
                        val requester = remember(contributor.login) {
                            contributorFocusRequesters.getOrPut(contributor.login) { FocusRequester() }
                        }
                        val isFirstItem = contributor.login == uiState.contributors.firstOrNull()?.login
                        ContributorCard(
                            contributor = contributor,
                            focusRequester = requester,
                            leftFocusRequester = leftFocusRequester,
                            upFocusRequester = if (isFirstItem) upFocusRequester else null,
                            onClick = { onContributorClick(contributor) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterStatusText(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TabErrorState(
    title: String,
    message: String,
    leftFocusRequester: FocusRequester,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val retryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        retryFocusRequester.requestFocusAfterFrames()
    }

    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .focusRequester(retryFocusRequester)
                    .focusProperties { left = leftFocusRequester },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContentColor = NuvioColors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun SupporterCard(
    supporter: SupporterDonation,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                if (upFocusRequester != null) up = upFocusRequester
            }
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(22.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(22.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NameAvatar(
                label = supporter.name,
                modifier = Modifier.size(58.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = supporter.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDonationDate(supporter.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                supporter.message?.let { message ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFocused) {
                            NuvioColors.TextPrimary.copy(alpha = 0.9f)
                        } else {
                            NuvioColors.TextSecondary
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = if (isFocused) NuvioColors.FocusRing else NuvioColors.TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ContributorCard(
    contributor: GitHubContributor,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                if (upFocusRequester != null) up = upFocusRequester
            }
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(22.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(22.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ContributorAvatar(
                login = contributor.login,
                avatarUrl = contributor.avatarUrl,
                modifier = Modifier.size(58.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = contributor.login,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    contributorRoleLabel(contributor.login)?.let { role ->
                        ContributorRoleBadge(role = role)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.contributors_total_contributions,
                        contributor.totalContributions
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused) {
                        NuvioColors.TextPrimary.copy(alpha = 0.9f)
                    } else {
                        NuvioColors.TextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = if (isFocused) NuvioColors.FocusRing else NuvioColors.TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ContributorRoleBadge(role: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NuvioColors.Background)
            .border(1.dp, NuvioColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioColors.TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun NameAvatar(
    label: String,
    modifier: Modifier = Modifier
) {
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(NuvioColors.Background)
            .border(1.dp, NuvioColors.Border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ContributorAvatar(
    login: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(avatarUrl)
            .crossfade(true)
            .build()
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(NuvioColors.Background)
            .border(1.dp, NuvioColors.Border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNullOrBlank() || painter.state is AsyncImagePainter.State.Error) {
            Text(
                text = login.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Image(
                painter = painter,
                contentDescription = login,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun SupportersTabButton(
    label: String,
    selected: Boolean,
    leftFocusRequester: FocusRequester?,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .heightIn(min = 54.dp)
            .focusRequester(focusRequester)
            .then(
                if (leftFocusRequester != null) {
                    Modifier.focusProperties { left = leftFocusRequester }
                } else {
                    Modifier
                }
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onClick()
            },
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.BackgroundCard else NuvioColors.Background,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(
                    border = BorderStroke(1.dp, NuvioColors.FocusRing.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(999.dp)
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(999.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isFocused -> NuvioColors.TextPrimary
                    selected -> NuvioColors.TextPrimary
                    else -> NuvioColors.TextSecondary
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SupporterDetailsDialog(
    supporter: SupporterDonation,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(supporter.key) {
        primaryFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = supporter.name,
        subtitle = formatDonationDate(supporter.date),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NameAvatar(
                label = supporter.name,
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = supporter.message ?: stringResource(R.string.supporters_no_message),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATIONS_URL)))
                    }
                },
                modifier = Modifier.focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContentColor = NuvioColors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.supporters_open_donations))
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContentColor = NuvioColors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun ContributorDetailsDialog(
    contributor: GitHubContributor,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primaryFocusRequester = remember { FocusRequester() }
    val supportLink = contributorSupportLink(contributor.login)
    var showSupportQr by remember(contributor.login) { mutableStateOf(false) }
    val supportQrBitmap = remember(supportLink?.kofiUrl) {
        supportLink?.kofiUrl?.let { url ->
            runCatching { QrCodeGenerator.generate(url, 360) }.getOrNull()
        }
    }

    LaunchedEffect(contributor.login) {
        primaryFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = contributor.login,
        subtitle = stringResource(
            R.string.contributors_total_contributions,
            contributor.totalContributions
        ),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ContributorAvatar(
                login = contributor.login,
                avatarUrl = contributor.avatarUrl,
                modifier = Modifier.size(72.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                contributorRoleLabel(contributor.login)?.let { role ->
                    ContributorRoleBadge(role = role)
                }
                Text(
                    text = contributor.profileUrl ?: stringResource(R.string.contributors_profile_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                supportLink?.kofiUrl?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
        }

        if (showSupportQr && supportQrBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = supportQrBitmap.asImageBitmap(),
                    contentDescription = "Contributor support QR code",
                    modifier = Modifier
                        .size(188.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val profileUrl = contributor.profileUrl ?: return@Button
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl))
                        )
                    }
                },
                enabled = contributor.profileUrl != null,
                modifier = Modifier.focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContentColor = NuvioColors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.contributors_open_github))
            }

            supportLink?.kofiUrl?.let {
                Button(
                    onClick = { showSupportQr = !showSupportQr },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        contentColor = NuvioColors.TextPrimary,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50))
                ) {
                    Text(
                        text = stringResource(
                            if (showSupportQr) {
                                R.string.contributors_hide_kofi_qr
                            } else {
                                R.string.contributors_show_kofi_qr
                            }
                        )
                    )
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContentColor = NuvioColors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.action_close))
            }
        }
    }
}

private fun contributorRoleLabel(login: String): String? = when (login.lowercase(Locale.ROOT)) {
    "milicevicivan" -> "Translator"
    "tapframe" -> "Maintainer"
    else -> null
}

private data class ContributorSupportLink(
    val kofiUrl: String? = null
)

private val contributorSupportLinks = mapOf(
    "skoruppa" to ContributorSupportLink(
        kofiUrl = "https://buymeacoffee.com/skoruppa"
    ),
    "crisszollo" to ContributorSupportLink(
        kofiUrl = "https://ko-fi.com/crisszollo"
    )
)

private fun contributorSupportLink(login: String): ContributorSupportLink? =
    contributorSupportLinks[login.lowercase(Locale.ROOT)]

private fun formatDonationDate(rawDate: String): String {
    return runCatching {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        Instant.parse(rawDate)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }.getOrDefault(rawDate)
}
