package com.nuvio.tv.ui.screens.party

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PARTY_CODE_LENGTH
import com.nuvio.tv.core.party.PartyError
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyPreview
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.isValidPartyCode
import com.nuvio.tv.core.party.normalizePartyCode
import com.nuvio.tv.core.party.partyErrorOf
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsHeaderInset
import com.nuvio.tv.ui.screens.stream.StreamBackdrop
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PartyScreenMode {
    Join,
    Joined,
    Party,
}

@Composable
fun PartyScreen(
    initialCode: String,
    onBackPress: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
    viewModel: PartyViewModel = hiltViewModel(),
) {
    val repository = viewModel.party
    val state by repository.state.collectAsStateWithLifecycle()
    val party = state.party
    var joined by remember { mutableStateOf<ActiveParty?>(null) }
    var pendingCode by remember {
        mutableStateOf(initialCode.takeIf { isValidPartyCode(it) && normalizePartyCode(it) != party?.code }.orEmpty())
    }
    var joining by remember { mutableStateOf(party == null || pendingCode.isNotEmpty()) }
    val mode = when {
        joined != null -> PartyScreenMode.Joined
        party != null && !joining -> PartyScreenMode.Party
        else -> PartyScreenMode.Join
    }
    val shown = joined ?: party
    var previewMedia by remember { mutableStateOf<PartyMedia?>(null) }
    val backdropMedia = if (mode == PartyScreenMode.Join) previewMedia else shown?.media

    BackHandler { onBackPress() }

    LaunchedEffect(joined) {
        val current = joined ?: return@LaunchedEffect
        delay(1600)
        onWatch(current.media)
        joined = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = backdropMedia?.let { it.background ?: it.poster },
            animationSpec = tween(NuvioMotion.tokens.durations.hero),
            label = "partyBackdrop",
        ) { image ->
            if (image != null) StreamBackdrop(backdrop = image, isLoading = false)
        }
        SettingsStandaloneScaffold(
            title = stringResource(R.string.party_title),
            subtitle = stringResource(R.string.party_settings_description),
            classicContainer = false,
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.party_title),
                subtitle = shown
                    ?.takeIf { mode != PartyScreenMode.Join }
                    ?.member(shown.hostMemberId)
                    ?.displayName
                    ?.let { stringResource(R.string.party_hosted_by, it) }
                    ?: stringResource(R.string.party_settings_description),
            )
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(140))
                },
                label = "partyScreenMode",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = settingsHeaderInset()),
            ) { target ->
                when (target) {
                    PartyScreenMode.Join -> JoinContent(
                        repository = repository,
                        initialCode = pendingCode,
                        onPreview = { previewMedia = it },
                        onJoined = { newParty ->
                            pendingCode = ""
                            joining = false
                            joined = newParty
                        },
                    )
                    PartyScreenMode.Joined -> shown?.let { JoinedContent(it) }
                    PartyScreenMode.Party -> party?.let { PartyContent(repository, it, onWatch) }
                }
            }
        }
    }
}

