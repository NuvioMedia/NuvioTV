@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.party

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PartyMember
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.formatPlaybackTime
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsNote
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.settingsRowBorder
import com.nuvio.tv.ui.screens.settings.settingsRowColors
import com.nuvio.tv.ui.screens.settings.settingsRowShape
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun CodeSection(
    code: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
    slotHeight: Dp = 48.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        AnimatedContent(
            targetState = connected,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "partyCodeLabel",
        ) { isConnected ->
            if (isConnected) {
                Text(
                    text = stringResource(R.string.party_code_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextTertiary,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot()
                    Text(
                        text = stringResource(R.string.party_reconnecting),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.Secondary,
                    )
                }
            }
        }
        CodeSlots(code = code, slotHeight = slotHeight)
    }
}

@Composable
internal fun MembersSection(
    party: ActiveParty,
    ringColor: Color,
    onRemove: (PartyMember) -> Unit,
    modifier: Modifier = Modifier,
    selfBehindMs: Long? = null,
) {
    val initialIds = remember { party.members.map { it.id }.toSet() }
    var memberToRemove by remember { mutableStateOf<PartyMember?>(null) }
    var inviting by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.party_seats, party.members.size, party.maxMembers),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextTertiary,
            )
            SeatDots(filled = party.members.size, total = party.maxMembers)
        }
        party.members.forEach { member ->
            key(member.id) {
                val appear = remember { MutableTransitionState(member.id in initialIds).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                ) {
                    MemberRow(
                        party = party,
                        member = member,
                        ringColor = ringColor,
                        onRemove = if (party.isHost && member.id != party.memberId) {
                            { memberToRemove = member }
                        } else {
                            null
                        },
                        selfBehindMs = selfBehindMs,
                    )
                }
            }
        }
        if (party.members.size < party.maxMembers) {
            PartyRow(onClick = { inviting = true }) {
                OpenSeat(size = 44.dp)
                Text(
                    text = stringResource(R.string.party_open_seat),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
    }

    memberToRemove?.let { member ->
        ConfirmDialog(
            title = stringResource(R.string.party_remove_title, member.displayName),
            message = stringResource(R.string.party_remove_message),
            confirmLabel = stringResource(R.string.party_remove),
            onConfirm = {
                memberToRemove = null
                onRemove(member)
            },
            onDismiss = { memberToRemove = null },
        )
    }
    if (inviting) {
        InviteDialog(code = party.code, link = party.inviteLink, onDismiss = { inviting = false })
    }
}

@Composable
private fun MemberRow(
    party: ActiveParty,
    member: PartyMember,
    ringColor: Color,
    onRemove: (() -> Unit)?,
    selfBehindMs: Long?,
) {
    val content: @Composable RowScope.() -> Unit = {
        PartyAvatar(
            member = member,
            size = 44.dp,
            presence = party.presenceState(member),
            ringColor = ringColor,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (member.id == party.memberId) {
                        "${member.displayName} (${stringResource(R.string.party_you)})"
                    } else {
                        member.displayName
                    },
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (member.id == party.hostMemberId) {
                    Text(
                        text = stringResource(R.string.party_host),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.Secondary,
                    )
                }
            }
            val behindMs = if (member.id == party.memberId) selfBehindMs else party.behindMs(member)
            val status = if (behindMs != null) {
                stringResource(R.string.party_behind, formatPlaybackTime(behindMs))
            } else {
                stringResource(party.statusRes(member))
            }
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 })
                },
                label = "partyMemberStatus",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                )
            }
        }
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.party_remove),
                tint = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (onRemove != null) {
        PartyRow(onClick = onRemove, content = content)
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun PartyRow(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = settingsRowColors(),
        border = settingsRowBorder(),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun SeatDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        repeat(total) { index ->
            val color by animateColorAsState(
                targetValue = if (index < filled) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border,
                animationSpec = tween(300),
                label = "seatDot",
            )
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
internal fun HostSettings(
    party: ActiveParty,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    sharingNote: String? = null,
) {
    Column {
        SettingsToggleRow(
            title = stringResource(R.string.party_host_only_title),
            subtitle = stringResource(R.string.party_host_only_description),
            checked = party.hostOnly,
            onToggle = { onHostOnlyChange(!party.hostOnly) },
        )
        SettingsToggleRow(
            title = stringResource(R.string.party_share_title),
            subtitle = stringResource(R.string.party_share_description),
            checked = party.sharing,
            onToggle = { onSharingChange(!party.sharing) },
            expandSubtitleOnFocus = true,
        )
        if (party.sharing && sharingNote != null) SettingsNote(sharingNote)
    }
}

@Composable
internal fun HostOnlyNote(modifier: Modifier = Modifier) {
    PartyHint(
        icon = Icons.Rounded.Lock,
        text = stringResource(R.string.party_host_only_note),
        modifier = modifier,
    )
}

@Composable
private fun PartyHint(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
    }
}

@Composable
internal fun PartyActions(
    party: ActiveParty,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    Column {
        SettingsActionRow(
            title = stringResource(R.string.party_leave),
            subtitle = null,
            onClick = onLeave,
            leadingIcon = Icons.AutoMirrored.Rounded.Logout,
        )
        if (party.isHost && party.members.size > 1) {
            SettingsActionRow(
                title = stringResource(R.string.party_end),
                subtitle = null,
                onClick = { confirmEnd = true },
                leadingIcon = Icons.Rounded.StopCircle,
            )
        }
        PartyHint(
            icon = Icons.Rounded.Smartphone,
            text = stringResource(R.string.party_phone_chat_note),
        )
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = stringResource(R.string.party_end_title),
            message = stringResource(R.string.party_end_message),
            confirmLabel = stringResource(R.string.party_end),
            onConfirm = {
                confirmEnd = false
                onEnd()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = message,
    ) {
        SettingsDialogActionRow {
            Box(modifier = Modifier.focusRequester(cancelFocusRequester)) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
            }
            SettingsDialogActionButton(
                text = confirmLabel,
                onClick = onConfirm,
                primary = true,
            )
        }
    }
}

@Composable
private fun InviteDialog(
    code: String,
    link: String,
    onDismiss: () -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    val qr = remember(link) {
        runCatching { QrCodeGenerator.generate(link, 420, margin = 1) }.getOrNull()
    }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.party_open_seat),
        width = 640.dp,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.party_invite_qr),
                    modifier = Modifier
                        .size(168.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.md)),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.party_invite_scan),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                )
                CodeSlots(code = code, slotHeight = 52.dp, textStyle = MaterialTheme.typography.headlineMedium)
                Text(
                    text = stringResource(R.string.party_invite_share),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        SettingsDialogActionRow {
            Box(modifier = Modifier.focusRequester(closeFocusRequester)) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                    primary = true,
                )
            }
        }
    }
}
