package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvProgramme

/*
 * How the guide lays a channel's programmes out and moves through them, kept apart from drawing
 * so it can be checked on its own. Times are epoch milliseconds.
 */

internal const val GUIDE_MINUTE = 60_000L
internal const val GUIDE_SLOT = 30 * GUIDE_MINUTE

internal fun guideFloorSlot(ms: Long): Long = ms - Math.floorMod(ms, GUIDE_SLOT)
internal fun guideCeilSlot(ms: Long): Long = guideFloorSlot(ms + GUIDE_SLOT - 1)

/**
 * A stretch of a channel's row: a [programme] for the time the guide gives it, or (null) time the
 * guide has nothing for. A programme cut in two by one inside it (a news break) is two blocks.
 */
internal data class LiveTvGuideBlock(val start: Long, val stop: Long, val programme: LiveTvProgramme?)

/** A block as drawn: [start]..[stop] is the part of [block] in the time the row holds. */
internal class LiveTvGuideCell(val block: LiveTvGuideBlock, val start: Long, val stop: Long)

/** Pieces left of an overlapped programme shorter than this are dropped. */
private const val MIN_PIECE_MS = GUIDE_MINUTE

/**
 * A channel's programmes as the guide shows them: in time order and never overlapping. Guides do
 * overlap (an entry running into the next, two guides feeding one channel); the later entry gets
 * its time, and the earlier one keeps what is left before it (and after it, when it lies inside).
 */
internal fun liveTvGuideBlocks(programmes: List<LiveTvProgramme>): List<LiveTvGuideBlock> {
    if (programmes.isEmpty()) return emptyList()
    var sorted = true
    for (i in 1 until programmes.size) {
        if (programmes[i].startEpochMs < programmes[i - 1].startEpochMs) {
            sorted = false
            break
        }
    }
    val ordered = if (sorted) programmes else programmes.sortedBy { it.startEpochMs }
    val out = ArrayList<LiveTvGuideBlock>(ordered.size + 2)
    for (programme in ordered) {
        val start = programme.startEpochMs
        val stop = programme.stopEpochMs
        if (stop <= start) continue
        // The same show listed twice with slightly different times (two feeds merged into one
        // guide, or a repeat entry): one block for both, rather than a sliver and then the show again.
        val last = out.lastOrNull()
        if (last?.programme != null && last.stop > start && sameShow(last.programme, programme)) {
            if (stop > last.stop) out[out.size - 1] = last.copy(stop = stop)
            continue
        }
        // Blocks already laid out that the new one overlaps: all end after its start, and the
        // list is in order with no overlaps, so they are at its end.
        var i = out.size - 1
        while (i >= 0 && out[i].stop > start) {
            val block = out[i]
            if (block.start < stop) {
                out.removeAt(i)
                if (block.stop > stop && block.stop - stop >= MIN_PIECE_MS) out.add(i, block.copy(start = stop))
                if (block.start < start && start - block.start >= MIN_PIECE_MS) out.add(i, block.copy(stop = start))
            }
            i--
        }
        var at = out.size
        while (at > 0 && out[at - 1].start >= start) at--
        out.add(at, LiveTvGuideBlock(start, stop, programme))
    }
    return out
}

private fun sameShow(a: LiveTvProgramme, b: LiveTvProgramme): Boolean =
    a.title === b.title || a.title.trim().equals(b.title.trim(), ignoreCase = true)

/** The lowest index whose block ends after [at] ([size] when none does). */
private fun List<LiveTvGuideBlock>.firstEndingAfter(at: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].stop > at) high = mid else low = mid + 1
    }
    return low
}

/**
 * The block the selection is on at [at]: the programme on then or, where the guide has nothing,
 * the half hour around [at] cut at the programmes either side, as TV guides step through empty time.
 */
internal fun List<LiveTvGuideBlock>.blockAt(at: Long): LiveTvGuideBlock {
    val index = firstEndingAfter(at)
    val next = getOrNull(index)
    if (next != null && next.start <= at) return next
    var start = guideFloorSlot(at)
    var stop = start + GUIDE_SLOT
    getOrNull(index - 1)?.let { if (it.stop > start) start = it.stop }
    if (next != null && next.start < stop) stop = next.start
    return LiveTvGuideBlock(start, stop, null)
}

/** The block after [block] (▶). */
internal fun List<LiveTvGuideBlock>.after(block: LiveTvGuideBlock): LiveTvGuideBlock = blockAt(block.stop)

/** The block before [block] (◀). */
internal fun List<LiveTvGuideBlock>.before(block: LiveTvGuideBlock): LiveTvGuideBlock = blockAt(block.start - 1)

/** The last programme ending by [time], or null. */
internal fun List<LiveTvGuideBlock>.programmeBefore(time: Long): LiveTvGuideBlock? = getOrNull(firstEndingAfter(time) - 1)

/**
 * What a row draws for [from]..[to]: each block there, cut to it, with the time between them
 * that the guide has nothing for as one "No guide" cell each.
 */
internal fun List<LiveTvGuideBlock>.cellsIn(from: Long, to: Long): List<LiveTvGuideCell> {
    val cells = ArrayList<LiveTvGuideCell>(8)
    var covered = from
    // The gap before the first block in view runs from where the block before it ends.
    var gapStart = getOrNull(firstEndingAfter(from) - 1)?.stop ?: Long.MIN_VALUE
    var index = firstEndingAfter(from)
    while (index < size && this[index].start < to) {
        val block = this[index]
        if (block.start > covered) cells += LiveTvGuideCell(LiveTvGuideBlock(gapStart, block.start, null), covered, block.start)
        cells += LiveTvGuideCell(block, maxOf(block.start, from), minOf(block.stop, to))
        covered = block.stop
        gapStart = block.stop
        index++
    }
    if (covered < to) cells += LiveTvGuideCell(LiveTvGuideBlock(gapStart, getOrNull(index)?.start ?: Long.MAX_VALUE, null), covered, to)
    return cells
}

/**
 * Where the view's left edge goes so [block] shows, on a half hour: unchanged when it shows
 * already; one that fits the view shows whole, a longer one by its start (or, coming back to it
 * from later, by its last part). Kept within [first]..[last], the time the guide can show.
 */
internal fun liveTvGuideViewFor(block: LiveTvGuideBlock, viewStart: Long, span: Long, first: Long, last: Long): Long {
    val end = viewStart + span
    val view = if (block.stop - block.start <= span) {
        when {
            block.start < viewStart -> guideFloorSlot(block.start)
            block.stop > end -> minOf(guideFloorSlot(block.start), guideCeilSlot(block.stop - span))
            else -> viewStart
        }
    } else {
        when {
            // Its start shows, with half an hour of it or more.
            block.start >= viewStart -> if (block.start <= end - GUIDE_SLOT) viewStart else guideFloorSlot(block.start)
            // It runs on from before the view: half an hour of it or more shows.
            block.stop >= viewStart + GUIDE_SLOT -> viewStart
            else -> guideFloorSlot(maxOf(block.start, block.stop - span + GUIDE_SLOT))
        }
    }
    val latest = maxOf(first, guideCeilSlot(last - span))
    return view.coerceIn(first, latest)
}

/** The time the selection follows between channels once on [block], with the view at [viewStart]. */
internal fun liveTvGuideAnchorFor(block: LiveTvGuideBlock, viewStart: Long): Long =
    maxOf(block.start, viewStart).coerceAtMost(block.stop - 1)
