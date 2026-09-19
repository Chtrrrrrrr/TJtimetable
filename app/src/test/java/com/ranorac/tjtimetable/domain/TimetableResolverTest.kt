package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TimetableResolver] and the [Timetable] aggregate — the 调休串休
 * resolution that turns a semester into concrete dated occurrences.
 *
 * The design under test inverts the usual question: it asks *"which weekday's
 * timetable does this DATE run?"* rather than *"which sessions are on Wednesday?"*.
 * Holiday suppression, 补课, and genuine weekend classes all fall out of that
 * inversion, so most of these tests pin down one of those three consequences.
 *
 * The fixture is the 2025-2026 学年第二学期 shape: teaching week 1 starts on Monday
 * 2026-02-23 and the term reports 18 teaching weeks, so week 18 is 06-22 … 06-28.
 * Verified landmarks: 02-23 Mon, 02-24 Tue, 02-25 Wed, 02-27 Fri, 02-28 Sat,
 * 03-01 Sun, 03-02 Mon, 03-03 Tue, 03-04 Wed, 03-06 Fri, 03-07 Sat, 03-08 Sun,
 * 03-09 Mon, 06-22 Mon, 06-28 Sun, 06-29 Mon (outside the term).
 */
class TimetableResolverTest {

    // ==================================================================
    // Fixture
    // ==================================================================

    private val term = TermCalendar(
        calendarId = "2025-2026-2",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = LocalDate.of(2026, 2, 23),
        endDate = LocalDate.of(2026, 6, 28),
        totalWeeks = 18,
    )

    private val math = Course(id = 1, name = "高等数学", teachingClassId = 1001)
    private val english = Course(id = 2, name = "大学英语", teachingClassId = 1002)
    private val programDesign = Course(id = 3, name = "程序设计", teachingClassId = 1003)
    private val pe = Course(id = 4, name = "体育", teachingClassId = 1004)
    private val hiddenCourse = Course(id = 5, name = "隐藏课", teachingClassId = 1005, hidden = true)
    private val cLanguage = Course(id = 6, name = "C语言程序设计", teachingClassId = 1006)
    private val linearAlgebra = Course(id = 7, name = "线性代数", teachingClassId = 1007)
    private val physics = Course(id = 8, name = "大学物理", teachingClassId = 1008)
    private val unparsedWeeks = Course(id = 9, name = "未解析周次", teachingClassId = 1009)

    private val allCourses = listOf(
        math, english, programDesign, pe, hiddenCourse,
        cLanguage, linearAlgebra, physics, unparsedWeeks,
    )

    private val everyWeek = WeekPattern.range(1, 18)

    private val allSessions = listOf(
        // Monday, units 1-2 — three of them, two sharing a 节 range so name ordering is testable.
        CourseSession(id = 101, courseId = math.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 1, endUnit = 2, weeks = everyWeek),
        CourseSession(id = 102, courseId = cLanguage.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 1, endUnit = 2, weeks = everyWeek),
        CourseSession(id = 103, courseId = linearAlgebra.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 5, endUnit = 6, weeks = everyWeek),
        // Wednesday, units 3-4 — the 补课 target.
        CourseSession(
            id = 104, courseId = english.id, dayOfWeek = DayOfWeek.WEDNESDAY,
            startUnit = 3, endUnit = 4, weeks = everyWeek, room = "南楼114",
        ),
        // Wednesday, units 7-8, 单周 only — drives the ghosting tests.
        CourseSession(
            id = 105, courseId = pe.id, dayOfWeek = DayOfWeek.WEDNESDAY,
            startUnit = 7, endUnit = 8, weeks = WeekPattern.parse("[1-17单]"),
        ),
        // A genuine Saturday class, units 5-6.
        CourseSession(id = 106, courseId = programDesign.id, dayOfWeek = DayOfWeek.SATURDAY, startUnit = 5, endUnit = 6, weeks = everyWeek),
        // Hidden course, Monday.
        CourseSession(id = 107, courseId = hiddenCourse.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 1, endUnit = 2, weeks = everyWeek),
        // Orphan: no Course with id 99 exists.
        CourseSession(id = 108, courseId = 99L, dayOfWeek = DayOfWeek.MONDAY, startUnit = 9, endUnit = 10, weeks = everyWeek),
        // Tuesday, units 3-4 — the date whose own weekday must be replaced by a 补课.
        CourseSession(id = 109, courseId = physics.id, dayOfWeek = DayOfWeek.TUESDAY, startUnit = 3, endUnit = 4, weeks = everyWeek),
        // Friday, units 1-2, with a week field the parser could not read.
        CourseSession(id = 110, courseId = unparsedWeeks.id, dayOfWeek = DayOfWeek.FRIDAY, startUnit = 1, endUnit = 2, weeks = WeekPattern.EMPTY),
    )

    private val week1Mon = LocalDate.of(2026, 2, 23)
    private val week1Tue = LocalDate.of(2026, 2, 24)
    private val week1Wed = LocalDate.of(2026, 2, 25)
    private val week1Fri = LocalDate.of(2026, 2, 27)
    private val week1Sat = LocalDate.of(2026, 2, 28)
    private val week1Sun = LocalDate.of(2026, 3, 1)
    private val week2Mon = LocalDate.of(2026, 3, 2)
    private val week2Tue = LocalDate.of(2026, 3, 3)
    private val week2Wed = LocalDate.of(2026, 3, 4)
    private val week2Fri = LocalDate.of(2026, 3, 6)
    private val week2Sat = LocalDate.of(2026, 3, 7)
    private val week2Sun = LocalDate.of(2026, 3, 8)
    private val week3Mon = LocalDate.of(2026, 3, 9)
    private val week18Mon = LocalDate.of(2026, 6, 22)

