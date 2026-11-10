package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurayIsoTest {
    private val sector = 2048
    private val partitionStart = 300
    private val mpls = mpls("00001" to 45_000L * 120, "00002" to 45_000L * 60)
    private val clpi = "HDMV0200".toByteArray() + ByteArray(40) { it.toByte() }

    private fun mpls(vararg items: Pair<String, Long>): ByteArray {
        var bytes = byteArrayOf()
        var at = 0L
        for ((clip, ticks) in items) {
            val body = ByteArray(20)
            (clip + "M2TS").toByteArray().copyInto(body, 0)
            for (i in 0..3) {
                body[12 + i] = (at shr (24 - 8 * i)).toByte()
                body[16 + i] = ((at + ticks) shr (24 - 8 * i)).toByte()
            }
            at += ticks
            bytes += byteArrayOf(0, 20) + body
        }
        val playlistStart = 16
        val data = ByteArray(playlistStart + 10 + bytes.size)
        "MPLS0200".toByteArray().copyInto(data, 0)
        data[11] = playlistStart.toByte()
        data[playlistStart + 4 + 2] = (items.size shr 8).toByte()
        data[playlistStart + 4 + 3] = items.size.toByte()
        bytes.copyInto(data, playlistStart + 10)
        return data
    }

    private fun ByteArray.u16(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.u32(at: Int, value: Long) {
        u16(at, (value and 0xFFFF).toInt())
        u16(at + 2, ((value shr 16) and 0xFFFF).toInt())
    }

    private fun ByteArray.u64(at: Int, value: Long) {
        u32(at, value and 0xFFFFFFFFL)
        u32(at + 4, value ushr 32)
    }

    private fun shortAd(length: Long, block: Long) = ByteArray(8).apply {
        u32(0, length)
        u32(4, block)
    }

    private fun longAd(length: Long, block: Long, partition: Int) = ByteArray(16).apply {
        u32(0, length)
        u32(4, block)
        u16(8, partition)
    }

    private fun entry(extended: Boolean, fileType: Int, length: Long, adType: Int, ads: ByteArray) = ByteArray(sector).apply {
        u16(0, if (extended) 266 else 261)
        this[27] = fileType.toByte()
        u16(34, adType)
        u64(56, length)
        val eaOffset = if (extended) 208 else 168
        val dataStart = if (extended) 216 else 176
        u32(eaOffset, 0)
        u32(eaOffset + 4, ads.size.toLong())
        ads.copyInto(this, dataStart)
    }

    private fun fid(name: String?, directory: Boolean, block: Long, partition: Int): ByteArray {
        val encoded = name?.let { byteArrayOf(8) + it.toByteArray(Charsets.ISO_8859_1) } ?: byteArrayOf()
        val size = (38 + encoded.size + 3) and 3.inv()
        return ByteArray(size).apply {
            u16(0, 257)
            this[18] = ((if (directory) 0x02 else 0) or (if (name == null) 0x0A else 0)).toByte()
            this[19] = encoded.size.toByte()
            longAd(sector.toLong(), block, partition).copyInto(this, 20)
            u16(36, 0)
            encoded.copyInto(this, 38)
        }
    }

    private fun image(metadata: Boolean, extended: Boolean = metadata): ByteArray {
        val image = ByteArray((partitionStart + 400) * sector)
        fun put(sectorIndex: Int, bytes: ByteArray) = bytes.copyInto(image, sectorIndex * sector)
        val metaRef = if (metadata) 1 else 0
        fun metaBlock(index: Int): Long = if (metadata) index.toLong() else 1L + index
        fun metaSector(index: Int) = partitionStart + 1 + index

        put(256, ByteArray(sector).apply {
            u16(0, 2)
            u32(16, 3L * sector)
            u32(20, 32)
        })
        put(32, ByteArray(sector).apply {
            u16(0, 5)
            u16(22, 0)
            u32(188, partitionStart.toLong())
            u32(192, 400)
        })
        put(33, ByteArray(sector).apply {
            u16(0, 6)
            u32(212, sector.toLong())
            u32(248, sector.toLong())
            u32(252, metaBlock(0))
            u16(256, metaRef)
            u32(268, if (metadata) 2 else 1)
            this[440] = 1
            this[441] = 6
            u16(444, 0)
            if (metadata) {
                this[446] = 2
                this[447] = 64
                "*UDF Metadata Partition".toByteArray().copyInto(this, 446 + 5)
                u16(446 + 38, 0)
                u32(446 + 40, 0)
                u32(446 + 44, 0)
            }
        })
        put(34, ByteArray(sector).apply { u16(0, 8) })
        if (metadata) {
            put(partitionStart, entry(true, 250, 16L * sector, 0, shortAd(16L * sector, 1)))
        }
        put(metaSector(0), ByteArray(sector).apply {
            u16(0, 256)
            longAd(sector.toLong(), metaBlock(1), metaRef).copyInto(this, 400)
        })
        fun directory(index: Int, dataIndex: Int, children: List<ByteArray>) {
            val data = children.fold(fid(null, true, metaBlock(index), metaRef)) { acc, child -> acc + child }
            put(metaSector(index), entry(extended, 4, data.size.toLong(), 0, shortAd(data.size.toLong(), metaBlock(dataIndex))))
            put(metaSector(dataIndex), data)
        }
        directory(1, 2, listOf(fid("BDMV", true, metaBlock(3), metaRef)))
        directory(3, 4, listOf(
            fid("PLAYLIST", true, metaBlock(5), metaRef),
            fid("STREAM", true, metaBlock(7), metaRef),
            fid("CLIPINF", true, metaBlock(9), metaRef)
        ))
        directory(5, 6, listOf(fid("00000.mpls", false, metaBlock(11), metaRef)))
        directory(7, 8, listOf(
            fid("00001.m2ts", false, metaBlock(12), metaRef),
            fid("00002.m2ts", false, metaBlock(13), metaRef)
        ))
        directory(9, 10, listOf(fid("00001.clpi", false, metaBlock(14), metaRef)))

        put(metaSector(11), entry(extended, 5, mpls.size.toLong(), 1, longAd(sector.toLong(), 100, 0)))
        put(partitionStart + 100, mpls)
        put(
            metaSector(12),
            entry(extended, 5, 3L * sector - 100, 1, longAd(2L * sector, 200, 0) + longAd(sector.toLong(), 202, 0))
        )
        put(metaSector(13), entry(extended, 5, 2L * sector, 1, longAd(2L * sector, 300, 0)))
        put(metaSector(14), entry(extended, 5, clpi.size.toLong(), 3, clpi))
        return image
    }

    private fun reader(bytes: ByteArray) = BlurayIso.RangeReader { offset, length ->
        if (offset >= bytes.size) ByteArray(0)
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    private fun assertVolume(metadata: Boolean) {
        val bytes = image(metadata)
        val volume = BlurayIso.Udf(reader(bytes)).load()
        assertNotNull(volume)
        assertEquals(metadata, volume!!.hasMetadataPartition)
        val files = volume.files(physicalFileData = false)
        assertEquals(
            listOf(BlurayIso.Extent((partitionStart + 200L) * sector, 3L * sector - 100)),
            files["BDMV/STREAM/00001.M2TS"]
        )
        assertEquals(
            listOf(BlurayIso.Extent((partitionStart + 300L) * sector, 2L * sector)),
            files["BDMV/STREAM/00002.M2TS"]
        )
        val image = BlurayIso.Image("test", "http://host/disc.iso", files, volume.names, reader(bytes))
        assertArrayEquals(mpls, image.readFile("/BDMV/PLAYLIST/00000.mpls"))
        assertArrayEquals(clpi, image.readFile("/BDMV/CLIPINF/00001.clpi"))
        assertEquals(listOf("BDMV/STREAM/00001.M2TS", "BDMV/STREAM/00002.M2TS"), image.paths("BDMV/STREAM", "m2ts"))
        assertEquals("00000.mpls", image.displayName("BDMV/PLAYLIST/00000.MPLS"))
        val parsed = BlurayPlaylistResolver.parseMpls("00000.mpls", image.readFile("BDMV/PLAYLIST/00000.mpls")!!)
        assertEquals(listOf("00001", "00002"), parsed!!.clipIds)
    }

    @Test
    fun `reads the BDMV files of a UDF 2_50 image with a metadata partition`() = assertVolume(metadata = true)

    @Test
    fun `reads the BDMV files of a UDF image without a metadata partition`() = assertVolume(metadata = false)

    @Test
    fun `reads plain file entries in a metadata partition`() {
        val bytes = image(metadata = true, extended = false)
        val files = BlurayIso.Udf(reader(bytes)).load()!!.files(physicalFileData = false)
        assertEquals(3L * sector - 100, files["BDMV/STREAM/00001.M2TS"]!!.sumOf { it.length })
    }

    @Test
    fun `is not an image without a volume anchor`() {
        assertNull(BlurayIso.Udf(reader(ByteArray(600 * sector))).load())
    }

    @Test
    fun `caches chunks across reads`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        var calls = 0
        val cached = BlurayIso.CachedRangeReader(
            BlurayIso.RangeReader { offset, length ->
                calls++
                bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
            },
            chunkSize = 1024,
            maxChunks = 16
        )
        assertArrayEquals(bytes.copyOfRange(1000, 3100), cached.read(1000, 2100))
        assertEquals(4, calls)
        assertArrayEquals(bytes.copyOfRange(2000, 2100), cached.read(2000, 100))
        assertEquals(4, calls)
        assertArrayEquals(bytes.copyOfRange(9900, 10_000), cached.read(9900, 500))
    }

    @Test
    fun `recognizes ISO streams by path or file name`() {
        assertTrue(BlurayIso.isLikelyIsoUrl("https://cdn.example/d/ABC/Movie.2009.BluRay.ISO", null))
        assertTrue(BlurayIso.isLikelyIsoUrl("https://cdn.example/d/ABC/movie.iso?token=1", null))
        assertTrue(BlurayIso.isLikelyIsoUrl("https://debrid.example/dl/123", "Movie (2009) [BD50].iso"))
        assertFalse(BlurayIso.isLikelyIsoUrl("https://debrid.example/dl/movie.mkv", "movie.mkv"))
        assertTrue(BlurayIso.isIsoUri("bdiso://abc/BDMV/STREAM/00001.m2ts"))
        assertFalse(BlurayIso.isIsoUri("https://abc/BDMV/STREAM/00001.m2ts"))
    }
}
