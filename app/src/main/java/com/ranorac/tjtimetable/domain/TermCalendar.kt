package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Maps 教学周 numbers onto real calendar dates, and back.
 *
 * Everything that needs "which date is week 5 Wednesday?" — the timetable grid,
 * the widget, and calendar reminders — goes through here.
 *
 * [firstWeekStart] is the first day of teaching week 1, which is *not* always
 * the semester's `beginDay` returned by the API: that field often points at the
 * 报到/注册 day, several days before teaching starts. It is therefore stored
 * explicitly and is user-adjustable in settings, which is the only reliable way
 * to handle the discrepancy.
 */
data class TermCalendar(
    /** 学期编号 (`calendarId`) used by the 教务 API. */
    val calendarId: String,
    /** e.g. `2025-2026学年度第2学期`. */
    val name: String,
    /** 学年度, e.g. 2025. */
    val year: Int,
    /** 1 = 秋季, 2 = 春季, 3 = 暑期. */
    val term: Int,
    /** First day of teaching week 1. */
    val firstWeekStart: LocalDate,
    /** Last day of the semester as reported by the API. */
    val endDate: LocalDate,
    /** Number of teaching weeks. */
    val totalWeeks: Int,
    /** Which weekday a teaching week begins on. 同济 uses Monday. */
    val weekStartDay: DayOfWeek = DayOfWeek.MONDAY,
    val isCurrent: Boolean = false,
) {

    fun weekStart(week: Int): LocalDate = firstWeekStart.plusWeeks((week - 1).toLong())

    fun weekEnd(week: Int): LocalDate = weekStart(week).plusDays(6)

    /** The date on which [dayOfWeek] of [week] falls. */
    fun dateOf(week: Int, dayOfWeek: DayOfWeek): LocalDate {
        val offset = ((dayOfWeek.value - weekStartDay.value) + 7) % 7
        return weekStart(week).plusDays(offset.toLong())
    }

    /** The teaching week containing [date], or null when outside the term. */
    fun weekOf(date: LocalDate): Int? {
        val diff = ChronoUnit.DAYS.between(firstWeekStart, date)
        if (diff < 0) return null
        val week = (diff / 7).toInt() + 1
        return if (week in 1..totalWeeks) week else null
    }

    fun contains(date: LocalDate): Boolean = weekOf(date) != null

    /** Every week number, ascending. */
    val weeks: List<Int> get() = (1..totalWeeks).toList()

    /** e.g. `3.2 – 3.8` for the week header. */
    fun weekLabel(week: Int): String {
        val s = weekStart(week)
        val e = weekEnd(week)
        return "${s.monthValue}.${s.dayOfMonth} – ${e.monthValue}.${e.dayOfMonth}"
    }

    companion object {
        /**
         * Builds a calendar from the `v1/rt/onetongji/school_calendar_current_term_calendar`
         * payload, which reports `beginDay` as epoch millis in the school's zone.
         */
        fun fromApi(
            calendarId: String,
            name: String,
            year: Int,
            term: Int,
            beginDay: LocalDate,
            endDay: LocalDate,
            totalWeeks: Int,
            isCurrent: Boolean = true,
        ): TermCalendar {
            // Snap the reported start onto a week boundary going backwards, so a
            // 报到日 mid-week still lands inside week 1.
            val offset = (beginDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
            val monday = beginDay.minusDays(offset.toLong())
            return TermCalendar(
                calendarId = calendarId,
                name = name,
                year = year,
                term = term,
                firstWeekStart = monday,
                endDate = endDay,
                totalWeeks = totalWeeks.coerceIn(1, WeekPattern.MAX_WEEK),
                weekStartDay = DayOfWeek.MONDAY,
                isCurrent = isCurrent,
            )
        }
    }
}

/**
 * The weekday columns to draw, in left-to-right order.
 *
 * With [startOnToday] the week is rotated so [today] occupies the first column,
 * which is what students on a five-day week generally want on a phone.
 *
 * Rotation only reorders columns — it never changes which dates a week contains,
 * and the first column is still a weekday, not a date, so a rotated week still
 * means the same thing. It is deliberately a no-op when today is a weekend the
 * grid is hiding, and when today is already first.
 *
 * This lives in `domain` rather than in the grid composable because it is pure
 * date semantics: the header and the grid must agree on it, and it is worth
 * testing without Compose.
 */
fun weekdayColumns(
    showWeekend: Boolean,
    startOnToday: Boolean,
    today: LocalDate,
): List<DayOfWeek> {
    val base = (1..(if (showWeekend) 7 else 5)).map { DayOfWeek.of(it) }
    if (!startOnToday) return base
    val index = base.indexOf(today.dayOfWeek)
    // index 0 already means "today is first", and -1 means today is hidden.
    if (index <= 0) return base
    return base.drop(index) + base.take(index)
}
