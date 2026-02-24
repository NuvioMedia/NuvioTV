package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test to verify the audio track fallback logic added for TrueHD / AudioTrack init failures.
 */
class AudioTrackFallbackTest {

    // Simulates the inline logic from PlayerRuntimeControllerInitialization.kt
    private fun findFallbackIndex(
        audioTracks: List<TrackInfo>,
        currentAudioIndex: Int
    ): Int {
        if (audioTracks.size <= 1 || currentAudioIndex < 0) return -1

        return audioTracks.indexOfFirst {
            it.index != currentAudioIndex && (it.codec?.contains("ac3", ignoreCase = true) == true || it.codec?.contains("aac", ignoreCase = true) == true)
        }.takeIf { it >= 0 } ?: audioTracks.indexOfFirst { it.index != currentAudioIndex }
    }

    @Test
    fun `fallback prefers AC3 track when current track fails`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "TrueHD Atmos", language = null, codec = "truehd", isSelected = true),
            TrackInfo(index = 1, name = "Dolby Digital", language = null, codec = "ac3", isSelected = false),
            TrackInfo(index = 2, name = "DTS", language = null, codec = "dts", isSelected = false)
        )
        
        val fallbackIndex = findFallbackIndex(tracks, 0)
        assertEquals(1, fallbackIndex)
    }

    @Test
    fun `fallback prefers AAC track over other unknown formats when AC3 is missing`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "TrueHD", language = null, codec = "truehd", isSelected = true),
            TrackInfo(index = 1, name = "Vorbis", language = null, codec = "vorbis", isSelected = false),
            TrackInfo(index = 2, name = "AAC Stereo", language = null, codec = "aac", isSelected = false)
        )
        
        val fallbackIndex = findFallbackIndex(tracks, 0)
        assertEquals(2, fallbackIndex)
    }

    @Test
    fun `fallback picks the next available track if neither AC3 nor AAC exist`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "TrueHD", language = null, codec = "truehd", isSelected = true),
            TrackInfo(index = 1, name = "DTS", language = null, codec = "dts", isSelected = false)
        )
        
        val fallbackIndex = findFallbackIndex(tracks, 0)
        assertEquals(1, fallbackIndex)
    }
    
    @Test
    fun `fallback returns -1 if only one track exists`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "TrueHD", language = null, codec = "truehd", isSelected = true)
        )
        
        val fallbackIndex = findFallbackIndex(tracks, 0)
        assertEquals(-1, fallbackIndex)
    }
}
