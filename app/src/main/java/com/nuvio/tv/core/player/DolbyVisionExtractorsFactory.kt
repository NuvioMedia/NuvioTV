package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * App-level Dolby Vision Profile 7 to 8.1 conversion that needs no forked Media3.
 *
 * It wraps a stock [ExtractorsFactory] and, for video tracks of containers whose
 * RPU rides in-band as NAL units (MP4 / fMP4 = length-delimited, TS = Annex-B),
 * intercepts the sample stream at the [TrackOutput] level and rewrites the
 * Dolby Vision RPU NAL (type 62) via [DoviBridge], drops the enhancement-layer
 * NAL units, and rewrites the codec string (dvhe.07 becomes dvhe.08).
 *
 * Matroska is special: the RPU arrives as BlockAdditional data that stock Media3
 * discards before any TrackOutput, so for MKV this factory swaps in the vendored
 * [com.nuvio.tv.core.player.dvmkv.MatroskaExtractor], which surfaces the RPU through
 * [DolbyVisionMatroskaTransformer].
 *
 * For any non-DV7 content (or when [config] is inactive) every wrapper is a strict
 * pass-through, so normal playback of all formats is unaffected.
 */
@UnstableApi
internal class DolbyVisionExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val config: DolbyVisionConversionConfig
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        if (!config.active) return extractor
        // Matroska: the DV7 RPU rides in BlockAdditional, which the stock
        // MatroskaExtractor discards before any TrackOutput. Swap it for the
        // vendored extractor that surfaces the RPU through a transformer.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            return DvMatroskaExtractor(
                DefaultSubtitleParserFactory(),
                /* flags= */ 0,
                DolbyVisionMatroskaTransformer(config)
            )
        }
        val nalFormat = nalFormatFor(extractor) ?: return extractor
        return DolbyVisionExtractor(extractor, config, nalFormat)
    }

    private fun nalFormatFor(extractor: Extractor): NalFormat? {
        val name = extractor.javaClass.name
        return when {
            // RPU is in-band in the sample for these containers, so reachable here.
            name.contains("FragmentedMp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("Mp4Extractor") -> NalFormat.LENGTH_DELIMITED
            name.contains("TsExtractor") -> NalFormat.ANNEX_B
            else -> null
        }
    }

    private companion object {
        const val STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor"
    }
}

/** How HEVC NAL units are framed in the sample stream for a given container. */
internal enum class NalFormat { ANNEX_B, LENGTH_DELIMITED }

/**
 * Per-playback DV7 to 8.1 conversion diagnostics, fed by the factory/transformer and read
 * by the player diagnostics. Reset per playback alongside the DoviBridge counters.
 */
internal object DolbyVisionConversionStats {
    private val codecStringRewriteCount = AtomicLong(0)
    @Volatile private var lastSourceProfile: Int? = null
    @Volatile private var lastConversionMode: Int? = null

    fun reset() {
        codecStringRewriteCount.set(0)
        lastSourceProfile = null
        lastConversionMode = null
    }

    /** Records the SOURCE DV profile (pre-conversion), e.g. 7. */
    fun recordSourceProfile(profile: Int?) {
        if (profile != null) lastSourceProfile = profile
    }

    fun recordConversionMode(mode: Int) {
        lastConversionMode = mode
    }

    fun recordCodecStringRewrite() {
        codecStringRewriteCount.incrementAndGet()
    }

    fun getCodecStringRewriteCount(): Long = codecStringRewriteCount.get()
    fun getLastSourceProfile(): Int? = lastSourceProfile
    fun getLastSelectedConversionMode(): Int? = lastConversionMode
}

/**
 * Drives the per-stream conversion decision. Mirrors the auto-pick used by the
 * extractor hook installer: profile-7 default = mode 1 (ToMel); profile-7 with
 * preserve-mapping = mode 5 (8.1 preserve); profile 5 = mode 3; a [forcedMode]
 * in 0..4 overrides all of it.
 */
