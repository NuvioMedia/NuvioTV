package com.nuvio.tv.ui.reshaped.pillnav

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext

/**
 * The screen behind the pill, recorded once per content frame into a display list the pill replays under its
 * lens. No blur and no pixel copy: the pill only redraws its own small area from the same recording.
 * Only on TVs that can run AGSL (Android 13+) and have memory to spare; everything else gets the static glass.
 */
@Stable
internal class PillGlassBackdrop(val layer: GraphicsLayer) {
    var contentOrigin by mutableStateOf(Offset.Zero)
        internal set

    /** Bumped after every content recording so the pill redraws with it. */
    var version by mutableIntStateOf(0)
        private set

    internal fun onRecorded() {
        // Written from the content's draw pass; never read there, so it can't re-invalidate the content.
        Snapshot.withoutReadObservation { version += 1 }
    }

    /** Draws the recorded content so that it lines up with a node whose root position is [origin]. */
    fun DrawScope.drawAligned(origin: Offset) {
        val shift = origin - contentOrigin
        translate(-shift.x, -shift.y) { drawLayer(layer) }
    }
}

private const val MinLensRamBytes = 2_500_000_000L // 3 GB boxes report about 2.8 GB; 2 GB boxes about 1.9 GB.

/** Android 13+ with at least 3 GB of RAM and not flagged low-RAM: the TVs that get the refracting pill. */
internal fun pillLensSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    if (manager.isLowRamDevice) return false
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem >= MinLensRamBytes
}

/** The backdrop recorder, or null where the pill falls back to static glass. */
@Composable
internal fun rememberPillGlassBackdrop(): PillGlassBackdrop? {
    val context = LocalContext.current
    val supported = remember { pillLensSupported(context) }
    if (!supported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { PillGlassBackdrop(layer) }
}

/** Put on the content the pill floats over: records it for the lens and draws it as usual. */
internal fun Modifier.pillGlassSource(backdrop: PillGlassBackdrop?): Modifier {
    if (backdrop == null) return this
    return onGloballyPositioned { backdrop.contentOrigin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            backdrop.onRecorded()
        }
}
