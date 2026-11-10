package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal class BlurayIsoDataSource(
    private val upstream: DataSource
) : DataSource {

    private var mapped = false
    private var dataSpec: DataSpec? = null
    private var sourceUri: Uri? = null
    private var extents: List<BlurayIso.Extent> = emptyList()
    private var extentIndex = 0
    private var extentRemaining = 0L
    private var bytesRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!BlurayIso.isIsoUri(dataSpec.uri)) {
            mapped = false
            return upstream.open(dataSpec)
        }
        mapped = true
        this.dataSpec = dataSpec
        val image = BlurayIso.image(dataSpec.uri.host)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val fileExtents = image.extents(dataSpec.uri.path.orEmpty())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val total = fileExtents.sumOf { it.length }
        if (dataSpec.position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = total - dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        sourceUri = Uri.parse(image.sourceUrl)
        extents = fileExtents
        extentIndex = 0
        var skip = dataSpec.position
        while (extentIndex < extents.size && skip >= extents[extentIndex].length) {
            skip -= extents[extentIndex].length
            extentIndex++
        }
        extentRemaining = 0L
        if (bytesRemaining > 0L && extentIndex < extents.size) openExtent(skip)
        return bytesRemaining
    }

    private fun openExtent(offsetInExtent: Long) {
        val extent = extents[extentIndex]
        extentRemaining = minOf(extent.length - offsetInExtent, bytesRemaining)
        val spec = dataSpec ?: return
        upstream.open(
            spec.buildUpon()
                .setUri(sourceUri ?: return)
                .setUriPositionOffset(0L)
                .setPosition(extent.offset + offsetInExtent)
                .setLength(extentRemaining)
                .setKey(null)
                .build()
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!mapped) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (extentRemaining <= 0L) {
            upstream.close()
            extentIndex++
            if (extentIndex >= extents.size) return C.RESULT_END_OF_INPUT
            openExtent(0L)
        }
        val count = minOf(length.toLong(), extentRemaining, bytesRemaining).toInt()
        val read = upstream.read(buffer, offset, count)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        extentRemaining -= read
        bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri? = if (mapped) dataSpec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            mapped = false
            dataSpec = null
            sourceUri = null
            extents = emptyList()
            extentIndex = 0
            extentRemaining = 0L
            bytesRemaining = 0L
        }
    }
}

internal class BlurayIsoDataSourceFactory(
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BlurayIsoDataSource(upstreamFactory.createDataSource())
}
