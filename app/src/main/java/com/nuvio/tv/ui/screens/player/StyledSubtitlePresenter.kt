package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
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

internal enum class SubtitleVerticalPosition { TOP, MIDDLE, BOTTOM }

internal enum class SubtitleHorizontalPosition { START, CENTER, END }

internal data class StyledSubtitleBlock(
    val text: AnnotatedString,
    val vertical: SubtitleVerticalPosition = SubtitleVerticalPosition.BOTTOM,
    val horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER
)

internal data class StyledSubtitleCues(val blocks: List<StyledSubtitleBlock> = emptyList()) {
    val isEmpty: Boolean get() = blocks.all { it.text.isEmpty() }
}

internal fun styledSubtitleCues(blocks: List<StyledSubtitleBlock>): StyledSubtitleCues =
    StyledSubtitleCues(
        blocks
            .filter { it.text.isNotEmpty() }
            .groupBy { it.vertical to it.horizontal }
            .map { (position, grouped) ->
                StyledSubtitleBlock(grouped.map { it.text }.joinLines(), position.first, position.second)
            }
    )

internal fun List<AnnotatedString>.joinLines(): AnnotatedString = buildAnnotatedString {
    this@joinLines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        append(line)
    }
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
        mutableCues.value = styledSubtitleCues(
            lastCues.filter { !it.text.isNullOrBlank() }.map { cue ->
                StyledSubtitleBlock(
                    text = cue.text?.toSubtitleAnnotatedString() ?: AnnotatedString(""),
                    vertical = cue.verticalPosition(),
                    horizontal = cue.horizontalPosition()
                )
            }
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

private fun Cue.verticalPosition(): SubtitleVerticalPosition = when {
    line == Cue.DIMEN_UNSET -> SubtitleVerticalPosition.BOTTOM
    lineType == Cue.LINE_TYPE_NUMBER ->
        if (line >= 0f) SubtitleVerticalPosition.TOP else SubtitleVerticalPosition.BOTTOM
    lineType != Cue.LINE_TYPE_FRACTION -> SubtitleVerticalPosition.BOTTOM
    line < 1f / 3f -> SubtitleVerticalPosition.TOP
    line <= 2f / 3f -> SubtitleVerticalPosition.MIDDLE
    else -> SubtitleVerticalPosition.BOTTOM
}

private fun Cue.horizontalPosition(): SubtitleHorizontalPosition = when {
    position == Cue.DIMEN_UNSET -> SubtitleHorizontalPosition.CENTER
    position < 1f / 3f -> SubtitleHorizontalPosition.START
    position > 2f / 3f -> SubtitleHorizontalPosition.END
    else -> SubtitleHorizontalPosition.CENTER
}

private fun CharSequence.toSubtitleAnnotatedString(): AnnotatedString {
    val spanned = this as? Spanned ?: return AnnotatedString(toString())
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val spanStyle = when {
                span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                span is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
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
