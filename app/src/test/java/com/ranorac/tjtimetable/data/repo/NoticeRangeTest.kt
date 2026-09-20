package com.ranorac.tjtimetable.data.repo

import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.CalendarDayKind
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.TermCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The one rule that stands between a pasted 教务处 notice and a silently wrong timetable.
 *
 * A notice says "5月6日（周六）补5月3日（周三）的课" — a month and a day, no year. The caller
 * supplies the year and the UI supplies the current one, so pasting LAST year's notice marks
 * this year's genuine teaching days as holidays. That is not a cosmetic error: a holiday
 * suppresses classes, and the student would quietly not be shown (or reminded about) courses
 * that really run.
 *
 * Tested here rather than through `TimetableRepository` because the repository needs a Room
 * database and an HTTP client, while the decision itself is pure — and the decision is where
 * the bug would be.
 */
class NoticeRangeTest {

    private val monday = LocalDate.of(2026, 3, 2)

    private val term = TermCalendar(
        calendarId = "122",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = monday,
        endDate = monday.plusWeeks(17),
        totalWeeks = 18,
    )

    private fun holiday(date: LocalDate) = DayAdjustment(
        date = date,
        kind = CalendarDayKind.HOLIDAY,
        noClasses = true,
        source = AdjustmentSource.MANUAL,
    )

    @Test
    fun `dates inside the term are accepted`() {
        val inside = LocalDate.of(2026, 5, 1)
        val (accepted, rejected) = listOf(holiday(inside)).insideTerm(term)

        assertEquals(listOf(inside), accepted.map { it.date })
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `a date just outside the term is still accepted`() {
        // 调休 for the first teaching week legitimately lands in the days before the term
        // starts, and 补课 for the last week in the days after it ends. A rule that only
        // accepted [firstWeekStart, endDate] would reject real arrangements.
        val before = monday.minusDays(7)
        val after = term.endDate.plusDays(7)
        val (accepted, rejected) = listOf(holiday(before), holiday(after)).insideTerm(term)

        assertEquals(listOf(before, after), accepted.map { it.date })
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `a date a year away is rejected`() {
        // The whole point: last year's notice, same month and day, must not touch this year.
        val lastYear = LocalDate.of(2025, 5, 1)
        val nextYear = LocalDate.of(2027, 5, 1)
        val inside = LocalDate.of(2026, 5, 1)
        val (accepted, rejected) =
            listOf(holiday(lastYear), holiday(inside), holiday(nextYear)).insideTerm(term)

        assertEquals(listOf(inside), accepted.map { it.date })
        assertEquals(listOf(lastYear, nextYear), rejected.map { it.date })
    }

    @Test
    fun `a date one day past the slack is rejected`() {
        // Pins the boundary so the slack cannot drift unnoticed.
        val edge = monday.minusDays(8)
        val (accepted, rejected) = listOf(holiday(edge)).insideTerm(term)

        assertTrue(accepted.isEmpty())
        assertEquals(listOf(edge), rejected.map { it.date })
    }

    @Test
    fun `no term means nothing can be validated and everything is accepted`() {
        // Refusing dates because the semester is unknown would be a worse failure than
        // writing them: the student pasted a notice precisely because the app did not know.
        val any = LocalDate.of(2025, 5, 1)
        val (accepted, rejected) = listOf(holiday(any)).insideTerm(null)

        assertEquals(listOf(any), accepted.map { it.date })
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `an empty list splits into two empty lists`() {
        val (accepted, rejected) = emptyList<DayAdjustment>().insideTerm(term)
        assertTrue(accepted.isEmpty())
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `the term fixture is the one the assertion asks about`() {
        // Guards the test itself: if the term's dates moved, the "inside" dates above would
        // stop being inside and the assertions would silently stop testing anything.
        assertTrue(term.contains(LocalDate.of(2026, 5, 1)))
    }
}
