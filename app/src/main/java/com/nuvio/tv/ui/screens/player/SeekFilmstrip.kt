package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.SeekThumbnailEntryPoint
import com.nuvio.tv.core.player.SeekThumbnailGenerator
import com.nuvio.tv.core.player.SeekThumbnailSlot
import com.nuvio.tv.core.player.SeekThumbnailSlots
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val SEEK_FILMSTRIP_DEBOUNCE_MS = 120L
private const val SEEK_FILMSTRIP_TARGET_TILE_WIDTH_DP = 170f
private const val SEEK_FILMSTRIP_MIN_TILES = 3
private const val SEEK_FILMSTRIP_MAX_TILES = 9
private val SEEK_FILMSTRIP_MAX_TILE_HEIGHT = 78.dp

/**
 * Netflix-style filmstrip of frame previews shown above the seek bar while the
 * user scrubs. It spans the full width of the progress bar: the number of tiles
 * adapts to the available width and every tile keeps a 16:9 shape.
 *
 * The row appears immediately as empty placeholders and fills in as frames
 * arrive from [SeekThumbnailGenerator], so there is no blank wait while the
 * first frame is being extracted. Nothing is rendered when the stream cannot
 * provide frames at all.
 *
 * @param prefetch when true, extraction starts as soon as the controls open
 * (before the user actually scrubs) so the first tiles are already warm.
 */
@Composable
fun SeekFilmstrip(
    streamUrl: String?,
    headers: Map<String, String>,
    currentPosition: Long,
    duration: Long,
    visible: Boolean,
    modifier: Modifier = Modifier,
    prefetch: Boolean = false
) {
    val context = LocalContext.current
    val generator = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SeekThumbnailEntryPoint::class.java
        ).seekThumbnailGenerator()
    }

    DisposableEffect(streamUrl) {
        onDispose { generator.scheduleRelease() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        val tileCount = (maxWidth.value / SEEK_FILMSTRIP_TARGET_TILE_WIDTH_DP)
            .toInt()
            .coerceIn(SEEK_FILMSTRIP_MIN_TILES, SEEK_FILMSTRIP_MAX_TILES)
        val tileWidth = maxWidth / tileCount
        val tileHeight = minOf(
            tileWidth * 9f / 16f,
            SEEK_FILMSTRIP_MAX_TILE_HEIGHT
        )

        val slots = remember(duration, currentPosition, tileCount) {
            SeekThumbnailSlots.slots(duration, currentPosition, tileCount)
        }
        val bucketKey = remember(slots) { slots.map { it.bucketMs } }
        val stepMs = remember(duration) { SeekThumbnailSlots.stepMs(duration) }
        val active = visible || prefetch
        val supported = active && !streamUrl.isNullOrBlank() && generator.isSupported(streamUrl)

        val frames = remember(streamUrl) { mutableStateMapOf<Long, Bitmap?>() }
        val failedBuckets = remember(streamUrl) { mutableStateListOf<Long>() }

        LaunchedEffect(visible, supported, streamUrl) {
            Log.d(
                "SeekThumbs",
                "gate visible=$visible prefetch=$prefetch supported=$supported slots=${slots.size} " +
                    "stepMs=$stepMs duration=$duration host=${streamUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }}"
            )
        }

        // Direzione dello scrub: la posizione dell'ultimo bucket servito. Serve
        // per precaricare il bucket oltre il bordo visibile sul lato in cui
        // l'utente sta andando, così il passo successivo trova il frame già
        // estratto invece di attendere una nuova estrazione di rete.
        val previousPositionMs = remember(streamUrl) { mutableStateOf(currentPosition) }

        LaunchedEffect(streamUrl, bucketKey, supported, active) {
            if (!active || !supported || streamUrl == null || stepMs <= 0L) {
                return@LaunchedEffect
            }
            // First paint must be instant; only debounced while the strip is
            // already showing and the scrub keeps hopping between buckets.
            if (frames.isNotEmpty()) delay(SEEK_FILMSTRIP_DEBOUNCE_MS)
            val previousPosition = previousPositionMs.value
            previousPositionMs.value = currentPosition
            val scrubForward = currentPosition >= previousPosition
            // Center tile first so the strip anchors visually before its edges
            // fill in; a parità di distanza vanno avanti i bucket verso la
            // direzione dello scrub, che è dove l'utente guarda.
            val ordered = slots.sortedWith(
                compareBy<SeekThumbnailSlot> { slot ->
                    abs((slot.bucketMs + stepMs / 2) - currentPosition)
                }.thenBy { slot ->
                    val ahead = (slot.bucketMs + stepMs / 2) > currentPosition
                    if (scrubForward == ahead) 0 else 1
                }
            )
            for (slot in ordered) {
                if (frames.containsKey(slot.bucketMs) || slot.bucketMs in failedBuckets) continue
                val frame = generator.getFrame(
                    url = streamUrl,
                    headers = headers,
                    positionMs = slot.bucketMs + stepMs / 2
                )
                if (frame != null) {
                    frames[slot.bucketMs] = frame
                } else {
                    failedBuckets += slot.bucketMs
                }
            }
            // Prefetch direzionale: riscalda il bucket successivo oltre il
            // bordo visibile nella direzione di scrub. Un fallimento qui non
            // segna il bucket come irrecuperabile: sarà la striscia vera e
            // propria a riprovarci quando quel bucket entrerà in vista.
            if (duration > 0L && slots.isNotEmpty()) {
                val edgeBucketMs = if (scrubForward) {
                    slots.maxOf { it.bucketMs } + stepMs
                } else {
                    slots.minOf { it.bucketMs } - stepMs
                }
                if (edgeBucketMs >= 0L && edgeBucketMs < duration &&
                    edgeBucketMs !in failedBuckets && !frames.containsKey(edgeBucketMs)
                ) {
                    val frame = generator.getFrame(
                        url = streamUrl,
                        headers = headers,
                        positionMs = edgeBucketMs + stepMs / 2
                    )
                    if (frame != null) frames[edgeBucketMs] = frame
                }
            }
        }

        val allFailed = slots.isNotEmpty() && slots.all { it.bucketMs in failedBuckets }

        AnimatedVisibility(
            visible = visible && supported && (frames.isNotEmpty() || !allFailed),
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(140)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slots.forEach { slot ->
                    val frame = frames[slot.bucketMs]
                    val tileShape = RoundedCornerShape(4.dp)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(tileHeight)
                            .clip(tileShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .then(
                                if (slot.isCenter) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = NuvioTheme.colors.Secondary,
                                        shape = tileShape
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        frame?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
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
