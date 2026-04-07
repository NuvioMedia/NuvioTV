package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal class BdavM2tsDataSource(
    private val upstream: DataSource
) : DataSource {

    private companion object {
        private const val TAG = "BdavM2tsDataSource"
        private const val BDAV_PACKET_SIZE = 192
        private const val TS_PACKET_SIZE = 188
        private const val BDAV_PREFIX_SIZE = BDAV_PACKET_SIZE - TS_PACKET_SIZE
        private const val SCRATCH_BUFFER_SIZE = 16 * 1024
    }

    private val scratchBuffer = ByteArray(SCRATCH_BUFFER_SIZE)
    private var scratchReadPosition = 0
    private var scratchReadLimit = 0
    private var sourcePosition = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        if (opened) {
            // Defensive reset: some loaders may reopen after an interrupted open without a close callback.
            runCatching { upstream.close() }
                .onFailure { closeError ->
                    Log.w(TAG, "open pre-close failed: ${closeError.javaClass.simpleName}: ${closeError.message}")
                }
            opened = false
        }

        val requestedTsStart = dataSpec.position.coerceAtLeast(0L)
        val requestedSourceStart = tsPositionToSourcePosition(requestedTsStart)
        val remappedLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            val requestedTsEnd = safeAdd(requestedTsStart, dataSpec.length)
            val requestedSourceEnd = tsPositionToSourcePosition(requestedTsEnd)
            (requestedSourceEnd - requestedSourceStart).coerceAtLeast(0L)
        }

        val remappedDataSpec = dataSpec.buildUpon()
            .setPosition(requestedSourceStart)
            .setLength(remappedLength)
            .build()

        val availableSourceLength = try {
            upstream.open(remappedDataSpec)
        } catch (error: Exception) {
            runCatching { upstream.close() }
                .onFailure { closeError ->
                    Log.w(TAG, "open cleanup close failed: ${closeError.javaClass.simpleName}: ${closeError.message}")
                }
            Log.e(
                TAG,
                "open failed: uri=${summarizeUri(dataSpec.uri)} tsStart=$requestedTsStart srcStart=$requestedSourceStart reqLen=${dataSpec.length} mappedLen=$remappedLength",
                error
            )
            throw error
        }
        sourcePosition = requestedSourceStart
        scratchReadPosition = 0
        scratchReadLimit = 0
        opened = true

        if (availableSourceLength == C.LENGTH_UNSET.toLong()) {
            return C.LENGTH_UNSET.toLong()
        }

        val sourceEnd = safeAdd(requestedSourceStart, availableSourceLength)
        return sourcePositionToTsPosition(sourceEnd) - sourcePositionToTsPosition(requestedSourceStart)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        var bytesWritten = 0
        while (bytesWritten < length) {
            if (scratchReadPosition >= scratchReadLimit) {
                val upstreamRead = upstream.read(scratchBuffer, 0, scratchBuffer.size)
                if (upstreamRead == C.RESULT_END_OF_INPUT) {
                    return if (bytesWritten == 0) C.RESULT_END_OF_INPUT else bytesWritten
                }
                scratchReadPosition = 0
                scratchReadLimit = upstreamRead
            }

            while (scratchReadPosition < scratchReadLimit && bytesWritten < length) {
                val packetOffset = (sourcePosition % BDAV_PACKET_SIZE).toInt()
                val availableInScratch = scratchReadLimit - scratchReadPosition
                val remainingRequested = length - bytesWritten
                val maxChunk = minOf(availableInScratch, remainingRequested)

                if (packetOffset < BDAV_PREFIX_SIZE) {
                    val skip = minOf(maxChunk, BDAV_PREFIX_SIZE - packetOffset)
                    scratchReadPosition += skip
                    sourcePosition += skip.toLong()
                    continue
                }

                val remainingPayloadInPacket = BDAV_PACKET_SIZE - packetOffset
                val copySize = minOf(maxChunk, remainingPayloadInPacket)
                System.arraycopy(
                    scratchBuffer,
                    scratchReadPosition,
                    buffer,
                    offset + bytesWritten,
                    copySize
                )
                scratchReadPosition += copySize
                sourcePosition += copySize.toLong()
                bytesWritten += copySize
            }
        }

        return bytesWritten
    }

    override fun close() {
        scratchReadPosition = 0
        scratchReadLimit = 0
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    private fun summarizeUri(uri: Uri?): String {
        if (uri == null) return "<null>"
        val scheme = uri.scheme
        val host = uri.host
        val path = uri.path.orEmpty()
        return when {
            !scheme.isNullOrBlank() && !host.isNullOrBlank() -> "$scheme://$host$path"
            !scheme.isNullOrBlank() && path.isNotBlank() -> "$scheme:$path"
            else -> uri.toString().substringBefore('?')
        }
    }

    private fun tsPositionToSourcePosition(tsPosition: Long): Long {
        if (tsPosition <= 0L) return BDAV_PREFIX_SIZE.toLong()
        val packetIndex = tsPosition / TS_PACKET_SIZE
        val payloadOffset = tsPosition % TS_PACKET_SIZE
        return safeAdd(safeMultiply(packetIndex, BDAV_PACKET_SIZE.toLong()), BDAV_PREFIX_SIZE.toLong() + payloadOffset)
    }

    private fun sourcePositionToTsPosition(sourcePosition: Long): Long {
        if (sourcePosition <= 0L) return 0L
        val packetIndex = sourcePosition / BDAV_PACKET_SIZE
        val packetOffset = (sourcePosition % BDAV_PACKET_SIZE).toInt()
        val payloadOffset = (packetOffset - BDAV_PREFIX_SIZE).coerceIn(0, TS_PACKET_SIZE)
        return safeAdd(safeMultiply(packetIndex, TS_PACKET_SIZE.toLong()), payloadOffset.toLong())
    }

    private fun safeAdd(a: Long, b: Long): Long {
        if (b <= 0L) return a
        return if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }

    private fun safeMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }
}

internal class BdavM2tsDataSourceFactory(
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BdavM2tsDataSource(upstreamFactory.createDataSource())
}
