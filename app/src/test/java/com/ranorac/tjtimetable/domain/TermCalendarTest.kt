package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TermCalendar], the 教学周 ↔ date mapping.
 *
 * The fixture semester is the 2025-2026 学年第二学期 shape: teaching week 1 starts on
 * Monday 2026-03-02 and the term reports 17 teaching weeks, ending Sunday 2026-06-28.
 */
class TermCalendarTest {

    private val spring2026 = TermCalendar(
        calendarId = "2025-2026-2",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = LocalDate.of(2026, 3, 2),
        endDate = LocalDate.of(2026, 6, 26),
        totalWeeks = 17,
    )

    // ------------------------------------------------------------------
    // dateOf / weekOf
    // ------------------------------------------------------------------

    @Test
    fun `dateOf maps every weekday of week 1`() {
        assertEquals(LocalDate.of(2026, 3, 2), spring2026.dateOf(1, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 3, 3), spring2026.dateOf(1, DayOfWeek.TUESDAY))
        assertEquals(LocalDate.of(2026, 3, 4), spring2026.dateOf(1, DayOfWeek.WEDNESDAY))
        assertEquals(LocalDate.of(2026, 3, 5), spring2026.dateOf(1, DayOfWeek.THURSDAY))
        assertEquals(LocalDate.of(2026, 3, 6), spring2026.dateOf(1, DayOfWeek.FRIDAY))
        assertEquals(LocalDate.of(2026, 3, 7), spring2026.dateOf(1, DayOfWeek.SATURDAY))
        assertEquals(LocalDate.of(2026, 3, 8), spring2026.dateOf(1, DayOfWeek.SUNDAY))
    }

    @Test
    fun `weekOf round trips dateOf for every weekday of every week`() {
        for (week in 1..spring2026.totalWeeks) {
            for (dow in DayOfWeek.values()) {
                val date = spring2026.dateOf(week, dow)
                assertEquals("week $week $dow round trip", week, spring2026.weekOf(date))
            }
        }
    }

    @Test
    fun `weekOf is null before the first teaching week starts`() {
        assertNull(spring2026.weekOf(LocalDate.of(2026, 3, 1)))
        assertNull(spring2026.weekOf(LocalDate.of(2026, 2, 23)))
        assertNull(spring2026.weekOf(LocalDate.of(2025, 9, 1)))
    }

    @Test
    fun `weekOf is null past the last teaching week`() {
        assertNull(spring2026.weekOf(LocalDate.of(2026, 6, 29)))
        assertNull(spring2026.weekOf(LocalDate.of(2026, 7, 10)))
    }

    @Test
    fun `weekOf counts seven day blocks from the first week start`() {
        assertEquals(1, spring2026.weekOf(LocalDate.of(2026, 3, 2)))
        assertEquals(1, spring2026.weekOf(LocalDate.of(2026, 3, 8)))
        assertEquals(2, spring2026.weekOf(LocalDate.of(2026, 3, 9)))
        assertEquals(2, spring2026.weekOf(LocalDate.of(2026, 3, 15)))
        assertEquals(17, spring2026.weekOf(LocalDate.of(2026, 6, 22)))
        assertEquals(17, spring2026.weekOf(LocalDate.of(2026, 6, 28)))
    }

    @Test
    fun `contains mirrors weekOf`() {
        assertTrue(spring2026.contains(LocalDate.of(2026, 3, 2)))
        assertTrue(spring2026.contains(LocalDate.of(2026, 6, 28)))
        assertFalse(spring2026.contains(LocalDate.of(2026, 3, 1)))
        assertFalse(spring2026.contains(LocalDate.of(2026, 6, 29)))
    }

