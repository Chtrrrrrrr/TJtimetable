package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for 调休串休 handling: [CalendarDayKind], [DayAdjustment],
 * [ScheduleAdjustmentSet], the [ScheduleAdjustmentSet.inferMakeupDays] heuristic and
 * the [ScheduleAdjustmentSet.parseNotice] prose parser.
 *
 * Reference weekdays used below (verified against the real calendar):
 * 2026-04-25 Sat, 04-26 Sun, 04-27 Mon, 04-28 Tue, 04-29 Wed, 04-30 Thu,
 * 05-01 Fri, 05-02 Sat, 05-03 Sun, 05-04 Mon, 05-06 Wed, 05-09 Sat.
 * The prose examples come from real 2023 调休 notices: 2023-05-06 was a Saturday that
 * made up 2023-05-03 (Wednesday) and 2023-04-23 (Sunday) ran 2023-04-25 (Tuesday).
 */
class ScheduleAdjustmentTest {

    private fun date(text: String): LocalDate = LocalDate.parse(text)

    private fun adjustmentSetOf(vararg adjustments: DayAdjustment): ScheduleAdjustmentSet =
        ScheduleAdjustmentSet.EMPTY.withAll(adjustments.asList())

    // ------------------------------------------------------------------
    // CalendarDayKind
    // ------------------------------------------------------------------

    @Test
    fun `calendar day kind maps every documented api code`() {
        assertEquals(CalendarDayKind.HOLIDAY, CalendarDayKind.fromApi("1"))
        assertEquals(CalendarDayKind.WORKDAY, CalendarDayKind.fromApi("2"))
        assertEquals(CalendarDayKind.WEEKEND, CalendarDayKind.fromApi("3"))
        assertEquals(CalendarDayKind.WINTER_BREAK, CalendarDayKind.fromApi("4"))
        assertEquals(CalendarDayKind.SUMMER_BREAK, CalendarDayKind.fromApi("5"))
    }

    @Test
    fun `an unknown api code becomes UNKNOWN`() {
        assertEquals(CalendarDayKind.UNKNOWN, CalendarDayKind.fromApi("9"))
        assertEquals(CalendarDayKind.UNKNOWN, CalendarDayKind.fromApi(null))
        assertEquals(CalendarDayKind.UNKNOWN, CalendarDayKind.fromApi("holiday"))
    }

    @Test
    fun `only holidays and breaks are non teaching kinds`() {
        assertTrue(CalendarDayKind.HOLIDAY.isNonTeaching)
        assertTrue(CalendarDayKind.WINTER_BREAK.isNonTeaching)
        assertTrue(CalendarDayKind.SUMMER_BREAK.isNonTeaching)
        assertFalse(CalendarDayKind.WORKDAY.isNonTeaching)
        assertFalse(CalendarDayKind.WEEKEND.isNonTeaching)
        assertFalse(CalendarDayKind.UNKNOWN.isNonTeaching)
    }

    // ------------------------------------------------------------------
    // fromApiCalendar
    // ------------------------------------------------------------------

