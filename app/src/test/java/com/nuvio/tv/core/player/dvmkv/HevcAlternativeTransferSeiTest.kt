package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class HevcAlternativeTransferSeiTest {

    // Prefix SEI as x265 writes it for --atc-sei 18 (HLG).
    private val alternativeTransferSei = bytes(0x4E, 0x01, 0x93, 0x01, 0x12, 0x80)

    // User data SEI whose payload needs an emulation prevention byte removed.
    private val otherSei = bytes(0x4E, 0x01, 0x05, 0x03, 0x00, 0x00, 0x03, 0x01, 0x80)

    private val idrSlice = bytes(0x28, 0x01, 0xAF, 0x1D, 0x30)

    @Test
    fun `reads the preferred transfer from the x265 SEI`() {
        assertEquals(18, parse(alternativeTransferSei))
    }

    @Test
    fun `finds the message after an escaped one in the same SEI`() {
        val sei = bytes(0x4E, 0x01, 0x05, 0x03, 0x00, 0x00, 0x03, 0x01, 0x93, 0x01, 0x12, 0x80)
        assertEquals(18, parse(sei))
    }

    @Test
    fun `an SEI without the message gives no value`() {
        assertEquals(Format.NO_VALUE, parse(otherSei))
    }

    @Test
    fun `peeks the SEI ahead of the slice and leaves the input untouched`() {
        val sample = accessUnit(4, otherSei, alternativeTransferSei, idrSlice)
        val input = input(sample)

        assertEquals(18, HevcAlternativeTransferSei.peekPreferredTransfer(input, sample.size, 4))
        assertEquals(0L, input.position)
        assertEquals(0L, input.peekPosition)
    }

    @Test
    fun `stops at the first slice`() {
        val sample = accessUnit(4, idrSlice, alternativeTransferSei)

        assertEquals(
            Format.NO_VALUE,
            HevcAlternativeTransferSei.peekPreferredTransfer(input(sample), sample.size, 4)
        )
    }

    @Test
    fun `handles short NAL length fields`() {
        val sample = accessUnit(2, alternativeTransferSei, idrSlice)

        assertEquals(18, HevcAlternativeTransferSei.peekPreferredTransfer(input(sample), sample.size, 2))
    }

    @Test
    fun `a NAL length past the sample gives no value`() {
        val sample = accessUnit(4, alternativeTransferSei)

        assertEquals(
            Format.NO_VALUE,
            HevcAlternativeTransferSei.peekPreferredTransfer(input(sample), sample.size - 1, 4)
        )
    }

    private fun parse(nalUnit: ByteArray): Int =
        HevcAlternativeTransferSei.parsePreferredTransfer(nalUnit, nalUnit.size)

    private fun accessUnit(lengthFieldSize: Int, vararg nalUnits: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nalUnit in nalUnits) {
            for (shift in (lengthFieldSize - 1) downTo 0) {
                out.write((nalUnit.size shr (8 * shift)) and 0xFF)
            }
            out.write(nalUnit)
        }
        return out.toByteArray()
    }

    private fun input(data: ByteArray): DefaultExtractorInput {
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            if (position >= data.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val count = minOf(length, data.size - position)
                System.arraycopy(data, position, buffer, offset, count)
                position += count
                count
            }
        }
        return DefaultExtractorInput(reader, 0, data.size.toLong())
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