    @Test
    fun `weekOf ignores endDate and is driven by totalWeeks`() {
        // INTENTIONAL, not a bug: the term is bounded by totalWeeks rather than by
        // endDate, because the API's endDay also covers 考试周 and holidays that sit
        // beyond the teaching weeks. Do not "fix" this by clamping to endDate.
        val short = spring2026.copy(endDate = LocalDate.of(2026, 3, 8))
        assertEquals(17, short.weekOf(LocalDate.of(2026, 6, 22)))
        assertTrue(short.contains(LocalDate.of(2026, 6, 28)))
    }

    // ------------------------------------------------------------------
    // week boundaries and labels
    // ------------------------------------------------------------------

    @Test
    fun `weekStart and weekEnd are the Monday and Sunday of the week`() {
        assertEquals(LocalDate.of(2026, 3, 2), spring2026.weekStart(1))
        assertEquals(LocalDate.of(2026, 3, 8), spring2026.weekEnd(1))
        assertEquals(LocalDate.of(2026, 3, 9), spring2026.weekStart(2))
        assertEquals(DayOfWeek.MONDAY, spring2026.weekStart(1).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, spring2026.weekEnd(1).dayOfWeek)
    }

    @Test
    fun `last week boundaries`() {
        assertEquals(LocalDate.of(2026, 6, 22), spring2026.weekStart(17))
        assertEquals(LocalDate.of(2026, 6, 28), spring2026.weekEnd(17))
        assertEquals(spring2026.weekEnd(17), spring2026.weekStart(17).plusDays(6))
    }

    @Test
    fun `weekLabel formats month dot day`() {
        assertEquals("3.2 – 3.8", spring2026.weekLabel(1))
        assertEquals("3.9 – 3.15", spring2026.weekLabel(2))
        assertEquals("6.22 – 6.28", spring2026.weekLabel(17))
    }

    @Test
    fun `weeks lists every teaching week`() {
        assertEquals(17, spring2026.weeks.size)
        assertEquals(1, spring2026.weeks.first())
        assertEquals(17, spring2026.weeks.last())
        assertEquals((1..17).toList(), spring2026.weeks)
    }

    @Test
    fun `dateOf anchors on the configured week start day`() {
        val sundayStart = spring2026.copy(
            firstWeekStart = LocalDate.of(2026, 3, 1),
            weekStartDay = DayOfWeek.SUNDAY,
        )
        assertEquals(LocalDate.of(2026, 3, 1), sundayStart.dateOf(1, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 3, 2), sundayStart.dateOf(1, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 3, 7), sundayStart.dateOf(1, DayOfWeek.SATURDAY))
        assertEquals(1, sundayStart.weekOf(LocalDate.of(2026, 3, 7)))
    }

    // ------------------------------------------------------------------
    // fromApi
    // ------------------------------------------------------------------

    @Test
    fun `fromApi snaps a mid week registration day back onto Monday of week 1`() {
        val cal = TermCalendar.fromApi(
            calendarId = "2025-2026-2",
            name = "2025-2026学年度第2学期",
            year = 2025,
            term = 2,
            beginDay = LocalDate.of(2026, 3, 5), // Thursday 报到日
            endDay = LocalDate.of(2026, 6, 26),
            totalWeeks = 17,
        )
        assertEquals(LocalDate.of(2026, 3, 2), cal.firstWeekStart)
        assertEquals(DayOfWeek.MONDAY, cal.firstWeekStart.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, cal.weekStartDay)
        assertEquals(LocalDate.of(2026, 6, 26), cal.endDate)
        assertEquals(17, cal.totalWeeks)
        assertTrue(cal.isCurrent)
        // The 报到日 itself still falls inside week 1.
        assertEquals(1, cal.weekOf(LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun `fromApi keeps a Monday begin day unchanged`() {
        val cal = TermCalendar.fromApi(
            "2025-2026-2", "n", 2025, 2, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 26), 17,
        )
        assertEquals(LocalDate.of(2026, 3, 2), cal.firstWeekStart)
    }

    @Test
    fun `fromApi snaps a Sunday begin day back six days`() {
        val cal = TermCalendar.fromApi(
            "2025-2026-2", "n", 2025, 2, LocalDate.of(2026, 3, 8), LocalDate.of(2026, 6, 26), 17,
        )
        assertEquals(LocalDate.of(2026, 3, 2), cal.firstWeekStart)
    }

    @Test
    fun `fromApi clamps the week count into the bitmask range`() {
        val zero = TermCalendar.fromApi(
            "id", "n", 2025, 2, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 26), 0,
        )
        assertEquals(1, zero.totalWeeks)
        assertEquals(1, zero.weeks.size)

        val huge = TermCalendar.fromApi(
            "id", "n", 2025, 2, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 26), 200,
        )
        assertEquals(WeekPattern.MAX_WEEK, huge.totalWeeks)
    }

