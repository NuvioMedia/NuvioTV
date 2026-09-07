package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import java.io.EOFException
import java.io.IOException

/**
 * Wraps an [ExtractorsFactory] to intercept and wrap stock Mp4Extractor instances with [NuvioMp4Extractor].
 */
@UnstableApi
class NuvioExtractorsFactory(
    private val delegate: ExtractorsFactory
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        if (extractor is NuvioMp4Extractor) return extractor
        val target = extractor.underlyingImplementation
        val targetName = target.javaClass.name
        val selfName = extractor.javaClass.name
        val isMp4 = targetName == "androidx.media3.extractor.mp4.Mp4Extractor" ||
                targetName.endsWith(".Mp4Extractor") ||
                selfName == "androidx.media3.extractor.mp4.Mp4Extractor" ||
                selfName.endsWith(".Mp4Extractor")
        return if (isMp4) {
            Log.d(TAG, "Wrapped Mp4Extractor ($selfName -> $targetName) with NuvioMp4Extractor")
            NuvioMp4Extractor(extractor)
        } else {
            extractor
        }
    }

    private companion object {
        private const val TAG = "NuvioExtractorsFactory"
    }
}

@UnstableApi
fun ExtractorsFactory.withNuvioMp4Extractor(): ExtractorsFactory {
    return if (this is NuvioExtractorsFactory) this else NuvioExtractorsFactory(this)
}

/**
 * Caches a trailing (non-faststart) `moov` in RAM so parallel-range playback can drop
 * only the chunks that fully overlap that atom, then replay it without re-fetching EOF.
 */
@UnstableApi
class NuvioMp4Extractor(
    private val delegate: Extractor,
    internal var onMoovParsedCallback: (() -> Unit)? = null
) : Extractor {

    private var moovOffset: Long = -1L
    private var moovSizeBytes: Long = 0L
    private var moovData: ByteArray? = null
    private var moovParsed: Boolean = false
    private var isFeedingCachedMoov: Boolean = false
    private var cachedMoovInput: ByteArrayExtractorInput? = null
    private var expectAtomHeader: Boolean = true
    private var lastInputLength: Long = -1L
    private var resolvedTailStartOffset: Long = -1L

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)

            override fun endTracks() {
                output.endTracks()
                notifyMoovParsed()
            }

            override fun seekMap(seekMap: SeekMap) {
                output.seekMap(seekMap)
                notifyMoovParsed()
            }
        })
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        lastInputLength = input.length

        if (isFeedingCachedMoov) {
            val memInput = cachedMoovInput
            if (memInput != null) {
                val res = delegate.read(memInput, seekPosition)
                if (res == Extractor.RESULT_SEEK) {
                    stopFeedingCachedMoov()
                    expectAtomHeader = true
                    notifyMoovParsed()
                    return res
                }
                if (memInput.position >= moovOffset + moovSizeBytes) {
                    stopFeedingCachedMoov()
                    notifyMoovParsed()
                }
                return res
            }
            stopFeedingCachedMoov()
        }

        if (expectAtomHeader) {
            val cachedResult = tryCacheTrailingMoov(input, seekPosition)
            if (cachedResult != null) return cachedResult
        }

        val result = delegate.read(input, seekPosition)
        if (result == Extractor.RESULT_SEEK) {
            expectAtomHeader = true
            if (resolvedTailStartOffset < 0L && input.position < 1024L * 1024L && input.length > 0L && seekPosition.position >= input.length / 2L) {
                resolvedTailStartOffset = seekPosition.position
                Log.d(TAG, "Mp4Extractor requested seek past mdat to tail at $resolvedTailStartOffset (file length=${input.length})")
            }
            val cached = moovData
            if (cached != null && seekPosition.position == moovOffset) {
                Log.d(TAG, "Delegate requested seek to moovOffset ($moovOffset); replaying from RAM cache.")
                return feedCachedMoov(cached, input.length, seekPosition)
            }
            return result
        }
        expectAtomHeader = !moovParsed
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        stopFeedingCachedMoov()
        expectAtomHeader = true
        delegate.seek(position, timeUs)
    }

    override fun release() {
        stopFeedingCachedMoov()
        moovData = null
        delegate.release()
    }

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun tryCacheTrailingMoov(input: ExtractorInput, seekPosition: PositionHolder): Int? {
        if (moovParsed || moovData != null || !isNearFileTail(input)) return null
        val currentPos = input.position
        val header = ByteArray(16)
        if (!input.peekFully(header, 0, 8, true)) {
            input.resetPeekPosition()
            return null
        }
        input.resetPeekPosition()
        val atomSize32 = readInt(header, 0)
        val atomType = readInt(header, 4)
        if (atomType != ATOM_TYPE_MOOV) return null

        var atomSize: Long = atomSize32.toLong() and 0xFFFFFFFFL
        if (atomSize == 1L) {
            if (!input.peekFully(header, 0, 16, true)) {
                input.resetPeekPosition()
                return null
            }
            input.resetPeekPosition()
            atomSize = parseLong(header, 8)
        }

        val remaining = if (input.length > 0L) input.length - currentPos else -1L
        if (atomSize < 8L) {
            return null
        }
        if (remaining >= 0L && atomSize > remaining) {
            Log.w(TAG, "Ignoring moov at $currentPos with size=$atomSize beyond remaining=$remaining")
            return null
        }

        moovOffset = currentPos
        moovSizeBytes = atomSize

        if (atomSize > MAX_MOOV_CACHE_SIZE) {
            Log.w(TAG, "Trailing moov at $currentPos with size=$atomSize exceeds cache cap ($MAX_MOOV_CACHE_SIZE); passing through but tracking for tail chunk release")
            return null
        }

        val data = try {
            ByteArray(atomSize.toInt())
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM caching moov ($atomSize bytes); passing through but tracking for tail chunk release")
            return null
        }
        try {
            input.readFully(data, 0, data.size)
        } catch (e: IOException) {
            moovOffset = -1L
            moovSizeBytes = 0L
            throw e
        }
        moovData = data
        Log.d(TAG, "Cached trailing moov at $moovOffset, size=$moovSizeBytes")
        return feedCachedMoov(data, input.length, seekPosition)
    }

    private fun feedCachedMoov(data: ByteArray, streamLength: Long, seekPosition: PositionHolder): Int {
        val memInput = ByteArrayExtractorInput(data, moovOffset, streamLength)
        cachedMoovInput = memInput
        isFeedingCachedMoov = true
        val memResult = delegate.read(memInput, seekPosition)
        if (memResult == Extractor.RESULT_SEEK) {
            stopFeedingCachedMoov()
            expectAtomHeader = true
            notifyMoovParsed()
            return memResult
        }
        if (memInput.position >= moovOffset + moovSizeBytes) {
            stopFeedingCachedMoov()
            notifyMoovParsed()
        }
        return memResult
    }

    private fun stopFeedingCachedMoov() {
        isFeedingCachedMoov = false
        cachedMoovInput = null
    }

    private fun notifyMoovParsed() {
        if (moovParsed) return
        moovParsed = true
        if (!isTrailingMoov()) {
            Log.d(TAG, "Moov parsed without a trailing atom (offset=$moovOffset, size=$moovSizeBytes); skipping tail release")
            return
        }
        Log.d(TAG, "Trailing moov parsed (offset=$moovOffset, size=$moovSizeBytes). Releasing overlapping chunks.")
        onMoovParsedCallback?.invoke() ?: ParallelRangeDataSource.releaseTailChunks(moovOffset, moovSizeBytes)
    }

    private fun isNearFileTail(input: ExtractorInput): Boolean {
        val length = input.length
        if (length <= 0L) return false
        if (resolvedTailStartOffset > 0L && input.position >= resolvedTailStartOffset) {
            return true
        }
        val tailWindow = maxOf(MAX_MOOV_CACHE_SIZE + 16L, 128L * 1024L * 1024L)
        return input.position >= length / 2L || input.position >= length - tailWindow
    }

    private fun isTrailingMoov(): Boolean {
        if (moovOffset < 0L || moovSizeBytes < 8L) return false
        val length = lastInputLength
        if (length <= 0L) return true
        if (moovOffset + moovSizeBytes > length + 8L) return false
        val endsNearEof = moovOffset + moovSizeBytes >= length - 8L
        val startsInLatterHalf = moovOffset >= length / 2L
        return endsNearEof || startsInLatterHalf
    }

    internal companion object {
        private const val TAG = "NuvioMp4Extractor"
        private const val ATOM_TYPE_MOOV = 0x6d6f6f76 // 'moov'
        internal const val MAX_MOOV_CACHE_SIZE = 32L * 1024L * 1024L

        private fun readInt(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }

        private fun parseLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }
    }
}

