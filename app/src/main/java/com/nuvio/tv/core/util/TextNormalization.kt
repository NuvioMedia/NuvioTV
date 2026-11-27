package com.nuvio.tv.core.util

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

/**
 * Normalises a title or file name for equality checks: lower-cases it, removes diacritics and
 * vowel/cantillation marks (accents, Hebrew niqqud, Arabic harakat), and collapses every run of
 * characters that are not letters or digits **in any script** into a single space.
 *
 * Unlike a `[^a-z0-9]` filter this keeps Hebrew, Arabic, Cyrillic, CJK and other non-Latin titles
 * intact instead of reducing them to an empty string.
 */
fun String.normalizeForMatching(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(NON_LETTER_OR_DIGIT, " ")
        .trim()
