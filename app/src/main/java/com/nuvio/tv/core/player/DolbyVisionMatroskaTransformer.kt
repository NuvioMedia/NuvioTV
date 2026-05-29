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
    private val config: DolbyVisionConversionConfig
) : MatroskaExtractor.DolbyVisionSampleTransformer {

    private var lastTransformedLength = 0

    // Reused across samples; grows to the largest frame once.
    private val scratch = ExposedByteArrayOutputStream(64 * 1024)

    private class ExposedByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun backingArray(): ByteArray = buf
    }

    override fun onDolbyVisionBlockAdditionalData(
        blockAdditionalData: ByteArray?,
        blockAddIdType: Int,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        if (blockAdditionalData == null) return null
        val profile = resolveProfile(null, dolbyVisionConfigBytes)
        if (!config.shouldConvert(profile)) return null
        return convertRpuNal(blockAdditionalData, config.conversionMode(profile))
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
        if (!config.shouldConvert(profile)) return null
        // DV5 signal-only unless a mode is forced in Advanced; keep the profile-5 RPU.
        if (profile == 5 && !config.convertDv5Rpu) return null
        val mode = config.conversionMode(profile)
        val baseChanged = rewriteMp4HevcSampleInto(sample, sampleLength, nalUnitLengthFieldLength, mode)

        if (blockAdditionalData == null) {
            return if (baseChanged) finishScratch() else null
        }

        // `blockAdditionalData` is the pending value produced by onDolbyVisionBlockAdditionalData
        // (already an 8.1 RPU). Re-running conversion is a no-op (libdovi returns null for non-DV7
        // input), so we fall back to the already-converted bytes.
        val convertedBlockAdditional = convertRpuNal(blockAdditionalData, mode) ?: blockAdditionalData
        if (!baseChanged) {
            scratch.reset()
            scratch.write(sample, 0, sampleLength)
        }
        return if (appendLengthDelimitedNalToScratch(convertedBlockAdditional, nalUnitLengthFieldLength)) {
            finishScratch()
        } else {
            null
        }
    }

    private fun finishScratch(): ByteArray {
        lastTransformedLength = scratch.size()
        return scratch.backingArray()
    }

    override fun onDolbyVisionCodecString(
        codecs: String?,
        dolbyVisionConfigBytes: ByteArray?
    ): String? {
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

    // ── Conversion + NAL helpers ──

    private fun convertRpuNal(nal: ByteArray, primaryMode: Int): ByteArray? {
        val primary = DoviBridge.convertDv7RpuToDv81(nal, primaryMode)?.takeIf { it.isNotEmpty() }
        if (primary != null) {
            DolbyVisionConversionStats.recordConversionMode(primaryMode)
            return primary
        }
        if (config.allowMode2Fallback && primaryMode == 2) {
            val fallback = DoviBridge.convertDv7RpuToDv81(nal, 1)?.takeIf { it.isNotEmpty() }
            if (fallback != null) DolbyVisionConversionStats.recordConversionMode(1)
            return fallback
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
                // RPU NAL: copy out just this small NAL, convert, normalize layer id.
                nalType == NAL_TYPE_UNSPEC62 -> {
                    val rpu = sample.copyOfRange(offset, offset + nalSize)
                    val convertedNal = normalizeNuhLayerIdToZero(convertRpuNal(rpu, mode) ?: rpu)
                    if (convertedNal !== rpu) changed = true
                    if (!writeLengthField(out, convertedNal.size, nalUnitLengthFieldLength)) return false
                    out.write(convertedNal)
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

    private fun resolveProfile(codecs: String?, configBytes: ByteArray?): Int? {
        resolveProfileFromCodecString(codecs)?.let { return it }
        if (configBytes == null || configBytes.isEmpty()) return null
        return runCatching {
            DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
        }.getOrNull()
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
    }
}
