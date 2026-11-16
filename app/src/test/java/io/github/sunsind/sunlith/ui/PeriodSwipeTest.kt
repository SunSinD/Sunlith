package io.github.sunsind.sunlith.ui

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodSwipeTest {
    /** A 60 Hz frame clock in real time, so the slide's spring runs as it would on screen. */
    private class FrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            time += 16_666_667L
            return onFrame(time)
        }
    }

    private fun swipe(state: PeriodSwipeState, by: Float) {
        state.start()
        state.dragBy(by)
        state.release(0f, 400f)
    }

    @Test
    fun aSecondSwipeDuringTheFirstSlideCommitsBothInsteadOfStoppingAtTheEdge() = runBlocking {
        val scope = CoroutineScope(coroutineContext + FrameClock() + Job())
        try {
            val commits = mutableListOf<Int>()
            val state = PeriodSwipeState(scope).apply { width = 1000; onCommit = { commits += it } }
            swipe(state, -400f)
            assertEquals("The slide already points at the next period", 1, state.previewSteps)
            withTimeout(2_000) { while (state.offset > -450f) delay(1) }
            assertTrue("The first slide is still running", commits.isEmpty() && state.offset > -1000f)

            val shown = state.offset
            state.start()
            assertEquals("The new drag commits the first slide at once", listOf(1), commits)
            assertEquals("The content stays where it was, measured from the new period", shown + 1000f, state.offset, 0.01f)
            assertTrue(state.keepOffsetOnCommit)
            assertEquals(0, state.previewSteps)

            state.dragBy(-900f)
            assertTrue("The second drag moves freely instead of stopping at the edge", state.offset < -300f)
            state.release(0f, 400f)
            withTimeout(5_000) { while (commits.size < 2 || state.offset != 0f) delay(5) }
            assertEquals("Two swipes are two periods", listOf(1, 1), commits)
            assertEquals(0, state.previewSteps)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingASlideNeverCommitsItForAnotherView() = runBlocking {
        val scope = CoroutineScope(coroutineContext + FrameClock() + Job())
        try {
            val commits = mutableListOf<Int>()
            val state = PeriodSwipeState(scope).apply { width = 1000; onCommit = { commits += it } }
            swipe(state, -400f)
            withTimeout(2_000) { while (state.offset > -450f) delay(1) }
            state.cancel()
            delay(100)
            assertTrue("A layout or view change drops the slide without moving the date", commits.isEmpty())
            assertEquals(0f, state.offset, 0f)
            assertEquals(0, state.previewSteps)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aShortDragSpringsBackWithoutACommit() = runBlocking {
        val scope = CoroutineScope(coroutineContext + FrameClock() + Job())
        try {
            val commits = mutableListOf<Int>()
            val state = PeriodSwipeState(scope).apply { width = 1000; onCommit = { commits += it } }
            swipe(state, -100f)
            assertEquals(0, state.previewSteps)
            withTimeout(5_000) { while (state.offset != 0f) delay(5) }
            assertTrue(commits.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
