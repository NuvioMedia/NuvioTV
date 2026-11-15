package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLetterboxMaskTest {

    @Test
    fun bars_forLetterboxedVideo_areTopAndBottom() {
        assertEquals(
            listOf(
                LetterboxBar(0f, 0f, 1920f, 104f),
                LetterboxBar(0f, 976f, 1920f, 1080f)
            ),
            letterboxBars(1920f, 1080f, 0f, 104f, 1920f, 976f)
        )
    }

    @Test
    fun bars_forPillarboxedVideo_areLeftAndRight() {
        assertEquals(
            listOf(
                LetterboxBar(0f, 0f, 240f, 1080f),
                LetterboxBar(1680f, 0f, 1920f, 1080f)
            ),
            letterboxBars(1920f, 1080f, 240f, 0f, 1680f, 1080f)
        )
    }

    @Test
    fun bars_forWindowedVideo_surroundItWithoutOverlapping() {
        assertEquals(
            listOf(
                LetterboxBar(0f, 0f, 1920f, 100f),
                LetterboxBar(0f, 900f, 1920f, 1080f),
                LetterboxBar(0f, 100f, 200f, 900f),
                LetterboxBar(1700f, 100f, 1920f, 900f)
            ),
            letterboxBars(1920f, 1080f, 200f, 100f, 1700f, 900f)
        )
    }

    @Test
    fun bars_forVideoFillingOrExceedingTheFrame_areAbsent() {
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(1920f, 1080f, 0f, 0f, 1920f, 1080f))
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(1920f, 1080f, -96f, -54f, 2016f, 1134f))
    }

    @Test
    fun bars_forZoomedVideo_keepOnlyTheRemainingBars() {
        assertEquals(
            listOf(
                LetterboxBar(0f, 0f, 1920f, 52f),
                LetterboxBar(0f, 1028f, 1920f, 1080f)
            ),
            letterboxBars(1920f, 1080f, -100f, 52f, 2020f, 1028f)
        )
    }

    @Test
    fun bars_forFractionalVideoBounds_neverCoverAPixelTheVideoTouches() {
        assertEquals(
            listOf(
                LetterboxBar(0f, 0f, 1920f, 52f),
                LetterboxBar(0f, 1028f, 1920f, 1080f),
                LetterboxBar(0f, 52f, 10f, 1028f),
                LetterboxBar(1910f, 52f, 1920f, 1028f)
            ),
            letterboxBars(1920f, 1080f, 10.4f, 52.6f, 1909.6f, 1027.4f)
        )
    }

    @Test
    fun bars_withoutAVideoRectangleInsideTheFrame_areAbsent() {
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(1920f, 1080f, 0f, 0f, 0f, 0f))
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(1920f, 1080f, 0f, 1200f, 1920f, 2000f))
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(0f, 0f, 0f, 104f, 1920f, 976f))
        assertEquals(emptyList<LetterboxBar>(), letterboxBars(1920f, 1080f, Float.NaN, 104f, 1920f, 976f))
    }
}