    private fun timetableWith(
        courses: List<Course> = allCourses,
        sessions: List<CourseSession> = allSessions,
        adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
        periodSchedule: PeriodSchedule = PeriodSchedule.TONGJI,
    ): Timetable = Timetable(term, courses, sessions, adjustments, periodSchedule)

    /** The fixture with no 调休 at all. */
    private val base = timetableWith()

    private fun adjustmentsOf(vararg entries: DayAdjustment): ScheduleAdjustmentSet =
        ScheduleAdjustmentSet.EMPTY.withAll(entries.asList())

    private fun namesOf(occurrences: List<ClassOccurrence>): List<String> =
        occurrences.map { it.course.name }

    /**
     * Two Monday sessions whose *import* order is the reverse of their time order —
     * the shape the 教务 API can return when a course's rows arrive unsorted.
     */
    private fun outOfOrderTimetable(): Timetable {
        val late = Course(id = 10, name = "晚课")
        val early = Course(id = 11, name = "早课")
        return timetableWith(
            courses = listOf(late, early),
            sessions = listOf(
                CourseSession(id = 1, courseId = late.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 5, endUnit = 6, weeks = everyWeek),
                CourseSession(id = 2, courseId = early.id, dayOfWeek = DayOfWeek.MONDAY, startUnit = 1, endUnit = 2, weeks = everyWeek),
            ),
        )
    }

    // ==================================================================
    // Timetable aggregate
    // ==================================================================

    @Test
    fun `timetable finds a course by id`() {
        assertEquals(math, base.course(math.id))
        assertEquals(unparsedWeeks, base.course(unparsedWeeks.id))
    }

    @Test
    fun `timetable course lookup returns null for an unknown id`() {
        assertNull(base.course(99L))
        assertNull(base.course(0L))
    }

    @Test
    fun `sessionsOf returns only the sessions of that course`() {
        assertEquals(listOf(104L), base.sessionsOf(english.id).map { it.id })
        assertEquals(listOf(101L), base.sessionsOf(math.id).map { it.id })
        assertEquals(listOf(105L), base.sessionsOf(pe.id).map { it.id })
    }

    @Test
    fun `sessionsOf is empty for an unknown course id`() {
        assertTrue(base.sessionsOf(999L).isEmpty())
        assertTrue(base.sessionsOf(-1L).isEmpty())
        // An orphan session is still returned by the aggregate — it is only the dated
        // resolver that drops sessions whose course is missing.
        assertEquals(listOf(108L), base.sessionsOf(99L).map { it.id })
    }

    @Test
    fun `sessionsIn returns only the sessions that run in that week`() {
        // 体育 is 单周, so it is missing from the even week 2 but present in week 1.
        assertTrue(base.sessionsIn(1).any { it.courseId == pe.id })
        assertFalse(base.sessionsIn(2).any { it.courseId == pe.id })
        assertTrue(base.sessionsIn(3).any { it.courseId == pe.id })
        // The unparsed-weeks session is never active, and orphans are still listed here.
        assertFalse(base.sessionsIn(1).any { it.courseId == unparsedWeeks.id })
        assertTrue(base.sessionsIn(1).any { it.courseId == 99L })
        // 9 sessions run in an odd week (8 every-week ones plus 体育), 8 in an even week.
        assertEquals(9, base.sessionsIn(1).size)
        assertEquals(8, base.sessionsIn(2).size)
    }

