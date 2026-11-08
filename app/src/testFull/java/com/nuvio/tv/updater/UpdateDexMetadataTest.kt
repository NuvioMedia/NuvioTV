package com.nuvio.tv.updater

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDexMetadataTest {

    // Same layout the build writes from AGP's dex metadata map.
    private val index = Properties().apply {
        setProperty("28", "1.dm")
        setProperty("29", "1.dm")
        setProperty("30", "1.dm")
        setProperty("31", "0.dm")
        setProperty(Int.MAX_VALUE.toString(), "0.dm")
    }

    @Test
    fun `listed API levels use their own file`() {
        assertEquals("1.dm", UpdateDexMetadata.selectFileName(index, 28))
        assertEquals("1.dm", UpdateDexMetadata.selectFileName(index, 30))
        assertEquals("0.dm", UpdateDexMetadata.selectFileName(index, 31))
    }

    @Test
    fun `levels above every listed one use the open-ended file`() {
        assertEquals("0.dm", UpdateDexMetadata.selectFileName(index, 36))
    }

    @Test
    fun `levels below the listed ones get no file`() {
        assertNull(UpdateDexMetadata.selectFileName(index, 27))
    }

    @Test
    fun `empty index gets no file`() {
        assertNull(UpdateDexMetadata.selectFileName(Properties(), 34))
    }
}
