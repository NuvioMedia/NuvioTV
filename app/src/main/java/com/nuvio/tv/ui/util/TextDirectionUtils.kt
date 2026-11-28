package com.nuvio.tv.ui.util

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/** Upper bound of code points inspected, so this stays cheap when called on every recomposition. */
private const val MAX_DIRECTION_SCAN_CODE_POINTS = 64

/**
 * Detects a string's own reading direction, independent of the app's ambient UI locale/LayoutDirection.
 *
 * Follows the first strong-directional character (Unicode bidi rules P2/P3), skipping emoji, with one
 * refinement for mixed text: when the string *starts* with a left-to-right run (a brand, a name, a
 * title in Latin script) but is mostly right-to-left letters, it is treated as RTL.
 * e.g. "Netflix מציגה סדרה חדשה" is RTL, while "Breaking Bad" and "Fast X: מהיר" stay LTR.
 * A string that starts with an RTL letter is always RTL (the fast path).
 */
fun String.contentTextDirection(): TextDirection {
    var index = 0
    var scanned = 0
    var startsWithLtr = false
    var ltrLetters = 0
    var rtlLetters = 0
    while (index < length && scanned < MAX_DIRECTION_SCAN_CODE_POINTS) {
        val codePoint = codePointAt(index)
        index += Character.charCount(codePoint)
        scanned++

        if (isEmojiOrModifier(codePoint)) continue

        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> {
                if (ltrLetters == 0 && rtlLetters == 0) return TextDirection.Rtl
                rtlLetters++
            }
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                if (ltrLetters == 0 && rtlLetters == 0) startsWithLtr = true
                ltrLetters++
            }
        }
    }
    return if (startsWithLtr && rtlLetters > ltrLetters) TextDirection.Rtl else TextDirection.Ltr
}

/**
 * Checks if a code point is an emoji or emoji-related modifier that should be skipped
 * when detecting text direction.
 */
private fun isEmojiOrModifier(codePoint: Int): Boolean {
    // Regional Indicators (U+1F1E6 to U+1F1FF) - used for flag emojis
    if (codePoint in 0x1F1E6..0x1F1FF) return true
    
    // Emoji characters in various Unicode ranges
    if (codePoint in 0x1F300..0x1F9FF) return true  // Miscellaneous Symbols and Pictographs, Emoticons, etc.
    if (codePoint in 0x2600..0x26FF) return true    // Miscellaneous Symbols
    if (codePoint in 0x2700..0x27BF) return true    // Dingbats
    if (codePoint in 0x1F000..0x1F02F) return true  // Mahjong Tiles, Domino Tiles
    
    // Emoji Variation Selector (U+FE0F)
    if (codePoint == 0xFE0F) return true
    
    // Zero-Width Joiner (U+200D) - used in emoji sequences
    if (codePoint == 0x200D) return true
    
    // Emoji Modifiers (U+1F3FB to U+1F3FF) - skin tones
    if (codePoint in 0x1F3FB..0x1F3FF) return true
    
    // Combining Diacritical Marks and other combining characters that might be used with emoji
    if (codePoint in 0x0300..0x036F) return true
    
    return false
}

/** True if the string's own content direction (see [contentTextDirection]) is RTL. */
fun String.isContentRtl(): Boolean = contentTextDirection() == TextDirection.Rtl

/**
 * The [LayoutDirection] matching the string's own content direction (see [contentTextDirection]).
 * Use this to override `LocalLayoutDirection` for a piece of content whose language differs from
 * the app's ambient UI direction.
 */
fun String.contentLayoutDirection(): LayoutDirection =
    if (isContentRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr

/**
 * Converts a TextDirection to an absolute horizontal alignment.
 * RTL text directions map to Right, LTR to Left.
 */
fun TextDirection.toAbsoluteAlignment(): Alignment.Horizontal =
    if (this == TextDirection.Rtl) AbsoluteAlignment.Right else AbsoluteAlignment.Left

/**
 * Keeps a left-to-right string (e.g. "2 GB") from being reordered to "GB 2" when it is shown in an
 * RTL layout, by wrapping it in LRM marks. Strings whose own content is RTL are returned unchanged.
 */
fun String.keepLtr(): String = if (isContentRtl()) this else "\u200E$this\u200E"
