package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.text.Cue
import com.nuvio.tv.core.player.SubtitleFonts
import com.nuvio.tv.data.local.SubtitleStyleSettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyledSubtitlePresenterTest {

    private val caption = Cue.Builder().setText("First caption").build()
    private val sign = Cue.Builder().setText("Sign").setLine(0.08f, Cue.LINE_TYPE_FRACTION).build()

    @Test
    fun `native rendering keeps every cue on the subtitle view`() {
        val presenter = StyledSubtitlePresenter()

        assertEquals(listOf(caption), presenter.present(listOf(caption), styled = false))
        assertTrue(presenter.cues.value.isEmpty)
    }

    @Test
    fun `styled rendering moves text cues to the overlay and keeps bitmap cues native`() {
        val presenter = StyledSubtitlePresenter()
        val bitmap = Cue.Builder().setBitmap(mockk<Bitmap>(relaxed = true)).build()

        assertEquals(listOf(bitmap), presenter.present(listOf(caption, bitmap), styled = true))
        assertEquals("First caption", presenter.cues.value.bottomText.text)
    }

    @Test
    fun `top positioned cues render at the top`() {
        val presenter = StyledSubtitlePresenter()

        presenter.present(listOf(caption, sign), styled = true)

        assertEquals("Sign", presenter.cues.value.topText.text)
        assertEquals("First caption", presenter.cues.value.bottomText.text)
    }

    @Test
    fun `srt alignment positions keep their row and column`() {
        val presenter = StyledSubtitlePresenter()
        val topLeft = Cue.Builder().setText("Top left")
            .setLine(0.08f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0.08f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build()
        val middle = Cue.Builder().setText("Middle")
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).build()

        presenter.present(listOf(topLeft, middle, caption), styled = true)

        assertEquals(
            listOf(
                Triple("Top left", SubtitleVerticalPosition.TOP, SubtitleHorizontalPosition.START),
                Triple("Middle", SubtitleVerticalPosition.MIDDLE, SubtitleHorizontalPosition.CENTER),
                Triple("First caption", SubtitleVerticalPosition.BOTTOM, SubtitleHorizontalPosition.CENTER)
            ),
            presenter.cues.value.blocks.map { Triple(it.text.text, it.vertical, it.horizontal) }
        )
    }

    @Test
    fun `line number cues use the top for non-negative lines`() {
        val presenter = StyledSubtitlePresenter()
        val top = Cue.Builder().setText("Top row").setLine(0f, Cue.LINE_TYPE_NUMBER).build()
        val bottom = Cue.Builder().setText("Bottom row").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()

        presenter.present(listOf(top, bottom), styled = true)

        assertEquals("Top row", presenter.cues.value.topText.text)
        assertEquals("Bottom row", presenter.cues.value.bottomText.text)
    }

    @Test
    fun `file colors follow the hdr brightness and the text opacity`() {
        val dimmed = subtitleFileColor(Color.Yellow, brightness = 0.5f, textAlpha = 0.8f)

        assertEquals(0.5f, dimmed.red, 0.01f)
        assertEquals(0.5f, dimmed.green, 0.01f)
        assertEquals(0f, dimmed.blue, 0.01f)
        assertEquals(0.8f, dimmed.alpha, 0.01f)
    }

    @Test
    fun `hdr style passes its brightness to file colors`() {
        val style = SubtitleStyleSettings(hdrStyleEnabled = true, hdrBrightness = 0)

        assertEquals(1f, style.forVideo(isHdrVideo = false).fileColorBrightness, 0f)
        assertTrue(style.forVideo(isHdrVideo = true).fileColorBrightness < 1f)
    }

    @Test
    fun `switching styling re-routes the cues already on screen`() {
        val presenter = StyledSubtitlePresenter()
        presenter.present(listOf(caption), styled = false)

        assertTrue(presenter.refresh(styled = true).isEmpty())
        assertEquals("First caption", presenter.cues.value.bottomText.text)
        assertEquals(listOf(caption), presenter.refresh(styled = false))
        assertTrue(presenter.cues.value.isEmpty)
    }

    @Test
    fun `cleared cues empty the overlay`() {
        val presenter = StyledSubtitlePresenter()
        presenter.present(listOf(caption), styled = true)

        presenter.present(emptyList(), styled = true)

        assertTrue(presenter.cues.value.isEmpty)
    }

    @Test
    fun `mpv text is shown at the bottom`() {
        val presenter = StyledSubtitlePresenter()

        presenter.showMpvText(" Hello\nthere ")
        assertEquals("Hello\nthere", presenter.cues.value.bottomText.text)
        presenter.showMpvText(null)
        assertTrue(presenter.cues.value.isEmpty)
    }

    @Test
    fun `styled renderer is only used for fonts, backgrounds and shadows`() {
        val defaults = SubtitleStyleSettings()

        assertFalse(defaults.usesStyledRenderer)
        assertTrue(defaults.copy(fontId = SubtitleFonts.SERIF).usesStyledRenderer)
        assertTrue(defaults.copy(backgroundColor = Color.Black.copy(alpha = 0.5f).toArgb()).usesStyledRenderer)
        assertTrue(defaults.copy(shadowEnabled = true).usesStyledRenderer)
        assertFalse(defaults.copy(backgroundRadius = 12, outlineEnabled = false).usesStyledRenderer)
    }
}
