package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream

/**
 * Strips HDR10+ SEI messages from an HEVC bitstream, leaving the HDR10 base
 * layer and all other SEI content intact.
 *
 * HDR10+ metadata arrives as user_data_registered_itu_t_t35 SEI (payload type 4)
 * inside HEVC Prefix SEI (NAL type 39) or Suffix SEI (NAL type 40) NAL units.
 * Identified by: ITU-T T.35 country_code=0xB5, provider_code=0x003C (Samsung),
 * provider_oriented_code=0x0001.
 *
 * Per-SEI-message filtering is used: non-HDR10+ messages in the same NAL unit
 * are preserved. If an entire SEI NAL contains only HDR10+ messages it is
 * dropped; if it has mixed content the NAL is reconstructed without HDR10+.
 */
@UnstableApi
internal object HevcHdr10PlusStripper {

    private const val NAL_TYPE_PREFIX_SEI = 39
    private const val NAL_TYPE_SUFFIX_SEI = 40
    private const val SEI_PAYLOAD_TYPE_USER_DATA_REGISTERED = 4

    // ITU-T T.35: country_code=0xB5 (USA), provider_code=0x003C, provider_oriented_code=0x0001
    private val HDR10_PLUS_T35_SIGNATURE = byteArrayOf(
        0xB5.toByte(), 0x00, 0x3C.toByte(), 0x00, 0x01
    )

