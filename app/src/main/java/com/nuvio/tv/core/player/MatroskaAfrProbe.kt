package com.nuvio.tv.core.player

import java.io.File
import java.io.RandomAccessFile

/** Lightweight EBML helpers for AFR OkHttp probing of Matroska/WebM. */
internal object MatroskaAfrProbe {

    const val ID_EBML = 0x1A45DFA3L
    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_SEEK = 0x4DBBL
    const val ID_SEEK_ID = 0x53ABL
    const val ID_SEEK_POSITION = 0x53ACL
    const val ID_INFO = 0x1549A966L
    const val ID_TRACKS = 0x1654AE6BL
    const val ID_ATTACHMENTS = 0x1941A469L
    const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L

    /** Max Tracks element we will pull for a sparse AFR probe. */
    const val MAX_TRACKS_FETCH_BYTES = 8_388_608L

    private val VINT_LENGTH_MASKS = longArrayOf(
        0x80L, 0x40L, 0x20L, 0x10L, 0x08L, 0x04L, 0x02L, 0x01L
    )

    data class EbmlElement(
        val id: Long,
        val offset: Long,
        val headerSize: Int,
        val dataSize: Long,
        val unknownSize: Boolean
    ) {
        val dataOffset: Long get() = offset + headerSize
        fun endExclusiveOrNull(): Long? = if (unknownSize) null else offset + headerSize + dataSize
    }

    data class MatroskaHeadLayout(
        /** Byte offset where Segment element payload begins. */
        val segmentDataOffset: Long,
        /** Longest prefix of file ending on a complete EBML element boundary. */
        val safePrefixLength: Long,
        /** Absolute file offset of Tracks element. */
        val tracksAbsoluteOffset: Long?,
        /** True when a complete Tracks element is in the safe prefix. */
        val tracksCompleteInPrefix: Boolean,
        /** True when safe prefix already contains a complete Cluster. */
        val clusterInPrefix: Boolean = false,
        /** Offset of Segment size VINT, or -1 if unparsed. */
        val segmentSizeVintOffset: Long = -1L,
        /** Width of Segment size VINT for in-place rewrites. */
        val segmentSizeVintLength: Int = 0,
        /** True when Segment declares an unknown size. */
        val segmentUnknownSize: Boolean = false
    )

    data class Vint(
        val value: Long,
        val length: Int
    )

