package com.ranorac.tjtimetable.calendar

import com.ranorac.tjtimetable.domain.CalendarDayKind
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.TimetableResolver
import com.ranorac.tjtimetable.domain.WeekPattern
import com.ranorac.tjtimetable.domain.AdjustmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests the calendar module's pure logic.
 *
 * `CalendarSync` needs a `ContentResolver`, so the provider calls can only be exercised
 * on a device — but everything that decides **what** to write is pure, and there the risk
 * is highest: a wrong recurrence rule silently produces a system calendar that
 * contradicts the timetable the student sees in the app.
 *
 * Note the entry point is [buildEventSpecs], not `buildRrule`. `buildRrule` is a
 * low-level encoder that takes a **contiguous run** and knows nothing about the week
 * pattern — choosing the runs, and the `INTERVAL=2` that expresses 单双周, is
 * `buildEventSpecs`' job. Testing `buildRrule` with a hand-made run asserts the wrong
 * thing entirely.
 *
 * The other valuable test here is [calendar and grid agree about which classes happen]:
 * `resolveOccurrences` is a **second, independent implementation** of the same question
 * `TimetableResolver` answers. Two implementations of one rule drift, and if they do the
 * calendar contradicts the app — so they are pinned against each other over a term
 * containing 单双周, a holiday and a 补课日.
 */
class CalendarSyncLogicTest {

    private val monday = LocalDate.of(2026, 3, 2)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val term = TermCalendar(
        calendarId = "122",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = monday,
        endDate = monday.plusWeeks(17),
        totalWeeks = 18,
    )

    private val everyWeek = Course(id = 1, name = "数据结构")
    private val oddOnly = Course(id = 2, name = "大学英语")
    private val evenOnly = Course(id = 3, name = "大学物理")

    private val everyWeekSession = CourseSession(
        id = 11, courseId = 1, dayOfWeek = DayOfWeek.MONDAY,
        startUnit = 1, endUnit = 2, weeks = WeekPattern.parse("1-17"), room = "南101",
    )
    private val oddSession = CourseSession(
        id = 12, courseId = 2, dayOfWeek = DayOfWeek.WEDNESDAY,
        startUnit = 5, endUnit = 6, weeks = WeekPattern.parse("[1-17单]"), room = "北203",
    )
    private val evenSession = CourseSession(
        id = 13, courseId = 3, dayOfWeek = DayOfWeek.FRIDAY,
        startUnit = 3, endUnit = 4, weeks = WeekPattern.parse("[2-16双]"), room = "物理馆",
    )

    private val sessions = listOf(everyWeekSession, oddSession, evenSession)
    private val courses = listOf(everyWeek, oddOnly, evenOnly)

    private val from: LocalDate get() = monday
    private val to: LocalDate get() = monday.plusWeeks(17)

    private fun specsFor(
        course: Course,
        session: CourseSession,
        adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
    ): List<CalendarEventSpec> {
        val occurrences = resolveOccurrences(
            listOf(course), listOf(session), term, PeriodSchedule.TONGJI, adjustments, from, to,
        )
        return buildEventSpecs(
            course, session, occurrences, term, PeriodSchedule.TONGJI, adjustments, zone, 15,
        )
    }

    // ---------------------------------------------------------- 单双周 rules

    @Test
    fun `every-week session gets a plain weekly rule`() {
        val specs = specsFor(everyWeek, everyWeekSession)
        assertEquals("one recurring row for a contiguous run", 1, specs.size)
        val rrule = specs.single().rrule
        assertTrue("must be weekly: $rrule", rrule!!.uppercase().contains("FREQ=WEEKLY"))
        assertTrue("on Monday: $rrule", rrule.uppercase().contains("BYDAY=MO"))
        // The crux: a plain weekly run must NOT be encoded as every-other-week.
        assertTrue("no INTERVAL=2 for a plain run: $rrule", !rrule.uppercase().contains("INTERVAL=2"))
        assertNull("nothing to cancel in an unbroken run", specs.single().exdates)
    }

