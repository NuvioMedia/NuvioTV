package com.nuvio.tv.ui.util

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDirection

/**
 * Detects a string's own reading direction from its first strong-directional character
 * (Unicode bidi rules P2/P3), independent of the app's ambient UI locale/LayoutDirection.
 * 
 * Skips emoji characters (including flag emojis) to find the actual text content direction.
 */
fun String.contentTextDirection(): TextDirection {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val charCount = Character.charCount(codePoint)
        
        // Skip emoji and emoji-related characters
        if (isEmojiOrModifier(codePoint)) {
            index += charCount
            continue
        }
        
        // Check directionality of non-emoji characters
        val directionality = Character.getDirectionality(codePoint)
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return TextDirection.Rtl
        }
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return TextDirection.Ltr
        }
        index += charCount
    }
    return TextDirection.Ltr
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
 * Converts a TextDirection to an absolute horizontal alignment.
 * RTL text directions map to Right, LTR to Left.
 */
fun TextDirection.toAbsoluteAlignment(): Alignment.Horizontal =
    if (this == TextDirection.Rtl) AbsoluteAlignment.Right else AbsoluteAlignment.Left