internal data class DolbyVisionConversionConfig(
    val active: Boolean,
    val forcedMode: Int = -1,
    val preserveMapping: Boolean = false,
    val dv5Enabled: Boolean = false,
    /** True when the user explicitly chose "Convert to DV8.1" (not AUTO). */
    val manualDv81: Boolean = false
) {
    /** Manual mode-2 default with per-RPU fallback to mode 1 (not for AUTO / forced). */
    val allowMode2Fallback: Boolean get() = manualDv81 && forcedMode !in 0..4

    /**
     * DV5 via the toggle is signal-only (codec rewritten to 8.1, profile-5 RPU kept). A
     * libdovi RPU rewrite runs only when a mode is forced in Advanced, OR when the user
     * explicitly chose Convert to DV8.1 with DV5 enabled. DV7 always converts.
     */
    val convertDv5Rpu: Boolean get() = forcedMode in 0..4 || (dv5Enabled && manualDv81)

    /** True when a track of [profile] should be converted. */
    fun shouldConvert(profile: Int?): Boolean {
        if (!active) return false
        return when (profile) {
            7 -> true
            // DV5 is already single-layer; only convert it when the user explicitly chose
            // Convert to DV8.1. AUTO leaves DV5 alone (converting it breaks colors).
            5 -> dv5Enabled && manualDv81
            else -> false
        }
    }

    /** libdovi conversion mode to use for [profile]. */
    fun conversionMode(profile: Int?): Int {
        if (forcedMode in 0..4) return forcedMode
        return when {
            (profile == 7 || profile == null) && preserveMapping -> 5
            profile == 5 -> 3
            manualDv81 -> 2 // manual Convert to DV8.1 prefers mode 2 (falls back to 1)
            else -> 1       // AUTO convert stays on mode 1
        }
    }
}

