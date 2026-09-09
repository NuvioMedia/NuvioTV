package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.RandomEpisodePicker
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.theme.NuvioMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class RandomEpisodeFocusTarget {
    CLOSE,
    UNWATCHED,
    INCLUDE_WATCHED,
    PLAY
}

@Composable
internal fun RandomEpisodeOverlay(
    meta: Meta,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeProgress: Map<Pair<Int, Int>, WatchProgress>,
    blurUnwatchedEpisodes: Boolean,
    onDismiss: () -> Unit,
    onPlay: (Video) -> Unit
) {
    val picker by produceState<RandomEpisodePicker?>(null, meta.videos, watchedEpisodes, episodeProgress) {
        val updatedPicker = withContext(Dispatchers.Default) {
            RandomEpisodePicker(meta, watchedEpisodes, episodeProgress)
        }
        updatedPicker.inheritHistoryFrom(value)
        value = updatedPicker
    }
    var selectedEpisode by remember { mutableStateOf<Video?>(null) }
    var includeWatched by remember { mutableStateOf(false) }
    val choiceFocusRequester = remember { FocusRequester() }
    val playFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    var settledFocusTarget by remember { mutableStateOf<RandomEpisodeFocusTarget?>(null) }
    var acceptsSelectKey by remember { mutableStateOf(false) }
    val compact = LocalConfiguration.current.screenHeightDp < 600
    val background = remember {
        Brush.linearGradient(listOf(Color(0xFF050505), Color(0xFF101010), Color(0xFF181818)))
    }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val entrance = rememberTransition(visibility, label = "randomEpisodeVisibility")
    val opacity by entrance.animateFloat(
        transitionSpec = {
            if (targetState) tween(240) else tween(140)
        },
        label = "overlayOpacity"
    ) { if (it) 1f else 0f }
    val contentScale by entrance.animateFloat(
        transitionSpec = {
            if (targetState) tween(240, easing = NuvioMotion.tokens.easings.emphasized)
            else tween(140, easing = NuvioMotion.tokens.easings.accelerate)
        },
        label = "overlayDepth"
    ) { if (it) 1f else 0.99f }
    val closing = !visibility.targetState
    val dismiss = { visibility.targetState = false }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val isResult = selectedEpisode != null
    val primaryFocusRequester = if (isResult) playFocusRequester else choiceFocusRequester
    val stepProgress by animateFloatAsState(
        targetValue = if (isResult) 1f else 0f,
        animationSpec = tween(180, easing = NuvioMotion.tokens.easings.emphasized),
        label = "randomEpisodeProgress"
    )
    val readyPicker = picker
    val focusTarget = when {
        readyPicker == null || readyPicker.count(true) == 0 -> RandomEpisodeFocusTarget.CLOSE
        isResult -> RandomEpisodeFocusTarget.PLAY
        readyPicker.count(false) > 0 -> RandomEpisodeFocusTarget.UNWATCHED
        else -> RandomEpisodeFocusTarget.INCLUDE_WATCHED
    }

    LaunchedEffect(picker) {
        selectedEpisode = selectedEpisode?.let { picker?.find(it.id, includeWatched) }
    }
    LaunchedEffect(focusTarget, picker != null, closing) {
        if (picker == null || closing) return@LaunchedEffect
        if (focusTarget == RandomEpisodeFocusTarget.CLOSE) {
            closeFocusRequester.requestFocusAfterFrames(frames = 0)
        } else {
            primaryFocusRequester.requestFocusAfterFrames(frames = 0)
        }
        settledFocusTarget = focusTarget
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) currentOnDismiss()
    }

    Dialog(
        onDismissRequest = {
            if (!closing) {
                if (isResult) selectedEpisode = null else dismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = opacity }
                .background(background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                    }
                    .onPreviewKeyEvent { event ->
                        if (closing) return@onPreviewKeyEvent true
                        val native = event.nativeKeyEvent
                        if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                acceptsSelectKey = true
                            }
                            if (!acceptsSelectKey) return@onPreviewKeyEvent true
                        }
                        false
                    }
                    .padding(horizontal = 48.dp, vertical = if (compact) 24.dp else 40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.random_episode_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(meta.name, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RandomEpisodeButton(
                        text = stringResource(R.string.action_close),
                        icon = Icons.Default.Close,
                        onClick = dismiss,
                        modifier = Modifier.focusRequester(closeFocusRequester).focusProperties {
                            canFocus = !closing && readyPicker != null &&
                                (focusTarget == RandomEpisodeFocusTarget.CLOSE || settledFocusTarget == focusTarget)
                            up = FocusRequester.Cancel
                            if (readyPicker != null && readyPicker.count(true) > 0) down = primaryFocusRequester
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        stringResource(R.string.random_episode_step_choose),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.graphicsLayer { alpha = 0.9f - stepProgress * 0.5f }
                    )
                    Text(
                        stringResource(R.string.random_episode_step_play),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.graphicsLayer { alpha = 0.4f + stepProgress * 0.5f }
                    )
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        readyPicker == null -> Text(stringResource(R.string.random_episode_loading), color = Color.White)
                        readyPicker.count(true) == 0 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.random_episode_empty_title), style = MaterialTheme.typography.headlineLarge, color = Color.White)
                            Text(stringResource(R.string.random_episode_empty_subtitle), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.65f))
                        }
                        else -> RandomEpisodeStep(selectedEpisode, Modifier.fillMaxSize()) { episode ->
                            val interactive = !closing && (episode != null) == isResult
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}),
                                contentAlignment = Alignment.Center
                            ) {
                                if (episode == null) {
                                    RandomEpisodeChoices(
                                        picker = readyPicker,
                                        interactive = interactive,
                                        primaryFocusRequester = choiceFocusRequester,
                                        closeFocusRequester = closeFocusRequester,
                                        onChoose = { include ->
                                            includeWatched = include
                                            selectedEpisode = readyPicker.pick(include)
                                        }
                                    )
                                } else {
                                    RandomEpisodeResult(
                                        episode = episode,
                                        isWatched = readyPicker.isWatched(episode),
                                        hideArtwork = blurUnwatchedEpisodes && !readyPicker.isWatched(episode),
                                        count = readyPicker.count(includeWatched),
                                        includeWatched = includeWatched,
                                        interactive = interactive,
                                        primaryFocusRequester = playFocusRequester,
                                        closeFocusRequester = closeFocusRequester,
                                        onPlay = { onPlay(episode) },
                                        onPickAgain = { selectedEpisode = readyPicker.pick(includeWatched) },
                                        onChangeSelection = { selectedEpisode = null }
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(if (isResult) R.string.random_episode_back_to_choices_hint else R.string.random_episode_back_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun RandomEpisodeChoices(
    picker: RandomEpisodePicker,
    interactive: Boolean,
    primaryFocusRequester: FocusRequester,
    closeFocusRequester: FocusRequester,
    onChoose: (Boolean) -> Unit
) {
    val unwatchedCount = picker.count(false)
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.random_episode_choose_title), style = MaterialTheme.typography.displayMedium, color = Color.White)
            Text(stringResource(R.string.random_episode_choose_subtitle), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
            Text(stringResource(R.string.random_episode_rules), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            RandomEpisodeButton(
                text = stringResource(R.string.random_episode_unwatched),
                subtitle = stringResource(R.string.random_episode_unwatched_sub),
                detail = pluralStringResource(R.plurals.random_episode_count, unwatchedCount, unwatchedCount),
                enabled = unwatchedCount > 0,
                interactive = interactive,
                onClick = { onChoose(false) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (unwatchedCount > 0) Modifier.focusRequester(primaryFocusRequester) else Modifier)
                    .focusProperties { up = closeFocusRequester }
            )
            RandomEpisodeButton(
                text = stringResource(R.string.random_episode_include_watched),
                subtitle = stringResource(R.string.random_episode_include_watched_sub),
                detail = pluralStringResource(R.plurals.random_episode_count, picker.count(true), picker.count(true)),
                interactive = interactive,
                onClick = { onChoose(true) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (unwatchedCount == 0) Modifier.focusRequester(primaryFocusRequester) else Modifier)
                    .focusProperties {
                        down = FocusRequester.Cancel
                        if (unwatchedCount == 0) up = closeFocusRequester
                    }
            )
            if (unwatchedCount == 0) {
                Text(stringResource(R.string.random_episode_caught_up), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun RandomEpisodeStep(
    episode: Video?,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(Video?) -> Unit
) {
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
    val layoutDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = episode,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        contentKey = { it != null },
        transitionSpec = {
            val direction = (if (targetState != null) 1 else -1) * layoutDirection
            ((fadeIn(tween(180, delayMillis = 50)) +
                slideInHorizontally(tween(260, easing = NuvioMotion.tokens.easings.emphasized)) { direction * distance }) togetherWith
                (fadeOut(tween(100)) +
                    slideOutHorizontally(tween(180, easing = NuvioMotion.tokens.easings.accelerate)) { -direction * distance / 2 }))
                .using(null)
        },
        label = "randomEpisodeStep",
        content = content
    )
}
