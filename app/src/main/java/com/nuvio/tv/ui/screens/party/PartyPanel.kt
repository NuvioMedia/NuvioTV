package com.nuvio.tv.ui.screens.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyMember
import com.nuvio.tv.core.party.PartyProfile
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.ui.screens.detail.formatPlaybackTime
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun PartyPanel(
    state: PartyUiState,
    profile: PartyProfile,
    playerMedia: PartyMedia,
    isSupported: Boolean,
    canStart: Boolean,
    canShareStream: Boolean,
    onStart: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated),
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.party_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                )
                DialogButton(
                    text = stringResource(R.string.action_close),
                    onClick = onClose,
                    isPrimary = false,
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            AnimatedContent(
                targetState = state.party,
                contentKey = { it != null },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "partyPanelContent",
                modifier = Modifier.fillMaxSize(),
            ) { party ->
                if (party == null) {
                    StartContent(
                        profile = profile,
                        busy = state.busy,
                        isSupported = isSupported,
                        canStart = canStart,
                        onStart = onStart,
                    )
                } else {
                    PartyContent(
                        party = party,
                        playerMedia = playerMedia,
                        canShareStream = canShareStream,
                        behindMs = behindMs,
                        onCatchUp = onCatchUp,
                        onSwitchParty = onSwitchParty,
                        onWatchWithParty = onWatchWithParty,
                        onLeave = onLeave,
                        onEnd = onEnd,
                        onRemove = onRemove,
                        onHostOnlyChange = onHostOnlyChange,
                        onSharingChange = onSharingChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartContent(
    profile: PartyProfile,
    busy: Boolean,
    isSupported: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
) {
    val startFocusRequester = remember { FocusRequester() }
    val canPress = isSupported && canStart
    LaunchedEffect(canPress) {
        if (canPress) startFocusRequester.requestFocusAfterFrames()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        SeatsHero(
            profile = profile,
            size = 56.dp,
            ringColor = NuvioTheme.colors.BackgroundElevated,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
        )
        Text(
            text = stringResource(R.string.party_start_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        Text(
            text = stringResource(R.string.party_start_description),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
        when {
            !isSupported -> PartyNote(stringResource(R.string.party_unavailable))
            !canStart -> PartyNote(stringResource(R.string.party_sign_in_required))
            else -> DialogButton(
                text = stringResource(R.string.party_start_action),
                onClick = onStart,
                isPrimary = true,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NuvioTheme.spacing.sm)
                    .focusRequester(startFocusRequester),
            )
        }
        ExperimentalNote()
    }
}

@Composable
private fun PartyContent(
    party: ActiveParty,
    playerMedia: PartyMedia,
    canShareStream: Boolean,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
) {
    val contentFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { contentFocusRequester.requestFocusAfterFrames() }
    Column(modifier = Modifier.fillMaxSize()) {
        CodeSection(code = party.code, connected = party.connected)
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sidePanel.inner))
        PartyDetailsList(
            party = party,
            playerMedia = playerMedia,
            canShareStream = canShareStream,
            behindMs = behindMs,
            onCatchUp = onCatchUp,
            onSwitchParty = onSwitchParty,
            onWatchWithParty = onWatchWithParty,
            onLeave = onLeave,
            onEnd = onEnd,
            onRemove = onRemove,
            onHostOnlyChange = onHostOnlyChange,
            onSharingChange = onSharingChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(contentFocusRequester),
        )
    }
}

@Composable
private fun PartyDetailsList(
    party: ActiveParty,
    playerMedia: PartyMedia,
    canShareStream: Boolean,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSwitch by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sidePanel.inner),
    ) {
        var lastBehindMs by remember { mutableStateOf(0L) }
        if (behindMs != null) lastBehindMs = behindMs
        AnimatedVisibility(
            visible = behindMs != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.party_behind_title, formatPlaybackTime(lastBehindMs)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                )
                DialogButton(
                    text = stringResource(R.string.party_catch_up),
                    onClick = onCatchUp,
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AnimatedVisibility(
            visible = playerMedia.key != party.media.key,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.party_now_watching, party.media.displayTitle()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                )
                DialogButton(
                    text = stringResource(R.string.party_watch_with_party),
                    onClick = onWatchWithParty,
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (party.isHost) {
                    DialogButton(
                        text = stringResource(R.string.party_switch, playerMedia.displayTitle()),
                        onClick = { confirmSwitch = true },
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        MembersSection(
            party = party,
            ringColor = NuvioTheme.colors.BackgroundElevated,
            onRemove = onRemove,
            selfBehindMs = behindMs,
        )
        if (party.isHost) {
            HostSettings(
                party = party,
                onHostOnlyChange = onHostOnlyChange,
                onSharingChange = onSharingChange,
                sharingNote = stringResource(R.string.party_share_unavailable).takeUnless { canShareStream },
            )
        }
        AnimatedVisibility(
            visible = !party.isHost && party.hostOnly,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            HostOnlyNote()
        }
        PartyActions(
            party = party,
            onLeave = onLeave,
            onEnd = onEnd,
        )
    }
    if (confirmSwitch) {
        ConfirmDialog(
            title = stringResource(R.string.party_switch_title),
            message = stringResource(R.string.party_switch_message, playerMedia.displayTitle()),
            confirmLabel = stringResource(R.string.party_switch_confirm),
            onConfirm = {
                confirmSwitch = false
                onSwitchParty()
            },
            onDismiss = { confirmSwitch = false },
        )
    }
}

@Composable
fun PartyStatusPill(
    visible: Boolean,
    members: List<PartyMember>,
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.full))
                .background(Color.Black.copy(alpha = 0.6f))
                .animateContentSize()
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = if (message != null) 16.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarStack(
                members = members,
                size = 30.dp,
                ringColor = Color.Black.copy(alpha = 0.6f),
            )
            AnimatedContent(
                targetState = message,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 })
                },
                label = "partyStatusMessage",
            ) { text ->
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 12f),
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
