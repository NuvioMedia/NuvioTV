package com.nuvio.tv.core.player

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.DolbyVisionConfig
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import java.io.ByteArrayOutputStream

/**
 * App-level (AAR mode) implementation of the vendored Matroska extractor's
 * [MatroskaExtractor.DolbyVisionSampleTransformer] seam.
 *
 * Performs the DV7 to DV8.1 conversion for MKV, wired to [DoviBridge]. The
 * extractor calls:
 *
 *  - [onDolbyVisionBlockAdditionalData] when it reads the DV7 enhancement-layer
 *    RPU from a Matroska BlockAdditional; we convert it to an 8.1 RPU NAL.
 *  - [transformHevcSample] just before committing the HEVC sample; we rewrite
 *    the base-layer NALs (dropping EL NALs, converting any in-band RPU) and
 *    append the converted BlockAdditional RPU.
 *  - [onDolbyVisionCodecString] when building the output Format; dvhe.07/dvh1.07
 *    becomes dvhe.08/dvh1.08 to advertise single-layer 8.1.
 *
 * Mode selection, the manual-DV8.1 mode-2 default and its per-RPU fallback to
 * mode 1 all come from [config], so behaviour matches the MP4/TS path in
 * [DolbyVisionExtractorsFactory].
 */
@UnstableApi
internal class DolbyVisionMatroskaTransformer(
    private val config: DolbyVisionConversionConfig,
    private val stripRpuOnly: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false,
) : MatroskaExtractor.DolbyVisionSampleTransformer {

    private var lastTransformedLength = 0

    // Reused across samples; grows to the largest frame once.
    private val scratch = ExposedByteArrayOutputStream(64 * 1024)

    // Cached per-stream once resolved. SEI carrying mastering-display/CLL info
    // is typically only sent on keyframes, so we keep checking each sample
    // until we find it or hit the detection window, rather than deciding off
    // just the first frame.
    private var dv5StripDecision: Boolean? = null
    private var dv5DetectionSamplesChecked = 0

    private fun shouldStripDv5Rpu(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int
    ): Boolean {
        dv5StripDecision?.let { return it }
        val detected = HevcDvRpuStripper.containsHdr10StaticMetadataSei(
            sample, sampleLength, nalUnitLengthFieldLength
        )
        if (detected) {
            dv5StripDecision = true
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=true -> STRIP")
            return true
        }
        dv5DetectionSamplesChecked++
        if (dv5DetectionSamplesChecked >= DV5_DETECTION_SAMPLE_LIMIT) {
            dv5StripDecision = false
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=false after $dv5DetectionSamplesChecked samples -> SKIP")
            return false
        }
        return false
    }

    // Reuses the package-private ExposedByteArrayOutputStream from HevcDvRpuStripper.kt

    override fun onDolbyVisionBlockAdditionalData(
        blockAdditionalData: ByteArray?,
        blockAddIdType: Int,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        if (blockAdditionalData == null) return null
        if (stripRpuOnly) return ByteArray(0)
        val profile = resolveProfile(null, dolbyVisionConfigBytes)
        if (!config.shouldConvert(profile)) return null
        // Single conversion site for BlockAdditional RPUs; transformHevcSample appends as-is.
        return convertRpuNal(blockAdditionalData, config.conversionMode(profile))
    }

    override fun shouldTransform(codecs: String?, dolbyVisionConfigBytes: ByteArray?): Boolean {
        if (stripHdr10PlusSei) return true
        val isDv = codecs?.startsWith("dv", ignoreCase = true) == true ||
                (dolbyVisionConfigBytes != null && dolbyVisionConfigBytes.isNotEmpty())
        if (stripRpuOnly) return isDv
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        return config.shouldConvert(profile)
    }

    override fun onHevcSample(
        sampleSizeBytes: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ) {
        // Telemetry-only seam; nothing to do.
    }

    override fun lastTransformedSampleLength(): Int = lastTransformedLength

    override fun transformHevcSample(
        sampleLengthDelimitedData: ByteArray?,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        val sample = sampleLengthDelimitedData ?: return null
        val profile = resolveProfile(null, dolbyVisionConfigBytes)

        lastTransformedLength = sampleLength

        if (stripRpuOnly) {
            if (profile == 5) {
                if (!shouldStripDv5Rpu(sample, sampleLength, nalUnitLengthFieldLength)) {
                    return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
                }
            }
            // Use the shared ExposedByteArrayOutputStream scratch buffer to avoid GC allocations on every frame
            val changed = HevcDvRpuStripper.stripRpuLengthDelimited(
                sample, sampleLength, nalUnitLengthFieldLength, scratch
            )
            if (changed) {
                val stripped = finishScratch()
                return stripHdr10PlusIfEnabled(stripped, lastTransformedLength, nalUnitLengthFieldLength) ?: stripped
            }
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (!config.shouldConvert(profile)) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (profile == 5 && !config.convertDv5Rpu) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        val mode = config.conversionMode(profile)
        val baseChanged = rewriteMp4HevcSampleInto(sample, sampleLength, nalUnitLengthFieldLength, mode)

        if (blockAdditionalData == null) {
            if (!baseChanged) {
                return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
            }
            val dvResult = finishScratch()
            return stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
        }

        if (!baseChanged) {
            scratch.reset()
            scratch.write(sample, 0, sampleLength)
        }
        // RPU already converted in onDolbyVisionBlockAdditionalData (or left raw on fail).
        if (!appendLengthDelimitedNalToScratch(blockAdditionalData, nalUnitLengthFieldLength)) {
            return null
        }
        val dvResult = finishScratch()
        return stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
    }

    private fun finishScratch(): ByteArray {
        lastTransformedLength = scratch.size()
        return scratch.backingArray()
    }

    override fun onDolbyVisionCodecString(
        codecs: String?,
        dolbyVisionConfigBytes: ByteArray?
    ): String? {
        if (stripRpuOnly) {
            return null
        }
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        if (!config.shouldConvert(profile)) return null
        DolbyVisionConversionStats.recordSourceProfile(profile)
        val normalized = normalizeDolbyVisionCodecString(codecs)
        return if (normalized != null && normalized != codecs) {
            DolbyVisionConversionStats.recordCodecStringRewrite()
            normalized
        } else {
            null
        }
    }

    /**
     * Applies HDR10+ SEI stripping to [data] if [stripHdr10PlusSei] is enabled.
     * Returns null when the feature is off or no HDR10+ was found; otherwise
     * returns the stripped bytes and updates [lastTransformedLength].
     */
    private fun stripHdr10PlusIfEnabled(
        data: ByteArray,
        len: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!stripHdr10PlusSei) return null
        val stripped = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(data, len, nalLengthFieldLength)
        if (stripped != null) {
            lastTransformedLength = stripped.size
            return stripped
        }
        return null
    }

    // ── Conversion + NAL helpers ──

    private fun convertRpuNal(nal: ByteArray, primaryMode: Int): ByteArray? {
        val outLen = DoviBridge.convertDv7RpuToDv81NonAllocating(nal, 0, nal.size, primaryMode)
        if (outLen > 0) {
            DolbyVisionConversionStats.recordConversionMode(primaryMode)
            return DoviBridge.rpuOutBuffer.copyOfRange(0, outLen)
        }
        if (config.allowMode2Fallback && primaryMode == 2) {
            val fallbackLen = DoviBridge.convertDv7RpuToDv81NonAllocating(nal, 0, nal.size, 1)
            if (fallbackLen > 0) {
                DolbyVisionConversionStats.recordConversionMode(1)
                return DoviBridge.rpuOutBuffer.copyOfRange(0, fallbackLen)
            }
        }
        return null
    }

    private fun rewriteMp4HevcSampleInto(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        mode: Int
    ): Boolean {
        if (nalUnitLengthFieldLength !in 1..4) return false
        var offset = 0
        var changed = false
        val out = scratch
        out.reset()
        while (offset + nalUnitLengthFieldLength <= sampleLength) {
            val nalSize = readLengthField(sample, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return false
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLength) return false
            val nalType = if (nalSize >= 1) nalUnitTypeAt(sample, offset) else -1
            val layerId = nuhLayerIdAt(sample, offset, nalSize)
            when {
                // Enhancement-layer NAL that isn't the RPU: drop it.
                layerId > 0 && nalType != NAL_TYPE_UNSPEC62 -> changed = true
                // P7 single-track: EL is in type-63 NALs at layer 0.
                nalType == NAL_TYPE_UNSPEC63 -> changed = true
                // RPU NAL: convert directly from sample buffer without JVM allocations
                nalType == NAL_TYPE_UNSPEC62 -> {
                    val outLen = DoviBridge.convertDv7RpuToDv81NonAllocating(sample, offset, nalSize, mode)
                    if (outLen > 0) {
                        changed = true
                        if (!writeLengthField(out, outLen, nalUnitLengthFieldLength)) return false
                        out.write(DoviBridge.rpuOutBuffer, 0, outLen)
                    } else {
                        // Conversion failed: forward the ORIGINAL RPU NAL, normalizing the
                        // 2-byte NAL header in place on the output stream. No allocation:
                        // we copy straight from the sample buffer.
                        if (!writeLengthField(out, nalSize, nalUnitLengthFieldLength)) return false
                        if (nalSize >= 2) {
                            out.write(sample[offset].toInt() and 0xFE)
                            out.write(sample[offset + 1].toInt() and 0x07)
                            if (nalSize > 2) out.write(sample, offset + 2, nalSize - 2)
                            changed = true
                        } else {
                            out.write(sample, offset, nalSize)
                        }
                    }
                }
                // Base-layer NAL: forward straight from the sample buffer, no copy.
                else -> {
                    if (!writeLengthField(out, nalSize, nalUnitLengthFieldLength)) return false
                    out.write(sample, offset, nalSize)
                }
            }
            offset += nalSize
        }
        if (offset != sampleLength) return false
        if (!changed) return false
        return out.size() > 0
    }

    private fun appendLengthDelimitedNalToScratch(
        nalPayload: ByteArray,
        nalUnitLengthFieldLength: Int
    ): Boolean {
        if (nalUnitLengthFieldLength !in 1..4 || nalPayload.isEmpty()) return false
        val maxNalSize = when (nalUnitLengthFieldLength) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            else -> Int.MAX_VALUE
        }
        if (nalPayload.size > maxNalSize) return false
        if (!writeLengthField(scratch, nalPayload.size, nalUnitLengthFieldLength)) return false
        scratch.write(nalPayload)
        return true
    }

    private fun normalizeDolbyVisionCodecString(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        val profileValue = parts[1].toIntOrNull() ?: return null
        if (profileValue != 5 && profileValue != 7) return null
        val width = parts[1].length.coerceAtLeast(2)
        parts[1] = "8".padStart(width, '0')
        return parts.joinToString(".")
    }

    private fun downgradeDolbyVisionCodecStringToHevc(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        return when (parts[0].lowercase()) {
            "dvhe" -> { parts[0] = "hvc1"; parts.joinToString(".") }
            "dvh1" -> { parts[0] = "hev1"; parts.joinToString(".") }
            else -> null
        }
    }

    private fun resolveProfile(codecs: String?, configBytes: ByteArray?): Int? {
        if (configBytes != null && configBytes.isNotEmpty()) {
            val parsedProfile = runCatching {
                DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
            }.getOrNull()
            if (parsedProfile != null) return parsedProfile
        }
        return resolveProfileFromCodecString(codecs)
    }

    private fun resolveProfileFromCodecString(codecs: String?): Int? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        return parts[1].toIntOrNull()
    }

    private fun nalUnitTypeAt(sample: ByteArray, offset: Int): Int =
        (sample[offset].toInt() ushr 1) and 0x3F

    private fun nuhLayerIdAt(sample: ByteArray, offset: Int, nalSize: Int): Int {
        if (nalSize < 2) return 0
        val b0 = sample[offset].toInt() and 0x01
        val b1 = sample[offset + 1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun getNuhLayerId(nalPayload: ByteArray): Int {
        if (nalPayload.size < 2) return 0
        val b0 = nalPayload[0].toInt() and 0x01
        val b1 = nalPayload[1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2 || getNuhLayerId(nalPayload) == 0) return nalPayload
        val out = nalPayload.copyOf()
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(out: ByteArrayOutputStream, value: Int, lengthBytes: Int): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out.write((value ushr (shift * 8)) and 0xFF)
        }
        return true
    }

    private companion object {
        const val NAL_TYPE_UNSPEC62 = 62
        const val NAL_TYPE_UNSPEC63 = 63
        const val DV5_DETECTION_SAMPLE_LIMIT = 15
    }
}
