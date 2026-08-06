package com.nuvio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MatroskaAfrProbeTest {

    @Test
    fun `readVint decodes one-byte size with length mask removed`() {
        // 0x82 => length 1, value 2
        val bytes = byteArrayOf(0x82.toByte())
        val vint = MatroskaAfrProbe.readVint(bytes, 0, removeLengthMask = true)
        assertNotNull(vint)
        assertEquals(2L, vint!!.value)
        assertEquals(1, vint.length)
    }

    @Test
    fun `isUnknownSize detects one-byte and eight-byte unknown patterns`() {
        assertTrue(MatroskaAfrProbe.isUnknownSize(0x7F, 1))
        assertTrue(MatroskaAfrProbe.isUnknownSize(0x00FFFFFFFFFFFFFFL, 8))
        assertFalse(MatroskaAfrProbe.isUnknownSize(32, 1))
    }

    @Test
    fun `safe prefix truncates torn Cluster but keeps complete Tracks`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracksPayload = ByteArray(32) { 0x11 }
        val tracks = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_TRACKS, tracksPayload)
        val clusterFull = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(200) { 0x22 }
        )
        // Tear the Cluster in half so the head ends mid-element.
        val tornCluster = clusterFull.copyOf(clusterFull.size / 2)
        val segmentPayload = tracks + tornCluster
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(segmentPayload)
        val full = ebml + segment

        val layout = MatroskaAfrProbe.analyzeHeadBytes(full)
        assertNotNull(layout)
        assertTrue(layout!!.tracksCompleteInPrefix)
        assertNotNull(layout.tracksAbsoluteOffset)
        // Safe prefix must end after Tracks, before the torn Cluster.
        val expectedPrefix = ebml.size + MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_SEGMENT).size +
            MatroskaAfrProbe.encodeUnknownSizeVint8().size + tracks.size
        assertEquals(expectedPrefix.toLong(), layout.safePrefixLength)
        assertTrue(layout.safePrefixLength < full.size)
    }

    @Test
    fun `SeekHead resolves Tracks absolute offset beyond head`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )

        // SeekPosition is relative to Segment payload start.
        // We'll place Tracks at segment-relative offset 5_000_000.
        val tracksRel = 5_000_000L
        val seekId = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_ID,
            MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_TRACKS)
        )
        val seekPos = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_POSITION,
            // 4-byte big-endian position
            byteArrayOf(
                ((tracksRel shr 24) and 0xFF).toByte(),
                ((tracksRel shr 16) and 0xFF).toByte(),
                ((tracksRel shr 8) and 0xFF).toByte(),
                (tracksRel and 0xFF).toByte()
            )
        )
        val seek = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK,
            seekId + seekPos
        )
        val seekHead = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_HEAD,
            seek
        )
        // Fake incomplete cluster after SeekHead so head has no Tracks.
        val torn = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(64) { 0x33 }
        ).copyOf(20)
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(seekHead + torn)
        val full = ebml + segment

        val layout = MatroskaAfrProbe.analyzeHeadBytes(full)
        assertNotNull(layout)
        assertFalse(layout!!.tracksCompleteInPrefix)
        val segmentDataOffset = layout.segmentDataOffset
        assertEquals(segmentDataOffset + tracksRel, layout.tracksAbsoluteOffset)
        assertTrue(layout.safePrefixLength > 0L)
        assertTrue(layout.safePrefixLength < full.size)
    }

    @Test
    fun `truncateToSafePrefix shortens torn file on disk`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_TRACKS,
            ByteArray(16) { 1 }
        )
        val tornCluster = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(100) { 2 }
        ).copyOf(40)
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(tracks + tornCluster)
        val full = ebml + segment

        val file = File.createTempFile("mkv_afr_trunc_", ".tmp")
        try {
            file.writeBytes(full)
            val layout = MatroskaAfrProbe.analyzeHead(file)
            assertNotNull(layout)
            val prefix = MatroskaAfrProbe.truncateToSafePrefix(file, layout!!)
            assertNotNull(prefix)
            assertEquals(prefix, file.length())
            assertTrue(file.length() < full.size)
            assertTrue(layout.tracksCompleteInPrefix)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `analyzeHead rejects non-EBML buffers`() {
        val mp4Like = byteArrayOf(0, 0, 0, 32, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0)
        assertNull(MatroskaAfrProbe.analyzeHeadBytes(mp4Like))
    }

    @Test
    fun `encodeSizeVintFixedWidth keeps width and rejects unusable values`() {
        val encoded = MatroskaAfrProbe.encodeSizeVintFixedWidth(32_689L, 8)
        assertNotNull(encoded)
        assertEquals(8, encoded!!.size)
        val decoded = MatroskaAfrProbe.readVint(encoded, 0, removeLengthMask = true)
        assertNotNull(decoded)
        assertEquals(32_689L, decoded!!.value)
        assertEquals(8, decoded.length)

        // Does not fit in a single byte, and must never emit the reserved unknown-size pattern.
        assertNull(MatroskaAfrProbe.encodeSizeVintFixedWidth(1_000L, 1))
        assertNull(MatroskaAfrProbe.encodeSizeVintFixedWidth(127L, 1))
        assertNull(MatroskaAfrProbe.encodeSizeVintFixedWidth(-1L, 8))
    }

    @Test
    fun `stub Cluster terminates Cluster-less head and resizes Segment`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_TRACKS,
            ByteArray(64) { 0x11 }
        )
        // Attachments larger than the head window, so the first Cluster is never reached.
        val tornAttachments = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_ATTACHMENTS,
            ByteArray(4096) { 0x22 }
        ).copyOf(48)
        val segment = buildSegmentWithDeclaredSize(
            payload = tracks + tornAttachments,
            declaredSize = 1_473_857_787L
        )
        val full = ebml + segment

        val file = File.createTempFile("mkv_afr_stub_", ".tmp")
        try {
            file.writeBytes(full)
            val layout = MatroskaAfrProbe.analyzeHead(file)
            assertNotNull(layout)
            assertTrue(layout!!.tracksCompleteInPrefix)
            assertFalse(layout.clusterInPrefix)
            assertFalse(layout.segmentUnknownSize)

            val prefix = MatroskaAfrProbe.truncateToSafePrefix(file, layout)
            assertNotNull(prefix)
            val patchedLength = MatroskaAfrProbe.appendStubClusterForHeadProbe(file, layout, prefix!!)
            assertNotNull(patchedLength)
            assertEquals(patchedLength, file.length())

            val patched = file.readBytes()
            val stub = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_CLUSTER, ByteArray(0))
            assertArrayEquals(stub, patched.copyOfRange(patched.size - stub.size, patched.size))

            // The rewritten Segment must end exactly at EOF instead of the original remote length.
            val patchedSegment = MatroskaAfrProbe.readElement(patched, ebml.size.toLong())
            assertNotNull(patchedSegment)
            assertEquals(MatroskaAfrProbe.ID_SEGMENT, patchedSegment!!.id)
            assertEquals(patched.size.toLong(), patchedSegment.endExclusiveOrNull())

            val patchedLayout = MatroskaAfrProbe.analyzeHeadBytes(patched)
            assertNotNull(patchedLayout)
            assertTrue(patchedLayout!!.clusterInPrefix)
            assertTrue(patchedLayout.tracksCompleteInPrefix)
            assertEquals(patched.size.toLong(), patchedLayout.safePrefixLength)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `head that already reaches a Cluster needs no stub terminator`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_TRACKS,
            ByteArray(16) { 0x11 }
        )
        val cluster = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(128) { 0x33 }
        )
        val tornCluster = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(128) { 0x44 }
        ).copyOf(40)
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(tracks + cluster + tornCluster)

        val layout = MatroskaAfrProbe.analyzeHeadBytes(ebml + segment)
        assertNotNull(layout)
        assertTrue(layout!!.clusterInPrefix)
        assertTrue(layout.segmentUnknownSize)
    }

    private fun buildSegmentWithDeclaredSize(payload: ByteArray, declaredSize: Long): ByteArray {
        val size = MatroskaAfrProbe.encodeSizeVintFixedWidth(declaredSize, 8)
        assertNotNull(size)
        return MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_SEGMENT) + size!! + payload
    }
}
