package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream

/**
 * Strips Dolby Vision RPU NAL units (HEVC NAL type 62) from an HEVC bitstream
 * on-the-fly, leaving the HDR10/HDR10+ base layer intact.
 */
@UnstableApi
internal object HevcDvRpuStripper {

    private const val NAL_TYPE_DV_RPU = 62
    private const val NAL_TYPE_DV_EL = 63

    /**
     * Rewrites a length-delimited (MP4/fMP4) sample, removing any NAL unit
     * whose type is 62 (DV RPU). Returns the rewritten bytes, or null if
     * nothing was stripped (caller should use the original).
     */
    fun stripRpuLengthDelimited(
        sample: ByteArray,
        sampleLen: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (sampleLen < nalLengthFieldLength) return null
        val out = ByteArrayOutputStream(sampleLen)
        var pos = 0
        var changed = false
        while (pos + nalLengthFieldLength <= sampleLen) {
            var nalSize = 0
            for (i in 0 until nalLengthFieldLength) {
                nalSize = (nalSize shl 8) or (sample[pos + i].toInt() and 0xFF)
            }
            val nalStart = pos + nalLengthFieldLength
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) return null
            val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
            if (nalType == NAL_TYPE_DV_RPU || nalType == NAL_TYPE_DV_EL) {
                // Drop this NAL entirely — don't write start code or payload
                changed = true
            } else {
                // Keep: write length prefix + NAL data
                for (i in nalLengthFieldLength - 1 downTo 0) {
                    out.write((nalSize ushr (i * 8)) and 0xFF)
                }
                out.write(sample, nalStart, nalSize)
            }
            pos = nalStart + nalSize
        }
        return if (changed) out.toByteArray() else null
    }

    /**
     * Rewrites an Annex-B (TS/raw HEVC) sample, removing DV RPU NAL units.
     * Returns the rewritten bytes, or null if nothing was stripped.
     */
    fun stripRpuAnnexB(sample: ByteArray, sampleLen: Int): ByteArray? {
        val out = ByteArrayOutputStream(sampleLen)
        var scan = 0
        var changed = false
        while (scan < sampleLen) {
            val startCode = findStartCode(sample, scan, sampleLen)
            if (startCode < 0) {
                out.write(sample, scan, sampleLen - scan)
                break
            }
            val scLen = startCodeLength(sample, startCode, sampleLen)
            val nalBegin = startCode + scLen
            val nextStartCode = findStartCode(sample, nalBegin + 2, sampleLen)
            val nalEnd = if (nextStartCode < 0) sampleLen else nextStartCode

            // Write any bytes before this start code
            if (startCode > scan) out.write(sample, scan, startCode - scan)

            if (nalBegin < nalEnd) {
                val nalType = (sample[nalBegin].toInt() ushr 1) and 0x3F
                if (nalType == NAL_TYPE_DV_RPU || nalType == NAL_TYPE_DV_EL) {
                    changed = true
                    // Drop start code + NAL payload entirely
                } else {
                    out.write(sample, startCode, nalEnd - startCode)
                }
            }
            scan = nalEnd
        }
        return if (changed) out.toByteArray() else null
    }

    private fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i + 2 < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) return i
                if (i + 3 < limit && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(data: ByteArray, offset: Int, limit: Int): Int {
        return if (offset + 3 < limit &&
            data[offset].toInt() == 0 &&
            data[offset + 1].toInt() == 0 &&
            data[offset + 2].toInt() == 0 &&
            data[offset + 3].toInt() == 1
        ) 4 else 3
    }
}
