package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

// The matroska extractor pulls ebml fields one at a time, which measured at 184 bytes per read and
// 1.4 million reads for a few minutes of playback. Every one of those crossed the disk cache and
// paid its per call cost, so they are served from one buffer filled by a single large read instead.
@UnstableApi
internal class BufferedReadDataSource(
    private val delegate: DataSource,
    bufferBytes: Int = DEFAULT_BUFFER_BYTES
) : DataSource {

    private val buffer = ByteArray(bufferBytes)
    private var position = 0
    private var limit = 0
    private var endOfInput = false

    override fun open(dataSpec: DataSpec): Long {
        position = 0
        limit = 0
        endOfInput = false
        return delegate.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == limit) {
            if (endOfInput) return C.RESULT_END_OF_INPUT
            // A read larger than the buffer would gain nothing by being copied through it.
            if (length >= buffer.size) return delegate.read(target, offset, length)
            val filled = delegate.read(buffer, 0, buffer.size)
            if (filled == C.RESULT_END_OF_INPUT) {
                endOfInput = true
                return C.RESULT_END_OF_INPUT
            }
            position = 0
            limit = filled
        }
        val available = minOf(length, limit - position)
        System.arraycopy(buffer, position, target, offset, available)
        position += available
        return available
    }

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        position = 0
        limit = 0
        endOfInput = false
        delegate.close()
    }

    companion object {
        const val DEFAULT_BUFFER_BYTES = 64 * 1024
    }
}