    /** The date each spec's rule starts on — its DTSTART, in the term's zone. */
    private fun startDates(specs: List<CalendarEventSpec>): List<LocalDate> =
        specs.map {
            java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate()
        }.sorted()

    /**
     * 单周 is **correctly dated** but encoded verbosely: `WeekPattern.ranges()` splits an
     * odd-only pattern into nine singletons, so `buildEventSpecs` emits nine
     * single-occurrence events rather than one `INTERVAL=2` rule — which is what its own
     * KDoc claims.
     *
     * The distinction matters and is asserted both ways: the calendar lands on exactly
     * the right days either way, so this is an **efficiency/doc mismatch, not a
     * correctness bug**. The property that must hold is the date set.
     */
    @Test
    fun `单周 session lands on exactly the odd weeks`() {
        val specs = specsFor(oddOnly, oddSession)
        val expected = (1..17).filter { it % 2 == 1 }
            .map { term.dateOf(it, DayOfWeek.WEDNESDAY) }

        assertEquals(
            "every odd week's Wednesday, and nothing else",
            expected,
            startDates(specs),
        )
        assertTrue("all nine odd weeks are covered", specs.size == expected.size)
        assertTrue(
            "each event covers exactly one week (verbose, but not wrong)",
            specs.all { it.rrule!!.uppercase().contains("COUNT=1") },
        )
    }

    @Test
    fun `双周 session lands on exactly the even weeks`() {
        val specs = specsFor(evenOnly, evenSession)
        val expected = (2..16).filter { it % 2 == 0 }
            .map { term.dateOf(it, DayOfWeek.FRIDAY) }

        assertEquals(
            "every even week's Friday, and nothing else",
            expected,
            startDates(specs),
        )
        assertTrue("each event covers exactly one week", specs.all { it.rrule!!.uppercase().contains("COUNT=1") })
    }

    @Test
    fun `an irregular week set becomes one row per contiguous run`() {
        // 1-4 plus 8-10 is two runs, and a single RRULE cannot express a gap.
        val split = CourseSession(
            id = 21, courseId = 1, dayOfWeek = DayOfWeek.TUESDAY,
            startUnit = 1, endUnit = 2, weeks = WeekPattern.parse("1-4,8-10"),
        )
        val specs = specsFor(everyWeek, split)
        assertEquals("two runs, two rows", 2, specs.size)
        assertTrue("every row carries a rule", specs.all { it.rrule != null })
    }

    // ------------------------------------------------------------- 调休

    @Test
    fun `a holiday inside a run becomes an EXDATE rather than shrinking the run`() {
        // Week 2's Monday. Shrinking COUNT would silently drop every week after it, so
        // the holiday must be expressed as an exclusion instead.
        val holiday = LocalDate.of(2026, 3, 9)
        val adjustments = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-03-09" to "1"))
            .with(
                DayAdjustment(
                    date = holiday,
                    kind = CalendarDayKind.HOLIDAY,
                    noClasses = true,
                    source = AdjustmentSource.API,
                ),
            )
        assertTrue("the holiday really is closed", !adjustments.isTeachingDay(holiday))

        val spec = specsFor(everyWeek, everyWeekSession, adjustments).single()
        assertTrue("the rule still covers the whole run", spec.rrule!!.uppercase().contains("COUNT="))
        val exdates = spec.exdates
        assertTrue("the holiday must be excluded: $exdates", !exdates.isNullOrEmpty())
        assertEquals("exactly one date is cancelled", 1, exdates!!.size)
    }

    @Test
    fun `a 补课日 is written as a standalone row with no rule`() {
        // The makeup Saturday sits in week 1 (odd), so the every-week Monday course is
        // the one that runs there.
        val holiday = LocalDate.of(2026, 3, 2)
        val makeup = LocalDate.of(2026, 3, 7)
        val adjustments = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf("2026-03-02" to "1", "2026-03-07" to "2"),
        ).with(
            DayAdjustment(
                date = makeup,
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.MONDAY,
                source = AdjustmentSource.MANUAL,
                note = "手动设为按周一课表",
            ),
        )