    @Test
    fun `fromApiCalendar maps every api code onto a kind`() {
        val set = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf(
                "2026-05-01" to "1",
                "2026-05-02" to "2",
                "2026-05-03" to "3",
                "2026-05-04" to "4",
                "2026-05-05" to "5",
            ),
        )
        assertEquals(5, set.size)
        assertEquals(CalendarDayKind.HOLIDAY, set.get(date("2026-05-01"))?.kind)
        assertEquals(CalendarDayKind.WORKDAY, set.get(date("2026-05-02"))?.kind)
        assertEquals(CalendarDayKind.WEEKEND, set.get(date("2026-05-03"))?.kind)
        assertEquals(CalendarDayKind.WINTER_BREAK, set.get(date("2026-05-04"))?.kind)
        assertEquals(CalendarDayKind.SUMMER_BREAK, set.get(date("2026-05-05"))?.kind)
        assertTrue(set.all.all { it.source == AdjustmentSource.API })
        assertTrue(set.all.all { it.followsWeekday == null })
    }

    @Test
    fun `fromApiCalendar skips unparseable date keys`() {
        val set = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf(
                "2026-05-01" to "1",
                "not-a-date" to "2",
                "2026-13-45" to "3",
                "" to "4",
                "20260501" to "1",
            ),
        )
        assertEquals(1, set.size)
        assertEquals(CalendarDayKind.HOLIDAY, set.get(date("2026-05-01"))?.kind)
    }

    @Test
    fun `fromApiCalendar keeps an unrecognised code as an unknown kind that is still a teaching day`() {
        // INTENTIONAL, not a bug: an unclassifiable day falls open and keeps its own
        // weekday, so a possibly-cancelled class is shown rather than silently hidden.
        val set = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-05-01" to "9"))
        assertEquals(CalendarDayKind.UNKNOWN, set.get(date("2026-05-01"))?.kind)
        assertEquals(DayOfWeek.FRIDAY, set.effectiveWeekday(date("2026-05-01")))
    }

    @Test
    fun `fromApiCalendar with an empty map is empty`() {
        val set = ScheduleAdjustmentSet.fromApiCalendar(emptyMap())
        assertEquals(0, set.size)
        assertTrue(set.all.isEmpty())
    }

    // ------------------------------------------------------------------
    // effectiveWeekday
    // ------------------------------------------------------------------

    @Test
    fun `a date with no entry keeps its own weekday`() {
        assertEquals(
            DayOfWeek.WEDNESDAY,
            ScheduleAdjustmentSet.EMPTY.effectiveWeekday(date("2026-05-06")),
        )
    }

    @Test
    fun `holiday has no classes`() {
        val set = adjustmentSetOf(DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY))
        assertNull(set.effectiveWeekday(date("2026-05-01")))
        assertFalse(set.isTeachingDay(date("2026-05-01")))
    }

    @Test
    fun `winter and summer breaks have no classes`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-01"), CalendarDayKind.WINTER_BREAK),
            DayAdjustment(date("2026-05-02"), CalendarDayKind.SUMMER_BREAK),
        )
        assertNull(set.effectiveWeekday(date("2026-05-01")))
        assertNull(set.effectiveWeekday(date("2026-05-02")))
    }

    @Test
    fun `a normal workday runs its own weekday`() {
        val set = adjustmentSetOf(DayAdjustment(date("2026-04-30"), CalendarDayKind.WORKDAY))
        assertEquals(DayOfWeek.THURSDAY, set.effectiveWeekday(date("2026-04-30")))
        assertTrue(set.isTeachingDay(date("2026-04-30")))
    }

    @Test
    fun `a followsWeekday override wins over the dates own weekday`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-06"), CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.MONDAY),
        )
        assertEquals(DayOfWeek.MONDAY, set.effectiveWeekday(date("2026-05-06")))
    }

    @Test
    fun `a followsWeekday override beats a holiday kind`() {
        // 补课日 that the API still reports as 节假日 must run the makeup timetable.
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-06"), CalendarDayKind.HOLIDAY, followsWeekday = DayOfWeek.MONDAY),
        )
        assertEquals(DayOfWeek.MONDAY, set.effectiveWeekday(date("2026-05-06")))
    }

    @Test
    fun `noClasses beats a followsWeekday override`() {
        val set = adjustmentSetOf(
            DayAdjustment(
                date = date("2026-05-06"),
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.MONDAY,
                noClasses = true,
                source = AdjustmentSource.MANUAL,
            ),
        )
        assertNull(set.effectiveWeekday(date("2026-05-06")))
        assertFalse(set.isTeachingDay(date("2026-05-06")))
    }

    @Test
    fun `weekend classes survive`() {
        // A Saturday the school marks as 周末 still runs its own weekday timetable, so
        // genuine weekend classes keep showing up on the grid.
        val set = adjustmentSetOf(DayAdjustment(date("2026-05-02"), CalendarDayKind.WEEKEND))
        assertEquals(DayOfWeek.SATURDAY, set.effectiveWeekday(date("2026-05-02")))
        assertTrue(set.isTeachingDay(date("2026-05-02")))
    }

    @Test
    fun `a weekend is a teaching day even without any entry`() {
        assertEquals(DayOfWeek.SATURDAY, ScheduleAdjustmentSet.EMPTY.effectiveWeekday(date("2026-05-02")))
        assertEquals(DayOfWeek.SUNDAY, ScheduleAdjustmentSet.EMPTY.effectiveWeekday(date("2026-05-03")))
        assertTrue(ScheduleAdjustmentSet.EMPTY.isTeachingDay(date("2026-05-02")))
    }

    @Test
    fun `a weekend workday still reports its own weekday when no override is set`() {
        val set = adjustmentSetOf(DayAdjustment(date("2026-05-02"), CalendarDayKind.WORKDAY))
        assertEquals(DayOfWeek.SATURDAY, set.effectiveWeekday(date("2026-05-02")))
    }

    // ------------------------------------------------------------------
    // with / withAll / without / queries
    // ------------------------------------------------------------------

    @Test
    fun `a manual entry is not overwritten by an api entry`() {
        val manual = DayAdjustment(
            date = date("2026-05-06"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.MONDAY,
            source = AdjustmentSource.MANUAL,
        )
        val api = DayAdjustment(date("2026-05-06"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.API)
        val result = adjustmentSetOf(manual).with(api)
        assertEquals(1, result.size)
        assertEquals(manual, result.get(date("2026-05-06")))
        assertEquals(AdjustmentSource.MANUAL, result.get(date("2026-05-06"))?.source)
        assertEquals(DayOfWeek.MONDAY, result.effectiveWeekday(date("2026-05-06")))
    }

    @Test
    fun `a manual entry is not overwritten by an inferred entry`() {
        val manual = DayAdjustment(
            date = date("2026-05-06"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.MONDAY,
            source = AdjustmentSource.MANUAL,
        )
        val inferred = DayAdjustment(
            date = date("2026-05-06"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.FRIDAY,
            source = AdjustmentSource.INFERRED,
        )
        val result = adjustmentSetOf(manual).with(inferred)
        assertEquals(DayOfWeek.MONDAY, result.get(date("2026-05-06"))?.followsWeekday)
        assertEquals(AdjustmentSource.MANUAL, result.get(date("2026-05-06"))?.source)
    }

    @Test
    fun `a manual entry can be replaced by another manual entry`() {
        val first = DayAdjustment(
            date = date("2026-05-06"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.MONDAY,
            source = AdjustmentSource.MANUAL,
        )
        val second = first.copy(followsWeekday = DayOfWeek.TUESDAY, note = "改过了")
        val result = adjustmentSetOf(first).with(second)
        assertEquals(1, result.size)
        assertEquals(second, result.get(date("2026-05-06")))
        assertEquals(DayOfWeek.TUESDAY, result.effectiveWeekday(date("2026-05-06")))
    }

    @Test
    fun `an api entry is replaced by an inferred entry`() {
        val api = DayAdjustment(date("2026-05-02"), CalendarDayKind.WORKDAY, source = AdjustmentSource.API)
        val inferred = DayAdjustment(
            date = date("2026-05-02"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.THURSDAY,
            source = AdjustmentSource.INFERRED,
        )
        val result = adjustmentSetOf(api).with(inferred)
        assertEquals(AdjustmentSource.INFERRED, result.get(date("2026-05-02"))?.source)
        assertEquals(DayOfWeek.THURSDAY, result.effectiveWeekday(date("2026-05-02")))
    }

    @Test
    fun `with adds a date that is not present yet`() {
        val set = adjustmentSetOf(DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY))
        val result = set.with(DayAdjustment(date("2026-05-02"), CalendarDayKind.WORKDAY))
        assertEquals(2, result.size)
        assertEquals(1, set.size) // the receiver is not mutated
        assertNull(set.get(date("2026-05-02")))
    }

    @Test
    fun `withAll applies every adjustment and lets manual win`() {
        val set = ScheduleAdjustmentSet.EMPTY.withAll(
            listOf(
                DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.API),
                DayAdjustment(
                    date = date("2026-05-01"),
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = DayOfWeek.MONDAY,
                    source = AdjustmentSource.MANUAL,
                ),
                DayAdjustment(date("2026-05-02"), CalendarDayKind.WORKDAY, source = AdjustmentSource.API),
            ),
        )
        assertEquals(2, set.size)
        assertEquals(AdjustmentSource.MANUAL, set.get(date("2026-05-01"))?.source)
        assertEquals(DayOfWeek.MONDAY, set.get(date("2026-05-01"))?.followsWeekday)
    }

    @Test
    fun `without removes a date`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY),
            DayAdjustment(date("2026-05-02"), CalendarDayKind.WORKDAY),
        )
        val result = set.without(date("2026-05-01"))
        assertEquals(1, result.size)
        assertNull(result.get(date("2026-05-01")))
        assertEquals(2, set.size) // the receiver is not mutated
        assertEquals(2, set.without(date("2026-05-09")).size)
    }

    @Test
    fun `manualEntries returns only hand edited rows sorted by date`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-06"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.MANUAL),
            DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.API),
            DayAdjustment(date("2026-05-04"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.MANUAL),
            DayAdjustment(date("2026-05-05"), CalendarDayKind.HOLIDAY, source = AdjustmentSource.INFERRED),
        )
        assertEquals(
            listOf(date("2026-05-04"), date("2026-05-06")),
            set.manualEntries().map { it.date },
        )
    }

    @Test
    fun `all is sorted by date`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-05-06"), CalendarDayKind.HOLIDAY),
            DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY),
            DayAdjustment(date("2026-05-03"), CalendarDayKind.WORKDAY),
        )
        assertEquals(
            listOf(date("2026-05-01"), date("2026-05-03"), date("2026-05-06")),
            set.all.map { it.date },
        )
    }

    // ------------------------------------------------------------------
    // isMakeup
    // ------------------------------------------------------------------

    @Test
    fun `isMakeup is true only when a different weekday runs`() {
        assertTrue(DayAdjustment(date("2026-05-06"), followsWeekday = DayOfWeek.MONDAY).isMakeup)
        assertTrue(DayAdjustment(date("2026-05-02"), followsWeekday = DayOfWeek.THURSDAY).isMakeup)
        assertFalse(DayAdjustment(date("2026-05-06"), followsWeekday = DayOfWeek.WEDNESDAY).isMakeup)
        assertFalse(DayAdjustment(date("2026-05-06")).isMakeup)
    }

    @Test
    fun `isMakeup is false for a day that was explicitly stopped`() {
        // A 停课 day is never a 补课日, whatever weekday it was pointed at ...
        val stopped = DayAdjustment(
            date = date("2026-05-06"),
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.MONDAY,
            noClasses = true,
            source = AdjustmentSource.MANUAL,
        )
        assertFalse(stopped.isMakeup)

        // ... and it still runs nothing, even though followsWeekday is set.
        val set = adjustmentSetOf(stopped)
        assertNull(set.effectiveWeekday(date("2026-05-06")))
        assertFalse(set.isTeachingDay(date("2026-05-06")))
        assertEquals(stopped, set.get(date("2026-05-06")))
    }

    // ------------------------------------------------------------------
    // nonTeachingDates
    // ------------------------------------------------------------------

    @Test
    fun `nonTeachingDates lists holidays breaks and no class days`() {
        val set = adjustmentSetOf(
            DayAdjustment(date("2026-04-29"), CalendarDayKind.WINTER_BREAK),
            DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY),
            DayAdjustment(
                date = date("2026-05-04"),
                kind = CalendarDayKind.WORKDAY,
                noClasses = true,
                source = AdjustmentSource.MANUAL,
            ),
        )
        val out = set.nonTeachingDates(date("2026-04-29"), date("2026-05-04"))
        assertEquals(
            listOf("2026-04-29", "2026-05-01", "2026-05-04"),
            out.map { it.toString() },
        )
    }

    @Test
    fun `nonTeachingDates is empty for an ordinary week`() {
        // Weekends count as teaching days (genuine weekend classes), so a plain
        // Saturday and Sunday are not listed.
        val out = ScheduleAdjustmentSet.EMPTY.nonTeachingDates(date("2026-05-02"), date("2026-05-03"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `nonTeachingDates returns nothing for a reversed range`() {
        val set = adjustmentSetOf(DayAdjustment(date("2026-05-01"), CalendarDayKind.HOLIDAY))
        assertTrue(set.nonTeachingDates(date("2026-05-05"), date("2026-05-01")).isEmpty())
    }

    // ------------------------------------------------------------------
    // parseNotice
    // ------------------------------------------------------------------

    @Test
    fun `parseNotice reads an explicit 补 A月B日 rule`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补5月3日（周三）的课", year = 2023)
        assertEquals(1, out.size)
        val a = out.single()
        assertEquals(date("2023-05-06"), a.date)
        assertEquals(CalendarDayKind.WORKDAY, a.kind)
        assertEquals(DayOfWeek.WEDNESDAY, a.followsWeekday)
        assertEquals(AdjustmentSource.MANUAL, a.source)
        assertEquals("补 5/3（周三）的课", a.note)
        assertTrue(a.isMakeup)
    }

    @Test
    fun `parseNotice prefers the parenthesised weekday label over the computed date`() {
        // The 补 A月B日 shape reads the trailing （周X） label and lets it outrank the
        // weekday computed from the date, because callers usually pass the *current*
        // year while the notice may belong to another one. With year 2026 the date
        // 2026-05-03 is a Sunday, so the label 周三 wins and the disagreement is
        // surfaced in the note instead of being applied silently.
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补5月3日（周三）的课", year = 2026)
        assertEquals(1, out.size)
        assertEquals(date("2026-05-06"), out.single().date)
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
        assertEquals(
            "补周三的课（公告写作 5/3，与所给年份不符，已按公告的周三处理）",
            out.single().note,
        )
        assertEquals(CalendarDayKind.WORKDAY, out.single().kind)
        assertEquals(AdjustmentSource.MANUAL, out.single().source)
    }

    @Test
    fun `parseNotice does not flag a year mismatch when the label agrees with the date`() {
        // Same prose parsed with the notice's own year: label and date agree, so the
        // note keeps the ordinary short form.
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补5月3日（周三）的课", year = 2023)
        assertEquals(1, out.size)
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
        assertEquals("补 5/3（周三）的课", out.single().note)
    }

    @Test
    fun `parseNotice reads the 星期 spelling of the parenthesised label`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补5月3日（星期三）的课", year = 2026)
        assertEquals(1, out.size)
        assertEquals(date("2026-05-06"), out.single().date)
        // 2026-05-03 is a Sunday, so the （星期三） label is what the row follows.
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
        assertEquals(
            "补周三的课（公告写作 5/3，与所给年份不符，已按公告的周三处理）",
            out.single().note,
        )
    }

    @Test
    fun `parseNotice keeps the explicit-date reading over the weekday-only reading`() {
        // Both rules match 5月6日 here, but rule 1 (explicit source date) is the more
        // specific reading, so the second sentence's 周三 wins over the first's 周一.
        val notice = "5月6日（周六）补周一的课。5月6日（周六）补5月3日（周三）的课"
        val out = ScheduleAdjustmentSet.parseNotice(notice, year = 2023)
        assertEquals(1, out.size)
        assertEquals(date("2023-05-06"), out.single().date)
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
        assertEquals("补 5/3（周三）的课", out.single().note)
    }

    @Test
    fun `parseNotice uses the weekday named in the prose for the 补周X form`() {
        // This shape names no second date, so the weekday is taken verbatim from the
        // prose and does not depend on the year argument.
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补周三的课", year = 2026)
        assertEquals(1, out.size)
        assertEquals(date("2026-05-06"), out.single().date)
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
        assertEquals("补周三的课", out.single().note)
    }

    @Test
    fun `parseNotice reads the 上 A月B日 的课程 wording`() {
        val out = ScheduleAdjustmentSet.parseNotice("4月23日（星期日）上4月25日（星期二）的课程", year = 2023)
        assertEquals(1, out.size)
        val a = out.single()
        assertEquals(date("2023-04-23"), a.date)
        // 2023-04-25 was a Tuesday.
        assertEquals(DayOfWeek.TUESDAY, a.followsWeekday)
        assertEquals(AdjustmentSource.MANUAL, a.source)
        assertTrue(a.isMakeup)
    }

    @Test
    fun `parseNotice reads a holiday span as consecutive holiday days`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月1日至5月5日放假", year = 2026)
        assertEquals(5, out.size)
        assertEquals(
            listOf("2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05"),
            out.map { it.date.toString() },
        )
        assertTrue(out.all { it.kind == CalendarDayKind.HOLIDAY })
        assertTrue(out.all { it.source == AdjustmentSource.MANUAL })
        assertTrue(out.all { it.followsWeekday == null })
        assertEquals("放假", out.first().note)
    }

    @Test
    fun `parseNotice reads a holiday span written with a dash`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月1日-5月3日放假", year = 2026)
        assertEquals(3, out.size)
        assertEquals("2026-05-01", out.first().date.toString())
        assertEquals("2026-05-03", out.last().date.toString())
    }

    @Test
    fun `parseNotice reads the 按周X课表上课 wording`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）按周三课表上课", year = 2026)
        assertEquals(1, out.size)
        val a = out.single()
        assertEquals(date("2026-05-06"), a.date)
        assertEquals(DayOfWeek.WEDNESDAY, a.followsWeekday)
        assertEquals(CalendarDayKind.WORKDAY, a.kind)
        assertEquals(AdjustmentSource.MANUAL, a.source)
        assertEquals("补周三的课", a.note)
    }

    @Test
    fun `parseNotice reads the weekday-only 补周X的课 wording`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（周六）补周一的课", year = 2026)
        assertEquals(1, out.size)
        assertEquals(DayOfWeek.MONDAY, out.single().followsWeekday)
    }

    @Test
    fun `parseNotice accepts half width parentheses and inner spacing`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日(周六) 补 5月3日(周三) 的课", year = 2023)
        assertEquals(1, out.size)
        assertEquals(date("2023-05-06"), out.single().date)
        assertEquals(DayOfWeek.WEDNESDAY, out.single().followsWeekday)
    }

    @Test
    fun `parseNotice accepts the 星期 spelling`() {
        val out = ScheduleAdjustmentSet.parseNotice("5月6日（星期六）补星期五的课", year = 2026)
        assertEquals(1, out.size)
        assertEquals(DayOfWeek.FRIDAY, out.single().followsWeekday)
    }

    @Test
    fun `parseNotice parses several rules out of one notice and sorts them`() {
        val notice = "4月23日（星期日）上4月25日（星期二）的课程。5月1日至5月5日放假。"
        val out = ScheduleAdjustmentSet.parseNotice(notice, year = 2023)
        assertEquals(6, out.size)
        assertEquals(
            listOf("2023-04-23", "2023-05-01", "2023-05-02", "2023-05-03", "2023-05-04", "2023-05-05"),
            out.map { it.date.toString() },
        )
        assertEquals(DayOfWeek.TUESDAY, out.first().followsWeekday)
        assertTrue(out.drop(1).all { it.kind == CalendarDayKind.HOLIDAY })
    }

    @Test
    fun `parseNotice keeps a makeup rule for a date that a holiday span also covers`() {
        // The 补课 rule is more specific than the 放假 span, so the 5月6日 row stays a
        // WORKDAY even though the span 5月1日至5月6日 also covers it.
        val notice = "5月1日至5月6日放假。5月6日（周六）补5月4日（周三）的课"
        val out = ScheduleAdjustmentSet.parseNotice(notice, year = 2026)
        assertEquals(6, out.size)
        val s06 = out.single { it.date == date("2026-05-06") }
        assertEquals(CalendarDayKind.WORKDAY, s06.kind)
        // The notice says 周三, and 2026-05-04 is a Monday, so the label wins.
        assertEquals(DayOfWeek.WEDNESDAY, s06.followsWeekday)
        assertEquals(
            "补周三的课（公告写作 5/4，与所给年份不符，已按公告的周三处理）",
            s06.note,
        )
        assertTrue(out.filter { it.date != date("2026-05-06") }.all { it.kind == CalendarDayKind.HOLIDAY })
    }

    @Test
    fun `parseNotice returns an empty list for blank text`() {
        assertTrue(ScheduleAdjustmentSet.parseNotice("", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("   ", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("\n\t", 2026).isEmpty())
    }

    @Test
    fun `parseNotice returns an empty list for garbage text instead of throwing`() {
        assertTrue(ScheduleAdjustmentSet.parseNotice("今天天气不错", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("???!!!", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("第1-17周", 2026).isEmpty())
    }

    @Test
    fun `parseNotice ignores impossible dates`() {
        assertTrue(ScheduleAdjustmentSet.parseNotice("13月45日补1月1日", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("2月30日补3月1日", 2026).isEmpty())
        assertTrue(ScheduleAdjustmentSet.parseNotice("5月1日至2月30日放假", 2026).isEmpty())
    }

    @Test
    fun `parseNotice rejects a holiday span that runs backwards`() {
        assertTrue(ScheduleAdjustmentSet.parseNotice("5月5日至5月1日放假", 2026).isEmpty())
    }

    // ------------------------------------------------------------------
    // inferMakeupDays
    // ------------------------------------------------------------------

    /**
     * A synthetic 五一-style block: Mon 2026-04-27 … Fri 2026-05-01 are 节假日 and the
     * two weekend days on either side are workdays, so the heuristic has something to
     * pair up.
     */
    private fun syntheticHolidayCalendar(): ScheduleAdjustmentSet =
        ScheduleAdjustmentSet.fromApiCalendar(
            mapOf(
                "2026-04-25" to "2", // Saturday workday
                "2026-04-26" to "2", // Sunday workday
                "2026-04-27" to "1", // Monday
                "2026-04-28" to "1", // Tuesday
                "2026-04-29" to "1", // Wednesday
                "2026-04-30" to "1", // Thursday
                "2026-05-01" to "1", // Friday
                "2026-05-02" to "2", // Saturday workday
                "2026-05-03" to "2", // Sunday workday
            ),
        )

    @Test
    fun `inferMakeupDays pairs weekend workdays with the weekdays the holiday swallowed`() {
        val base = syntheticHolidayCalendar()
        val inferred = ScheduleAdjustmentSet.inferMakeupDays(base)

        assertEquals(base.size, inferred.size)
        val makeup = inferred.all.filter { it.source == AdjustmentSource.INFERRED }
        assertEquals(4, makeup.size)
        assertTrue(makeup.all { it.kind == CalendarDayKind.WORKDAY })
        assertTrue(makeup.all { it.followsWeekday != null })
        assertTrue(makeup.all { it.isMakeup })

        // Days before the block are paired first-last, days after it last-first.
        assertEquals(DayOfWeek.MONDAY, inferred.get(date("2026-04-25"))?.followsWeekday)
        assertEquals(DayOfWeek.TUESDAY, inferred.get(date("2026-04-26"))?.followsWeekday)
        assertEquals(DayOfWeek.THURSDAY, inferred.get(date("2026-05-02"))?.followsWeekday)
        assertEquals(DayOfWeek.FRIDAY, inferred.get(date("2026-05-03"))?.followsWeekday)
    }

    @Test
    fun `inferMakeupDays leaves the holiday days themselves alone`() {
        val inferred = ScheduleAdjustmentSet.inferMakeupDays(syntheticHolidayCalendar())
        for (day in 27..30) {
            val d = date("2026-04-${day}")
            assertEquals(CalendarDayKind.HOLIDAY, inferred.get(d)?.kind)
            assertEquals(AdjustmentSource.API, inferred.get(d)?.source)
            assertNull(inferred.effectiveWeekday(d))
        }
        assertNull(inferred.effectiveWeekday(date("2026-05-01")))
    }

    @Test
    fun `inferMakeupDays never overwrites a manual entry`() {
        val manual = DayAdjustment(
            date = date("2026-04-26"),
            kind = CalendarDayKind.WORKDAY,
            noClasses = true,
            source = AdjustmentSource.MANUAL,
            note = "学生自行设定",
        )
        val base = syntheticHolidayCalendar().with(manual)
        val inferred = ScheduleAdjustmentSet.inferMakeupDays(base)

        assertEquals(manual, inferred.get(date("2026-04-26")))
        assertEquals(AdjustmentSource.MANUAL, inferred.get(date("2026-04-26"))?.source)
        assertNull(inferred.effectiveWeekday(date("2026-04-26")))
        // The manual weekend workday also stops the pairing on its own side ...
        assertNull(inferred.get(date("2026-04-25"))?.followsWeekday)
        assertEquals(AdjustmentSource.API, inferred.get(date("2026-04-25"))?.source)
        // ... while the other side is unaffected.
        assertEquals(DayOfWeek.FRIDAY, inferred.get(date("2026-05-03"))?.followsWeekday)
        assertEquals(
            listOf(date("2026-05-02"), date("2026-05-03")),
            inferred.all.filter { it.source == AdjustmentSource.INFERRED }.map { it.date },
        )
    }

    @Test
    fun `inferMakeupDays on an empty set does not crash`() {
        val result = ScheduleAdjustmentSet.inferMakeupDays(ScheduleAdjustmentSet.EMPTY)
        assertEquals(0, result.size)
        assertTrue(result.all.isEmpty())
    }

    @Test
    fun `inferMakeupDays leaves a calendar without holidays unchanged`() {
        val base = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf("2026-04-27" to "2", "2026-04-28" to "2", "2026-05-02" to "2"),
        )
        val result = ScheduleAdjustmentSet.inferMakeupDays(base)
        assertEquals(base.size, result.size)
        assertTrue(result.all.all { it.source == AdjustmentSource.API })
        assertTrue(result.all.all { it.followsWeekday == null })
    }

    @Test
    fun `inferMakeupDays adds nothing when a holiday block swallows no weekday`() {
        // 2026-05-02/03 are a Saturday and a Sunday, so nothing is swallowed.
        val base = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf("2026-05-02" to "1", "2026-05-03" to "1", "2026-05-09" to "2"),
        )
        val result = ScheduleAdjustmentSet.inferMakeupDays(base)
        assertTrue(result.all.all { it.source == AdjustmentSource.API })
    }

    @Test
    fun `inferMakeupDays on a single non holiday day returns it untouched`() {
        val base = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-04-27" to "2"))
        val result = ScheduleAdjustmentSet.inferMakeupDays(base)
        assertEquals(1, result.size)
        assertEquals(AdjustmentSource.API, result.get(date("2026-04-27"))?.source)
    }

    // ------------------------------------------------------------------
    // Chinese weekday helpers
    // ------------------------------------------------------------------

    @Test
    fun `weekday chinese labels`() {
        assertEquals("一", DayOfWeek.MONDAY.cn())
        assertEquals("二", DayOfWeek.TUESDAY.cn())
        assertEquals("三", DayOfWeek.WEDNESDAY.cn())
        assertEquals("四", DayOfWeek.THURSDAY.cn())
        assertEquals("五", DayOfWeek.FRIDAY.cn())
        assertEquals("六", DayOfWeek.SATURDAY.cn())
        assertEquals("日", DayOfWeek.SUNDAY.cn())
        assertEquals("周六", DayOfWeek.SATURDAY.cnLabel())
        assertEquals("周日", DayOfWeek.SUNDAY.cnLabel())
        assertEquals("周一", DayOfWeek.MONDAY.cnLabel())
    }

    // ------------------------------------------------------------------
    // 调休 editor semantics.
    //
    // The editor screen writes exactly these DayAdjustment shapes, so they are
    // pinned here rather than being verifiable only by tapping through the UI.
    // ------------------------------------------------------------------

    @Test
    fun `forcing normal classes on a holiday really opens that weekday`() {
        // The subtle one. effectiveWeekday() returns null when the kind is
        // non-teaching AND followsWeekday is null, so writing "正常上课" as
        // noClasses = false with a null followsWeekday would silently do nothing on
        // a 节假日. The editor therefore names the weekday explicitly.
        val holiday = LocalDate.of(2026, 5, 1) // a Friday
        val base = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-05-01" to "1"))
        assertNull(base.effectiveWeekday(holiday))

        val forced = base.with(
            DayAdjustment(
                date = holiday,
                kind = CalendarDayKind.HOLIDAY,
                followsWeekday = holiday.dayOfWeek,
                noClasses = false,
                source = AdjustmentSource.MANUAL,
            ),
        )
        assertEquals(DayOfWeek.FRIDAY, forced.effectiveWeekday(holiday))
        assertTrue(forced.isTeachingDay(holiday))
        // Same weekday, so it is not a 补课.
        assertFalse(forced.get(holiday)!!.isMakeup)
    }

    @Test
    fun `a null followsWeekday on a holiday stays closed`() {
        // Guards the exact mistake the test above exists to catch.
        val holiday = LocalDate.of(2026, 5, 1)
        val set = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-05-01" to "1"))
            .with(
                DayAdjustment(
                    date = holiday,
                    kind = CalendarDayKind.HOLIDAY,
                    followsWeekday = null,
                    noClasses = false,
                    source = AdjustmentSource.MANUAL,
                ),
            )
        assertNull(set.effectiveWeekday(holiday))
    }

    @Test
    fun `forcing no classes beats a weekday override`() {
        val date = LocalDate.of(2026, 5, 9) // a Saturday marked as a workday
        val set = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-05-09" to "2"))
            .with(
                DayAdjustment(
                    date = date,
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = DayOfWeek.WEDNESDAY,
                    noClasses = true,
                    source = AdjustmentSource.MANUAL,
                ),
            )
        assertNull(set.effectiveWeekday(date))
        assertFalse(set.isTeachingDay(date))
    }

    @Test
    fun `a manual edit replaces an inferred row for the same date`() {
        val date = LocalDate.of(2026, 5, 9)
        val inferred = DayAdjustment(
            date = date,
            kind = CalendarDayKind.WORKDAY,
            followsWeekday = DayOfWeek.WEDNESDAY,
            source = AdjustmentSource.INFERRED,
            note = "推测补 5/6（周三）的课",
        )
        val manual = inferred.copy(
            followsWeekday = DayOfWeek.THURSDAY,
            source = AdjustmentSource.MANUAL,
        )
        val set = ScheduleAdjustmentSet.EMPTY.with(inferred).with(manual)

        // The student's correction wins, and only it counts as a manual entry —
        // which is what stops refreshAdjustments() from overwriting it later.
        assertEquals(AdjustmentSource.MANUAL, set.get(date)!!.source)
        assertEquals(DayOfWeek.THURSDAY, set.effectiveWeekday(date))
        assertEquals(1, set.manualEntries().size)
    }

    @Test
    fun `clearing a manual row needs a refresh to restore the calendar default`() {
        // "恢复为校历自动判断" is a TWO-step operation, and this test pins why.
        // Deleting the override leaves the date with no row at all, and a missing
        // row means "no information" — which falls back to the date's own weekday,
        // so classes would RUN on what is really a 节假日. The 校历's own row has to
        // be re-derived afterwards, which is exactly why
        // AdjustmentViewModel.resetToAuto() clears and then refreshes.
        val holiday = LocalDate.of(2026, 5, 1) // a Friday
        val apiRow = DayAdjustment(
            date = holiday,
            kind = CalendarDayKind.HOLIDAY,
            source = AdjustmentSource.API,
        )
        val manual = DayAdjustment(
            date = holiday,
            kind = CalendarDayKind.HOLIDAY,
            // A deliberately wrong student override: 2026-05-01 is not a Wednesday.
            followsWeekday = DayOfWeek.WEDNESDAY,
            source = AdjustmentSource.MANUAL,
        )
        val set = ScheduleAdjustmentSet.EMPTY.with(apiRow).with(manual)
        assertEquals(DayOfWeek.WEDNESDAY, set.effectiveWeekday(holiday))

        // Step 1 — drop the override. With no row left, the date reverts to its own
        // weekday, which is the opposite of what "restore the default" should mean.
        val cleared = set.without(holiday)
        assertNull(cleared.get(holiday))
        assertEquals(DayOfWeek.FRIDAY, cleared.effectiveWeekday(holiday))
        assertEquals(0, cleared.manualEntries().size)

        // Step 2 — re-deriving the 校历 row closes the day again.
        val refreshed = cleared.with(apiRow)
        assertNull(refreshed.effectiveWeekday(holiday))
        assertFalse(refreshed.isTeachingDay(holiday))
    }
}
