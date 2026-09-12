package com.nuvio.tv.ui.util

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Real Compose recompositions, without requiring an Android window or focusable UI nodes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedFocusRequesterTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private suspend fun TestScope.withComposition(test: suspend Harness.() -> Unit) {
        val harness = Harness(this)
        try {
            harness.test()
        } finally {
            harness.close()
        }
    }

    private class Harness(private val scope: TestScope) {
        val registry = mutableMapOf<String, FocusRequester>()
        val rendered = mutableMapOf<String, FocusRequester>()
        private val keys = mutableStateOf(listOf("a", "b"))
        private val revision = mutableStateOf(0)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(EmptyApplier(), recomposer)

        init {
            composition.setContent {
                revision.value
                rendered.clear()
                keys.value.forEach { itemKey ->
                    key(itemKey) { rendered[itemKey] = registry.rememberFocusRequester(itemKey) }
                }
            }
        }

        fun recompose(newKeys: List<String> = keys.value) {
            keys.value = newKeys
            revision.value++
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            clock.sendFrame(revision.value * 16_000_000L)
            scope.runCurrent()
        }

        fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test fun `reordering keeps identities shared with restoration callbacks`() = runTest {
        withComposition {
            val first = rendered.getValue("a")
            val second = rendered.getValue("b")
            recompose(listOf("b", "a"))
            assertSame(first, rendered["a"])
            assertSame(second, rendered["b"])
            assertSame(first, registry["a"])
            assertNotSame(first, second)
        }
    }

    @Test fun `lazy disposal and reentry retain callback requester`() = runTest {
        withComposition {
            val retained = registry.getValue("a")
            recompose(emptyList())
            recompose(listOf("a"))
            assertSame(retained, rendered["a"])
            assertSame(retained, registry["a"])
        }
    }

    @Test fun `owner replacement is used instead of an orphan cached by composition`() = runTest {
        withComposition {
            recompose()
            val replacement = FocusRequester()
            registry["a"] = replacement
            recompose()
            assertSame(replacement, rendered["a"])
        }
    }

    @Test fun `clearing retained map repopulates callback lookup during recomposition`() = runTest {
        withComposition {
            recompose()
            registry.clear()
            recompose()
            assertSame(rendered.getValue("a"), registry.getValue("a"))
            assertSame(rendered.getValue("b"), registry.getValue("b"))
        }
    }

    @Test fun `removed and pruned item gets a new requester on reentry`() = runTest {
        withComposition {
            val removed = registry.getValue("a")
            recompose(listOf("b"))
            registry.keys.retainAll(setOf("b"))
            recompose(listOf("a", "b"))
            assertNotSame(removed, rendered["a"])
            assertSame(rendered["a"], registry["a"])
        }
    }
}
