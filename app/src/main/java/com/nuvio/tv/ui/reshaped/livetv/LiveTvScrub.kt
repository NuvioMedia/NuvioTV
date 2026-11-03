package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.screens.player.PlayerScrubRates
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Rewinding a channel with catch-up, live or in a replay: a time picked on a bar from the start of
 * the show to now, played by asking the provider for a replay from there. Seeking inside the
 * stream would need it to be seekable, and most IPTV streams (TS) are not.
 */
@Stable
internal class LiveTvScrub(val channel: LiveTvChannel, fromMs: Long, targetMs: Long) {
    /** The start of the bar: the show's start (earlier ones are added by going past it). */
    var fromMs by mutableLongStateOf(fromMs)
    /** The time played from once the keys rest (or OK is pressed). */
    var targetMs by mutableLongStateOf(targetMs)
}

internal object LiveTvScrubSteps {
    private const val MINUTE_MS = 60_000L
    /** Without a guide, how far back the bar reaches at a time. */
    const val NO_GUIDE_SPAN_MS = 2L * 60 * MINUTE_MS
    /** This near to now plays the channel live. */
    const val LIVE_MARGIN_MS = 45_000L

    /** The player's own steps for a held ◀▶ (10 s growing to a minute), so rewinding feels the same. */
    fun stepMs(repeatCount: Int): Long = PlayerScrubRates.stepMsForKeyRepeat(repeatCount)

    /**
     * Where a held ◀ from [fromMs] to [toMs] stops: at the start of a show it would pass (the
     * latest, so the show it is in), else [toMs]; one stopped at a start stays there. Starts are
     * in time order, so the search stops early.
     */
    fun heldBackTo(schedule: List<LiveTvProgramme>, fromMs: Long, toMs: Long): Long {
        var stop = toMs
        for (programme in schedule) {
            val start = programme.startEpochMs
            if (start == fromMs) return fromMs
            if (start > fromMs) break
            if (start > stop) stop = start
        }
        return stop
    }

    /**
     * Where the bar starts for a time [atMs]: the start of the programme on then in [schedule],
     * else [NO_GUIDE_SPAN_MS] before; never before [earliestMs] (what the provider keeps).
     */
    fun rangeStart(schedule: List<LiveTvProgramme>, atMs: Long, earliestMs: Long): Long {
        val programme = schedule.firstOrNull { atMs >= it.startEpochMs && atMs < it.stopEpochMs }
        val start = programme?.startEpochMs ?: (atMs - NO_GUIDE_SPAN_MS)
        return start.coerceAtLeast(earliestMs)
    }
}

/**
 * The rewind bar, drawn as the player's own seek bar is when seeking on the bare picture (same
 * track, accent fill, size, place and time text), from the bar's start to now.
 */
@Composable
internal fun LiveTvScrubBar(scrub: LiveTvScrub) {
    // Only while the bar shows: the live edge moves with the clock.
    val now by produceState(LiveTvClock.nowEpochMs()) {
        while (true) {
            delay(1_000L)
            value = LiveTvClock.nowEpochMs()
        }
    }
    val target = scrub.targetMs
    val from = scrub.fromMs
    val span = (now - from).coerceAtLeast(1L)
    val programme = remember(scrub.channel.guideKey, target / 60_000L) {
        LiveTvRepository.schedule(scrub.channel.guideKey).firstOrNull { target >= it.startEpochMs && target < it.stopEpochMs }
    }
    val progress by animateFloatAsState(
        targetValue = ((target - from).toFloat() / span).coerceIn(0f, 1f),
        animationSpec = tween(100),
        label = "rewind",
    )
    // Made once per theme, not on each tick of the clock.
    val palette = NuvioTheme.palette
    val accentBrush = remember(palette) { palette.accentBrush() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTheme.spacing.sm)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(maxWidth * progress)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accentBrush),
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // What the picked time is in (the player's bar has the film's name at the top instead).
                Text(
                    text = programme?.title ?: scrub.channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = NuvioTheme.spacing.md),
                )
                Text(
                    text = "${formatScrubTime(target - from)} / ${formatScrubTime(now - from)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** As the player writes times: 4:05, 1:02:09. */
private fun formatScrubTime(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs) else "%d:%02d".format(Locale.ROOT, minutes, secs)
}
