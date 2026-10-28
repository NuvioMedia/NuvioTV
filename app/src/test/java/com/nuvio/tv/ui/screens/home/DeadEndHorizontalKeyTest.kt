package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadEndHorizontalKeyTest {

    @Test
    fun `ltr last item ignores right only`() {
        assertTrue(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionRight,
                isLastInRow = true,
                layoutDirection = LayoutDirection.Ltr
            )
        )
        assertFalse(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionLeft,
                isLastInRow = true,
                layoutDirection = LayoutDirection.Ltr
            )
        )
        assertFalse(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionUp,
                isLastInRow = true,
                layoutDirection = LayoutDirection.Ltr
            )
        )
    }

    @Test
    fun `rtl last item ignores left only`() {
        assertTrue(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionLeft,
                isLastInRow = true,
                layoutDirection = LayoutDirection.Rtl
            )
        )
        assertFalse(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionRight,
                isLastInRow = true,
                layoutDirection = LayoutDirection.Rtl
            )
        )
    }

    @Test
    fun `non-last item never ignores horizontal keys`() {
        assertFalse(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionRight,
                isLastInRow = false,
                layoutDirection = LayoutDirection.Ltr
            )
        )
        assertFalse(
            shouldIgnoreDeadEndHorizontalKey(
                key = Key.DirectionLeft,
                isLastInRow = false,
                layoutDirection = LayoutDirection.Rtl
            )
        )
    }
}