@Composable
private fun JoinContent(
    repository: PartyRepository,
    initialCode: String,
    onPreview: (PartyMedia?) -> Unit,
    onJoined: (ActiveParty) -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val available by repository.available.collectAsStateWithLifecycle()
    val canUse by repository.canUse.collectAsStateWithLifecycle()
    val profile by repository.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val firstKeyFocusRequester = remember { FocusRequester() }
    val submitFocusRequester = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val prefilled = remember { isValidPartyCode(initialCode) }
    var code by remember {
        mutableStateOf(if (prefilled) "" else normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH))
    }
    var error by remember { mutableStateOf<PartyError?>(null) }
    var preview by remember { mutableStateOf<PartyPreview?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val blockedError = when {
        !available -> PartyError.Unavailable
        !canUse -> PartyError.SignInRequired
        else -> null
    }
    val shownError = blockedError ?: error

    suspend fun reject(result: PartyError) {
        error = result
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 420
                -14f at 60
                12f at 120
                -8f at 180
                6f at 240
                -3f at 300
            },
        )
    }

    fun submit() {
        if (!isValidPartyCode(code) || state.busy || blockedError != null) return
        scope.launch {
            error = null
            val result = repository.join(code)
            val joinedParty = repository.state.value.party
            if (result == null && joinedParty != null) {
                onJoined(joinedParty)
            } else {
                reject(result ?: PartyError.Network)
            }
        }
    }

    fun lookUp(target: String) {
        previewJob?.cancel()
        previewing = true
        previewJob = scope.launch {
            val result = repository.preview(target)
            previewing = false
            val failure = partyErrorOf(result.error)
            when {
                failure == null && result.media != null -> {
                    preview = result
                    if (result.isFull) error = PartyError.Full
                    submitFocusRequester.requestFocusAfterFrames()
                }
                failure != null && failure != PartyError.Network -> reject(failure)
            }
        }
    }

    fun changeCode(value: String) {
        if (state.busy || value == code) return
        code = value
        error = null
        preview = null
        previewJob?.cancel()
        previewing = false
        if (isValidPartyCode(value)) lookUp(value)
    }

    LaunchedEffect(Unit) {
        if (blockedError != null) return@LaunchedEffect
        if (prefilled) {
            val target = normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH)
            delay(250)
            target.indices.forEach { index ->
                delay(60)
                code = target.take(index + 1)
            }
            lookUp(target)
        }
        firstKeyFocusRequester.requestFocusAfterFrames()
    }

    LaunchedEffect(preview) { onPreview(preview?.media) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            AnimatedContent(
                targetState = preview,
                contentKey = { it != null },
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { it / 10 }) togetherWith fadeOut(tween(140))
                },
                label = "partyJoinPreview",
            ) { current ->
                if (current != null) {
                    PreviewCard(current)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                        SeatsHero(
                            profile = profile,
                            size = 64.dp,
                            ringColor = NuvioTheme.colors.Background,
                            modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs),
                        )
                        Text(
                            text = stringResource(R.string.party_join_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = NuvioTheme.colors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.party_join_description),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                }
            }
            CodeSlots(
                code = code,
                activeIndex = if (blockedError == null && !state.busy) code.length.coerceAtMost(PARTY_CODE_LENGTH - 1) else null,
                textStyle = MaterialTheme.typography.headlineLarge,
                slotHeight = 64.dp,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xs)
                    .graphicsLayer { translationX = shake.value.dp.toPx() },
            )
            AnimatedContent(
                targetState = shownError,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 }) togetherWith fadeOut(tween(120))
                },
                label = "partyJoinError",
            ) { current ->
                Text(
                    text = current?.let { stringResource(it.messageRes) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.Error,
                    minLines = 1,
                )
            }
            ExperimentalNote()
            AnimatedVisibility(
                visible = preview == null,
                enter = fadeIn(tween(NuvioMotion.tokens.durations.fast)),
                exit = fadeOut(tween(NuvioMotion.tokens.durations.quick)),
            ) {
                Text(
                    text = stringResource(R.string.party_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                )
            }
        }
        CodeKeypad(
            enabled = blockedError == null,
            canSubmit = isValidPartyCode(code) &&
                error !in setOf(PartyError.NotFound, PartyError.Removed, PartyError.RateLimited),
            busy = state.busy || previewing,
            onKey = { character -> if (code.length < PARTY_CODE_LENGTH) changeCode(code + character) },
            onDelete = { changeCode(code.dropLast(1)) },
            onSubmit = ::submit,
            firstKeyFocusRequester = firstKeyFocusRequester,
            submitFocusRequester = submitFocusRequester,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewCard(preview: PartyPreview) {
    val media = preview.media ?: return
    val host = preview.host
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
        MediaHeader(media)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            if (host != null) {
                PartyAvatar(member = host, size = NuvioTheme.sizes.avatars.sm)
                Text(
                    text = stringResource(R.string.party_hosted_by, host.displayName),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.party_seats, preview.members, preview.maxMembers),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
    }
}

@Composable
private fun MediaHeader(media: PartyMedia) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        MediaTitle(media)
        media.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaTitle(media: PartyMedia, centered: Boolean = false) {
    var logoFailed by remember(media.logo) { mutableStateOf(false) }
    val logo = media.logo?.takeIf { it.isNotBlank() && !logoFailed }
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = media.title,
                contentScale = ContentScale.Fit,
                alignment = if (centered) Alignment.Center else Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(100.dp),
            )
        } else if (media.title.isNotBlank()) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                textAlign = textAlign,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        media.episodeLabel()?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JoinedContent(party: ActiveParty) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.CenterVertically),
    ) {
        AvatarStack(
            members = party.members,
            size = NuvioTheme.sizes.avatars.lg,
            animateIn = true,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
        )
        Text(
            text = stringResource(R.string.party_joined_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        MediaTitle(
            media = party.media,
            centered = true,
        )
    }
}

@Composable
private fun PartyContent(
    repository: PartyRepository,
    party: ActiveParty,
    onWatch: (PartyMedia) -> Unit,
) {
    val listState = rememberLazyListState()
    val watchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { watchFocusRequester.requestFocusAfterFrames() }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxl),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        ) {
            MediaHeader(party.media)
            CodeSection(
                code = party.code,
                connected = party.connected,
                slotHeight = 52.dp,
            )
        }
        Box(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                item(key = "party_actions") {
                    SettingsGroupCard {
                        SettingsActionRow(
                            title = stringResource(R.string.party_watch_with_party),
                            subtitle = null,
                            onClick = { onWatch(party.media) },
                            leadingIcon = Icons.Rounded.PlayArrow,
                            modifier = Modifier.focusRequester(watchFocusRequester),
                        )
                        PartyActions(
                            party = party,
                            onLeave = repository::leave,
                            onEnd = repository::end,
                        )
                    }
                }
                item(key = "party_members") {
                    SettingsGroupCard {
                        MembersSection(
                            party = party,
                            ringColor = NuvioTheme.colors.BackgroundCard,
                            onRemove = { member -> repository.removeMember(member.id) },
                        )
                        if (!party.isHost && party.hostOnly) HostOnlyNote()
                    }
                }
                if (party.isHost) {
                    item(key = "party_host_settings") {
                        SettingsGroupCard {
                            HostSettings(
                                party = party,
                                onHostOnlyChange = repository::setHostOnly,
                                onSharingChange = repository::setSharing,
                            )
                        }
                    }
                }
            }
            SettingsVerticalScrollIndicators(state = listState)
        }
    }
}
