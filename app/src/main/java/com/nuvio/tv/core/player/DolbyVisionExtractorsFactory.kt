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
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        if (!config.active && !stripDvRpu && !stripHdr10PlusSei) return extractor
        // Matroska: the DV7 RPU rides in BlockAdditional, which the stock
        // MatroskaExtractor discards before any TrackOutput. Swap it for the
        // vendored extractor that surfaces the RPU through a transformer.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            return DvMatroskaExtractor(
                DefaultSubtitleParserFactory(),
                /* flags= */ 0,
                DolbyVisionMatroskaTransformer(
                    config = if (config.active) config else DolbyVisionConversionConfig(active = false),
                    stripRpuOnly = stripDvRpu && !config.active,
                    stripHdr10PlusSei = stripHdr10PlusSei,
                )
            )
        }
        val nalFormat = nalFormatFor(extractor) ?: return extractor
        return DolbyVisionExtractor(extractor, config, nalFormat, stripDvRpu, stripHdr10PlusSei)
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
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(DolbyVisionExtractorOutput(output, config, nalFormat, stripDvRpu, stripHdr10PlusSei))
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
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            NativeOptimizedVideoTrackOutput(
                delegate = track,
                config = config,
                nalFormat = nalFormat,
                stripDvRpu = stripDvRpu,
                stripHdr10PlusSei = stripHdr10PlusSei
            )
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class NativeOptimizedVideoTrackOutput(
    private val delegate: TrackOutput,
    private val config: DolbyVisionConversionConfig,
    private val nalFormat: NalFormat,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : TrackOutput {

    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var inputScratch = ByteArray(0)
    private val scratch = ParsableByteArray()

    private var converting = false
    private var shouldProcess = false
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

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        profile = parseDvProfile(format.codecs)
        converting = config.active && config.shouldConvert(profile)
        nalLengthFieldLength = parseNalLengthFieldLength(format)

        val strippedCodecs = if (stripDvRpu) stripDvCodecString(format.codecs) else null
        val codecsToUse = strippedCodecs ?: if (converting) rewriteDvCodecString(format.codecs) else format.codecs

        var outFormat = format
        if (codecsToUse != null && codecsToUse != format.codecs) {
            outFormat = format.buildUpon().setCodecs(codecsToUse).build()
        }
        if (stripDvRpu && strippedCodecs != null) {
            outFormat = outFormat.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        }

        val rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        val shouldStripDovi = stripDvRpu && strippedCodecs != null && strippedCodecs != format.codecs
        
        shouldProcess = rewriteSamples || shouldStripDovi || stripHdr10PlusSei
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
        if (!shouldProcess) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
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
        if (!shouldProcess || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
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
        if (!shouldProcess || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEnd = pendingLen - carrySize

        val mode = if (converting) config.conversionMode(profile) else 1
        val formatVal = if (nalFormat == NalFormat.LENGTH_DELIMITED) 1 else 0

        // Process in native C++ layer
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample = pendingBuf,
            sampleLen = sampleEnd,
            nalFormat = formatVal,
            nalLengthFieldLength = nalLengthFieldLength,
            convertDovi = converting,
            doviMode = mode,
            doviProfile = profile ?: -1,
            stripDoviRpu = stripDvRpu,
            stripHdr10Plus = stripHdr10PlusSei
        )

        val useRewritten = written > 0
        val outputData = if (useRewritten) DoviBridge.rpuOutBuffer else pendingBuf
        val outputLen = if (useRewritten) written else sampleEnd

        scratch.reset(outputData, outputLen)
        delegate.sampleData(scratch, outputLen)
        delegate.sampleMetadata(timeUs, flags, outputLen, 0, cryptoData)

        if (carrySize > 0) {
            System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        }
        pendingLen = carrySize
    }

    private companion object {
        fun parseDvProfile(codecs: String?): Int? {
            if (codecs.isNullOrBlank()) return null
            val m = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.")
                .find(codecs.trim().lowercase()) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        fun rewriteDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.")
                .replace(codecs) { mr -> "${mr.groupValues[1]}.08." }
        }

        fun stripDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvh1)\\.[0-9]+\\.[0-9]+")
                .replace(codecs.trim()) { "hvc1.2.4.L153.B0" }
                .takeIf { it != codecs }
        }

        fun parseNalLengthFieldLength(format: Format): Int {
            val csd = format.initializationData.firstOrNull() ?: return 4
            if (csd.size <= 21) return 4
            if (csd[0].toInt() != 1) return 4
            return (csd[21].toInt() and 0x03) + 1
        }
    }
}
