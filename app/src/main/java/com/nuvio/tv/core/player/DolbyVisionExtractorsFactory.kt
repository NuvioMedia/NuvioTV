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
 * Matroska is special for two reasons:
 * 1. DV7 RPU arrives as BlockAdditional data that stock Media3 discards before
 *    any TrackOutput, so MKV swaps in the vendored
 *    [com.nuvio.tv.core.player.dvmkv.MatroskaExtractor] for RPU transform.
 * 2. That same vendored extractor sniffs the first DTS sample to distinguish
 *    core DTS vs DTS-HD MA / DTS:X (mkvmerge tags every DTS variant as A_DTS;
 *    stock media3 maps that to core DTS with no inspection). The Matroska swap
 *    is therefore unconditional — even when DV conversion is inactive — so
 *    native-DV / policy-OFF boxes still get correct HD audio mime for bitstream.
 *
 * For any non-DV7 content (or when [config] is inactive) every non-Matroska
 * wrapper is a strict pass-through, so normal playback of all formats is unaffected.
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
        // Matroska is swapped unconditionally: the vendored extractor carries the
        // DTS-HD MA / DTS:X first-sample sniff. Gating behind DV features meant
        // native-DV boxes (policy OFF — most likely to bitstream) never got the
        // sniff and negotiated core DTS for DTS-HD content. With an inactive
        // config, shouldTransform() is false so non-DV samples pass through.
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
        if (!config.active && !stripDvRpu && !stripHdr10PlusSei) return extractor
        val nalFormat = nalFormatFor(extractor) ?: return extractor
        return DolbyVisionExtractor(extractor, config, nalFormat, stripDvRpu, stripHdr10PlusSei)
    }

    private fun nalFormatFor(extractor: Extractor): NalFormat? {
        val name = extractor.javaClass.name
        return when {
            // Media3's Mp4Extractor / FragmentedMp4Extractor rewrite NAL length prefixes into
            // 4-byte Annex-B start codes before calling TrackOutput, and TsExtractor carries
            // Annex-B PES payloads, so all wrapped containers deliver Annex-B samples.
            // RPU is in-band in the sample for these containers, so reachable here.
            name.contains("FragmentedMp4Extractor") ||
                name.contains("Mp4Extractor") ||
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

    private var wrappedOutput: DolbyVisionExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val wrapped = DolbyVisionExtractorOutput(output, config, nalFormat, stripDvRpu, stripHdr10PlusSei)
        wrappedOutput = wrapped
        delegate.init(wrapped)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        // Drop any half-buffered sample so stale bytes never leak into the first frame after a seek.
        wrappedOutput?.resetTracks()
        delegate.seek(position, timeUs)
    }

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

    private val videoTracks = mutableMapOf<Int, NativeOptimizedVideoTrackOutput>()

    fun resetTracks() {
        videoTracks.values.forEach { it.reset() }
    }

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            NativeOptimizedVideoTrackOutput(
                delegate = track,
                config = config,
                nalFormat = nalFormat,
                stripDvRpu = stripDvRpu,
                stripHdr10PlusSei = stripHdr10PlusSei
            ).also { videoTracks[id] = it }
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

    // False until format() has been called. Some extractors (TsExtractor's H265Reader) push sample
    // bytes BEFORE format(), so we must buffer those instead of passing them through, otherwise the
    // first keyframe gets split between the pass-through and the processed path.
    private var formatDecided = false
    private var shouldProcess = false
    private var rewriteSamples = false
    private var shouldStripDovi = false
    private var shouldStripHdr10Plus = false
    private var profile: Int? = null
    private var codecs: String? = null
    private var nalLengthFieldLength = 4

    private var preFormatPassthrough = false

    // Bytes buffered before format() lives outside DefaultAllocator, so cap it: past this
    // size we stop buffering and pass bytes through untouched (a split first frame is
    // better than unbounded off-heap memory LoadControl cannot see).
    private val buffering: Boolean get() = (!formatDecided && !preFormatPassthrough) || shouldProcess

    /** Discards buffered, not-yet-committed sample bytes (called on seek). */
    fun reset() {
        pendingLen = 0
    }

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

    /** Forwards [count] bytes from the start of the pending buffer untouched and removes them. */
    private fun flushPending(count: Int) {
        val n = count.coerceIn(0, pendingLen)
        if (n == 0) return
        scratch.reset(pendingBuf, n)
        delegate.sampleData(scratch, n)
        System.arraycopy(pendingBuf, n, pendingBuf, 0, pendingLen - n)
        pendingLen -= n
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

        val hevcLike = isHevcLike(format, outFormat)
        rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        // DV strip applies to any HEVC track carrying in-band RPU/EL NALs (TS has no DV
        // codec signaling, so it never produces a codecs rewrite but still needs the drop).
        shouldStripDovi = stripDvRpu && hevcLike
        // HDR10+ SEI only exists in HEVC. Running the HEVC NAL parser over H.264 (or any other
        // codec, including AVC-based Dolby Vision profile 9) mis-reads NAL headers and
        // drops/corrupts slices, which shows up as macroblocking.
        shouldStripHdr10Plus = stripHdr10PlusSei && hevcLike

        shouldProcess = rewriteSamples || shouldStripDovi || shouldStripHdr10Plus
        formatDecided = true
        codecs = outFormat.codecs
        delegate.format(outFormat)

        // Bytes that arrived before format() on a track we do not touch go straight through.
        if (!shouldProcess) flushPending(pendingLen)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!buffering) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureInputScratch(length)
        val read = input.read(inputScratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            if (!formatDecided && pendingLen + read > MAX_PRE_FORMAT_BUFFER_BYTES) {
                flushPending(pendingLen)
                preFormatPassthrough = true
                scratch.reset(inputScratch, read)
                delegate.sampleData(scratch, read, sampleDataPart)
                return read
            }
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
        if (!buffering || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        if (!formatDecided && pendingLen + length > MAX_PRE_FORMAT_BUFFER_BYTES) {
            flushPending(pendingLen)
            preFormatPassthrough = true
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
        if (!shouldProcess) {
            // Includes the "metadata before format()" case: nothing to rewrite, release the buffer.
            flushPending(pendingLen)
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        if (pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        // The sample is the `size` bytes that end `offset` bytes before the end of the buffer.
        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEndAbs = pendingLen - carrySize
        val sampleStartAbs = (sampleEndAbs - size).coerceAtLeast(0)
        // Anything before the sample start is not part of the sample; forward it unchanged.
        flushPending(sampleStartAbs)
        val sampleEnd = pendingLen - carrySize

        val mode = if (rewriteSamples) config.conversionMode(profile) else 1
        val formatVal = if (nalFormat == NalFormat.LENGTH_DELIMITED) 1 else 0

        // Process in native C++ layer
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample = pendingBuf,
            sampleLen = sampleEnd,
            nalFormat = formatVal,
            nalLengthFieldLength = nalLengthFieldLength,
            convertDovi = rewriteSamples,
            doviMode = mode,
            doviProfile = profile ?: -1,
            stripDoviRpu = shouldStripDovi,
            stripHdr10Plus = shouldStripHdr10Plus
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
        const val MAX_PRE_FORMAT_BUFFER_BYTES = 8 * 1024 * 1024

        fun isHevcLike(vararg formats: Format): Boolean = formats.any { f ->
            // The codec string is authoritative: AVC-based Dolby Vision (dvav/dva1,
            // profile 9) reports VIDEO_DOLBY_VISION mime but carries an H.264 bitstream.
            val c = f.codecs?.trim()?.lowercase()
            if (!c.isNullOrEmpty()) {
                return@any c.startsWith("hev1") || c.startsWith("hvc1") ||
                    c.startsWith("dvhe") || c.startsWith("dvh1")
            }
            f.sampleMimeType == MimeTypes.VIDEO_H265
        }

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
