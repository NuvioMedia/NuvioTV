package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class StyledSubtitleCues(
    val top: AnnotatedString = AnnotatedString(""),
    val bottom: AnnotatedString = AnnotatedString("")
) {
    val isEmpty: Boolean get() = top.isEmpty() && bottom.isEmpty()
}

internal class StyledSubtitlePresenter {
    private val mutableCues = MutableStateFlow(StyledSubtitleCues())
    val cues: StateFlow<StyledSubtitleCues> = mutableCues.asStateFlow()
    private var lastCues: List<Cue> = emptyList()

    fun present(cues: List<Cue>, styled: Boolean): List<Cue> {
        lastCues = cues
        return route(styled)
    }

    fun refresh(styled: Boolean): List<Cue> = route(styled)

    fun showMpvText(text: String?) {
        mutableCues.value = parseMpvSubtitleText(text.orEmpty())
    }

    fun clear() {
        lastCues = emptyList()
        mutableCues.value = StyledSubtitleCues()
    }

    private fun route(styled: Boolean): List<Cue> {
        if (!styled) {
            mutableCues.value = StyledSubtitleCues()
            return lastCues
        }
        val textCues = lastCues.filter { !it.text.isNullOrBlank() }
        mutableCues.value = StyledSubtitleCues(
            top = textCues.filter { it.isTopAnchored() }.joinToAnnotatedString(),
            bottom = textCues.filterNot { it.isTopAnchored() }.joinToAnnotatedString()
        )
        return lastCues.filter { it.text == null }
    }
}

internal class StyledCueTextOutput(
    private val delegate: TextOutput,
    private val present: (List<Cue>) -> List<Cue>
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(CueGroup(present(cueGroup.cues), cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        @Suppress("DEPRECATION")
        delegate.onCues(present(cues))
    }
}

private fun Cue.isTopAnchored(): Boolean =
    line != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION && line < 0.5f

private fun List<Cue>.joinToAnnotatedString(): AnnotatedString = buildAnnotatedString {
    this@joinToAnnotatedString.forEachIndexed { index, cue ->
        if (index > 0) append('\n')
        append(cue.text?.toSubtitleAnnotatedString() ?: AnnotatedString(""))
    }
}

private fun CharSequence.toSubtitleAnnotatedString(): AnnotatedString {
    val spanned = this as? Spanned ?: return AnnotatedString(toString())
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val spanStyle = when {
                span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                span is StyleSpan && span.style == Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                span is StyleSpan && span.style == Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                span is StyleSpan && span.style == Typeface.BOLD_ITALIC ->
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> null
            } ?: return@forEach
            addStyle(spanStyle, spanned.getSpanStart(span), spanned.getSpanEnd(span))
        }
    }
}
