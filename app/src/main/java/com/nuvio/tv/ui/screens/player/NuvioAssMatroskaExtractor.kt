package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.regex.Pattern
import java.util.zip.DataFormatException
import java.util.zip.Inflater

@OptIn(UnstableApi::class)
internal class NuvioAssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    flags: Int = 0
) : MatroskaExtractor(subtitleParserFactory, flags) {

    private var currentAttachmentName: String? = null
    private var currentAttachmentMime: String? = null
    internal val subtitleSample: ParsableByteArray =
        subtitleSampleField.get(this) as ParsableByteArray

    override fun getElementType(id: Int): Int {
        return when (id) {
            ID_ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_FILE_NAME -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_MIME_TYPE -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
            else -> super.getElementType(id)
        }
    }

    override fun isLevel1Element(id: Int): Boolean {
        return super.isLevel1Element(id) || id == ID_ATTACHMENTS
    }

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_EBML -> {
                if (assHandler.renderType != AssRenderType.CUES) {
                    val currentExtractor = extractorOutput.get(this) as ExtractorOutput
                    if (currentExtractor !is NuvioAssSubtitleExtractorOutput) {
                        extractorOutput.set(
                            this,
                            NuvioAssSubtitleExtractorOutput(currentExtractor, assHandler, this)
                        )
                    }
                }
                super.startMasterElement(id, contentPosition, contentSize)
            }
            ID_ATTACHED_FILE -> clearAttachment()
            else -> super.startMasterElement(id, contentPosition, contentSize)
        }
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            ID_VIDEO -> {
                val track = getCurrentTrack(id)
                assHandler.setVideoSize(track.width, track.height)
                super.endMasterElement(id)
            }
            ID_ATTACHED_FILE -> clearAttachment()
            else -> super.endMasterElement(id)
        }
    }

    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_FILE_NAME -> currentAttachmentName = value
            ID_FILE_MIME_TYPE -> currentAttachmentMime = value
            else -> super.stringElement(id, value)
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            ID_FILE_DATA -> {
                val attachmentName = requireNotNull(currentAttachmentName)
                val attachmentMime = requireNotNull(currentAttachmentMime)

                if (attachmentMime in fontMimeTypes) {
                    val data = ByteArray(contentSize)
                    input.readFully(data, 0, contentSize)
                    addFontMethod?.invoke(assHandler, attachmentName, data)
                } else {
                    input.skipFully(contentSize)
                }
            }
            else -> super.binaryElement(id, contentSize, input)
        }
    }

    private fun clearAttachment() {
        currentAttachmentName = null
        currentAttachmentMime = null
    }

    private companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_VIDEO = 0xE0
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_NAME = 0x466E
        const val ID_FILE_MIME_TYPE = 0x4660
        const val ID_FILE_DATA = 0x465C

        val fontMimeTypes = listOf(
            "font/ttf",
            "font/otf",
            "font/sfnt",
            "font/woff",
            "font/woff2",
            "application/font-sfnt",
            "application/font-woff",
            "application/x-truetype-font",
            "application/vnd.ms-opentype",
            "application/x-font-ttf"
        )

        val extractorOutput = MatroskaExtractor::class.java
            .getDeclaredField("extractorOutput")
            .apply { isAccessible = true }

        val subtitleSampleField = MatroskaExtractor::class.java
            .getDeclaredField("subtitleSample")
            .apply { isAccessible = true }

        val addFontMethod: Method? = AssHandler::class.java.declaredMethods.firstOrNull { method ->
            method.name == "addFont" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == ByteArray::class.java
        }?.apply {
            isAccessible = true
        }
    }
}

@OptIn(UnstableApi::class)
private class NuvioAssSubtitleExtractorOutput(
    private val delegate: ExtractorOutput,
    private val assHandler: AssHandler,
    private val extractor: NuvioAssMatroskaExtractor
) : ExtractorOutput by delegate {
    override fun track(id: Int, type: Int): TrackOutput {
        return if (type == C.TRACK_TYPE_TEXT) {
            NuvioAssTrackOutput(delegate.track(id, type), assHandler, extractor)
        } else {
            delegate.track(id, type)
        }
    }
}

@OptIn(UnstableApi::class)
private class NuvioAssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: NuvioAssMatroskaExtractor
) : TrackOutput by delegate {

    private var isAss = false
    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAss = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs.isValidTs) {
            val sample = extractor.subtitleSample
            val endIndex = findTokenIndex(sample.data, 1)
            val lineIndex = findTokenIndex(sample.data, 2)
            if (endIndex > 0 && lineIndex > endIndex) {
                val rawDuration = sample.data.decodeToString(endIndex, lineIndex - 1)
                val durationUs = parseTimecodeUs(rawDuration)
                if (durationUs.isValidTs) {
                    val dialogue = sample.data.dialoguePayload(
                        offset = lineIndex,
                        limit = sample.limit()
                    )

                    assHandler.readTrackDialogue(
                        trackId = trackId,
                        start = timeUs / 1000,
                        duration = durationUs / 1000,
                        data = dialogue,
                        offset = 0,
                        length = dialogue.size
                    )
                }
            }
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) return C.TIME_UNSET

        var timestampUs =
            Util.castNonNull(matcher.group(1)).toLong() * 60 * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(2)).toLong() * 60 * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(3)).toLong() * C.MICROS_PER_SECOND
        timestampUs += Util.castNonNull(matcher.group(4)).toLong() * 10_000
        return timestampUs
    }

    private fun findTokenIndex(array: ByteArray, tokenNumber: Int): Int {
        if (tokenNumber == 0) return 0
        var tokensFound = 0
        array.forEachIndexed { index, byte ->
            if (byte == COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return 0
    }

    private fun ByteArray.dialoguePayload(offset: Int, limit: Int): ByteArray {
        if (offset >= size) return EMPTY_BYTE_ARRAY
        val boundedLimit = limit.coerceIn(offset, size)
        val rawEnd = if (looksLikeZlib(offset, size)) size else boundedLimit
        val rawPayload = copyOfRange(offset, rawEnd)
        return maybeInflate(rawPayload)
    }

    private fun ByteArray.looksLikeZlib(offset: Int, limit: Int): Boolean {
        if (limit - offset < 2) return false
        val cmf = this[offset].toInt() and 0xFF
        val flg = this[offset + 1].toInt() and 0xFF
        return cmf and 0x0F == 8 && ((cmf shl 8) + flg) % 31 == 0
    }

    private fun maybeInflate(data: ByteArray): ByteArray {
        if (!data.looksLikeZlib(offset = 0, limit = data.size)) return data

        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(INFLATE_BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                } else {
                    break
                }
            }
            val inflated = output.toByteArray()
            if (inflater.finished() && inflated.isNotEmpty()) inflated else data
        } catch (_: DataFormatException) {
            data
        } finally {
            inflater.end()
        }
    }

    private val Long.isValidTs: Boolean
        get() = this != C.TIME_UNSET

    private companion object {
        val SSA_TIMECODE_PATTERN: Pattern =
            Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")

        const val COMMA: Byte = 44
        const val INFLATE_BUFFER_SIZE = 4096
        val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}
