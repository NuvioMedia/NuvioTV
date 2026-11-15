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
        assertEquals("First caption", presenter.cues.value.bottom.text)
    }

    @Test
    fun `top positioned cues render at the top`() {
        val presenter = StyledSubtitlePresenter()

        presenter.present(listOf(caption, sign), styled = true)

        assertEquals("Sign", presenter.cues.value.top.text)
        assertEquals("First caption", presenter.cues.value.bottom.text)
    }

    @Test
    fun `switching styling re-routes the cues already on screen`() {
        val presenter = StyledSubtitlePresenter()
        presenter.present(listOf(caption), styled = false)

        assertTrue(presenter.refresh(styled = true).isEmpty())
        assertEquals("First caption", presenter.cues.value.bottom.text)
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
        assertEquals("Hello\nthere", presenter.cues.value.bottom.text)
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
