package io.github.sunsind.sunlith.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Horizontal paging between periods (month, week, day). The content follows the finger, the next or previous
 * period slides in beside it, and the change is committed only when the finger is released past a quarter of the
 * width or flung clearly sideways. The date changes once, after the slide settles, so nothing reloads mid-gesture.
 */
@Stable
internal class PeriodSwipeState(private val scope: CoroutineScope) {
    var pager by mutableStateOf<PagerState?>(null)
    var offset by mutableFloatStateOf(0f)
        private set
    var width = 0
    var enabled by mutableStateOf(true)
    var motion by mutableStateOf(true)
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
        pager?.let { pages ->
            job = scope.launch {
                var next = dir
                try {
                    do {
                        val target = (pages.settledPage + next).coerceIn(0, pages.pageCount - 1)
                        if (motion) pages.animateScrollToPage(target, animationSpec = tween(280, easing = FastOutSlowInEasing))
                        else pages.scrollToPage(target)
                        next = queuedSteps.compareTo(0)
                        queuedSteps -= next
                    } while (next != 0)
                } finally { job = null; queuedSteps = 0 }
            }
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
                animate(offset, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { value, _ -> offset = value }
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
internal fun Modifier.periodSwipe(state: PeriodSwipeState, allowChildHorizontalScroll: Boolean = false): Modifier = pointerInput(state, allowChildHorizontalScroll) {
    val slop = viewConfiguration.touchSlop
    val flingVelocity = 400.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!state.enabled && allowChildHorizontalScroll) {
            var moved = Offset.Zero
            while (true) {
                // Skip the down already seen on Initial; clickable may consume it on Main.
                awaitPointerEvent(PointerEventPass.Initial)
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                moved += change.positionChange()
                if (abs(moved.x) > slop && abs(moved.x) > abs(moved.y) * 1.4f) change.consume()
            }
            return@awaitEachGesture
        }
        if (state.pager != null && state.enabled) return@awaitEachGesture
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

/** Split layouts share one drag across their header and body. Absolute keys retain the incoming period on commit. */
@Composable
internal fun SlidingPeriod(
    state: PeriodSwipeState,
    period: Long,
    modifier: Modifier = Modifier,
    content: @Composable (Long) -> Unit,
) {
    val savedPages = rememberSaveableStateHolder()
    var prepared by remember { mutableStateOf(setOf(period)) }
    val previousPeriod = remember(state) { longArrayOf(period) }
    SideEffect {
        if (previousPeriod[0] != period) {
            previousPeriod[0] = period
            if (state.offset != 0f) state.cancel()
        }
    }
    LaunchedEffect(period, state.enabled) {
        if (state.enabled) {
            for (next in listOf(period - 1, period + 1)) {
                withFrameNanos { }
                withFrameNanos { }
                prepared = (prepared + period + next).filterTo(mutableSetOf()) { it in (period - 1)..(period + 1) }
            }
        } else prepared = setOf(period)
    }
    Box(modifier.clipToBounds()) {
        for (absolute in (period - 1)..(period + 1)) {
            val dir = (absolute - period).toInt()
            key(absolute) {
                if (dir == 0 || state.peek == dir || absolute in prepared) {
                    savedPages.SaveableStateProvider(absolute) {
                        Box(
                            (if (dir == 0) Modifier else Modifier.matchParentSize()).graphicsLayer {
                                val side = if ((dir > 0) != state.rtl) 1f else -1f
                                translationX = state.offset + if (dir == 0) 0f else side * state.width
                            }.then(if (dir == 0) Modifier else Modifier.clearAndSetSemantics { }),
                        ) { content(absolute) }
                    }
                }
            }
        }
    }
}

/** The visible page remains the same composition and scroll state when its settled date is committed. */
@Composable
internal fun PagedPeriod(
    state: PeriodSwipeState,
    period: Long,
    modifier: Modifier = Modifier,
    content: @Composable (Long) -> Unit,
) {
    val origin = rememberSaveable { period }
    val center = 1_000_000
    val expected = (center + period - origin).toInt().coerceIn(0, 2_000_000)
    val pager = rememberPagerState(initialPage = expected, pageCount = { 2_000_001 })
    val accepted = remember(pager) { intArrayOf(expected) }
    val syncing = remember(pager) { booleanArrayOf(false) }
    val firstSync = remember(pager) { booleanArrayOf(true) }
    DisposableEffect(state, pager) {
        state.pager = pager
        onDispose { if (state.pager === pager) state.pager = null }
    }
    SideEffect {
        if (firstSync[0] || expected != accepted[0]) {
            firstSync[0] = false
            syncing[0] = true
            accepted[0] = expected
            state.start()
            // A tapped date and its page must land in the same measure pass. A launched scroll can
            // leave the previous page under the newly selected date for the first rendered frame.
            pager.requestScrollToPage(expected)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { settled ->
            if (syncing[0]) {
                if (settled == accepted[0]) syncing[0] = false
            } else {
                val delta = settled - accepted[0]
                accepted[0] = settled
                if (delta != 0) state.onCommit(delta)
            }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier,
        key = { origin + it - center },
        userScrollEnabled = state.enabled,
        beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(
            state = pager,
            snapAnimationSpec = if (state.motion) spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy) else snap(),
            snapPositionalThreshold = 0.25f,
        ),
    ) { page ->
        Box(Modifier.fillMaxSize().then(if (page == expected) Modifier else Modifier.clearAndSetSemantics { })) {
            content(origin + page - center)
        }
    }
}
