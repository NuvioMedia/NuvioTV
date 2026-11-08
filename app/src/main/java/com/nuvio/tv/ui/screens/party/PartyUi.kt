package com.nuvio.tv.ui.screens.party

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PartyError
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyMember
import com.nuvio.tv.core.party.PartyMemberStatus
import com.nuvio.tv.core.party.PartyPresence
import com.nuvio.tv.core.party.PartyProfile
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

internal enum class PartyPresenceState {
    Watching,
    Busy,
    Away,
    Offline,
}

@Composable
internal fun PartyAvatar(
    member: PartyMember,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    presence: PartyPresenceState? = null,
    ringColor: Color = NuvioTheme.colors.Background,
) {
    PartyAvatar(
        name = member.displayName,
        imageUrl = member.avatarUrl,
        color = member.avatarColor,
        modifier = modifier,
        size = size,
        presence = presence,
        ringColor = ringColor,
    )
}

@Composable
internal fun PartyAvatar(
    name: String,
    imageUrl: String?,
    color: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    presence: PartyPresenceState? = null,
    ringColor: Color = NuvioTheme.colors.Background,
) {
    Box(modifier = modifier.size(size)) {
        ProfileAvatarCircle(
            name = name,
            colorHex = color.orEmpty(),
            size = size,
            avatarImageUrl = imageUrl,
        )
        if (presence != null) {
            PresenceDot(
                state = presence,
                ringColor = ringColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f),
            )
        }
    }
}

@Composable
private fun PresenceDot(
    state: PartyPresenceState,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val accent = NuvioTheme.colors.Secondary
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (state == PartyPresenceState.Busy) {
            val transition = rememberInfiniteTransition(label = "presence")
            val pulse by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
                label = "presencePulse",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f + pulse
                        scaleY = 1f + pulse
                        alpha = 1f - pulse
                    }
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(ringColor),
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .clip(CircleShape)
                .then(
                    when (state) {
                        PartyPresenceState.Watching, PartyPresenceState.Busy -> Modifier.background(accent)
                        PartyPresenceState.Away -> Modifier.background(NuvioTheme.colors.TextTertiary)
                        PartyPresenceState.Offline -> Modifier.border(NuvioTheme.strokes.thin, NuvioTheme.colors.TextTertiary, CircleShape)
                    },
                ),
        )
    }
}

@Composable
internal fun AvatarStack(
    members: List<PartyMember>,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color = NuvioTheme.colors.Background,
    animateIn: Boolean = false,
) {
    val shown = members.take(3)
    val extra = members.size - shown.size
    val slots = shown.size + if (extra > 0) 1 else 0
    val step = size * 0.7f
    Box(modifier = modifier.width(size + step * (slots - 1).coerceAtLeast(0))) {
        shown.forEachIndexed { index, member ->
            key(member.id) {
                StackSlot(index = index, slots = slots, step = step, ringColor = ringColor, animateIn = animateIn) {
                    PartyAvatar(member = member, size = size)
                }
            }
        }
        if (extra > 0) {
            StackSlot(index = shown.size, slots = slots, step = step, ringColor = ringColor, animateIn = animateIn) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(NuvioTheme.colors.BackgroundCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$extra",
                        color = NuvioTheme.colors.TextPrimary,
                        fontSize = (size.value * 0.36f).sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StackSlot(
    index: Int,
    slots: Int,
    step: Dp,
    ringColor: Color,
    animateIn: Boolean,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(if (animateIn) 0f else 1f) }
    if (animateIn) {
        LaunchedEffect(Unit) {
            delay(index * 90L)
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
    }
    Box(
        modifier = Modifier
            .offset(x = step * index)
            .zIndex((slots - index).toFloat())
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .border(NuvioTheme.strokes.medium, ringColor, CircleShape),
    ) {
        content()
    }
}

@Composable
internal fun OpenSeat(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val outline = NuvioTheme.colors.TextTertiary
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color = outline,
                    radius = this.size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(
                        width = NuvioTheme.strokes.thin.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
internal fun SeatsHero(
    profile: PartyProfile,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val step = size * 0.72f
    Box(modifier = modifier.width(size + step * 2)) {
        OpenSeat(size = size, modifier = Modifier.offset(x = step * 2))
        OpenSeat(size = size, modifier = Modifier.offset(x = step))
        PartyAvatar(
            name = profile.name,
            imageUrl = profile.imageUrl,
            color = profile.color,
            size = size,
            modifier = Modifier
                .zIndex(1f)
                .border(NuvioTheme.strokes.heavy, ringColor, CircleShape),
        )
    }
}

@Composable
internal fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulsingDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulsingDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(NuvioTheme.colors.Secondary),
    )
}

@Composable
internal fun PartyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = modifier,
    )
}

@Composable
internal fun ExperimentalNote() {
    Text(
        text = stringResource(R.string.party_experimental),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary,
    )
}

private fun ActiveParty.presenceOf(member: PartyMember): PartyPresence? =
    presence[member.id] ?: PartyPresence(memberId = member.id).takeIf { member.id == memberId }

internal fun ActiveParty.presenceState(member: PartyMember): PartyPresenceState {
    val presence = presenceOf(member) ?: return PartyPresenceState.Offline
    if (presence.mediaKey != media.key) return PartyPresenceState.Away
    return when (presence.status) {
        PartyMemberStatus.Ready -> PartyPresenceState.Watching
        PartyMemberStatus.Loading, PartyMemberStatus.Buffering -> PartyPresenceState.Busy
        PartyMemberStatus.Browsing, PartyMemberStatus.Behind -> PartyPresenceState.Away
    }
}

internal fun ActiveParty.behindMs(member: PartyMember): Long? =
    presenceOf(member)
        ?.takeIf { it.status == PartyMemberStatus.Behind && it.mediaKey == media.key }
        ?.let { it.behindMs ?: 0L }

@get:StringRes
internal val PartyError.messageRes: Int
    get() = when (this) {
        PartyError.NotFound -> R.string.party_error_not_found
        PartyError.Full -> R.string.party_error_full
        PartyError.Removed -> R.string.party_error_removed
        PartyError.Ended -> R.string.party_error_ended
        PartyError.RateLimited -> R.string.party_error_rate_limited
        PartyError.SignInRequired -> R.string.party_sign_in_required
        PartyError.Unavailable -> R.string.party_unavailable
        PartyError.Network -> R.string.party_error_network
    }

@StringRes
internal fun ActiveParty.statusRes(member: PartyMember): Int {
    val presence = presenceOf(member) ?: return R.string.party_status_offline
    if (presence.mediaKey != media.key) {
        return if (presence.status == PartyMemberStatus.Browsing) {
            R.string.party_status_choosing
        } else {
            R.string.party_status_elsewhere
        }
    }
    return when (presence.status) {
        PartyMemberStatus.Ready, PartyMemberStatus.Behind -> R.string.party_status_ready
        PartyMemberStatus.Buffering -> R.string.party_status_buffering
        PartyMemberStatus.Loading -> R.string.party_status_loading
        PartyMemberStatus.Browsing -> R.string.party_status_choosing
    }
}

@Composable
internal fun PartyMedia.episodeLabel(): String? {
    val season = season ?: return null
    val episode = episode ?: return null
    val label = stringResource(R.string.party_episode_format, season, episode)
    return episodeTitle?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
}

@Composable
internal fun PartyMedia.displayTitle(): String {
    val season = season
    val episode = episode
    if (season == null || episode == null) return title
    val label = stringResource(R.string.party_episode_format, season, episode)
    return if (title.isBlank()) label else "$title · $label"
}
