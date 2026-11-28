package com.nuvio.tv.core.util

import java.text.BreakIterator

/**
 * The first user-perceived character of the text: a whole emoji (including skin-tone / ZWJ sequences
 * and flags), or a letter together with its combining marks (Hebrew niqqud, accents).
 * `firstOrNull()` would return half of a surrogate pair for an emoji such as "🎬".
 */
fun String.firstGraphemeOrNull(): String? {
    val text = trim()
    if (text.isEmpty()) return null
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.next()
    return text.substring(0, if (end == BreakIterator.DONE) text.length else end)
}
