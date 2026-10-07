package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Frames either side of the centre: one in full and one cut off by the screen edge. */
internal const val FilmstripSideFrames = 2
internal const val FilmstripSize = FilmstripSideFrames * 2 + 1
internal val FilmstripGap = 12.dp
private const val CenterScale = 1.06f
private val FrameShape = RoundedCornerShape(8.dp)
private val CenterBorder = 2.dp

/** A filmstrip position whose lookup finished: [thumbnail] is null past the title's start or end. */
internal class FilmstripFrame(val thumbnail: SeekPreviewThumbnail?)

/**
 * The frames shown while scrubbing, oldest first, centre at [FilmstripSideFrames]. A null entry
 * is still being looked up and is not drawn yet.
 */
internal class Filmstrip(
    val frames: List<FilmstripFrame?>,
    val revision: Int,
    val offsetMs: Long,
) {
    val center: SeekPreviewThumbnail? get() = frames[FilmstripSideFrames]?.thumbnail

    /** True when every neighbour came from the same frames ([revision]) and offset. */
    fun matches(revision: Int, offsetMs: Long) = this.revision == revision && this.offsetMs == offsetMs

    /** How many places [cueStartMs] sits from the centre, or null when it is not in the strip. */
    fun stepsTo(cueStartMs: Long): Int? {
        val index = frames.indexOfFirst { it?.thumbnail?.cueStartMs == cueStartMs }
        return if (index < 0) null else index - FilmstripSideFrames
    }

    companion object {
        /**
         * The strip around a new [center], keeping the neighbours this strip already has when the
         * centre only moved along it ([steps] places), so a step needs one new frame, not four.
         */
        fun around(
            center: SeekPreviewThumbnail,
            previous: Filmstrip?,
            steps: Int?,
            revision: Int,
            offsetMs: Long,
        ): Filmstrip {
            val frames = List(FilmstripSize) { index ->
                when {
                    index == FilmstripSideFrames -> FilmstripFrame(center)
                    previous == null || steps == null -> null
                    else -> previous.frames.getOrNull(index + steps)
                }
            }
            return Filmstrip(frames, revision, offsetMs)
        }
    }
}

/**
 * Looks up the neighbours [strip] lacks — every neighbour when [refreshAll] — walking outwards
 * from the centre one cue at a time. Side lookups use [SeekPreviewTrack.sideThumbnailFor], so
 * they never pull on-device decoding away from the frame being scrubbed to.
 */
internal suspend fun SeekPreviewTrack.fillFilmstrip(strip: Filmstrip, refreshAll: Boolean): Filmstrip {
    val frames = strip.frames.toMutableList()
    val offset = strip.offsetMs
    for (direction in intArrayOf(1, -1)) {
        var from: SeekPreviewThumbnail? = strip.center ?: break
        for (distance in 1..FilmstripSideFrames) {
            val index = FilmstripSideFrames + direction * distance
            val edge = from
            if (edge == null) {
                // Past the title's start or end there is nothing more on this side.
                frames[index] = FilmstripFrame(null)
                continue
            }
            val known = frames[index]
            val frame = if (known != null && !refreshAll) {
                known
            } else {
                val probe = if (direction > 0) edge.cueEndMs - offset else edge.cueStartMs - offset - 1
                val found = sideThumbnailFor(probe)?.takeIf { candidate ->
                    // No progress means the title ends (or starts) here.
                    if (direction > 0) candidate.cueStartMs > edge.cueStartMs else candidate.cueStartMs < edge.cueStartMs
                }
                FilmstripFrame(found)
            }
            frames[index] = frame
            from = frame.thumbnail
        }
    }
    return Filmstrip(frames, strip.revision, strip.offsetMs)
}

/**
 * Netflix-style scrub previews: the frame being scrubbed to in the middle with a white outline,
 * the ones before and after it either side. When the centre moves along the strip, the strip
 * slides by that many frames ([slide], in frames) instead of jumping. The slide only moves
 * layers, so it costs no recomposition.
 */
@Composable
internal fun SeekPreviewFilmstripRow(
    strip: Filmstrip,
    slide: Animatable<Float, AnimationVector1D>,
    frameWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val frameHeight = frameWidth * 9f / 16f
    val stepPx = with(LocalDensity.current) { (frameWidth + FilmstripGap).toPx() }
    Box(modifier = modifier.fillMaxWidth().height(frameHeight * CenterScale)) {
        strip.frames.forEachIndexed { index, frame ->
            key(index) {
                // Nothing is drawn past a title's start or end, nor while a side frame is
                // still loading, so the strip never shows an empty black tile.
                if (frame?.thumbnail != null) {
                    val place = index - FilmstripSideFrames
                    val isCenter = place == 0
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(((place + slide.value) * stepPx).roundToInt(), 0) }
                            .size(frameWidth, frameHeight)
                            .then(
                                if (isCenter) Modifier.graphicsLayer {
                                    scaleX = CenterScale
                                    scaleY = CenterScale
                                } else Modifier
                            )
                            .clip(FrameShape)
                            .background(Color.Black)
                            .then(if (isCenter) Modifier.border(CenterBorder, Color.White, FrameShape) else Modifier)
                    ) {
                        frame?.thumbnail?.let { thumbnail ->
                            val image = remember(thumbnail.bitmap) { thumbnail.bitmap.asImageBitmap() }
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
