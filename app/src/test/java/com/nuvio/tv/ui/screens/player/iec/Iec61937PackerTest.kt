package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Iec61937PackerTest {

    @Test
    fun packTrueHd_writesPreambleAndSwapsPayload() {
        val mat = ByteArray(Iec61937Packer.TRUEHD_IEC_SIZE)
        mat[8] = 0x12
        mat[9] = 0x34
        val packed = Iec61937Packer.packTrueHd(mat)
        val header = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.short)
        assertEquals(Iec61937Packer.PREAMBLE2, header.short)
        assertEquals(Iec61937Packer.TYPE_TRUEHD.toShort(), header.short)
        assertEquals(Iec61937Packer.TRUEHD_LENGTH_FIELD.toShort(), header.short)
        assertEquals(0x34.toByte(), packed[8])
        assertEquals(0x12.toByte(), packed[9])
    }

    @Test
    fun packDtsHd_eightChannelBurstIs32768() {
        val au = ByteArray(100) { 0xAB.toByte() }
        val period = Iec61937Packer.dtsHdIecPeriod(channelCount = 8, coreSampleCount = 512)
        assertEquals(8192, period)
        val packed = Iec61937Packer.packDtsHd(au, period)
        assertEquals(32768, packed.size)
        val header = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.short)
        assertEquals(Iec61937Packer.PREAMBLE2, header.short)
        val type = header.short.toInt() and 0xFFFF
        assertEquals(Iec61937Packer.TYPE_DTSHD, type and 0xFF)
        assertEquals(4, type shr 8)
    }

    @Test
    fun dtsHdPeriod_stereoUses2048() {
        assertEquals(2048, Iec61937Packer.dtsHdIecPeriod(channelCount = 2, coreSampleCount = 512))
    }
}
