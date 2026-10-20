package com.nuvio.tv.data.mediaserver

/**
 * Grid layout of server-generated seek-preview ("trickplay") sheets.
 *
 * Each sheet is one image holding [columns] x [rows] thumbnails of [thumbWidth] x [thumbHeight],
 * taken every [intervalMs].
 */
data class SeekPreviewLayout(
    /** Identifies the underlying media + resolution so caches survive a stream restart. */
    val key: String,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val columns: Int,
    val rows: Int,
    val thumbnailCount: Int,
    val intervalMs: Long
) {
    val thumbnailsPerSheet: Int get() = columns * rows

    val isValid: Boolean
        get() = thumbWidth > 0 && thumbHeight > 0 && columns > 0 && rows > 0 &&
            thumbnailCount > 0 && intervalMs > 0

    fun thumbnailIndexAt(positionMs: Long): Int =
        (positionMs.coerceAtLeast(0L) / intervalMs).toInt().coerceIn(0, thumbnailCount - 1)

    fun sheetIndexOf(thumbnailIndex: Int): Int = thumbnailIndex / thumbnailsPerSheet

    fun cellOf(thumbnailIndex: Int): SeekPreviewCell {
        val inSheet = thumbnailIndex % thumbnailsPerSheet
        return SeekPreviewCell(column = inSheet % columns, row = inSheet / columns)
    }
}

data class SeekPreviewCell(val column: Int, val row: Int)

interface SeekPreview {
    val layout: SeekPreviewLayout

    suspend fun sheet(index: Int): ByteArray?
}