/**
 * In-memory [ExtractorInput] serving cached MP4 atom bytes at a specific file offset.
 */
@UnstableApi
internal class ByteArrayExtractorInput(
    private val data: ByteArray,
    private val baseOffset: Long,
    private val streamLength: Long
) : ExtractorInput {

    private var readPosition: Long = baseOffset
    private var peekPosition: Long = baseOffset

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos < 0 || relPos >= data.size) return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length, data.size - relPos)
        System.arraycopy(data, relPos, buffer, offset, bytesToRead)
        readPosition += bytesToRead
        peekPosition = maxOf(peekPosition, readPosition)
        return bytesToRead
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos < 0 || relPos + length > data.size) {
            if (allowEndOfInput && (relPos < 0 || relPos >= data.size)) return false
            throw EOFException("Cannot read fully $length bytes from memory buffer (available: ${data.size - relPos.coerceAtLeast(0)})")
        }
        System.arraycopy(data, relPos, target, offset, length)
        readPosition += length
        peekPosition = maxOf(peekPosition, readPosition)
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos < 0 || relPos >= data.size) return C.RESULT_END_OF_INPUT
        val bytesToSkip = minOf(length, data.size - relPos)
        readPosition += bytesToSkip
        peekPosition = maxOf(peekPosition, readPosition)
        return bytesToSkip
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos < 0 || relPos + length > data.size) {
            if (allowEndOfInput && (relPos < 0 || relPos >= data.size)) return false
            throw EOFException("Cannot skip fully $length bytes from memory buffer (available: ${data.size - relPos.coerceAtLeast(0)})")
        }
        readPosition += length
        peekPosition = maxOf(peekPosition, readPosition)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos < 0 || relPos >= data.size) return C.RESULT_END_OF_INPUT
        val bytesToPeek = minOf(length, data.size - relPos)
        System.arraycopy(data, relPos, target, offset, bytesToPeek)
        peekPosition += bytesToPeek
        return bytesToPeek
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos < 0 || relPos + length > data.size) {
            if (allowEndOfInput && (relPos < 0 || relPos >= data.size)) return false
            throw EOFException("Cannot peek fully $length bytes from memory buffer (available: ${data.size - relPos.coerceAtLeast(0)})")
        }
        System.arraycopy(data, relPos, target, offset, length)
        peekPosition += length
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos < 0 || relPos + length > data.size) {
            if (allowEndOfInput && (relPos < 0 || relPos >= data.size)) return false
            throw EOFException("Cannot advance peek position by $length bytes")
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekPosition = readPosition
    }

    override fun getPeekPosition(): Long = peekPosition

    override fun getPosition(): Long = readPosition

    override fun getLength(): Long = streamLength

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        readPosition = position
        peekPosition = position
        throw e
    }
}