    fun analyzeHead(file: File): MatroskaHeadLayout? {
        if (!file.exists() || file.length() < 4L) return null
        return runCatching {
            val len = file.length().coerceAtMost(4_194_304L + 65_536L).toInt()
            val bytes = ByteArray(len)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(bytes)
            }
            val layout = analyzeHeadBytes(bytes) ?: return@runCatching null
            if (layout.clusterInPrefix || !hasClusterAtEof(file)) return@runCatching layout
            layout.copy(clusterInPrefix = true)
        }.getOrNull()
    }

    /** True when the file ends on a Cluster element (sparse Pass 2 stub past the head window). */
    fun hasClusterAtEof(file: File, scanBytes: Long = 8_192L): Boolean {
        val fileLen = file.length()
        if (fileLen < 12L) return false
        val scan = minOf(scanBytes, fileLen).toInt()
        val buf = ByteArray(scan)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fileLen - scan)
            raf.readFully(buf)
        }
        val baseOffset = fileLen - scan
        for (start in (scan - 12).downTo(0)) {
            val element = readElement(buf, start.toLong()) ?: continue
            val end = element.endExclusiveOrNull() ?: continue
            if (element.id == ID_CLUSTER && baseOffset + end == fileLen) return true
        }
        return false
    }

    fun analyzeHeadBytes(bytes: ByteArray): MatroskaHeadLayout? {
        if (bytes.size < 4) return null
        val limit = bytes.size.toLong()

        val ebml = readElement(bytes, 0L) ?: return null
        if (ebml.id != ID_EBML) return null
        val ebmlEnd = ebml.endExclusiveOrNull() ?: return null
        if (ebmlEnd > limit) {
            // Incomplete EBML header — no usable prefix.
            return MatroskaHeadLayout(
                segmentDataOffset = 0L,
                safePrefixLength = 0L,
                tracksAbsoluteOffset = null,
                tracksCompleteInPrefix = false
            )
        }

        val segment = readElement(bytes, ebmlEnd) ?: return MatroskaHeadLayout(
            segmentDataOffset = 0L,
            safePrefixLength = ebmlEnd,
            tracksAbsoluteOffset = null,
            tracksCompleteInPrefix = false
        )
        if (segment.id != ID_SEGMENT) {
            return MatroskaHeadLayout(
                segmentDataOffset = 0L,
                safePrefixLength = ebmlEnd,
                tracksAbsoluteOffset = null,
                tracksCompleteInPrefix = false
            )
        }

        val segmentDataOffset = segment.dataOffset
        val segmentIdLength = elementIdBytes(ID_SEGMENT).size
        var pos = segmentDataOffset
        var safePrefix = segmentDataOffset
        var tracksAbsoluteOffset: Long? = null
        var tracksCompleteInPrefix = false
        var clusterInPrefix = false
        val segmentEnd = segment.endExclusiveOrNull()?.coerceAtMost(limit) ?: limit

        while (pos < segmentEnd && pos < limit) {
            val child = readElement(bytes, pos)
            if (child == null) {
                // Torn header at EOF — keep last complete boundary.
                break
            }
            if (child.unknownSize) {
                // Unknown-size child (rare at this level) — cannot safely include payload.
                break
            }
            val childEnd = child.endExclusiveOrNull() ?: break
            if (childEnd > limit) {
                // Incomplete child — stop before it.
                break
            }

            safePrefix = childEnd
            when (child.id) {
                ID_SEEK_HEAD -> {
                    val fromSeek = parseTracksOffsetFromSeekHead(
                        bytes = bytes,
                        seekHead = child,
                        segmentDataOffset = segmentDataOffset
                    )
                    if (fromSeek != null) tracksAbsoluteOffset = fromSeek
                }
                ID_TRACKS -> {
                    tracksAbsoluteOffset = child.offset
                    tracksCompleteInPrefix = true
                }
                ID_CLUSTER -> clusterInPrefix = true
            }
            pos = childEnd
        }

        return MatroskaHeadLayout(
            segmentDataOffset = segmentDataOffset,
            safePrefixLength = safePrefix,
            tracksAbsoluteOffset = tracksAbsoluteOffset,
            tracksCompleteInPrefix = tracksCompleteInPrefix,
            clusterInPrefix = clusterInPrefix,
            segmentSizeVintOffset = segment.offset + segmentIdLength,
            segmentSizeVintLength = segment.headerSize - segmentIdLength,
            segmentUnknownSize = segment.unknownSize
        )
    }

    /** Truncates [file] to safe prefix boundary, or null if unneeded/failed. */
    fun truncateToSafePrefix(file: File, layout: MatroskaHeadLayout? = null): Long? {
        val resolved = layout ?: analyzeHead(file) ?: return null
        val prefix = resolved.safePrefixLength
        if (prefix <= 0L) return null
        val current = file.length()
        if (prefix >= current) return prefix
        return runCatching {
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(prefix) }
            prefix
        }.getOrNull()
    }

    /**
     * Appends a minimal Cluster stub (Timecode + timed SimpleBlocks) and updates Segment size
     * so demuxers publish tracks from a head that ends before the first real Cluster.
     * An empty Cluster is not enough for NextLib/FFmpeg — they need block timing metadata.
     */
    fun appendStubClusterForHeadProbe(
        file: File,
        layout: MatroskaHeadLayout,
        contentEnd: Long = file.length()
    ): Long? {
        if (layout.segmentSizeVintOffset < 0L) return null
        if (contentEnd <= layout.segmentDataOffset) return null

        val stub = buildMinimalStubCluster()
        val newLength = contentEnd + stub.size
        // A known-size Segment declaring the full remote length would still run past EOF.
        val patchedSize = if (layout.segmentUnknownSize) {
            null
        } else {
            encodeSizeVintFixedWidth(
                value = newLength - layout.segmentDataOffset,
                length = layout.segmentSizeVintLength
            ) ?: return null
        }

        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                if (contentEnd > raf.length()) return@use null
                raf.setLength(contentEnd)
                raf.seek(contentEnd)
                raf.write(stub)
                if (patchedSize != null) {
                    raf.seek(layout.segmentSizeVintOffset)
                    raf.write(patchedSize)
                }
                newLength
            }
        }.getOrNull()
    }

    /** Minimal Cluster with block timing so demuxers expose Tracks on truncated heads. */
    fun buildMinimalStubCluster(
        frameCount: Int = 40,
        frameDurationMs: Int = 42
    ): ByteArray {
        val blocks = buildSimpleBlockPayload(
            frameCount = frameCount,
            frameDurationMs = frameDurationMs
        )
        val payload = buildElement(ID_CLUSTER_TIMECODE, encodeUint(0L)) + blocks
        return buildElement(ID_CLUSTER, payload)
    }

    private fun buildSimpleBlockPayload(frameCount: Int, frameDurationMs: Int): ByteArray {
        val frame = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x65.toByte(), 0x88.toByte(), 0x84.toByte()
        )
        val out = java.io.ByteArrayOutputStream(frameCount * 24)
        repeat(frameCount) { index ->
            val relativeMs = index * frameDurationMs
            val payload = byteArrayOf(0x81.toByte()) +
                byteArrayOf(
                    ((relativeMs shr 8) and 0xFF).toByte(),
                    (relativeMs and 0xFF).toByte()
                ) +
                byteArrayOf(0x80.toByte()) +
                frame
            out.write(buildElement(ID_SIMPLE_BLOCK, payload))
        }
        return out.toByteArray()
    }

    private fun encodeUint(value: Long): ByteArray {
        require(value >= 0L)
        if (value == 0L) return byteArrayOf(0x00)
        var remaining = value
        val bytes = ArrayList<Byte>()
        while (remaining > 0L) {
            bytes.add(0, (remaining and 0xFF).toByte())
            remaining = remaining ushr 8
        }
        return bytes.toByteArray()
    }

    /** Reads an EBML element header at [absoluteOffset] from [bytes]. */
    fun readElementFromBufferStart(bytes: ByteArray, absoluteOffset: Long): EbmlElement? {
        val relative = readElement(bytes, 0L) ?: return null
        return relative.copy(offset = absoluteOffset)
    }

    fun readElement(bytes: ByteArray, offset: Long): EbmlElement? {
        if (offset < 0L || offset >= bytes.size) return null
        val idVint = readVint(bytes, offset.toInt(), removeLengthMask = false) ?: return null
        val sizePos = offset + idVint.length
        if (sizePos >= bytes.size) return null
        val sizeVint = readVint(bytes, sizePos.toInt(), removeLengthMask = true) ?: return null
        val headerSize = idVint.length + sizeVint.length
        val unknown = isUnknownSize(sizeVint.value, sizeVint.length)
        return EbmlElement(
            id = idVint.value,
            offset = offset,
            headerSize = headerSize,
            dataSize = if (unknown) -1L else sizeVint.value,
            unknownSize = unknown
        )
    }

    fun readVint(bytes: ByteArray, offset: Int, removeLengthMask: Boolean): Vint? {
        if (offset < 0 || offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        var length = -1
        for (i in VINT_LENGTH_MASKS.indices) {
            if ((first.toLong() and VINT_LENGTH_MASKS[i]) != 0L) {
                length = i + 1
                break
            }
        }
        if (length < 0 || offset + length > bytes.size) return null
        var value = bytes[offset].toLong() and 0xFFL
        if (removeLengthMask) {
            value = value and VINT_LENGTH_MASKS[length - 1].inv()
        }
        for (i in 1 until length) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return Vint(value = value, length = length)
    }

    fun isUnknownSize(sizeValue: Long, sizeVintLength: Int): Boolean {
        val dataBits = 7 * sizeVintLength
        if (dataBits >= 63) {
            // 8-byte unknown size uses 56 data bits all set → 0x00FFFFFFFFFFFFFF after mask,
            // first byte contributes with length bit cleared.
            return sizeValue == 0x00FFFFFFFFFFFFFFL
        }
        val unknown = (1L shl dataBits) - 1L
        return sizeValue == unknown
    }

    private fun parseTracksOffsetFromSeekHead(
        bytes: ByteArray,
        seekHead: EbmlElement,
        segmentDataOffset: Long
    ): Long? {
        val dataStart = seekHead.dataOffset.toInt()
        val dataEnd = (seekHead.endExclusiveOrNull() ?: return null).toInt().coerceAtMost(bytes.size)
        var pos = dataStart.toLong()
        while (pos + 2 < dataEnd) {
            val seek = readElement(bytes, pos) ?: break
            val seekEnd = seek.endExclusiveOrNull()
            if (seekEnd == null || seekEnd > dataEnd) break
            if (seek.id == ID_SEEK) {
                var seekId: Long? = null
                var seekPosition: Long? = null
                var inner = seek.dataOffset
                val innerEnd = seekEnd
                while (inner + 2 < innerEnd) {
                    val field = readElement(bytes, inner) ?: break
                    val fieldEnd = field.endExclusiveOrNull()
                    if (fieldEnd == null || fieldEnd > innerEnd) break
                    when (field.id) {
                        ID_SEEK_ID -> seekId = readElementInteger(bytes, field)
                        ID_SEEK_POSITION -> seekPosition = readElementInteger(bytes, field)
                    }
                    inner = fieldEnd
                }
                if (seekId == ID_TRACKS && seekPosition != null && seekPosition >= 0L) {
                    return segmentDataOffset + seekPosition
                }
            }
            pos = seekEnd
        }
        return null
    }

    private fun readElementInteger(bytes: ByteArray, element: EbmlElement): Long? {
        if (element.unknownSize || element.dataSize <= 0L || element.dataSize > 8L) return null
        val start = element.dataOffset.toInt()
        val end = (element.dataOffset + element.dataSize).toInt()
        if (start < 0 || end > bytes.size) return null
        var value = 0L
        for (i in start until end) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return value
    }

    /** Writes a size VINT for [value] using the smallest fitting width. */
    fun encodeSizeVint(value: Long): ByteArray {
        require(value >= 0L)
        for (length in 1..8) {
            val dataBits = 7 * length
            val max = if (dataBits >= 63) Long.MAX_VALUE else (1L shl dataBits) - 1L
            if (value > max) continue
            // Unknown-size reserved pattern must not be emitted for known sizes.
            if (dataBits < 63 && value == max) continue
            val out = ByteArray(length)
            var v = value
            for (i in length - 1 downTo 0) {
                out[i] = (v and 0xFFL).toByte()
                v = v ushr 8
            }
            out[0] = (out[0].toInt() or VINT_LENGTH_MASKS[length - 1].toInt()).toByte()
            return out
        }
        error("value too large for EBML vint: $value")
    }

    /** Writes a fixed-width size VINT for [value] using [length] bytes, or null if invalid or doesn't fit. */
    fun encodeSizeVintFixedWidth(value: Long, length: Int): ByteArray? {
        if (value < 0L || length !in 1..8) return null
        val dataBits = 7 * length
        val max = if (dataBits >= 63) Long.MAX_VALUE else (1L shl dataBits) - 1L
        if (value > max) return null
        if (isUnknownSize(value, length)) return null

        val out = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        out[0] = (out[0].toInt() or VINT_LENGTH_MASKS[length - 1].toInt()).toByte()
        return out
    }

    /** Converts element ID to big-endian byte array. */
    fun elementIdBytes(id: Long): ByteArray {
        require(id > 0L) { "invalid EBML element id: $id" }
        val width = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFF_FFFFL -> 3
            id <= 0xFFFF_FFFFL -> 4
            else -> error("EBML element id too large: 0x${id.toString(16)}")
        }
        val out = ByteArray(width)
        var v = id
        for (i in width - 1 downTo 0) {
            out[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        val parsedLen = run {
            val first = out[0].toInt() and 0xFF
            for (i in VINT_LENGTH_MASKS.indices) {
                if ((first.toLong() and VINT_LENGTH_MASKS[i]) != 0L) return@run i + 1
            }
            -1
        }
        require(parsedLen == width) {
            "id 0x${id.toString(16)} is not a canonical EBML ID wire form (width=$width parsedLen=$parsedLen)"
        }
        return out
    }

    /** Build a minimal EBML element: id + size + payload. */
    fun buildElement(id: Long, payload: ByteArray): ByteArray {
        return elementIdBytes(id) + encodeSizeVint(payload.size.toLong()) + payload
    }

    /** Unknown-size Segment payload marker (common Matroska form). */
    fun encodeUnknownSizeVint8(): ByteArray {
        return byteArrayOf(
            0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
    }

    fun buildSegmentUnknownSize(payload: ByteArray): ByteArray {
        return elementIdBytes(ID_SEGMENT) + encodeUnknownSizeVint8() + payload
    }
}
