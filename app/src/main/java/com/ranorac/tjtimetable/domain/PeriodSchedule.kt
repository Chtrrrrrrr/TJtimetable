package com.ranorac.tjtimetable.domain

import java.time.LocalTime

/** A single 节 (class period) and the wall-clock span it occupies. */
data class Period(val index: Int, val start: LocalTime, val end: LocalTime)

/**
 * The 作息时间表 — which clock time each 节 starts and ends.
 *
 * Defaults to 同济大学's published schedule (11 节). Different campuses and
 * different 作息 versions exist, so every row is editable in settings; calendar
 * reminders always follow whatever is configured here rather than a constant.
 */
data class PeriodSchedule(val periods: List<Period>) {

    fun period(unit: Int): Period? = periods.firstOrNull { it.index == unit }

    fun startOf(unit: Int): LocalTime? = period(unit)?.start

    fun endOf(unit: Int): LocalTime? = period(unit)?.end

    /** Clock span covered by `startUnit..endUnit`, or null if either is unknown. */
    fun spanOf(startUnit: Int, endUnit: Int): Pair<LocalTime, LocalTime>? {
        // A reversed range would otherwise return a span that ends before it
        // starts, which silently produces a negative-length calendar event.
        if (endUnit < startUnit) return null
        val from = startOf(startUnit) ?: return null
        val to = endOf(endUnit) ?: return null
        return from to to
    }

    val maxUnit: Int get() = periods.maxOfOrNull { it.index } ?: 0

    companion object {
        /**
         * 同济大学 作息时间表, 11 节:
         * 上午 1-4 节, 下午 5-8 节, 晚上 9-11 节.
         */
        val TONGJI: PeriodSchedule = PeriodSchedule(
            listOf(
                Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                Period(2, LocalTime.of(8, 50), LocalTime.of(9, 35)),
                Period(3, LocalTime.of(10, 0), LocalTime.of(10, 45)),
                Period(4, LocalTime.of(10, 50), LocalTime.of(11, 35)),
                Period(5, LocalTime.of(13, 30), LocalTime.of(14, 15)),
                Period(6, LocalTime.of(14, 20), LocalTime.of(15, 5)),
                Period(7, LocalTime.of(15, 30), LocalTime.of(16, 15)),
                Period(8, LocalTime.of(16, 20), LocalTime.of(17, 5)),
                Period(9, LocalTime.of(18, 30), LocalTime.of(19, 15)),
                Period(10, LocalTime.of(19, 20), LocalTime.of(20, 5)),
                Period(11, LocalTime.of(20, 10), LocalTime.of(20, 55)),
            ),
        )
    }
}