    @Test
    fun `sessionsIn ignores 调休 adjustments`() {
        // A holiday date does not change which sessions "run in" the week; only the
        // dated resolver applies 调休.
        val holiday = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Wed, CalendarDayKind.HOLIDAY)))
        assertEquals(base.sessionsIn(2), holiday.sessionsIn(2))
    }

    @Test
    fun `isEmpty counts sessions and not courses`() {
        assertFalse(base.isEmpty)
        // NOTE: a timetable holding courses but no sessions reports empty, because
        // isEmpty only looks at sessions.
        val coursesOnly = timetableWith(sessions = emptyList())
        assertTrue(coursesOnly.isEmpty)
        assertEquals(allCourses.size, coursesOnly.courses.size)
    }

    @Test
    fun `empty factory builds a timetable with nothing in it`() {
        val empty = Timetable.empty(term)
        assertTrue(empty.isEmpty)
        assertTrue(empty.courses.isEmpty())
        assertTrue(empty.sessions.isEmpty())
        assertEquals(0, empty.adjustments.size)
        assertNull(empty.course(1L))
        assertTrue(empty.sessionsOf(1L).isEmpty())
        assertTrue(empty.sessionsIn(1).isEmpty())
        assertTrue(TimetableResolver.weeklySlots(empty).isEmpty())
        assertTrue(TimetableResolver.occurrencesForWeek(empty, 1).isEmpty())
        assertTrue(TimetableResolver.weekGrid(empty, 1, includeInactive = true).isEmpty())
        assertNull(TimetableResolver.nextOccurrence(empty, week1Mon, LocalTime.of(0, 0)))
    }

    @Test
    fun `the period schedule defaults to tongji and can be overridden`() {
        assertEquals(PeriodSchedule.TONGJI, base.periodSchedule)
        assertEquals(PeriodSchedule.TONGJI.periods.size, Timetable.empty(term).periodSchedule.periods.size)
        val custom = PeriodSchedule(listOf(Period(1, LocalTime.of(9, 0), LocalTime.of(9, 45))))
        assertEquals(1, timetableWith(periodSchedule = custom).periodSchedule.periods.size)
    }

    // ==================================================================
    // 1. Holiday suppression
    // ==================================================================

    @Test
    fun `a holiday date produces no occurrences`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Wed, CalendarDayKind.HOLIDAY)))
        assertTrue(TimetableResolver.occurrencesForDate(t, week2Wed).isEmpty())
        // The very same Wednesday without the adjustment does have classes.
        assertEquals(listOf("大学英语"), namesOf(TimetableResolver.occurrencesForDate(base, week2Wed)))
    }

    @Test
    fun `winter and summer breaks suppress a date too`() {
        val winter = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week1Wed, CalendarDayKind.WINTER_BREAK)))
        assertTrue(TimetableResolver.occurrencesForDate(winter, week1Wed).isEmpty())

        val summer = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week1Tue, CalendarDayKind.SUMMER_BREAK)))
        assertTrue(TimetableResolver.occurrencesForDate(summer, week1Tue).isEmpty())
    }

    @Test
    fun `a holiday drops one day from the week grid but keeps the rest`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Wed, CalendarDayKind.HOLIDAY)))
        val grid = TimetableResolver.occurrencesForWeek(t, 2)
        assertEquals(listOf("C语言程序设计", "高等数学", "线性代数", "大学物理", "程序设计"), namesOf(grid))
        assertTrue(grid.none { it.date == week2Wed })
    }

    @Test
    fun `a holiday suppresses ghosted occurrences as well`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Wed, CalendarDayKind.HOLIDAY)))
        val grid = TimetableResolver.weekGrid(t, 2, includeInactive = true)
        // No occurrence at all on the holiday: neither the active 大学英语 nor the
        // ghosted 体育, even though includeInactive is true.
        assertTrue(grid.none { it.date == week2Wed })
        assertTrue(grid.none { it.course == pe })
        // The Friday ghost of an unparsed-weeks session is untouched.
        assertEquals(1, grid.count { it.date == week2Fri })
    }

    @Test
    fun `holiday suppression applies wherever the date falls`() {
        // Wednesday of week 1 and of week 3 are both real teaching days; suppressing
        // week 1's Wednesday must not affect the others.
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week1Wed, CalendarDayKind.HOLIDAY)))
        assertTrue(TimetableResolver.occurrencesForDate(t, week1Wed).isEmpty())
        assertEquals(listOf("大学英语", "体育"), namesOf(TimetableResolver.occurrencesForDate(t, LocalDate.of(2026, 3, 11))))
    }

    // ==================================================================
    // 2. 补课日 — the date runs another weekday's timetable
    // ==================================================================

    @Test
    fun `a makeup day runs the followed weekday sessions`() {
        // Tuesday 03-03 runs Monday's timetable.
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(
                    date = week2Tue,
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = DayOfWeek.MONDAY,
                    source = AdjustmentSource.MANUAL,
                ),
            ),
        )
        val occurrences = TimetableResolver.occurrencesForDate(t, week2Tue)
        assertEquals(
            setOf("C语言程序设计", "高等数学", "线性代数"),
            namesOf(occurrences).toSet(),
        )
        assertTrue(occurrences.all { it.date == week2Tue })
        assertTrue(occurrences.all { it.week == 2 })
    }

    @Test
    fun `a makeup day shows the followed weekday instead of its own`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Tue, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.MONDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        // Tuesday's own course, 大学物理, must NOT run on a day that follows Monday.
        assertFalse(namesOf(TimetableResolver.occurrencesForDate(t, week2Tue)).contains("大学物理"))
        assertEquals(listOf("大学物理"), namesOf(TimetableResolver.occurrencesForDate(base, week2Tue)))
    }

    @Test
    fun `a makeup saturday shows the followed weekday and not the saturday course`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.WEDNESDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        val occurrences = TimetableResolver.occurrencesForDate(t, week2Sat)
        assertEquals(listOf("大学英语"), namesOf(occurrences))
        // 程序设计 is a genuine Saturday class and is suppressed on this date.
        assertFalse(namesOf(occurrences).contains("程序设计"))
        // Without the adjustment the Saturday class is exactly what shows.
        assertEquals(listOf("程序设计"), namesOf(TimetableResolver.occurrencesForDate(base, week2Sat)))
    }

    @Test
    fun `makeup occurrences are flagged isMakeup`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.WEDNESDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        val occurrence = TimetableResolver.occurrencesForDate(t, week2Sat).single()
        assertTrue(occurrence.isMakeup)
        assertEquals(DayOfWeek.WEDNESDAY, occurrence.session.dayOfWeek)
        assertEquals(week2Sat, occurrence.date)
    }

    @Test
    fun `a 补课 is an addition and not a move`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.WEDNESDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        // The real Wednesday still runs it, un-flagged ...
        val onWednesday = TimetableResolver.occurrencesForDate(t, week2Wed)
        assertEquals(listOf("大学英语"), namesOf(onWednesday))
        assertFalse(onWednesday.single().isMakeup)
        // ... and the 补课 Saturday runs it again, flagged.
        val onSaturday = TimetableResolver.occurrencesForDate(t, week2Sat)
        assertEquals(listOf("大学英语"), namesOf(onSaturday))
        assertTrue(onSaturday.single().isMakeup)
    }

    @Test
    fun `ordinary teaching days are not flagged as makeup`() {
        for (date in listOf(week1Mon, week1Tue, week1Wed, week1Sat, week2Mon, week2Wed, week2Sat)) {
            assertTrue(
                "no occurrence on $date should be a 补课",
                TimetableResolver.occurrencesForDate(base, date).none { it.isMakeup },
            )
        }
    }

    @Test
    fun `a date that follows its own weekday is not a makeup day`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.SATURDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        val occurrences = TimetableResolver.occurrencesForDate(t, week2Sat)
        assertEquals(listOf("程序设计"), namesOf(occurrences))
        assertFalse(occurrences.single().isMakeup)
        assertEquals(DayOfWeek.SATURDAY, occurrences.single().session.dayOfWeek)
    }

    @Test
    fun `a holiday converted into a 补课日 still runs the followed weekday`() {
        // The school calendar sometimes reports a 补课 Saturday as 节假日; the
        // followsWeekday override outranks the kind, so the makeup still runs.
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.HOLIDAY, followsWeekday = DayOfWeek.WEDNESDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        val occurrences = TimetableResolver.occurrencesForDate(t, week2Sat)
        assertEquals(listOf("大学英语"), namesOf(occurrences))
        assertTrue(occurrences.single().isMakeup)
    }

    // ==================================================================
    // 4. noClasses
    // ==================================================================

    @Test
    fun `noClasses suppresses the date even when followsWeekday is set`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(
                    date = week2Wed,
                    kind = CalendarDayKind.WORKDAY,
                    followsWeekday = DayOfWeek.MONDAY,
                    noClasses = true,
                    source = AdjustmentSource.MANUAL,
                ),
            ),
        )
        assertTrue(TimetableResolver.occurrencesForDate(t, week2Wed).isEmpty())
        assertTrue(TimetableResolver.weekGrid(t, 2, includeInactive = true).none { it.date == week2Wed })
        assertEquals(5, TimetableResolver.occurrencesForWeek(t, 2).size)
    }

    // ==================================================================
    // 3. Weekend classes survive
    // ==================================================================

    @Test
    fun `a genuine saturday class still appears`() {
        val occurrences = TimetableResolver.occurrencesForDate(base, week1Sat)
        assertEquals(listOf("程序设计"), namesOf(occurrences))
        assertEquals(DayOfWeek.SATURDAY, occurrences.single().session.dayOfWeek)
        assertFalse(occurrences.single().isMakeup)
    }

    @Test
    fun `a weekend day marked WEEKEND still runs its own weekday`() {
        // INTENTIONAL, not a bug: a date marked 周末 is not non-teaching, so its own
        // weekday runs and genuine weekend classes keep showing on the grid.
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week1Sat, CalendarDayKind.WEEKEND)))
        assertEquals(listOf("程序设计"), namesOf(TimetableResolver.occurrencesForDate(t, week1Sat)))
        assertTrue(t.adjustments.isTeachingDay(week1Sat))
    }

    // ==================================================================
    // 6-8. weekGrid, ghosting and week bounds
    // ==================================================================

    @Test
    fun `occurrencesForWeek omits a session that does not run that week`() {
        // 体育 is 1-17单, so week 2 (even) has no 体育 at all.
        assertFalse(namesOf(TimetableResolver.occurrencesForWeek(base, 2)).contains("体育"))
        assertEquals(6, TimetableResolver.occurrencesForWeek(base, 2).size)
    }

    @Test
    fun `weekGrid with includeInactive ghosts that session with isActive false`() {
        val grid = TimetableResolver.weekGrid(base, 2, includeInactive = true)
        val ghost = grid.single { it.course == pe }
        assertFalse(ghost.isActive)
        assertEquals(week2Wed, ghost.date)
        assertEquals(2, ghost.week)
        assertEquals(7, ghost.startUnit)
        assertEquals(8, ghost.endUnit)
        assertEquals(LocalTime.of(15, 30), ghost.startTime)
        assertEquals(LocalTime.of(17, 5), ghost.endTime)
    }

    @Test
    fun `an odd week has the same session active and no ghost`() {
        val grid = TimetableResolver.weekGrid(base, 3, includeInactive = true)
        val peOccurrences = grid.filter { it.course == pe }
        assertEquals(1, peOccurrences.size)
        assertTrue(peOccurrences.single().isActive)
        assertEquals(LocalDate.of(2026, 3, 11), peOccurrences.single().date)
    }

    @Test
    fun `a ghost is the only difference between the two grid modes`() {
        val active = TimetableResolver.weekGrid(base, 2, includeInactive = false)
        val ghosted = TimetableResolver.weekGrid(base, 2, includeInactive = true)
        assertEquals(6, active.size)
        assertEquals(8, ghosted.size)
        assertTrue(active.all { it.isActive })
        assertEquals(2, ghosted.count { !it.isActive })
        assertTrue(ghosted.containsAll(active))
    }

    @Test
    fun `occurrencesForWeek is weekGrid with includeInactive false`() {
        assertEquals(
            TimetableResolver.weekGrid(base, 2, includeInactive = false),
            TimetableResolver.occurrencesForWeek(base, 2),
        )
        assertEquals(
            TimetableResolver.weekGrid(base, 1, includeInactive = false),
            TimetableResolver.occurrencesForWeek(base, 1),
        )
    }

    @Test
    fun `weekGrid covers the seven days of the requested week`() {
        val grid = TimetableResolver.weekGrid(base, 1, includeInactive = true)
        assertTrue(grid.all { it.week == 1 })
        assertTrue(grid.all { it.date in week1Mon..week1Sun })
        assertTrue(grid.map { it.date }.distinct().size <= 7)
        assertEquals(8, grid.size)
    }

    @Test
    fun `week queries outside the term are empty`() {
        for (week in listOf(-1, 0, 19, 100)) {
            assertTrue("week $week must be empty", TimetableResolver.occurrencesForWeek(base, week).isEmpty())
            assertTrue(
                "week $week must be empty even when ghosting",
                TimetableResolver.weekGrid(base, week, includeInactive = true).isEmpty(),
            )
        }
    }

    @Test
    fun `the last teaching week is still resolved`() {
        assertEquals(
            setOf("C语言程序设计", "高等数学", "线性代数"),
            namesOf(TimetableResolver.occurrencesForDate(base, week18Mon)).toSet(),
        )
        assertEquals(6, TimetableResolver.occurrencesForWeek(base, 18).size)
    }

    // ==================================================================
    // occurrencesForDate / occurrencesBetween
    // ==================================================================

    @Test
    fun `occurrencesForDate returns that date's weekday sessions`() {
        assertEquals(
            setOf("C语言程序设计", "高等数学", "线性代数"),
            namesOf(TimetableResolver.occurrencesForDate(base, week1Mon)).toSet(),
        )
        assertEquals(listOf("大学物理"), namesOf(TimetableResolver.occurrencesForDate(base, week1Tue)))
        assertEquals(
            setOf("大学英语", "体育"),
            namesOf(TimetableResolver.occurrencesForDate(base, week1Wed)).toSet(),
        )
    }

    @Test
    fun `a day with no sessions yields nothing`() {
        assertTrue(TimetableResolver.occurrencesForDate(base, week1Sun).isEmpty())
        assertTrue(TimetableResolver.occurrencesForDate(base, week2Sun).isEmpty())
    }

    @Test
    fun `occurrencesForDate outside the term is empty`() {
        assertTrue(TimetableResolver.occurrencesForDate(base, LocalDate.of(2026, 2, 22)).isEmpty())
        assertTrue(TimetableResolver.occurrencesForDate(base, LocalDate.of(2026, 1, 1)).isEmpty())
        assertTrue(TimetableResolver.occurrencesForDate(base, LocalDate.of(2026, 6, 29)).isEmpty())
        assertTrue(TimetableResolver.occurrencesForDate(base, LocalDate.of(2026, 7, 20)).isEmpty())
    }

    @Test
    fun `occurrencesForDate matches the corresponding grid entry`() {
        for (date in listOf(week1Mon, week1Tue, week1Wed, week1Sat, week2Mon, week2Tue, week2Wed, week2Sat)) {
            val fromDate = TimetableResolver.occurrencesForDate(base, date)
            val fromGrid = TimetableResolver.weekGrid(base, term.weekOf(date)!!, includeInactive = false)
                .filter { it.date == date }
            // Compared as sets: occurrencesForDate does not apply OCCURRENCE_ORDER (see
            // the ordering tests), while weekGrid does.
            assertEquals("date $date size", fromGrid.size, fromDate.size)
            assertEquals("date $date contents", fromGrid.toSet(), fromDate.toSet())
        }
    }

    @Test
    fun `occurrencesBetween includes both endpoints`() {
        val occurrences = TimetableResolver.occurrencesBetween(base, week2Mon, week2Sat)
        assertEquals(6, occurrences.size)
        assertEquals(week2Mon, occurrences.first().date)
        assertEquals(week2Sat, occurrences.last().date)
        assertTrue(occurrences.any { it.date == week2Mon })
        assertTrue(occurrences.any { it.date == week2Sat })
    }

    @Test
    fun `occurrencesBetween over a reversed range is empty`() {
        assertTrue(TimetableResolver.occurrencesBetween(base, week2Sat, week2Mon).isEmpty())
    }

    @Test
    fun `occurrencesBetween over a single date matches occurrencesForDate`() {
        assertEquals(
            TimetableResolver.occurrencesForDate(base, week1Wed),
            TimetableResolver.occurrencesBetween(base, week1Wed, week1Wed),
        )
    }

    @Test
    fun `occurrencesBetween skips holidays`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Tue, CalendarDayKind.HOLIDAY)))
        val occurrences = TimetableResolver.occurrencesBetween(t, week2Mon, week2Wed)
        assertEquals(listOf("C语言程序设计", "高等数学", "线性代数", "大学英语"), namesOf(occurrences))
        assertFalse(namesOf(occurrences).contains("大学物理"))
    }

    @Test
    fun `occurrencesBetween clamps to the term without failing`() {
        val occurrences = TimetableResolver.occurrencesBetween(base, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(week1Mon, occurrences.first().date)
        // The last dated slot of the term is the Saturday of week 18.
        assertEquals(LocalDate.of(2026, 6, 27), occurrences.last().date)
        // 6 dated slots every week, plus 体育 in the nine odd weeks up to week 17.
        assertEquals(18 * 6 + 9, occurrences.size)
    }

    // ==================================================================
    // 9. Ordering
    // ==================================================================

    @Test
    fun `occurrences sort by date then startUnit then course name`() {
        // Week 2: Monday's unit-5 class comes before Wednesday's unit-3 class,
        // because the date is the primary key.
        val grid = TimetableResolver.occurrencesForWeek(base, 2)
        assertEquals(
            listOf(
                week2Mon to "C语言程序设计",
                week2Mon to "高等数学",
                week2Mon to "线性代数",
                week2Tue to "大学物理",
                week2Wed to "大学英语",
                week2Sat to "程序设计",
            ),
            grid.map { it.date to it.course.name },
        )
        assertEquals(
            listOf(1, 1, 5, 3, 3, 5),
            grid.map { it.startUnit },
        )
    }

    @Test
    fun `the same startUnit is broken by course name`() {
        val monday = TimetableResolver.weekGrid(base, 1, includeInactive = false).filter { it.date == week1Mon }
        assertEquals(listOf(1, 1, 5), monday.map { it.startUnit })
        // "C语言程序设计" sorts before "高等数学" (C = U+0043 < 高 = U+9AD8).
        assertEquals(listOf("C语言程序设计", "高等数学", "线性代数"), namesOf(monday))
    }

    @Test
    fun `occurrencesBetween sorts across weeks`() {
        val occurrences = TimetableResolver.occurrencesBetween(base, week1Sat, week2Tue)
        assertEquals(listOf(week1Sat, week2Mon, week2Mon, week2Mon, week2Tue), occurrences.map { it.date })
        assertEquals(
            listOf("程序设计", "C语言程序设计", "高等数学", "线性代数", "大学物理"),
            namesOf(occurrences),
        )
    }

    @Test
    fun `the ghosting grid keeps the same ordering`() {
        val grid = TimetableResolver.weekGrid(base, 2, includeInactive = true)
        assertEquals(
            listOf(
                week2Mon to 1, week2Mon to 1, week2Mon to 5,
                week2Tue to 3,
                week2Wed to 3, week2Wed to 7,
                week2Fri to 1,
                week2Sat to 5,
            ),
            grid.map { it.date to it.startUnit },
        )
        assertEquals(week2Fri, grid.single { !it.isActive && it.course == unparsedWeeks }.date)
    }

    @Test
    fun `occurrencesForDate sorts by startUnit like the other entry points`() {
        // Regression test. occurrencesForDate used to return Timetable.sessions
        // order while only weekGrid and occurrencesBetween sorted. That was not
        // cosmetic: the widget renders this list and then truncates it with
        // take(n), so a 5-6节 class could be listed above a 1-2节 one AND the
        // earliest classes could be dropped from the truncated window entirely.
        val t = outOfOrderTimetable()
        assertEquals(
            listOf(1, 5),
            TimetableResolver.occurrencesForDate(t, week1Mon).map { it.startUnit },
        )
        // All three entry points must agree about the very same date.
        assertEquals(
            listOf(1, 5),
            TimetableResolver.weekGrid(t, 1, includeInactive = false).map { it.startUnit },
        )
        assertEquals(
            listOf(1, 5),
            TimetableResolver.occurrencesBetween(t, week1Mon, week1Mon).map { it.startUnit },
        )
        assertEquals(
            TimetableResolver.weekGrid(t, 1, includeInactive = false),
            TimetableResolver.occurrencesForDate(t, week1Mon),
        )
    }

    // ==================================================================
    // 11. Times, spans and labels
    // ==================================================================

    @Test
    fun `times come from the tongji period schedule`() {
        val occurrences = TimetableResolver.occurrencesForDate(base, week2Mon)
        val linear = occurrences.single { it.course == linearAlgebra }
        assertEquals(LocalTime.of(13, 30), linear.startTime)
        assertEquals(LocalTime.of(15, 5), linear.endTime)

        val englishOccurrence = TimetableResolver.occurrencesForDate(base, week2Wed).single()
        assertEquals(LocalTime.of(10, 0), englishOccurrence.startTime)
        assertEquals(LocalTime.of(11, 35), englishOccurrence.endTime)
    }

    @Test
    fun `unitSpan and unitLabel describe a multi unit session`() {
        val linear = TimetableResolver.occurrencesForDate(base, week2Mon).single { it.course == linearAlgebra }
        assertEquals(5, linear.startUnit)
        assertEquals(6, linear.endUnit)
        assertEquals(2, linear.unitSpan)
        assertEquals("5-6 节", linear.unitLabel)
    }

    @Test
    fun `timeLabel formats the span`() {
        val linear = TimetableResolver.occurrencesForDate(base, week2Mon).single { it.course == linearAlgebra }
        assertEquals("13:30–15:05", linear.timeLabel)

        val englishOccurrence = TimetableResolver.occurrencesForDate(base, week2Wed).single()
        assertEquals("10:00–11:35", englishOccurrence.timeLabel)
    }

    @Test
    fun `unitLabel and timeLabel for a single unit session`() {
        val course = Course(id = 1, name = "习题课")
        val session = CourseSession(
            id = 1, courseId = 1, dayOfWeek = DayOfWeek.MONDAY,
            startUnit = 4, endUnit = 4, weeks = everyWeek,
        )
        val occurrence = TimetableResolver.occurrencesForDate(
            timetableWith(courses = listOf(course), sessions = listOf(session)),
            week1Mon,
        ).single()
        assertEquals(1, occurrence.unitSpan)
        assertEquals("4 节", occurrence.unitLabel)
        assertEquals(LocalTime.of(10, 50), occurrence.startTime)
        assertEquals(LocalTime.of(11, 35), occurrence.endTime)
        assertEquals("10:50–11:35", occurrence.timeLabel)
    }

    @Test
    fun `a session whose 节 is unknown still appears without times`() {
        val course = Course(id = 1, name = "无作息课")
        val session = CourseSession(
            id = 1, courseId = 1, dayOfWeek = DayOfWeek.MONDAY,
            startUnit = 12, endUnit = 12, weeks = everyWeek,
        )
        val occurrence = TimetableResolver.occurrencesForDate(
            timetableWith(courses = listOf(course), sessions = listOf(session)),
            week1Mon,
        ).single()
        assertNull(occurrence.startTime)
        assertNull(occurrence.endTime)
        assertNull(occurrence.timeLabel)
        assertEquals("12 节", occurrence.unitLabel)
        assertEquals(1, occurrence.unitSpan)
    }

    @Test
    fun `a custom period schedule replaces tongji`() {
        val custom = PeriodSchedule(listOf(Period(1, LocalTime.of(9, 5), LocalTime.of(9, 50))))
        val course = Course(id = 1, name = "早课")
        val session = CourseSession(
            id = 1, courseId = 1, dayOfWeek = DayOfWeek.MONDAY,
            startUnit = 1, endUnit = 1, weeks = everyWeek,
        )
        val occurrence = TimetableResolver.occurrencesForDate(
            timetableWith(courses = listOf(course), sessions = listOf(session), periodSchedule = custom),
            week1Mon,
        ).single()
        assertEquals(LocalTime.of(9, 5), occurrence.startTime)
        assertEquals(LocalTime.of(9, 50), occurrence.endTime)
        assertEquals("09:05–09:50", occurrence.timeLabel)
    }

    @Test
    fun `room comes from the session and the occurrence keeps its session`() {
        val occurrence = TimetableResolver.occurrencesForDate(base, week2Wed).single()
        assertEquals("南楼114", occurrence.room)
        assertEquals(104L, occurrence.session.id)
        assertEquals(english, occurrence.course)
        assertEquals(2, occurrence.week)
    }

    // ==================================================================
    // 12. Hidden courses, orphans and unreadable week fields
    // ==================================================================

    @Test
    fun `a hidden course produces no occurrences`() {
        assertFalse(namesOf(TimetableResolver.occurrencesForDate(base, week1Mon)).contains("隐藏课"))
        assertFalse(namesOf(TimetableResolver.occurrencesForWeek(base, 1)).contains("隐藏课"))
        assertFalse(namesOf(TimetableResolver.occurrencesBetween(base, week1Mon, week2Sun)).contains("隐藏课"))
    }

    @Test
    fun `a hidden course is absent even from the ghosting grid`() {
        assertFalse(namesOf(TimetableResolver.weekGrid(base, 2, includeInactive = true)).contains("隐藏课"))
    }

    @Test
    fun `hiding a course only removes it from the resolver`() {
        // The session is still part of the aggregate, so the course list and the
        // weekday grouping are unaffected by the hidden flag.
        assertEquals(listOf(107L), base.sessionsOf(hiddenCourse.id).map { it.id })
        assertTrue(TimetableResolver.weeklySlots(base).getValue(DayOfWeek.MONDAY).any { it.courseId == hiddenCourse.id })
    }

    @Test
    fun `a session whose course is missing is skipped`() {
        val all = TimetableResolver.weekGrid(base, 1, includeInactive = true)
        assertTrue(all.none { it.startUnit == 9 })
        assertTrue(all.all { it.course.id != 99L })
        // ... but it is still visible to the raw aggregate queries.
        assertTrue(base.sessionsIn(1).any { it.id == 108L })
    }

    @Test
    fun `a session whose week field failed to parse is never dated`() {
        // NOTE: WeekPattern.parse returns EMPTY for an unreadable week field, and an
        // empty pattern never runs — so this course is invisible to every dated
        // consumer (grid without ghosting, widget, calendar, reminders).
        assertTrue(TimetableResolver.occurrencesForDate(base, week1Fri).isEmpty())
        assertTrue(TimetableResolver.occurrencesForDate(base, week2Fri).isEmpty())
        assertFalse(
            namesOf(TimetableResolver.occurrencesBetween(base, week1Mon, week18Mon)).contains("未解析周次"),
        )
    }

    @Test
    fun `such a session is still ghosted by the grid`() {
        val grid = TimetableResolver.weekGrid(base, 1, includeInactive = true)
        val ghost = grid.single { it.course == unparsedWeeks }
        assertFalse(ghost.isActive)
        assertEquals(week1Fri, ghost.date)
    }

    // ==================================================================
    // weeklySlots
    // ==================================================================

    @Test
    fun `weeklySlots groups sessions by weekday`() {
        val slots = TimetableResolver.weeklySlots(base)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), slots.keys)
        assertEquals(5, slots.getValue(DayOfWeek.MONDAY).size)
        assertEquals(1, slots.getValue(DayOfWeek.TUESDAY).size)
        assertEquals(2, slots.getValue(DayOfWeek.WEDNESDAY).size)
        assertEquals(1, slots.getValue(DayOfWeek.FRIDAY).size)
        assertEquals(1, slots.getValue(DayOfWeek.SATURDAY).size)
        assertEquals(allSessions.size, slots.values.sumOf { it.size })
    }

    @Test
    fun `weeklySlots is a raw grouping that ignores hidden courses and orphans`() {
        // INTENTIONAL: weeklySlots is a plain groupBy used for the course list, so it
        // does not filter what the dated resolver filters.
        val slots = TimetableResolver.weeklySlots(base)
        assertTrue(slots.getValue(DayOfWeek.MONDAY).any { it.courseId == hiddenCourse.id })
        assertTrue(slots.getValue(DayOfWeek.MONDAY).any { it.courseId == 99L })
    }

    // ==================================================================
    // 13. nextOccurrence
    // ==================================================================

    @Test
    fun `nextOccurrence returns the first class later today`() {
        val next = TimetableResolver.nextOccurrence(base, week2Mon, LocalTime.of(7, 0))
        assertEquals(week2Mon, next!!.date)
        assertEquals("C语言程序设计", next.course.name)
        assertEquals(LocalTime.of(8, 0), next.startTime)
    }

    @Test
    fun `nextOccurrence skips a class that already started`() {
        val next = TimetableResolver.nextOccurrence(base, week2Mon, LocalTime.of(9, 0))
        assertEquals(week2Mon, next!!.date)
        assertEquals("线性代数", next.course.name)
        assertEquals(LocalTime.of(13, 30), next.startTime)
    }

    @Test
    fun `nextOccurrence treats a class starting right now as already started`() {
        val next = TimetableResolver.nextOccurrence(base, week2Mon, LocalTime.of(13, 30))
        assertEquals(week2Tue, next!!.date)
        assertEquals("大学物理", next.course.name)
    }

    @Test
    fun `nextOccurrence moves to the next teaching day when today is over`() {
        val next = TimetableResolver.nextOccurrence(base, week2Mon, LocalTime.of(20, 0))
        assertEquals(week2Tue, next!!.date)
        assertEquals(LocalTime.of(10, 0), next.startTime)
    }

    @Test
    fun `nextOccurrence from a day with no classes`() {
        val next = TimetableResolver.nextOccurrence(base, week2Sun, LocalTime.of(0, 0))
        assertEquals(week3Mon, next!!.date)
        assertEquals("C语言程序设计", next.course.name)
    }

    @Test
    fun `nextOccurrence looks ahead at most twenty one days`() {
        // The look-ahead window is a hard from + 21 days, so a date before the term is
        // only answered when a teaching day falls inside that window.
        assertNull(TimetableResolver.nextOccurrence(base, LocalDate.of(2026, 2, 1), LocalTime.of(0, 0)))
        assertEquals(
            week1Mon,
            TimetableResolver.nextOccurrence(base, LocalDate.of(2026, 2, 2), LocalTime.of(0, 0))!!.date,
        )
        assertEquals(
            "C语言程序设计",
            TimetableResolver.nextOccurrence(base, LocalDate.of(2026, 2, 2), LocalTime.of(0, 0))!!.course.name,
        )
    }

    @Test
    fun `nextOccurrence after the term returns null`() {
        assertNull(TimetableResolver.nextOccurrence(base, LocalDate.of(2026, 6, 29), LocalTime.of(0, 0)))
    }

    @Test
    fun `nextOccurrence skips a holiday and lands on the next real teaching day`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Tue, CalendarDayKind.HOLIDAY)))
        val next = TimetableResolver.nextOccurrence(t, week2Mon, LocalTime.of(20, 0))
        assertEquals(week2Wed, next!!.date)
        assertEquals("大学英语", next.course.name)
    }

    @Test
    fun `nextOccurrence skips a holiday that would otherwise start the day`() {
        val t = timetableWith(adjustments = adjustmentsOf(DayAdjustment(week2Mon, CalendarDayKind.HOLIDAY)))
        val next = TimetableResolver.nextOccurrence(t, week2Mon, LocalTime.of(7, 0))
        assertEquals(week2Tue, next!!.date)
        assertEquals("大学物理", next.course.name)
    }

    @Test
    fun `nextOccurrence never returns an inactive or ghosted session`() {
        // On Wednesday the only active class is 大学英语 (10:00); after it, the next
        // real class is Saturday's 程序设计, not Friday's phantom 未解析周次 and not
        // the 单周-only 体育.
        val next = TimetableResolver.nextOccurrence(base, week2Wed, LocalTime.of(19, 0))
        assertEquals(week2Sat, next!!.date)
        assertEquals("程序设计", next.course.name)
        assertTrue(next.isActive)
    }

    @Test
    fun `nextOccurrence follows the followed weekday on a makeup day`() {
        val t = timetableWith(
            adjustments = adjustmentsOf(
                DayAdjustment(week2Sat, CalendarDayKind.WORKDAY, followsWeekday = DayOfWeek.WEDNESDAY, source = AdjustmentSource.MANUAL),
            ),
        )
        val next = TimetableResolver.nextOccurrence(t, week2Sat, LocalTime.of(9, 0))
        assertEquals(week2Sat, next!!.date)
        assertEquals("大学英语", next.course.name)
        assertTrue(next.isMakeup)
    }

    @Test
    fun `nextOccurrence ignores hidden courses`() {
        val next = TimetableResolver.nextOccurrence(base, week2Mon, LocalTime.of(7, 0))
        assertFalse(next!!.course.hidden)
        assertFalse(TimetableResolver.occurrencesBetween(base, week2Mon, week2Sun).any { it.course.hidden })
    }

    @Test
    fun `nextOccurrence skips the whole of today when the start time is unknown`() {
        // NOTE: the predicate keeps an occurrence on `from` only when its startTime is
        // known and still ahead, so with an empty 作息表 nothing on that date can ever
        // qualify and the search jumps to the next date.
        val noSchedule = timetableWith(periodSchedule = PeriodSchedule(emptyList()))
        val next = TimetableResolver.nextOccurrence(noSchedule, week2Mon, LocalTime.of(0, 0))
        assertEquals(week2Tue, next!!.date)
        assertNull(next.startTime)
        assertNull(TimetableResolver.nextOccurrence(Timetable.empty(term), week2Mon, LocalTime.of(0, 0)))
    }

    // ==================================================================
    // Consistency between the aggregate and the resolver
    // ==================================================================

    @Test
    fun `every dated occurrence points back at a real course and session`() {
        for (occurrence in TimetableResolver.occurrencesBetween(base, week1Mon, week18Mon)) {
            assertEquals(occurrence.course, base.course(occurrence.session.courseId))
            assertTrue(base.sessions.any { it.id == occurrence.session.id })
            assertTrue(occurrence.date in term.weekStart(occurrence.week)..term.weekEnd(occurrence.week))
            assertTrue(occurrence.session.dayOfWeek == occurrence.date.dayOfWeek || occurrence.isMakeup)
        }
    }

    @Test
    fun `a full term of occurrences matches the per week counts`() {
        var fromWeeks = 0
        for (week in 1..term.totalWeeks) fromWeeks += TimetableResolver.occurrencesForWeek(base, week).size
        // 6 slots every week, and 体育 runs in the nine odd weeks up to week 17.
        assertEquals(18 * 6 + 9, fromWeeks)
        assertEquals(
            fromWeeks,
            TimetableResolver.occurrencesBetween(base, term.weekStart(1), term.weekEnd(18)).size,
        )
    }
}
