package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRedirectTargetTest {

    private val base = "https://addon.example.com/token123/play/default-movie-tt1-"

    private fun stream(url: String, name: String, addonName: String = "Addon") = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null
    )

    private val listed = listOf(
        stream("${base}0", "2160p DTS-HD"),
        stream("${base}1", "2160p DTS"),
        stream("${base}2", "1080p AC3")
    )

    @Test
    fun `a failover redirect resolves to the release it landed on`() {
        val target = findRedirectTargetStream(listed, "Addon", "${base}0", "${base}1")
        assertEquals("2160p DTS", target?.name)
    }

    @Test
    fun `the requested query carried across the hop still matches`() {
        val target = findRedirectTargetStream(listed, "Addon", "${base}0?player=tv", "${base}2?player=tv")
        assertEquals("1080p AC3", target?.name)
    }

    @Test
    fun `a redirect to an unlisted url such as a debrid cdn matches nothing`() {
        assertNull(findRedirectTargetStream(listed, "Addon", "${base}0", "https://cdn.example.net/dl/file.mkv"))
    }

    @Test
    fun `another addon listing the target url is not taken for this stream`() {
        val other = listOf(stream("${base}1", "Other", addonName = "Elsewhere"))
        assertNull(findRedirectTargetStream(other, "Addon", "${base}0", "${base}1"))
    }

    @Test
    fun `a target that only differs in encoding from the request is not a redirect`() {
        val requested = "https://addon.example.com/token123/play/a b"
        assertNull(findRedirectTargetStream(listed, "Addon", requested, "https://addon.example.com/token123/play/a%20b"))
    }
}
