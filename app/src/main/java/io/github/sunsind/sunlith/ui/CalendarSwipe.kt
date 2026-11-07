package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Horizontal paging between periods (month, week, day). The content follows the finger, the next or previous
 * period slides in beside it, and the change is committed only when the finger is released past a quarter of the
 * width or flung clearly sideways. The date changes once, after the slide settles, so nothing reloads mid-gesture.
 */
@Stable
internal class PeriodSwipeState(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    var width = 0
    var enabled = true
    var motion = true
    var rtl = false
    var onCommit: (Int) -> Unit = {}
    private var job: Job? = null
    private var queuedSteps = 0

    /** -1 or 1 while the previous or next period is partly visible, 0 at rest. */
    val peek: Int by derivedStateOf {
        val v = offset
        when {
            v == 0f -> 0
            (v < 0f) != rtl -> 1
            else -> -1
        }
    }

    fun start() {
        job?.cancel()
        job = null
        queuedSteps = 0
    }

    fun cancel() { start(); offset = 0f }

    fun cancelDrag() { settle(0f, 0) }

    fun dragBy(delta: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        offset = (offset + delta).coerceIn(-w, w)
    }

    fun release(velocity: Float, flingVelocity: Float) {
        val w = width.toFloat()
        val v = offset
        if (w <= 0f || v == 0f) {
            offset = 0f
            return
        }
        val fast = abs(velocity) > flingVelocity
        val towards = sign(velocity) == sign(v)
        val commit = enabled && !(fast && !towards) && (abs(v) > w * 0.25f || (fast && towards))
        val dir = if ((v < 0f) != rtl) 1 else -1
        settle(if (commit) sign(v) * w else 0f, if (commit) dir else 0)
    }

    /** Used by the arrows, so they move the same way as a swipe. */
    fun animateStep(dir: Int) {
        if (job?.isActive == true) {
            queuedSteps += dir
            return
        }
        val w = width.toFloat()
        if (!motion || w <= 0f) {
            start()
            offset = 0f
            onCommit(dir)
            return
        }
        settle(if ((dir > 0) != rtl) -w else w, dir)
    }

    private fun settle(target: Float, dir: Int) {
        job?.cancel()
        job = scope.launch {
            if (motion && offset != target) {
                animate(offset, target, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
            }
            if (dir != 0) onCommit(dir)
            offset = 0f
            job = null
            val next = queuedSteps.compareTo(0)
            queuedSteps -= next
            if (next != 0) animateStep(next)
        }
    }
}

@Composable
internal fun rememberPeriodSwipe(enabled: Boolean, motion: Boolean, onCommit: (Int) -> Unit): PeriodSwipeState {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val state = remember(scope) { PeriodSwipeState(scope) }
    SideEffect {
        state.enabled = enabled
        state.motion = motion
        state.rtl = rtl
        state.onCommit = onCommit
    }
    return state
}

/**
 * Claims deliberate sideways drags on the Initial pass, before a vertical child can take the same movement.
 * Once vertical scrolling wins, the rest of that gesture stays with the child, even if the thumb curves sideways.
 * When swiping is off, sideways drags are still consumed so they never count as a tap on an event or date.
 */
internal fun Modifier.periodSwipe(state: PeriodSwipeState): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    val flingVelocity = 400.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var dragging = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (change.changedToUpIgnoreConsumed()) {
                    if (dragging) {
                        change.consume()
                        state.release(tracker.calculateVelocity().x, flingVelocity)
                        released = true
                    }
                    break
                }
                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                if (!dragging) {
                    total += change.positionChange()
                    val ax = abs(total.x)
                    val ay = abs(total.y)
                    if (ay > slop && ay >= ax) break
                    if (ax > slop && ax > ay * 1.4f) {
                        dragging = true
                        state.start()
                        if (state.enabled) state.dragBy(total.x - sign(total.x) * slop)
                        change.consume()
                    } else {
                        // Honour a child that starts scrolling while the direction is still undecided.
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any { it.id == down.id && it.isConsumed }) break
                    }
                } else {
                    if (state.enabled) state.dragBy(change.positionChange().x)
                    change.consume()
                }
            }
        } finally {
            if (dragging && !released) state.cancelDrag()
        }
    }
}

/**
 * The current slot stays in the composition, preserving its scroll state. Full-month callers can opt into warming
 * both neighbours after the first frame, avoiding composition at touch slop. Other layouts create a neighbour on
 * demand so a timeline can capture the current vertical scroll position. Neighbours never affect the current slot's
 * measured size and stay outside accessibility.
 */
@Composable
internal fun SlidingPeriod(
    state: PeriodSwipeState,
    modifier: Modifier = Modifier,
    prewarmNeighbours: Boolean = false,
    content: @Composable (Int) -> Unit,
) {
    var prewarmed by remember(state, prewarmNeighbours) { mutableStateOf(false) }
    LaunchedEffect(state, prewarmNeighbours) {
        if (prewarmNeighbours) {
            withFrameNanos { }
            prewarmed = true
        }
    }
    Box(modifier.clipToBounds()) {
        Box(Modifier.graphicsLayer { translationX = state.offset }) { content(0) }
        repeat(2) { index ->
            val dir = if (index == 0) -1 else 1
            if (prewarmed || state.peek == dir) {
                key(dir) {
                    Box(
                        Modifier.matchParentSize().graphicsLayer {
                            val side = if ((dir > 0) != state.rtl) 1f else -1f
                            translationX = state.offset + side * state.width
                            alpha = if (state.peek == dir) 1f else 0f
                        }.clearAndSetSemantics { },
                    ) { content(dir) }
                }
            }
        }
    }
}