    @Test
    fun `fromApi can build a non current calendar`() {
        val cal = TermCalendar.fromApi(
            "id", "n", 2025, 2, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 26), 17,
            isCurrent = false,
        )
        assertFalse(cal.isCurrent)
    }

    // ------------------------------------------------------------------
    // Column ordering (the 从今天开始显示 setting)
    // ------------------------------------------------------------------

    private val monday = LocalDate.of(2026, 3, 2)

    @Test
    fun `columns run monday to sunday by default`() {
        val days = weekdayColumns(showWeekend = true, startOnToday = false, today = monday)
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            ),
            days,
        )
    }

    @Test
    fun `hiding the weekend drops saturday and sunday`() {
        val days = weekdayColumns(showWeekend = false, startOnToday = false, today = monday)
        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            days,
        )
    }

    @Test
    fun `startOnToday rotates the week so today leads`() {
        val wednesday = monday.plusDays(2)
        val days = weekdayColumns(showWeekend = true, startOnToday = true, today = wednesday)
        assertEquals(
            listOf(
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            ),
            days,
        )
    }

    @Test
    fun `rotation only reorders and never adds or drops a column`() {
        // If rotation changed the set of days, the grid would silently stop
        // showing a weekday — the failure mode this guards against.
        val base = weekdayColumns(showWeekend = true, startOnToday = false, today = monday)
        for (offset in 0L..6L) {
            val rotated = weekdayColumns(
                showWeekend = true,
                startOnToday = true,
                today = monday.plusDays(offset),
            )
            assertEquals(base.size, rotated.size)
            assertEquals(base.toSet(), rotated.toSet())
        }
    }

    @Test
    fun `rotation is a no-op when today is already first`() {
        assertEquals(
            weekdayColumns(true, false, monday),
            weekdayColumns(showWeekend = true, startOnToday = true, today = monday),
        )
    }

    @Test
    fun `rotation is a no-op when today is a hidden weekend`() {
        // With the weekend hidden there is no column to move today into, so the
        // grid must fall back to Monday-first rather than dropping a day.
        val expected = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        assertEquals(
            expected,
            weekdayColumns(showWeekend = false, startOnToday = true, today = monday.plusDays(5)),
        )
        assertEquals(
            expected,
            weekdayColumns(showWeekend = false, startOnToday = true, today = monday.plusDays(6)),
        )
    }

    @Test
    fun `rotation still works when the weekend is shown`() {
        val saturday = monday.plusDays(5)
        assertEquals(
            listOf(
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
            weekdayColumns(showWeekend = true, startOnToday = true, today = saturday),
        )
    }

    @Test
    fun `rotation depends only on the weekday, not on the date`() {
        // The grid passes today's real date while displaying an arbitrary week, so
        // rotation has to be a pure function of the weekday.
        val march = weekdayColumns(true, true, LocalDate.of(2026, 3, 4)) // a Wednesday
        val may = weekdayColumns(true, true, LocalDate.of(2026, 5, 6)) // also a Wednesday
        assertEquals(march, may)
        assertEquals(DayOfWeek.WEDNESDAY, march.first())
    }
}
