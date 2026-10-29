package com.nuvio.tv.data.mapper

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerNavigationArgs
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeSubStreamMappingTest {
    @Test
    fun `direct resolver retains episode one URL through mapping and player arguments`() {
        val json = checkNotNull(javaClass.getResource("/animesub-e01-sanitized.json")).readText()
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(StreamResponseDto::class.java)
        val dto = checkNotNull(adapter.fromJson(json)).streams!!.single()
        val stream = dto.toDomain("AnimeSub+", null)
        val url = checkNotNull(dto.url)
        val filename = checkNotNull(dto.behaviorHints?.filename)

        assertEquals(url, stream.getStreamUrl())
        assertEquals(filename, stream.behaviorHints?.filename)
        assertNull(stream.getEffectiveFileIdx())
        assertFalse(stream.isTorrent())
        assertFalse(stream.isDirectDebrid())

        // Navigation has already decoded its path argument when it reaches SavedStateHandle.
        val args = PlayerNavigationArgs.from(SavedStateHandle(mapOf("streamUrl" to url)))
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(args.streamUrl, emptyMap())
        assertEquals(url, request.url)
        assertEquals(emptyMap<String, String>(), request.headers)
    }
}