        val specs = specsFor(everyWeek, everyWeekSession, adjustments)
        val standalone = specs.filter { it.rrule == null }
        assertTrue(
            "a displaced class needs its own non-recurring row: ${specs.map { it.rrule }}",
            standalone.isNotEmpty(),
        )
    }

    // ------------------------------------------- calendar vs. grid agreement

    private fun adjustmentsWithMakeup(): ScheduleAdjustmentSet {
        val makeup = LocalDate.of(2026, 3, 7) // Saturday of week 1 (odd)
        return ScheduleAdjustmentSet.fromApiCalendar(
            mapOf("2026-03-02" to "1", "2026-03-07" to "2"),
        ).with(
            DayAdjustment(
                date = makeup,
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.MONDAY,
                source = AdjustmentSource.MANUAL,
                note = "手动设为按周一课表",
            ),
        )
    }

    @Test
    fun `calendar and grid agree about which classes happen`() {
        val adjustments = adjustmentsWithMakeup()

        val calendarSide = resolveOccurrences(
            courses, sessions, term, PeriodSchedule.TONGJI, adjustments, from, to,
        )
        val gridSide = TimetableResolver.occurrencesBetween(
            Timetable(term, courses, sessions, adjustments, PeriodSchedule.TONGJI), from, to,
        )

        // Identity of a class meeting: which course, which day, which 节 range. Times and
        // rooms are presentation and deliberately not compared.
        fun key(o: com.ranorac.tjtimetable.domain.ClassOccurrence) =
            "${o.date}|${o.course.id}|${o.startUnit}-${o.endUnit}"

        val calendarKeys = calendarSide.map(::key).toSet()
        val gridKeys = gridSide.map(::key).toSet()

        assertEquals(
            "the system calendar and the in-app grid must not disagree",
            gridKeys.sorted(),
            calendarKeys.sorted(),
        )
        // 17 Monday + 9 odd Wednesdays + 8 even Fridays = 34 meetings.
        assertEquals("fixture arithmetic", 34, gridKeys.size)
        // And the fixture must actually exercise 调休, or agreement proves nothing.
        assertTrue(
            "the makeup Saturday runs the Monday course on both sides",
            gridKeys.contains("2026-03-07|1|1-2"),
        )
        assertTrue(
            "the holiday Monday is absent from both sides",
            gridKeys.none { it.startsWith("2026-03-02|") },
        )
        assertTrue(
            "the 单周 course only appears in odd weeks",
            calendarSide.filter { it.course.id == 2L }
                .all { term.weekOf(it.date)!! % 2 == 1 },
        )
    }

    @Test
    fun `both sides close a holiday and keep a makeup`() {
        val adjustments = adjustmentsWithMakeup()
        val holiday = LocalDate.of(2026, 3, 2)
        val makeup = LocalDate.of(2026, 3, 7)
        val timetable = Timetable(term, courses, sessions, adjustments, PeriodSchedule.TONGJI)

        assertTrue(
            "no classes on the holiday",
            TimetableResolver.occurrencesForDate(timetable, holiday).isEmpty(),
        )
        assertTrue(
            "no classes on the holiday (calendar side)",
            resolveOccurrences(courses, sessions, term, PeriodSchedule.TONGJI, adjustments, holiday, holiday).isEmpty(),
        )

        assertTrue(
            "grid shows the 补课",
            TimetableResolver.occurrencesForDate(timetable, makeup).any { it.course.id == 1L },
        )
        assertTrue(
            "calendar shows the 补课",
            resolveOccurrences(courses, sessions, term, PeriodSchedule.TONGJI, adjustments, makeup, makeup)
                .any { it.course.id == 1L },
        )
    }

    @Test
    fun `no sessions or an inverted range produces nothing`() {
        assertTrue(
            resolveOccurrences(courses, emptyList(), term, PeriodSchedule.TONGJI, ScheduleAdjustmentSet.EMPTY, from, to).isEmpty(),
        )
        assertTrue(
            resolveOccurrences(courses, sessions, term, PeriodSchedule.TONGJI, ScheduleAdjustmentSet.EMPTY, to, from).isEmpty(),
        )
    }
}
