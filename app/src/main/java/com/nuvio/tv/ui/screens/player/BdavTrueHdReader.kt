package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Adds two Blu-ray stream types to Media3's TS payload readers, which skip them: Dolby TrueHD
 * (HDMV stream type 0x83), without which a clip offers only its AC-3 tracks, and presentation
 * graphics (0x90), the disc's subtitles.
 */
internal class BdavTsPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory
) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders() = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        when (streamType) {
            STREAM_TYPE_HDMV_TRUEHD -> PesReader(BdavTrueHdReader(esInfo.language, esInfo.roleFlags))
            STREAM_TYPE_HDMV_PGS -> PesReader(BdavPgsReader(esInfo.language, esInfo.roleFlags))
            else -> delegate.createPayloadReader(streamType, esInfo)
        }

    companion object {
        const val STREAM_TYPE_HDMV_TRUEHD = 0x83
        const val STREAM_TYPE_HDMV_PGS = 0x90

        fun withDefaults(flags: Int) = BdavTsPayloadReaderFactory(DefaultTsPayloadReaderFactory(flags))
    }
}

/**
 * Reads a Blu-ray TrueHD stream. Its PES packets hold either an AC-3 frame, the core kept for
 * older players and skipped here because the disc also has separate AC-3 tracks, or TrueHD
 * access units. Units are emitted from the first major sync on, [Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT]
 * per sample, as Matroska and MP4 deliver TrueHD for passthrough.
 */
internal class BdavTrueHdReader(
    private val language: String?,
    private val roleFlags: Int
) : ElementaryStreamReader {
    private lateinit var output: TrackOutput
    private lateinit var formatId: String

    private var packet = ByteArray(8 * 1024)
    private var packetLength = 0
    private var packetTimeUs = C.TIME_UNSET

    private var formatEmitted = false
    private var sampleRate = 0
    private var unitDurationUs = 0L
    private var synced = false
    private var nextUnitTimeUs = C.TIME_UNSET

    private var chunk = ByteArray(64 * 1024)
    private var chunkLength = 0
    private var chunkUnits = 0
    private var chunkTimeUs = C.TIME_UNSET

    override fun seek() {
        packetLength = 0
        packetTimeUs = C.TIME_UNSET
        nextUnitTimeUs = C.TIME_UNSET
        synced = false
        resetChunk()
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        packetLength = 0
        packetTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        val length = data.bytesLeft()
        if (packetLength + length > packet.size) packet = packet.copyOf(maxOf(packet.size * 2, packetLength + length))
        data.readBytes(packet, packetLength, length)
        packetLength += length
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        readPacket()
        packetLength = 0
        if (isEndOfInput) flushChunk()
    }

    private fun readPacket() {
        // The AC-3 core shares the PID; its frames start with the AC-3 sync word.
        if (packetLength >= 2 && packet[0] == 0x0B.toByte() && packet[1] == 0x77.toByte()) return
        var unitTimeUs = if (packetTimeUs != C.TIME_UNSET) packetTimeUs else nextUnitTimeUs
        var position = 0
        while (position + 4 <= packetLength) {
            // Each access unit starts with a check nibble and its length in 16-bit words.
            val unitLength = (((packet[position].toInt() and 0x0F) shl 8) or (packet[position + 1].toInt() and 0xFF)) * 2
            if (unitLength < 4 || position + unitLength > packetLength) break
            if (isMajorSync(packet, position, unitLength)) onMajorSync(position)
            if (synced && unitTimeUs != C.TIME_UNSET) {
                appendToChunk(position, unitLength, unitTimeUs)
                if (chunkUnits == Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT) flushChunk()
            }
            if (unitTimeUs != C.TIME_UNSET && unitDurationUs > 0) unitTimeUs += unitDurationUs
            position += unitLength
        }
        nextUnitTimeUs = unitTimeUs
    }

    private fun onMajorSync(position: Int) {
        if (!formatEmitted) {
            val info = parseMajorSync(packet, position) ?: return
            sampleRate = info.sampleRate
            output.format(
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                    .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
                    .setChannelCount(info.channelCount)
                    .setSampleRate(info.sampleRate)
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .build()
            )
            formatEmitted = true
        }
        val samples = Ac3Util.parseTrueHdSyncframeAudioSampleCount(packet.copyOfRange(position, position + Ac3Util.TRUEHD_SYNCFRAME_PREFIX_LENGTH))
        if (samples > 0 && sampleRate > 0) unitDurationUs = samples * C.MICROS_PER_SECOND / sampleRate
        synced = true
    }

    private fun appendToChunk(position: Int, length: Int, timeUs: Long) {
        if (chunkLength + length > chunk.size) chunk = chunk.copyOf(maxOf(chunk.size * 2, chunkLength + length))
        System.arraycopy(packet, position, chunk, chunkLength, length)
        if (chunkUnits == 0) chunkTimeUs = timeUs
        chunkLength += length
        chunkUnits++
    }

    /** Writes the pending chunk as one sample. Data is only written whole, so a seek never leaves a partial sample. */
    private fun flushChunk() {
        if (chunkUnits > 0 && formatEmitted) {
            output.sampleData(ParsableByteArray(chunk.copyOf(chunkLength)), chunkLength)
            output.sampleMetadata(chunkTimeUs, C.BUFFER_FLAG_KEY_FRAME, chunkLength, 0, null)
        }
        resetChunk()
    }

    private fun resetChunk() {
        chunkLength = 0
        chunkUnits = 0
        chunkTimeUs = C.TIME_UNSET
    }

    data class MajorSyncInfo(val sampleRate: Int, val channelCount: Int)

    companion object {
        /** Channels each bit of a TrueHD channel arrangement adds, lowest bit first (as FFmpeg's mlp_parse). */
        private val CHANNELS_PER_BIT = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)

        fun isMajorSync(data: ByteArray, position: Int, unitLength: Int): Boolean =
            unitLength >= 12 &&
                data[position + 4] == 0xF8.toByte() && data[position + 5] == 0x72.toByte() &&
                data[position + 6] == 0x6F.toByte() && data[position + 7] == 0xBA.toByte()

        /**
         * Reads the TrueHD format info after the major sync word: the 4-bit rate code, then the
         * 6-channel presentation's 5-bit and the 8-channel presentation's 13-bit channel arrangement.
         */
        fun parseMajorSync(data: ByteArray, position: Int): MajorSyncInfo? {
            if (position + 12 > data.size) return null
            val info = ((data[position + 8].toLong() and 0xFF) shl 24) or ((data[position + 9].toLong() and 0xFF) shl 16) or
                ((data[position + 10].toLong() and 0xFF) shl 8) or (data[position + 11].toLong() and 0xFF)
            val rateCode = ((info ushr 28) and 0xF).toInt()
            if (rateCode == 0xF) return null
            val sampleRate = (if (rateCode and 8 != 0) 44100 else 48000) shl (rateCode and 7)
            val sixChannel = ((info ushr 15) and 0x1F).toInt()
            val eightChannel = (info and 0x1FFF).toInt()
            val channels = channelCount(eightChannel).takeIf { it > 0 } ?: channelCount(sixChannel)
            return if (channels > 0) MajorSyncInfo(sampleRate, channels) else null
        }

        private fun channelCount(arrangement: Int): Int =
            CHANNELS_PER_BIT.indices.sumOf { bit -> if ((arrangement shr bit) and 1 != 0) CHANNELS_PER_BIT[bit] else 0 }
    }
}
