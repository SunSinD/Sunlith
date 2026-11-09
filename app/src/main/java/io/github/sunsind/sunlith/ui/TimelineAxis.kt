package io.github.sunsind.sunlith.ui

import kotlin.math.ceil

internal data class TimelineGap(
    val startMinute: Int,
    val endMinute: Int,
    val y: Float,
    val height: Float,
)

internal class TimelineAxis(
    val hourPx: Float,
    occupied: List<Pair<Int, Int>>,
    groupEmpty: Boolean = false,
    gapPx: Float = 32f,
) {
    private data class Segment(val start: Int, val end: Int, val y: Float, val height: Float)

    private val segments: List<Segment>
    val gaps: List<TimelineGap>
    val totalHeight: Float
    val normalHourMarkers: List<Int>

    init {
        require(hourPx.isFinite() && hourPx > 0f)
        require(gapPx.isFinite() && gapPx > 0f)
        val busyHours = BooleanArray(24)
        occupied.forEach { (from, to) ->
            if (to < from || to < 0 || from >= 1440) return@forEach
            val start = from.coerceIn(0, 1440)
            val end = to.coerceIn(0, 1440)
            if (end <= start && from != to) return@forEach
            val firstHour = start / 60
            val endHour = if (end > start) ceil(end / 60f).toInt() else firstHour + 1
            for (hour in (firstHour - 1).coerceAtLeast(0) until (endHour + 1).coerceAtMost(24)) {
                busyHours[hour] = true
            }
        }
        val emptyRuns = mutableListOf<Pair<Int, Int>>()
        if (groupEmpty && busyHours.any { it }) {
            var hour = 0
            while (hour < 24) {
                if (busyHours[hour]) {
                    hour++
                } else {
                    val first = hour
                    while (hour < 24 && !busyHours[hour]) hour++
                    if (hour - first >= 3) emptyRuns += first * 60 to hour * 60
                }
            }
        }
        val builtSegments = mutableListOf<Segment>()
        val builtGaps = mutableListOf<TimelineGap>()
        var minute = 0
        var y = 0f
        fun append(end: Int, height: Float) {
            if (end <= minute) return
            builtSegments += Segment(minute, end, y, height)
            minute = end
            y += height
        }
        emptyRuns.forEach { (start, end) ->
            append(start, (start - minute) / 60f * hourPx)
            val height = minOf(gapPx, (end - start) / 60f * hourPx)
            builtGaps += TimelineGap(start, end, y, height)
            append(end, height)
        }
        append(1440, (1440 - minute) / 60f * hourPx)
        segments = builtSegments
        gaps = builtGaps
        totalHeight = y
        normalHourMarkers = (0..24).map { it * 60 }.filter { marker ->
            gaps.none { marker >= it.startMinute && marker <= it.endMinute }
        }
    }

    fun minuteToY(minute: Int): Float = minuteToY(minute.toFloat())

    fun minuteToY(minute: Float): Float {
        val value = minute.coerceIn(0f, 1440f)
        val segment = segments.firstOrNull { value <= it.end } ?: segments.last()
        return segment.y + (value - segment.start) / (segment.end - segment.start) * segment.height
    }

    fun yToMinute(y: Float): Float {
        val value = y.coerceIn(0f, totalHeight)
        val segment = segments.firstOrNull { value <= it.y + it.height } ?: segments.last()
        return segment.start + (value - segment.y) / segment.height * (segment.end - segment.start)
    }
}
