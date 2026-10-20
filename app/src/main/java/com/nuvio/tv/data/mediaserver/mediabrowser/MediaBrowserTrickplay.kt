package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.SeekPreviewLayout
import kotlin.math.abs

internal class SelectedTrickplay(val width: Int, val layout: SeekPreviewLayout)

private const val PREFERRED_THUMB_WIDTH = 320

/**
 * Picks the trickplay resolution for [mediaSourceId] (falling back to the only/first source) whose
 * thumbnail width is closest to [PREFERRED_THUMB_WIDTH]. Smaller sheets download faster on slow links.
 */
internal fun selectTrickplay(
    itemId: String,
    mediaSourceId: String,
    trickplay: Map<String, Map<String, TrickplayInfo>>
): SelectedTrickplay? {
    val resolutions = trickplay[mediaSourceId] ?: trickplay.values.firstOrNull() ?: return null
    return resolutions.entries
        .mapNotNull { (widthKey, info) ->
            val width = widthKey.toIntOrNull() ?: info.width
            val layout = SeekPreviewLayout(
                key = "$itemId/$mediaSourceId/$width",
                thumbWidth = info.width,
                thumbHeight = info.height,
                columns = info.tileWidth,
                rows = info.tileHeight,
                thumbnailCount = info.thumbnailCount,
                intervalMs = info.interval
            )
            if (width > 0 && layout.isValid) SelectedTrickplay(width, layout) else null
        }
        .minByOrNull { abs(it.width - PREFERRED_THUMB_WIDTH) }
}
