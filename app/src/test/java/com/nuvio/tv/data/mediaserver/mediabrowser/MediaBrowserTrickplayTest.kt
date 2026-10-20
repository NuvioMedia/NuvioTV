package com.nuvio.tv.data.mediaserver.mediabrowser

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBrowserTrickplayTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun info(width: Int) = TrickplayInfo(
        width = width,
        height = width * 9 / 16,
        tileWidth = 10,
        tileHeight = 10,
        thumbnailCount = 200,
        interval = 10_000
    )

    @Test
    fun `item without trickplay still parses`() {
        val item = json.decodeFromString(BaseItem.serializer(), """{"Id":"abc","Name":"Movie"}""")
        assertNull(item.trickplay)
    }

    @Test
    fun `null trickplay does not break item parsing`() {
        val item = json.decodeFromString(BaseItem.serializer(), """{"Id":"abc","Trickplay":null}""")
        assertNull(item.trickplay)
    }

    @Test
    fun `trickplay metadata parses`() {
        val item = json.decodeFromString(
            BaseItem.serializer(),
            """
            {"Id":"abc","Trickplay":{"src1":{"320":{"Width":320,"Height":180,"TileWidth":10,
            "TileHeight":10,"ThumbnailCount":200,"Interval":10000,"Bandwidth":1234}}}}
            """.trimIndent()
        )
        val parsed = item.trickplay?.get("src1")?.get("320")
        assertNotNull(parsed)
        assertEquals(320, parsed!!.width)
        assertEquals(10_000L, parsed.interval)
    }

    @Test
    fun `selects resolution closest to 320 wide`() {
        val selected = selectTrickplay(
            itemId = "abc",
            mediaSourceId = "src1",
            trickplay = mapOf("src1" to mapOf("160" to info(160), "400" to info(400), "640" to info(640)))
        )
        assertEquals(400, selected?.width)
        assertEquals("abc/src1/400", selected?.layout?.key)
    }

    @Test
    fun `falls back to the only media source when ids differ`() {
        val selected = selectTrickplay("abc", "other", mapOf("src1" to mapOf("320" to info(320))))
        assertEquals(320, selected?.width)
    }

    @Test
    fun `ignores incomplete entries`() {
        val broken = info(320).copy(interval = 0)
        assertNull(selectTrickplay("abc", "src1", mapOf("src1" to mapOf("320" to broken))))
        assertNull(selectTrickplay("abc", "src1", emptyMap()))
    }
}