    /**
     * Rewrites a length-delimited (MP4/fMP4) sample, removing HDR10+ SEI messages.
     * Returns the rewritten bytes, or null if nothing was stripped.
     */
    fun stripHdr10PlusLengthDelimited(
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
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) {
                // Fallback: If length field parsing fails due to missing/corrupted CSD,
                // perform an emergency Annex-B byte scan pass over this sample frame.
                return stripHdr10PlusAnnexB(sample, sampleLen)
            }
            val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
            if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                val filtered = filterSeiNal(sample, nalStart, nalSize)
                when {
                    filtered == null -> {
                        // No HDR10+ in this NAL; keep as-is
                        for (i in nalLengthFieldLength - 1 downTo 0) {
                            out.write((nalSize ushr (i * 8)) and 0xFF)
                        }
                        out.write(sample, nalStart, nalSize)
                    }
                    filtered.isNotEmpty() -> {
                        // Some HDR10+ stripped; output reduced SEI NAL
                        changed = true
                        for (i in nalLengthFieldLength - 1 downTo 0) {
                            out.write((filtered.size ushr (i * 8)) and 0xFF)
                        }
                        out.write(filtered)
                    }
                    else -> changed = true // entire NAL was HDR10+; drop it
                }
            } else {
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
     * Rewrites an Annex-B (TS/raw HEVC) sample, removing HDR10+ SEI messages.
     * Returns the rewritten bytes, or null if nothing was stripped.
     */
    fun stripHdr10PlusAnnexB(sample: ByteArray, sampleLen: Int): ByteArray? {
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

            if (startCode > scan) out.write(sample, scan, startCode - scan)

            if (nalBegin < nalEnd) {
                val nalSize = nalEnd - nalBegin
                val nalType = (sample[nalBegin].toInt() ushr 1) and 0x3F
                if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                    val filtered = filterSeiNal(sample, nalBegin, nalSize)
                    when {
                        filtered == null -> out.write(sample, startCode, nalEnd - startCode)
                        filtered.isNotEmpty() -> {
                            changed = true
                            out.write(sample, startCode, scLen)
                            out.write(filtered)
                        }
                        else -> changed = true // entire NAL was HDR10+; drop it
                    }
                } else {
                    out.write(sample, startCode, nalEnd - startCode)
                }
            }
            scan = nalEnd
        }
        return if (changed) out.toByteArray() else null
    }

    /**
     * Parses a SEI NAL unit and removes HDR10+ messages.
     * Returns null  → no HDR10+ found (caller keeps original)
     * Returns empty → all messages were HDR10+ (caller drops NAL)
     * Returns bytes → filtered NAL with HDR10+ messages removed
     */
    private fun filterSeiNal(data: ByteArray, nalOffset: Int, nalSize: Int): ByteArray? {
        if (nalSize < 3) return null

        // 1. Un-escape the NAL unit payload to get raw RBSP bytes (remove 0x00 0x00 0x03)
        val rbsp = ByteArrayOutputStream(nalSize)
        // Preserve the 2-byte HEVC NAL header
        rbsp.write(data[nalOffset].toInt() and 0xFF)
        rbsp.write(data[nalOffset + 1].toInt() and 0xFF)

        var i = nalOffset + 2
        val end = nalOffset + nalSize
        while (i < end) {
            if (i + 2 < end && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 3) {
                rbsp.write(0)
                rbsp.write(0)
                i += 3
            } else {
                rbsp.write(data[i].toInt() and 0xFF)
                i++
            }
        }

        val rbspData = rbsp.toByteArray()
        val rbspEnd = rbspData.size

        // 2. Filter out HDR10+ messages from the un-escaped RBSP data
        val outRbsp = ByteArrayOutputStream(rbspEnd)
        outRbsp.write(rbspData[0].toInt() and 0xFF)
        outRbsp.write(rbspData[1].toInt() and 0xFF)

        var pos = 2
        var hasHdr10Plus = false

        while (pos < rbspEnd) {
            // Lone 0x80 at end = RBSP stop bit; nothing more to parse
            if (rbspEnd - pos == 1 && (rbspData[pos].toInt() and 0xFF) == 0x80) break

            val msgStart = pos

            // Read SEI payloadType (variable-length encoding)
            var payloadType = 0
            while (pos < rbspEnd) {
                val b = rbspData[pos++].toInt() and 0xFF
                payloadType += b
                if (b != 0xFF) break
            }

            // Read SEI payloadSize (variable-length encoding)
            var payloadSize = 0
            while (pos < rbspEnd) {
                val b = rbspData[pos++].toInt() and 0xFF
                payloadSize += b
                if (b != 0xFF) break
            }

            if (pos + payloadSize > rbspEnd) return null // malformed; keep original
            val payloadStart = pos
            pos += payloadSize
            val msgEnd = pos

            if (payloadType == SEI_PAYLOAD_TYPE_USER_DATA_REGISTERED &&
                payloadSize >= HDR10_PLUS_T35_SIGNATURE.size &&
                matchesSignature(rbspData, payloadStart, HDR10_PLUS_T35_SIGNATURE)
            ) {
                hasHdr10Plus = true // strip this SEI message
            } else {
                outRbsp.write(rbspData, msgStart, msgEnd - msgStart) // keep verbatim
            }
        }

        if (!hasHdr10Plus) return null
        if (outRbsp.size() <= 2) return ByteArray(0) // only NAL header left → whole NAL was HDR10+

        outRbsp.write(0x80) // RBSP stop bit
        val filteredRbsp = outRbsp.toByteArray()

        // 3. Re-escape the modified RBSP data back into a valid NAL unit payload
        val finalNal = ByteArrayOutputStream(filteredRbsp.size)
        finalNal.write(filteredRbsp[0].toInt() and 0xFF)
        finalNal.write(filteredRbsp[1].toInt() and 0xFF)

        var consecutiveZeros = 0
        if (filteredRbsp[0] == 0.toByte()) consecutiveZeros++
        if (filteredRbsp[1] == 0.toByte()) {
            consecutiveZeros = if (consecutiveZeros == 1) 2 else 1
        } else {
            consecutiveZeros = 0
        }

        for (j in 2 until filteredRbsp.size) {
            val b = filteredRbsp[j].toInt() and 0xFF
            if (consecutiveZeros == 2 && (b == 0 || b == 1 || b == 2 || b == 3)) {
                finalNal.write(0x03)
                consecutiveZeros = 0
            }
            finalNal.write(b)
            if (b == 0) {
                consecutiveZeros++
            } else {
                consecutiveZeros = 0
            }
        }

        return finalNal.toByteArray()
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

    private fun matchesSignature(data: ByteArray, offset: Int, sig: ByteArray): Boolean {
        if (offset + sig.size > data.size) return false
        for (i in sig.indices) {
            if (data[offset + i] != sig[i]) return false
        }
        return true
    }

    private fun startCodeLength(data: ByteArray, offset: Int, limit: Int): Int {
        return if (offset + 3 < limit &&
            data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 &&
            data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1
        ) 4 else 3
    }
}