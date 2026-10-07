package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Frame width as a share of the width available (the progress bar's), clamped for a 10-foot
 * screen: about 260 dp on a typical 960 dp wide TV, never smaller than 240 dp nor wider than
 * 400 dp on large layouts.
 */
private const val FrameWidthFraction = 0.30f
private val MinFrameWidth = 240.dp
private val MaxFrameWidth = 400.dp
private val FrameGapAboveBar = 10.dp
private const val LingerAfterScrubMs = 1500L
private const val SlideMs = 220

/**
 * The scrub-time preview, Netflix style, above the progress bar: the frame of the cue the scrub
 * lands on in the middle, outlined, with the frames before and after it either side (see
 * [SeekPreviewFilmstripRow]).
 *
 * Grid-locked scrubbing (see [SeekPreviewCueStepper]) parks the playhead on the centre frame's
 * own timestamp, so the frame outlined is the frame playback resumes on, and the controls' own
 * time readout stays the single, honest position label.
 */
@Composable
fun SeekPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val seekPreview = viewModel.seekPreview
    val track by seekPreview.previewTrack.collectAsStateWithLifecycle()
    val offsetState by seekPreview.offsetMs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()
    val activeTrack = track

    val previewTs = uiState.pendingPreviewSeekPosition
    val scrubActive = previewTs != null || uiState.showSeekOverlay

    // Prevent flicker between repeated seek inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scrubActive) {
        if (scrubActive) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val displayTs = previewTs ?: timeline.currentPosition
    val offsetMs = offsetState.toLong()
    // On-device tracks fill in while playing; a new revision means the frame may have sharpened.
    val revision by (activeTrack?.revision ?: NoRevision).collectAsStateWithLifecycle()
    var strip by remember(activeTrack) { mutableStateOf<Filmstrip?>(null) }
    // In frames: how far the strip still has to slide to settle on its centre.
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Conflate rapid scrub/nudge changes: a lookup that finishes takes the latest request next.
    val requestFlow = remember(activeTrack) {
        MutableStateFlow(PreviewRequest(displayTs, offsetMs, revision, lingerVisible))
    }
    LaunchedEffect(activeTrack, displayTs, offsetMs, revision, lingerVisible) {
        requestFlow.value = PreviewRequest(displayTs, offsetMs, revision, lingerVisible)
    }
    LaunchedEffect(activeTrack) {
        if (activeTrack == null) {
            seekPreview.onPreviewCueResolved(null)
            return@LaunchedEffect
        }
        // The host stays composed while the controls are up, so the playhead alone would
        // re-read the frame every progress tick for a frame that cannot have changed.
        // Caching the *inputs* to the re-centring decision below — the covering cue and which
        // side of its midpoint the position falls — replays that decision exactly, so the
        // cache expires precisely when the frame is due to hand over to its successor. New
        // on-device frames only matter while the preview is on screen.
        var cachedCovering: SeekPreviewCue? = null
        var cachedPrefersSuccessor = false
        var cachedOffsetMs: Long? = null
        var cachedRevision: Int? = null
        // Neighbours load on their own, so the next scrub step never waits for them.
        var fillJob: Job? = null
        // Each lookup runs to the end, then the latest request is taken (the StateFlow conflates
        // the ones in between). Cancelling on every new request starved a held D-pad scrub: each
        // repeat cancelled the lookup in flight, so the frame stayed on an old one until release.
        requestFlow.collect { (positionMs, offset, rev, visible) ->
            val covering = cachedCovering
            if (offset == cachedOffsetMs &&
                (rev == cachedRevision || !visible) &&
                covering != null &&
                covering.contains(positionMs) &&
                covering.prefersSuccessorFor(positionMs) == cachedPrefersSuccessor
            ) {
                // Shown again on the same frame: load the neighbours skipped while hidden.
                val current = strip
                if (visible && current != null && fillJob?.isActive != true && current.frames.any { it == null }) {
                    fillJob = scope.launch {
                        val filled = activeTrack.fillFilmstrip(current, refreshAll = false)
                        if (strip === current) strip = filled
                    }
                }
                return@collect
            }
            // Single writer for the track's offset: the manual sync correction is pushed in
            // right before the lookup so a nudge is reflected on the very next frame.
            activeTrack.offsetMs = offset
            // Only overwrite on success — keeps the last good frame visible during a fetch.
            val coveringThumbnail = activeTrack.thumbnailFor(positionMs) ?: return@collect
            // Cue times arrive on the preview timeline; undo the sync offset so they can be
            // compared with, and assigned to, playback positions.
            val coveringCue = SeekPreviewCue(
                startMs = coveringThumbnail.cueStartMs - offset,
                endMs = coveringThumbnail.cueEndMs - offset
            )

            // A cue's frame is captured at its start, so past the halfway mark the next cue's
            // frame is the closer one.
            val prefersSuccessor = coveringCue.prefersSuccessorFor(positionMs)
            val successor = if (prefersSuccessor) {
                activeTrack.thumbnailFor(coveringCue.endMs)
                    ?.takeIf { it.cueStartMs != coveringThumbnail.cueStartMs }
            } else {
                null
            }
            cachedCovering = coveringCue
            cachedPrefersSuccessor = prefersSuccessor
            cachedOffsetMs = offset
            cachedRevision = rev

            val center = successor ?: coveringThumbnail
            val previous = strip
            val steps = previous?.stepsTo(center.cueStartMs)
            val reusable = previous != null && previous.matches(rev, offset)
            val seeded = Filmstrip.around(center, previous, steps, rev, offset)
            strip = seeded
            seekPreview.onPreviewCueResolved(
                SeekPreviewCue(center.cueStartMs - offset, center.cueEndMs - offset)
            )
            if (!visible) {
                scope.launch { slide.snapTo(0f) }
            } else if (steps != null && steps != 0) {
                scope.launch {
                    val start = (slide.value + steps)
                        .coerceIn(-FilmstripSideFrames.toFloat(), FilmstripSideFrames.toFloat())
                    slide.snapTo(start)
                    slide.animateTo(0f, tween(SlideMs, easing = FastOutSlowInEasing))
                }
            } else if (steps == null) {
                scope.launch { slide.snapTo(0f) }
            }
            // Neighbours only matter while the strip is on screen; hidden, only the centre's
            // cue is kept current for grid-locked seeking.
            fillJob?.cancel()
            fillJob = if (visible) {
                scope.launch {
                    val filled = activeTrack.fillFilmstrip(seeded, refreshAll = !reusable)
                    if (strip === seeded) strip = filled
                }
            } else {
                null
            }
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && activeTrack != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val frameWidth = (maxWidth * FrameWidthFraction)
                .coerceIn(MinFrameWidth, MaxFrameWidth)
                .coerceAtMost(maxWidth)
            strip?.let { current ->
                SeekPreviewFilmstripRow(
                    strip = current,
                    slide = slide,
                    frameWidth = frameWidth,
                    modifier = Modifier.padding(bottom = FrameGapAboveBar)
                )
            }
        }
    }
}

private data class PreviewRequest(
    val positionMs: Long,
    val offsetMs: Long,
    val revision: Int,
    val visible: Boolean
)

private val NoRevision: StateFlow<Int> = MutableStateFlow(0)