/** Wraps an [Extractor] to inject a DV-rewriting [ExtractorOutput]. */
@UnstableApi
private class DolbyVisionExtractor(
    private val delegate: Extractor,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(DolbyVisionExtractorOutput(output, config, nalFormat))
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Wraps an [ExtractorOutput] to swap video [TrackOutput]s for DV-rewriting ones. */
@UnstableApi
private class DolbyVisionExtractorOutput(
    private val delegate: ExtractorOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            DolbyVisionTrackOutput(track, config, nalFormat)
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * The actual RPU-rewriting [TrackOutput]. Buffers a sample's bytes and, on
 * [sampleMetadata], rewrites the DV RPU NAL (and drops EL NAL units) before
 * forwarding to the delegate. A no-op pass-through unless the track is a DV
 * profile the config wants converted.
 */
@UnstableApi
private class DolbyVisionTrackOutput(
    private val delegate: TrackOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat
) : TrackOutput {

    private val scratch = ParsableByteArray()
    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var inputScratch = ByteArray(0)
    private var outBuf = ByteArray(0)
    private var outLen = 0

    private var converting = false
    private var rewriteSamples = false
    private var profile: Int? = null
    private var codecs: String? = null
    private var nalLengthFieldLength = 4

    private fun ensurePendingCapacity(extra: Int) {
        val need = pendingLen + extra
        if (pendingBuf.size < need) {
            var newSize = if (pendingBuf.isEmpty()) 16 * 1024 else pendingBuf.size
            while (newSize < need) newSize = newSize shl 1
            pendingBuf = pendingBuf.copyOf(newSize)
        }
    }

    private fun ensureInputScratch(size: Int) {
        if (inputScratch.size < size) {
            var newSize = if (inputScratch.isEmpty()) 16 * 1024 else inputScratch.size
            while (newSize < size) newSize = newSize shl 1
            inputScratch = ByteArray(newSize)
        }
    }

    private fun outReset() {
        outLen = 0
    }

    private fun outEnsureCapacity(extra: Int) {
        val need = outLen + extra
        if (outBuf.size < need) {
            var newSize = if (outBuf.isEmpty()) 16 * 1024 else outBuf.size
            while (newSize < need) newSize = newSize shl 1
            outBuf = outBuf.copyOf(newSize)
        }
    }

    private fun outWrite(src: ByteArray, srcPos: Int, len: Int) {
        if (len <= 0) return
        outEnsureCapacity(len)
        System.arraycopy(src, srcPos, outBuf, outLen, len)
        outLen += len
    }

    private fun outWriteLengthPrefix(value: Int, lengthFieldLength: Int) {
        outEnsureCapacity(lengthFieldLength)
        for (i in lengthFieldLength - 1 downTo 0) {
            outBuf[outLen] = ((value ushr (i * 8)) and 0xFF).toByte()
            outLen += 1
        }
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        profile = parseDvProfile(format.codecs)
        converting = config.shouldConvert(profile)
        // Signal-only DV5 (toggle) just relabels the codec; its samples pass through
        // untouched, so skip the per-sample buffer/scan entirely.
        rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        nalLengthFieldLength = parseNalLengthFieldLength(format)
        var outFormat = format
        if (converting) {
            DolbyVisionConversionStats.recordSourceProfile(profile)
            val rewritten = rewriteDvCodecString(format.codecs)
            if (rewritten != null && rewritten != format.codecs) {
                outFormat = format.buildUpon().setCodecs(rewritten).build()
                DolbyVisionConversionStats.recordCodecStringRewrite()
            }
        }
        codecs = outFormat.codecs
        delegate.format(outFormat)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!rewriteSamples) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureInputScratch(length)
        val read = input.read(inputScratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            ensurePendingCapacity(read)
            System.arraycopy(inputScratch, 0, pendingBuf, pendingLen, read)
            pendingLen += read
        } else {
            scratch.reset(inputScratch, read)
            delegate.sampleData(scratch, read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int
    ) {
        if (!rewriteSamples || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensurePendingCapacity(length)
        data.readBytes(pendingBuf, pendingLen, length)
        pendingLen += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (!rewriteSamples || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        // `offset` = trailing buffered bytes that belong to the NEXT sample. Keep
        // them in `pending` and rewrite only this sample's bytes. We deliver the
        // rewritten sample fresh and pass offset 0 (nothing delivered after it),
        // which keeps sizing correct even when the rewrite changes the length.
        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEnd = pendingLen - carrySize
        val rewrittenLen = when (nalFormat) {
            NalFormat.ANNEX_B -> rewriteAnnexB(pendingBuf, sampleEnd)
            NalFormat.LENGTH_DELIMITED -> rewriteLengthDelimited(pendingBuf, sampleEnd, nalLengthFieldLength)
        }
        val useRewritten = rewrittenLen > 0
        val outputData = if (useRewritten) outBuf else pendingBuf
        val outputLen = if (useRewritten) rewrittenLen else sampleEnd
        scratch.reset(outputData, outputLen)
        delegate.sampleData(scratch, outputLen)
        delegate.sampleMetadata(timeUs, flags, outputLen, 0, cryptoData)
        if (carrySize > 0) {
            System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        }
        pendingLen = carrySize
    }

    // ── Length-delimited (MP4 / fMP4) ──
    private fun rewriteLengthDelimited(sample: ByteArray, sampleLen: Int, lengthFieldLength: Int): Int {
        if (sampleLen < lengthFieldLength) return -1
        outReset()
        var changed = false
        var pos = 0
        while (pos + lengthFieldLength <= sampleLen) {
            var nalSize = 0
            for (i in 0 until lengthFieldLength) {
                nalSize = (nalSize shl 8) or (sample[pos + i].toInt() and 0xFF)
            }
            val nalStart = pos + lengthFieldLength
            if (nalSize <= 0 || nalStart + nalSize > sampleLen) return -1
            val nalType = nalUnitTypeAt(sample, nalStart)
            val layerId = nuhLayerIdAt(sample, nalStart, nalSize)
            when {
                // Enhancement-layer NAL that isn't the RPU: drop it.
                layerId > 0 && nalType != NAL_TYPE_DV_RPU -> changed = true
                // RPU NAL: copy out just this small NAL, convert, normalize layer id.
                nalType == NAL_TYPE_DV_RPU -> {
                    val nal = sample.copyOfRange(nalStart, nalStart + nalSize)
                    val transformed = normalizeNuhLayerIdToZero(convertDvRpu(nal) ?: nal)
                    if (transformed !== nal) changed = true
                    outWriteLengthPrefix(transformed.size, lengthFieldLength)
                    outWrite(transformed, 0, transformed.size)
                }
                // Base-layer NAL: forward straight from the sample buffer, no copy.
                else -> {
                    outWriteLengthPrefix(nalSize, lengthFieldLength)
                    outWrite(sample, nalStart, nalSize)
                }
            }
            pos = nalStart + nalSize
        }
        if (pos != sampleLen) return -1
        return if (changed) outLen else 0
    }

    // ── Annex-B (TS) ── ported from H265Reader.DolbyVisionTransformingTrackOutput
    private fun rewriteAnnexB(sample: ByteArray, sampleLen: Int): Int {
        outReset()
        var scan = 0
        var changed = false
        while (scan < sampleLen) {
            val start = findStartCode(sample, scan, sampleLen)
            if (start < 0) break
            var next = findStartCode(sample, start + 3, sampleLen)
            if (next < 0) next = sampleLen
            if (start > scan) outWrite(sample, scan, start - scan)
            val scLen = startCodeLength(sample, start, next)
            val payloadOffset = start + scLen
            if (payloadOffset >= next) {
                outWrite(sample, start, next - start)
            } else {
                val nalSize = next - payloadOffset
                val nalType = nalUnitTypeAt(sample, payloadOffset)
                val layerId = nuhLayerIdAt(sample, payloadOffset, nalSize)
                when {
                    // Enhancement-layer NAL that isn't the RPU: drop it.
                    layerId > 0 && nalType != NAL_TYPE_DV_RPU -> changed = true
                    // RPU NAL: copy out just this small NAL, convert, normalize layer id.
                    nalType == NAL_TYPE_DV_RPU -> {
                        val nal = sample.copyOfRange(payloadOffset, next)
                        val transformed = normalizeNuhLayerIdToZero(convertDvRpu(nal) ?: nal)
                        if (transformed !== nal) changed = true
                        outWrite(sample, start, scLen)
                        outWrite(transformed, 0, transformed.size)
                    }
                    // Base-layer NAL: forward start code + payload straight from the buffer.
                    else -> outWrite(sample, start, next - start)
                }
            }
            scan = next
        }
        if (scan < sampleLen) outWrite(sample, scan, sampleLen - scan)
        return if (changed) outLen else 0
    }

    private fun convertDvRpu(nal: ByteArray): ByteArray? {
        val mode = config.conversionMode(profile)
        var converted = DoviBridge.convertDv7RpuToDv81(nal, mode)?.takeIf { it.isNotEmpty() }
        if (converted != null) {
            DolbyVisionConversionStats.recordConversionMode(mode)
        } else if (config.allowMode2Fallback && mode == 2) {
            converted = DoviBridge.convertDv7RpuToDv81(nal, 1)?.takeIf { it.isNotEmpty() }
            if (converted != null) DolbyVisionConversionStats.recordConversionMode(1)
        }
        return converted
    }

    private companion object {
        const val NAL_TYPE_DV_RPU = 62

        fun nalUnitTypeAt(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() ushr 1) and 0x3F

        fun nuhLayerIdAt(data: ByteArray, offset: Int, nalSize: Int): Int {
            if (nalSize < 2) return 0
            val b0 = data[offset].toInt() and 0x01
            val b1 = data[offset + 1].toInt() and 0xF8
            return (b0 shl 5) or (b1 ushr 3)
        }

        fun parseDvProfile(codecs: String?): Int? {
            if (codecs.isNullOrBlank()) return null
            val m = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.")
                .find(codecs.trim().lowercase()) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        /** dvhe.05/.07.xx becomes dvhe.08.xx so the Format advertises single-layer 8.1. */
        fun rewriteDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.")
                .replace(codecs) { mr -> "${mr.groupValues[1]}.08." }
        }

        fun parseNalLengthFieldLength(format: Format): Int {
            // HEVC hvcC: lengthSizeMinusOne is the low 2 bits of the byte at
            // index 21. Fall back to the near-universal 4 if not parseable.
            val csd = format.initializationData.firstOrNull() ?: return 4
            if (csd.size <= 21) return 4
            // Only trust it if this looks like an hvcC (configurationVersion == 1).
            if (csd[0].toInt() != 1) return 4
            return (csd[21].toInt() and 0x03) + 1
        }

        fun nuhLayerId(nal: ByteArray): Int {
            if (nal.size < 2) return 0
            val b0 = nal[0].toInt() and 0x01
            val b1 = nal[1].toInt() and 0xF8
            return (b0 shl 5) or (b1 ushr 3)
        }

        fun normalizeNuhLayerIdToZero(nal: ByteArray): ByteArray {
            if (nal.size < 2 || nuhLayerId(nal) == 0) return nal
            val copy = nal.copyOf()
            copy[0] = (copy[0].toInt() and 0xFE).toByte()
            copy[1] = (copy[1].toInt() and 0x07).toByte()
            return copy
        }

        fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
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

        fun startCodeLength(data: ByteArray, startCodeOffset: Int, limit: Int): Int {
            return if (startCodeOffset + 3 < limit &&
                data[startCodeOffset].toInt() == 0 &&
                data[startCodeOffset + 1].toInt() == 0 &&
                data[startCodeOffset + 2].toInt() == 0 &&
                data[startCodeOffset + 3].toInt() == 1
            ) 4 else 3
        }
    }
}
