package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReminderPlanner] and [ClassReminder] — the pure scheduling policy that
 * decides when the student should be told about an upcoming class.
 *
 * The planner is deliberately clock-free: every entry point takes `now`, so the whole
 * policy is pinned here without WorkManager, a device or a real clock. The tests fall
 * into four groups:
 *
 *  1. the arithmetic — trigger = class start − lead, and the past / horizon / cap
 *     filtering around it;
 *  2. the 作息表 dependency — an occurrence with no known 节 has no instant to fire at
 *     and is skipped;
 *  3. the [TimetableResolver] dependency — because reminders are derived from resolved
 *     occurrences, 节假日, 调休补课日 and 单双周 are all respected for free;
 *  4. the `due` window the worker scans, which is a different (window-based) question
 *     from `plan`'s (future-based) one and behaves differently at the edges.
 *
 * The fixture is a 2025-2026 学年第二学期 whose teaching week 1 starts on Monday
 * 2026-03-02, with four weekly slots taken from [PeriodSchedule.TONGJI]:
 *
 *  - 高等数学 (math)     Mon 1-2 节  08:00–09:35  南楼114   every week
 *  - 大学英语 (english)  Wed 3-4 节  10:00–11:35  北楼201   every week
 *  - 体育 (pe)           Wed 7-8 节  15:30–17:05  no room   `[1-17单]`, odd weeks only
 *  - 大学物理 (physics)  Thu 5-6 节  13:30–15:05  物理楼305 every week
 *
 * Every `now` is passed explicitly — `LocalDate.now()` / `LocalDateTime.now()` appear
 * nowhere, so the whole file is deterministic.
 */
class ReminderPlannerTest {

    // ==================================================================
    // Fixture
    // ==================================================================

    private val term = TermCalendar(
        calendarId = "2025-2026-2",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = LocalDate.of(2026, 3, 2), // a Monday
        endDate = LocalDate.of(2026, 7, 5), // Sunday of week 18
        totalWeeks = 18,
    )

    private val math = Course(id = 1, name = "高等数学", teachingClassId = 2001)
    private val english = Course(id = 2, name = "大学英语", teachingClassId = 2002)
    private val pe = Course(id = 3, name = "体育", teachingClassId = 2003)
    private val physics = Course(id = 4, name = "大学物理", teachingClassId = 2004)

    private val everyWeek = WeekPattern.range(1, 18)

    /** Monday 1-2 节: 08:00–09:35. */
    private val mathMonday = CourseSession(
        id = 11, courseId = math.id, dayOfWeek = DayOfWeek.MONDAY,
        startUnit = 1, endUnit = 2, weeks = everyWeek, room = "南楼114",
    )

    /** Wednesday 3-4 节: 10:00–11:35. */
    private val englishWednesday = CourseSession(
        id = 12, courseId = english.id, dayOfWeek = DayOfWeek.WEDNESDAY,
        startUnit = 3, endUnit = 4, weeks = everyWeek, room = "北楼201",
    )

    /** Wednesday 7-8 节: 15:30–17:05, 单周 only, and deliberately room-less. */
    private val peWednesdayOddWeeks = CourseSession(
        id = 13, courseId = pe.id, dayOfWeek = DayOfWeek.WEDNESDAY,
        startUnit = 7, endUnit = 8, weeks = WeekPattern.parse("[1-17单]"),
        rawWeeks = "[1-17单]",
    )

    /** Thursday 5-6 节: 13:30–15:05. */
    private val physicsThursday = CourseSession(
        id = 14, courseId = physics.id, dayOfWeek = DayOfWeek.THURSDAY,
        startUnit = 5, endUnit = 6, weeks = everyWeek, room = "物理楼305",
    )

    private val allCourses = listOf(math, english, pe, physics)
    private val allSessions = listOf(mathMonday, englishWednesday, peWednesdayOddWeeks, physicsThursday)

    // Term landmarks, cross-checked against TermCalendar.weekOf.
    private val week1Mon = LocalDate.of(2026, 3, 2)
    private val week1Wed = LocalDate.of(2026, 3, 4)
    private val week1Thu = LocalDate.of(2026, 3, 5)
    private val week1Sat = LocalDate.of(2026, 3, 7)
    private val week2Mon = LocalDate.of(2026, 3, 9)
    private val week2Wed = LocalDate.of(2026, 3, 11)
    private val week2Thu = LocalDate.of(2026, 3, 12)
    private val week3Mon = LocalDate.of(2026, 3, 16)
    private val week3Wed = LocalDate.of(2026, 3, 18)
    private val week4Wed = LocalDate.of(2026, 3, 25)

    /** Monday of week 1, before any class starts. */
    private val mondayEarly = week1Mon.atTime(6, 0)

    private fun timetableWith(
        courses: List<Course> = allCourses,
        sessions: List<CourseSession> = allSessions,
        adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
        periodSchedule: PeriodSchedule = PeriodSchedule.TONGJI,
    ): Timetable = Timetable(term, courses, sessions, adjustments, periodSchedule)

    /** The fixture without any 调休. */
    private val base = timetableWith()

    /** Sorted `triggerAt to courseName` view, which is what most ordering tests compare. */
    private fun stampsOf(reminders: List<ClassReminder>): List<Pair<LocalDateTime, String>> =
        reminders.map { it.triggerAt to it.courseName }

    private fun namesOf(reminders: List<ClassReminder>): List<String> = reminders.map { it.courseName }

    /** `plan` with the fixture's usual arguments, so each test only states what it varies. */
    private fun planOf(
        now: LocalDateTime = mondayEarly,
        leadMinutes: Int = 15,
        horizonDays: Long = 7,
        maxReminders: Int = ReminderPlanner.DEFAULT_MAX_REMINDERS,
        timetable: Timetable = base,
    ): List<ClassReminder> =
        ReminderPlanner.plan(timetable, now, leadMinutes, horizonDays, maxReminders)

    // ==================================================================
    // Constants and the default plan
    // ==================================================================

    @Test
    fun `the documented defaults are what the planner is built on`() {
        assertEquals(14L, ReminderPlanner.DEFAULT_HORIZON_DAYS)
        assertEquals(40, ReminderPlanner.DEFAULT_MAX_REMINDERS)
        assertEquals(1440, ReminderPlanner.MAX_LEAD_MINUTES)
        assertEquals(24 * 60, ReminderPlanner.MAX_LEAD_MINUTES)
    }

    @Test
    fun `the default plan covers the whole horizon and nothing past it`() {
        val plan = ReminderPlanner.plan(base, mondayEarly, 15)
        assertEquals(
            listOf(
                week1Mon.atTime(7, 45) to "高等数学",
                week1Wed.atTime(9, 45) to "大学英语",
                week1Wed.atTime(15, 15) to "体育",
                week1Thu.atTime(13, 15) to "大学物理",
                week2Mon.atTime(7, 45) to "高等数学",
                week2Wed.atTime(9, 45) to "大学英语",
                week2Thu.atTime(13, 15) to "大学物理",
                week3Mon.atTime(7, 45) to "高等数学",
            ),
            stampsOf(plan),
        )
        assertTrue("no trigger may be in the past", plan.all { it.triggerAt.isAfter(mondayEarly) })
        assertTrue(
            "no occurrence may come from past the horizon",
            plan.all { it.occurrence.date in mondayEarly.toLocalDate()..mondayEarly.toLocalDate().plusDays(14) },
        )
    }

    // ==================================================================
    // 1-2, 8. Trigger arithmetic and the past
    // ==================================================================

    @Test
    fun `trigger time is the class start minus the lead`() {
        val reminder = planOf(horizonDays = 0).single()
        assertEquals(LocalDateTime.of(2026, 3, 2, 7, 45), reminder.triggerAt)
        assertEquals(LocalTime.of(8, 0), reminder.startTime)
        assertEquals(15, reminder.leadMinutes)
        assertEquals("高等数学", reminder.courseName)
        assertEquals(week1Mon, reminder.occurrence.date)
        assertEquals(1, reminder.occurrence.week)
        assertEquals(11L, reminder.occurrence.session.id)
    }

    @Test
    fun `every trigger is derived from its own occurrence date and 作息 start`() {
        val plan = ReminderPlanner.plan(base, mondayEarly, 15)
        assertTrue(plan.isNotEmpty())
        for (reminder in plan) {
            val start = reminder.startTime
            assertNotNull(start)
            assertEquals(
                LocalDateTime.of(reminder.occurrence.date, start!!).minusMinutes(reminder.leadMinutes.toLong()),
                reminder.triggerAt,
            )
        }
    }

    @Test
    fun `a lead of zero triggers exactly at the class start`() {
        val reminder = planOf(leadMinutes = 0, horizonDays = 0).single()
        assertEquals(week1Mon.atTime(8, 0), reminder.triggerAt)
        assertEquals(reminder.triggerAt.toLocalTime(), reminder.startTime)
        assertEquals(LocalTime.of(8, 0), reminder.startTime)
        assertEquals(0, reminder.leadMinutes)
    }

    @Test
    fun `a trigger in the past is excluded`() {
        // 08:00 minus 15 is 07:45, so anything from 07:45 on has lost that class.
        assertTrue(planOf(now = week1Mon.atTime(7, 46), horizonDays = 0).isEmpty())
        assertTrue(planOf(now = week1Mon.atTime(8, 0), horizonDays = 0).isEmpty())
        assertTrue(planOf(now = week1Mon.atTime(23, 59), horizonDays = 0).isEmpty())
        assertTrue(
            planOf(now = week1Mon.atTime(9, 0)).none { it.occurrence.date == week1Mon },
        )
    }

    @Test
    fun `a trigger exactly at now is excluded`() {
        // reminderFor demands `trigger.isAfter(now)`, so equality is NOT enough: on the
        // stroke of 07:45 the 07:45 reminder is already gone.
        assertTrue(planOf(now = week1Mon.atTime(7, 45), horizonDays = 0).isEmpty())
        // `next` does not go null here — it simply moves on to the next class.
        val next = ReminderPlanner.next(base, week1Mon.atTime(7, 45), 15)
        assertNotNull(next)
        assertEquals(week1Wed.atTime(9, 45), next!!.triggerAt)
        assertEquals("大学英语", next.courseName)
        // It is that one instant that is dropped, not the rest of the day:
        assertEquals(
            listOf(week1Wed.atTime(9, 45) to "大学英语"),
            stampsOf(planOf(now = week1Mon.atTime(7, 45)).take(1)),
        )
    }

    @Test
    fun `a trigger one minute ahead is included`() {
        val now = week1Mon.atTime(7, 44)
        val reminder = planOf(now = now, horizonDays = 0).single()
        assertEquals(week1Mon.atTime(7, 45), reminder.triggerAt)
        assertTrue(reminder.triggerAt.isAfter(now))
        assertEquals(1, planOf(now = now, horizonDays = 0).size)
    }

    // ==================================================================
    // 4. Ordering
    // ==================================================================

    @Test
    fun `reminders are ordered by trigger time across days`() {
        val plan = planOf()
        assertEquals(
            listOf(
                week1Mon.atTime(7, 45) to "高等数学",
                week1Wed.atTime(9, 45) to "大学英语",
                week1Wed.atTime(15, 15) to "体育",
                week1Thu.atTime(13, 15) to "大学物理",
                week2Mon.atTime(7, 45) to "高等数学",
            ),
            stampsOf(plan),
        )
        assertEquals(plan.map { it.triggerAt }, plan.map { it.triggerAt }.sorted())
    }

    @Test
    fun `two classes on the same day are ordered by their own start times`() {
        val wednesday = planOf().filter { it.occurrence.date == week1Wed }
        assertEquals(listOf("大学英语", "体育"), namesOf(wednesday))
        assertEquals(listOf(week1Wed.atTime(9, 45), week1Wed.atTime(15, 15)), wednesday.map { it.triggerAt })
    }

    // ==================================================================
    // 5. The horizon
    // ==================================================================

    @Test
    fun `a class beyond the horizon is excluded`() {
        // horizonDays = 6 ends on 03-08, so week 2's Monday is out of reach.
        val plan = planOf(horizonDays = 6)
        assertEquals(4, plan.size)
        assertTrue(plan.none { it.occurrence.date == week2Mon })
        assertTrue(plan.all { it.occurrence.date <= LocalDate.of(2026, 3, 8) })
    }

    @Test
    fun `a class on the horizon's last day is included`() {
        // horizonDays = 7 ends on 03-09 and occurrencesBetween includes both endpoints.
        val plan = planOf(horizonDays = 7)
        assertEquals(5, plan.size)
        assertEquals(week2Mon, plan.last().occurrence.date)
        assertEquals(week2Mon.atTime(7, 45), plan.last().triggerAt)
    }

    @Test
    fun `the horizon counts whole days from today and not from now`() {
        // horizonDays = 0 still covers the entirety of today, so a class later today is
        // planned even though only a few hours remain.
        val plan = planOf(now = mondayEarly, horizonDays = 0)
        assertEquals(1, plan.size)
        assertEquals(week1Mon, plan.single().occurrence.date)
        assertEquals(week1Mon.atTime(7, 45), plan.single().triggerAt)
    }

    @Test
    fun `a negative horizon behaves like today only`() {
        assertEquals(planOf(horizonDays = 0), planOf(horizonDays = -3))
        assertEquals(1, planOf(horizonDays = -30).size)
    }

    // ==================================================================
    // 6. maxReminders
    // ==================================================================

    @Test
    fun `maxReminders truncates to the earliest N and keeps the time order`() {
        val full = planOf(horizonDays = 30)
        val truncated = planOf(horizonDays = 30, maxReminders = 3)
        assertEquals(17, full.size)
        assertEquals(full.take(3), truncated)
        assertEquals(
            listOf(
                week1Mon.atTime(7, 45) to "高等数学",
                week1Wed.atTime(9, 45) to "大学英语",
                week1Wed.atTime(15, 15) to "体育",
            ),
            stampsOf(truncated),
        )
        assertEquals(truncated.map { it.triggerAt }, truncated.map { it.triggerAt }.sorted())
        // The cap never reorders: the truncated head is the head of the full plan.
        assertEquals(full.first(), truncated.first())
    }

    @Test
    fun `a maxReminders of zero or less returns nothing`() {
        // FIXED: the cap used to be `coerceAtLeast(1)`, which made "no reminders at all"
        // inexpressible — a caller asking for zero silently got one. It is now
        // `coerceAtLeast(0)`, so a cap of 0 (or negative) honestly means none, while any
        // positive cap still truncates to the EARLIEST N, preserving time order.
        val full = planOf(horizonDays = 30)
        assertTrue(planOf(horizonDays = 30, maxReminders = 0).isEmpty())
        assertTrue(planOf(horizonDays = 30, maxReminders = -5).isEmpty())
        assertEquals(full.take(1), planOf(horizonDays = 30, maxReminders = 1))
        assertEquals(full.take(3), planOf(horizonDays = 30, maxReminders = 3))
    }

    @Test
    fun `the default cap truncates a plan that spans the installed term`() {
        val plan = planOf(horizonDays = 400)
        assertEquals(ReminderPlanner.DEFAULT_MAX_REMINDERS, plan.size)
        assertTrue("the term resolver must clamp the dates", plan.all { term.contains(it.occurrence.date) })
        assertEquals(plan.map { it.triggerAt }, plan.map { it.triggerAt }.sorted())
    }

    // ==================================================================
    // 7. Lead time clamping
    // ==================================================================

    @Test
    fun `a negative lead behaves like zero`() {
        val zero = planOf(leadMinutes = 0)
        val negative = planOf(leadMinutes = -30)
        assertTrue(zero.isNotEmpty())
        assertEquals(zero, negative)
        assertTrue(negative.all { it.leadMinutes == 0 })
        assertTrue(
            "clamping to 0 must make the trigger the class start",
            negative.all { it.triggerAt == LocalDateTime.of(it.occurrence.date, it.startTime!!) },
        )
    }

    @Test
    fun `a lead above the maximum behaves like MAX_LEAD_MINUTES`() {
        val capped = planOf(leadMinutes = ReminderPlanner.MAX_LEAD_MINUTES)
        val huge = planOf(leadMinutes = 10_000)
        assertTrue(capped.isNotEmpty())
        assertEquals(capped, huge)
        assertTrue(huge.all { it.leadMinutes == ReminderPlanner.MAX_LEAD_MINUTES })
        for (reminder in huge) {
            assertEquals(
                LocalDateTime.of(reminder.occurrence.date, reminder.startTime!!).minusMinutes(1440),
                reminder.triggerAt,
            )
        }
    }

    @Test
    fun `a maximal lead drops a class whose trigger has already passed`() {
        // With a 24h lead, Monday's 08:00 class triggers on Sunday 08:00, which is behind
        // `now` — so the first planned reminder is Wednesday's.
        val plan = planOf(leadMinutes = ReminderPlanner.MAX_LEAD_MINUTES)
        assertEquals(week1Wed.atTime(10, 0).minusMinutes(1440), plan.first().triggerAt)
        assertEquals("大学英语", plan.first().courseName)
        assertTrue(plan.none { it.occurrence.date == week1Mon })
    }

    // ==================================================================
    // 3. Occurrences with no known start time
    // ==================================================================

    @Test
    fun `an occurrence with no known start time is skipped`() {
        val noSchedule = timetableWith(periodSchedule = PeriodSchedule(emptyList()))
        // The occurrence itself still exists — it just has no clock time.
        val occurrence = TimetableResolver.occurrencesForDate(noSchedule, week1Mon).single()
        assertNull(occurrence.startTime)
        // ... and with no instant to fire at, there is no reminder.
        assertTrue(planOf(timetable = noSchedule, horizonDays = 28).isEmpty())
        assertNull(ReminderPlanner.next(noSchedule, mondayEarly, 15))
        assertTrue(ReminderPlanner.due(noSchedule, week1Mon.atTime(8, 0), 15).isEmpty())
    }

    @Test
    fun `a session whose 节 is missing from the 作息表 is skipped`() {
        // The 作息表 knows 5-6 节 but not the 1-2 节 this session sits in. Only 高等数学's
        // Monday session is in this timetable, so nothing else can produce a reminder and
        // the only variable is the missing 节.
        val partial = PeriodSchedule(listOf(Period(5, LocalTime.of(13, 30), LocalTime.of(14, 15))))
        val t = timetableWith(
            courses = listOf(math),
            sessions = listOf(mathMonday),
            periodSchedule = partial,
        )
        assertNull(TimetableResolver.occurrencesForDate(t, week1Mon).single().startTime)
        assertTrue(planOf(timetable = t, horizonDays = 28).isEmpty())
        assertNull(ReminderPlanner.next(t, mondayEarly, 15))
    }

    @Test
    fun `the very same session is planned once its 节 is known`() {
        // Same fixture, same session, only the 作息表 differs: this is what proves the
        // skip above is about the missing 节 and not about the timetable as a whole.
        val known = PeriodSchedule(listOf(Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45))))
        val t = timetableWith(
            courses = listOf(math),
            sessions = listOf(mathMonday),
            periodSchedule = known,
        )
        val reminder = planOf(timetable = t, horizonDays = 0).single()
        assertEquals(week1Mon.atTime(7, 45), reminder.triggerAt)
        assertEquals("高等数学", reminder.courseName)
    }

    // ==================================================================
    // 9, 10. Empty timetable and `next`
    // ==================================================================

    @Test
    fun `an empty timetable plans nothing and does not throw`() {
        val empty = Timetable.empty(term)
        assertTrue(ReminderPlanner.plan(empty, mondayEarly, 15).isEmpty())
        // Even the degenerate arguments stay quiet.
        assertTrue(ReminderPlanner.plan(empty, mondayEarly, -30, horizonDays = 0, maxReminders = 0).isEmpty())
        assertTrue(ReminderPlanner.plan(empty, mondayEarly, 10_000, horizonDays = -5).isEmpty())
        assertNull(ReminderPlanner.next(empty, mondayEarly, 15))
        assertNull(ReminderPlanner.next(empty, LocalDateTime.of(2026, 7, 6, 6, 0), ReminderPlanner.MAX_LEAD_MINUTES))
        assertTrue(ReminderPlanner.due(empty, mondayEarly, 15).isEmpty())
        assertTrue(ReminderPlanner.due(empty, week1Mon.atTime(7, 45), 15, windowMinutes = 0).isEmpty())
    }

    @Test
    fun `a timetable holding courses but no sessions is empty too`() {
        val coursesOnly = timetableWith(sessions = emptyList())
        assertTrue(coursesOnly.isEmpty)
        assertFalse(coursesOnly.courses.isEmpty())
        assertTrue(planOf(timetable = coursesOnly).isEmpty())
        assertNull(ReminderPlanner.next(coursesOnly, mondayEarly, 15))
        assertTrue(ReminderPlanner.due(coursesOnly, mondayEarly, 15).isEmpty())
    }

    @Test
    fun `next returns the earliest future reminder`() {
        val next = ReminderPlanner.next(base, mondayEarly, 15)
        assertNotNull(next)
        assertEquals(week1Mon.atTime(7, 45), next!!.triggerAt)
        assertEquals("高等数学", next.courseName)
        assertEquals("南楼114", next.room)
        assertEquals(LocalTime.of(8, 0), next.startTime)
        assertEquals(15, next.leadMinutes)
    }

    @Test
    fun `next moves past a class whose trigger has already fired`() {
        val next = ReminderPlanner.next(base, week1Mon.atTime(8, 30), 15)!!
        assertEquals(week1Wed.atTime(9, 45), next.triggerAt)
        assertEquals("大学英语", next.courseName)
        // The 07:45 trigger never reappears in the plan.
        assertTrue(planOf(now = week1Mon.atTime(8, 30)).none { it.triggerAt == week1Mon.atTime(7, 45) })
    }

    @Test
    fun `next is exactly the head of the plan`() {
        val horizon = ReminderPlanner.DEFAULT_HORIZON_DAYS
        assertEquals(
            planOf(horizonDays = horizon).firstOrNull(),
            ReminderPlanner.next(base, mondayEarly, 15),
        )
        assertEquals(
            planOf(now = week2Thu.atTime(13, 15), horizonDays = horizon).firstOrNull(),
            ReminderPlanner.next(base, week2Thu.atTime(13, 15), 15),
        )
    }

    @Test
    fun `next reports the clamped lead time`() {
        val next = ReminderPlanner.next(base, mondayEarly, 10_000)!!
        assertEquals(ReminderPlanner.MAX_LEAD_MINUTES, next.leadMinutes)
        // 08:00 minus 24h on 03-02 is 03-01 08:00 and already past, so Wednesday is next.
        assertEquals("大学英语", next.courseName)
        assertEquals(week1Wed.atTime(10, 0).minusMinutes(1440), next.triggerAt)
    }

    @Test
    fun `next is null when no class remains inside the horizon`() {
        // After the last class of the term ...
        assertNull(ReminderPlanner.next(base, LocalDateTime.of(2026, 7, 2, 20, 0), 15))
        // ... once the term is over ...
        assertNull(ReminderPlanner.next(base, LocalDateTime.of(2026, 7, 6, 6, 0), 15))
        // ... and long after it.
        assertNull(ReminderPlanner.next(base, LocalDateTime.of(2027, 1, 1, 6, 0), 15))
    }

    // ==================================================================
    // 11. The due window
    // ==================================================================

    @Test
    fun `a reminder is due at its exact trigger instant`() {
        val due = ReminderPlanner.due(base, week1Mon.atTime(7, 45), 15)
        assertEquals(1, due.size)
        assertEquals("高等数学", due.single().courseName)
        assertEquals(week1Mon.atTime(7, 45), due.single().triggerAt)
        assertEquals(LocalTime.of(8, 0), due.single().startTime)
        assertEquals(15, due.single().leadMinutes)
    }

    @Test
    fun `the due window includes a trigger exactly windowMinutes old`() {
        val due = ReminderPlanner.due(base, week1Mon.atTime(7, 50), 15, windowMinutes = 5)
        assertEquals(listOf(week1Mon.atTime(7, 45)), due.map { it.triggerAt })
    }

    @Test
    fun `a reminder older than the window is not due`() {
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 51), 15, windowMinutes = 5).isEmpty())
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(8, 0), 15, windowMinutes = 5).isEmpty())
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(23, 0), 15).isEmpty())
    }

    @Test
    fun `the default window is wide enough to absorb a deferred worker`() {
        // FIXED: the default window used to be 5 minutes. WorkManager's minimum interval is
        // 15 minutes and it defers freely under Doze/app standby, so a run even slightly late
        // found nothing due — and because the worker then arms the *next* reminder while
        // `plan` never returns a past trigger, that class was never reminded about at all.
        assertEquals(30L, ReminderPlanner.DEFAULT_DUE_WINDOW_MINUTES)
        // The widening is what matters, not 30 as a magic number: the default window is now
        // longer than any lead the UI offers, so a reminder is posted anywhere between its
        // trigger and the start of the class. The old 5-minute default ended the window at
        // 07:50 — 10 minutes before the 08:00 class the student's 15-minute lead asked about.
        val late = ReminderPlanner.due(base, week1Mon.atTime(7, 52), 15)
        assertEquals(listOf("高等数学"), namesOf(late))
    }

    @Test
    fun `a deferred worker still posts a reminder that its lead has not yet outlived`() {
        // The window's whole job: the trigger fired at 07:45, the worker ran 14 minutes late
        // — an ordinary Doze / app-standby deferral — and the class has not started, so the
        // reminder is still posted rather than silently dropped. The old 5-minute default
        // gave up at 07:50 and, because the worker then arms the *next* reminder while `plan`
        // never returns a past trigger, that class could never be reminded about again.
        val late = ReminderPlanner.due(base, week1Mon.atTime(7, 59), 15)
        assertEquals(listOf("高等数学"), namesOf(late))
        assertEquals(listOf(week1Mon.atTime(7, 45)), late.map { it.triggerAt })
        // A narrow window keeps its old meaning exactly: only the last `windowMinutes` count.
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 52), 15, windowMinutes = 5).isEmpty())
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(7, 50), 15, windowMinutes = 5).size)
    }

    @Test
    fun `the window never outlives the class it is about`() {
        // A 24h lead puts 高等数学's trigger on Sunday 08:00; on Monday 08:30 the class is
        // already half over, so no window — however wide — may post it. Without this bound a
        // generous window would turn into a burst of notifications for classes already
        // attended, which is the opposite failure to the one the wider default fixes.
        val wide = ReminderPlanner.due(base, week1Mon.atTime(8, 30), 15, windowMinutes = 10_000)
        assertTrue(wide.isEmpty())

        // One minute before the class it is still a reminder, not a report.
        val justInTime = ReminderPlanner.due(base, week1Mon.atTime(7, 59), 15, windowMinutes = 10_000)
        assertEquals(listOf("高等数学"), namesOf(justInTime))
        // At 08:00 the class is starting; the reminder is at its last allowed instant.
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(8, 0), 15, windowMinutes = 10_000).size)
        // One minute later the lecture is under way, so however wide the window is it stops.
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(8, 1), 15, windowMinutes = 10_000).isEmpty())

        // 准点 mode is bounded by the class itself: the trigger IS 08:00, so the reminder fires
        // at that instant and no later. It is the lead, not the window, that decides how much
        // warning the student gets.
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(8, 0), 0, windowMinutes = 10_000).size)
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(8, 1), 0, windowMinutes = 10_000).isEmpty())
    }

    @Test
    fun `a reminder still in the future is not due`() {
        assertTrue(ReminderPlanner.due(base, mondayEarly, 15).isEmpty())
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 44), 15).isEmpty())
    }

    @Test
    fun `the due window is measured in whole minutes truncated towards zero`() {
        // Inclusive at the far end: 5 minutes old is still inside a 5 minute window.
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(7, 50, 59), 15, windowMinutes = 5).size)
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 51), 15, windowMinutes = 5).isEmpty())
        // NOTE (quirk, pinned as built): Duration.toMinutes() truncates towards zero, so a
        // trigger less than one minute in the FUTURE reports elapsed == 0 and is treated
        // as due — the KDoc's "negative = not yet due" is only true a whole minute ahead.
        // Firing up to 59s early is harmless for a class reminder, so this is pinned, not
        // filed as a defect.
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(7, 44, 59), 15).size)
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 44), 15).isEmpty())
    }

    @Test
    fun `a zero length window only catches the exact instant`() {
        assertEquals(1, ReminderPlanner.due(base, week1Mon.atTime(7, 45), 15, windowMinutes = 0).size)
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 46), 15, windowMinutes = 0).isEmpty())
    }

    @Test
    fun `a negative window is clamped to zero rather than matching nothing`() {
        // FIXED: windowMinutes used to be the only unclamped numeric parameter, so a
        // negative window silently returned an empty list. It is now clamped to 0, i.e.
        // "only the exact instant counts", consistent with leadMinutes and horizonDays.
        assertEquals(
            ReminderPlanner.due(base, week1Mon.atTime(7, 45), 15, windowMinutes = 0),
            ReminderPlanner.due(base, week1Mon.atTime(7, 45), 15, windowMinutes = -1),
        )
        // One minute late falls outside a zero-width window, so nothing fires.
        assertTrue(ReminderPlanner.due(base, week1Mon.atTime(7, 46), 15, windowMinutes = -1).isEmpty())
    }

    @Test
    fun `due clamps the lead time the same way plan does`() {
        val zeroLead = ReminderPlanner.due(base, week1Mon.atTime(8, 0), 0)
        val negativeLead = ReminderPlanner.due(base, week1Mon.atTime(8, 0), -30)
        assertTrue(zeroLead.isNotEmpty())
        assertEquals(zeroLead, negativeLead)
        assertTrue(zeroLead.all { it.leadMinutes == 0 })
        assertTrue(zeroLead.all { it.triggerAt.toLocalTime() == it.startTime })
    }

    @Test
    fun `due and plan describe the same reminder identically`() {
        val planned = planOf(now = week1Mon.atTime(7, 0), horizonDays = 0).single()
        val due = ReminderPlanner.due(base, week1Mon.atTime(7, 45), 15).single()
        assertEquals(planned, due)
    }

    // ==================================================================
    // 12. `due` only scans today and tomorrow
    // ==================================================================

    @Test
    fun `due finds tomorrow's class when a long lead pulls its trigger into today`() {
        // 大学物理 runs on Thursday 03-05 13:30; with the maximum 24h lead its trigger is
        // Wednesday 03-04 13:30, i.e. today. That is exactly why both days are scanned.
        val due = ReminderPlanner.due(base, week1Wed.atTime(13, 30), ReminderPlanner.MAX_LEAD_MINUTES)
        assertEquals(listOf("大学物理"), namesOf(due))
        assertEquals(week1Thu, due.single().occurrence.date)
        assertEquals(week1Wed.atTime(13, 30), due.single().triggerAt)
        assertEquals(ReminderPlanner.MAX_LEAD_MINUTES, due.single().leadMinutes)
    }

    @Test
    fun `a class three days out is never due however large the lead`() {
        // 大学物理 is three days after `now`. It IS scheduled — the plan holds it with a
        // trigger of 03-04 13:30 — but it is nowhere near due, so nothing fires.
        //
        // Two independent guards keep it that way: `due` scans only today and tomorrow, and
        // the clamp caps the lead at 24h, so the earliest a 03-05 class can ever trigger is
        // 03-04 — still days in the future at this `now`. An absurd lead cannot reach round
        // that: 10_000 is clamped to 1440 for exactly this reason, so the assertion holds
        // for both the requested and the clamped value.
        val now = week1Mon.atTime(8, 0)
        assertTrue(ReminderPlanner.due(base, now, ReminderPlanner.MAX_LEAD_MINUTES).isEmpty())
        assertTrue(ReminderPlanner.due(base, now, 10_000).isEmpty())
        val planned = planOf(now = now, leadMinutes = ReminderPlanner.MAX_LEAD_MINUTES, horizonDays = 14)
            .single { it.occurrence.date == week1Thu }
        assertEquals(week1Wed.atTime(13, 30), planned.triggerAt)
        assertTrue(planned.triggerAt.isAfter(now))
        // Nothing on or after 03-04 is reachable by `due` at this instant: today holds only
        // the 08:00 class, and five minutes after it began no window — the default one
        // included — may still post its reminder.
        assertTrue(ReminderPlanner.due(base, now.plusMinutes(5), 15).isEmpty())
        assertTrue(ReminderPlanner.due(base, now.plusHours(3), 15).isEmpty())
    }

    @Test
    fun `a wide window reports every reminder still worth posting, in trigger order`() {
        // The reason the window exists, isolated from the lead: 大学英语 starts at 10:00 and
        // the student asked for a 90-minute lead, so its trigger is 08:30. A worker deferred
        // to 09:40 must still post it — that is a reminder with 20 minutes to spare, and the
        // old 5-minute window would have thrown the class away for good.
        val late = ReminderPlanner.due(base, week1Wed.atTime(9, 40), 90, windowMinutes = 70)
        assertEquals(listOf("大学英语"), namesOf(late))
        assertEquals(listOf(week1Wed.atTime(8, 30)), late.map { it.triggerAt })

        // The old 5-minute default on the very same deferral: gone, permanently.
        assertTrue(ReminderPlanner.due(base, week1Wed.atTime(9, 40), 90, windowMinutes = 5).isEmpty())

        // A trickle of delay is caught by a window of the old size too, which is what shows
        // the wider default buys robustness rather than changing the rule.
        assertEquals(1, ReminderPlanner.due(base, week1Wed.atTime(8, 34), 90, windowMinutes = 5).size)
        assertEquals(1, ReminderPlanner.due(base, week1Wed.atTime(8, 34), 90).size)

        // The one-minute mark that matters: past the class start nothing is posted, however
        // wide the window is made. 大学英语 has begun at 10:01...
        assertTrue(ReminderPlanner.due(base, week1Wed.atTime(10, 1), 90, windowMinutes = 400).isEmpty())
        // ... and 体育, whose trigger is 15:15 with a 15-minute lead, stops at 15:31.
        assertEquals(1, ReminderPlanner.due(base, week1Wed.atTime(15, 29), 15, windowMinutes = 400).size)
        assertTrue(ReminderPlanner.due(base, week1Wed.atTime(15, 31), 15, windowMinutes = 400).isEmpty())

        // Tomorrow's 大学物理 IS scanned, but with a 15 minute lead its trigger is 03-05
        // 13:15 — still in the future — so it is scheduled and simply not due yet.
        assertTrue(late.none { it.occurrence.date == week1Thu })
        assertTrue(
            planOf(now = week1Wed.atTime(9, 40), horizonDays = 2)
                .any { it.occurrence.date == week1Thu && it.triggerAt == week1Thu.atTime(13, 15) },
        )
    }

    // ==================================================================
    // 13. 调休 / 节假日 are respected for free
    // ==================================================================

    @Test
    fun `a holiday suppresses every reminder on that date`() {
        // 03-04 is reported by the school calendar payload as 节假日 (code "1").
        val holiday = timetableWith(
            adjustments = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-03-04" to "1")),
        )
        assertEquals(
            listOf("大学英语", "体育"),
            namesOf(planOf(timetable = base).filter { it.occurrence.date == week1Wed }),
        )
        val plan = planOf(timetable = holiday)
        assertTrue("a class that does not run can never be reminded about", plan.none { it.occurrence.date == week1Wed })
        assertEquals(3, plan.size)
        assertEquals(
            listOf(week1Mon.atTime(7, 45), week1Thu.atTime(13, 15), week2Mon.atTime(7, 45)),
            plan.map { it.triggerAt },
        )
    }

    @Test
    fun `next skips a holiday and lands on the next class that does run`() {
        val holiday = timetableWith(
            adjustments = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-03-04" to "1")),
        )
        val now = week1Mon.atTime(9, 0)
        assertEquals("大学英语", ReminderPlanner.next(base, now, 15)!!.courseName)
        val next = ReminderPlanner.next(holiday, now, 15)!!
        assertEquals("大学物理", next.courseName)
        assertEquals(week1Thu.atTime(13, 15), next.triggerAt)
    }

    @Test
    fun `due stays empty on a holiday`() {
        val holiday = timetableWith(
            adjustments = ScheduleAdjustmentSet.fromApiCalendar(mapOf("2026-03-04" to "1")),
        )
        val now = week1Wed.atTime(9, 45)
        assertEquals(listOf("大学英语"), namesOf(ReminderPlanner.due(base, now, 15)))
        assertTrue(ReminderPlanner.due(holiday, now, 15).isEmpty())
    }

    @Test
    fun `a 调休补课日 does produce a reminder for the followed weekday's class`() {
        // A Saturday 补课 running Monday's timetable: the reminder follows the date the
        // class actually happens, not the weekday the session nominally sits on.
        val makeup = ScheduleAdjustmentSet.EMPTY.with(
            DayAdjustment(
                date = week1Sat,
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.MONDAY,
                source = AdjustmentSource.MANUAL,
            ),
        )
        val t = timetableWith(adjustments = makeup)
        assertTrue("an ordinary Saturday has no classes", planOf().none { it.occurrence.date == week1Sat })
        val reminder = planOf(timetable = t).single { it.occurrence.date == week1Sat }
        assertEquals("高等数学", reminder.courseName)
        assertEquals(week1Sat.atTime(7, 45), reminder.triggerAt)
        assertTrue(reminder.occurrence.isMakeup)
        assertEquals(DayOfWeek.MONDAY, reminder.occurrence.session.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, week1Sat.dayOfWeek)
        // One extra reminder, and nothing else moved.
        assertEquals(6, planOf(timetable = t).size)
    }

    @Test
    fun `a date that runs no classes produces no reminder`() {
        val cancelled = ScheduleAdjustmentSet.EMPTY.with(
            DayAdjustment(
                date = week1Thu,
                kind = CalendarDayKind.WORKDAY,
                noClasses = true,
                source = AdjustmentSource.MANUAL,
            ),
        )
        val t = timetableWith(adjustments = cancelled)
        assertTrue(planOf(timetable = base).any { it.occurrence.date == week1Thu })
        assertTrue(planOf(timetable = t).none { it.occurrence.date == week1Thu })
        assertEquals(4, planOf(timetable = t).size)
    }

    // ==================================================================
    // 14. 单双周
    // ==================================================================

    @Test
    fun `an odd week only session produces reminders only in odd weeks`() {
        // 体育 is `[1-17单]`; the horizon spans Wednesdays of weeks 1 to 4.
        val plan = planOf(leadMinutes = 0, horizonDays = 28)
        val peReminders = plan.filter { it.courseName == "体育" }
        assertEquals(listOf(week1Wed, week3Wed), peReminders.map { it.occurrence.date })
        assertTrue(peReminders.all { it.occurrence.week % 2 == 1 })
        assertTrue(peReminders.all { it.occurrence.session.weeks.isOddOnly })
        assertTrue(peReminders.all { it.occurrence.isActive })
        assertEquals(listOf(week1Wed.atTime(15, 30), week3Wed.atTime(15, 30)), peReminders.map { it.triggerAt })
        // The contrast: an every-week course is planned on the even weeks in between.
        assertEquals(
            listOf(week1Wed, week2Wed, week3Wed, week4Wed),
            plan.filter { it.courseName == "大学英语" }.map { it.occurrence.date },
        )
    }

    // ==================================================================
    // 15. ClassReminder accessors
    // ==================================================================

    @Test
    fun `a reminder exposes the course name room and start time`() {
        val reminder = planOf(horizonDays = 0).single()
        assertEquals("高等数学", reminder.courseName)
        assertEquals("南楼114", reminder.room)
        assertEquals(LocalTime.of(8, 0), reminder.startTime)
        // The accessors are passthroughs onto the occurrence.
        assertEquals(reminder.occurrence.course.name, reminder.courseName)
        assertEquals(reminder.occurrence.room, reminder.room)
        assertEquals(reminder.occurrence.startTime, reminder.startTime)
    }

    @Test
    fun `the room is null when the session has no room`() {
        val reminder = planOf().single { it.courseName == "体育" }
        assertNull(reminder.room)
        assertNull(reminder.occurrence.session.room)
        assertEquals("体育", reminder.courseName)
        assertEquals(LocalTime.of(15, 30), reminder.startTime)
    }

    @Test
    fun `every planned reminder always has a start time and an active occurrence`() {
        // Unlike ClassOccurrence.startTime — which is nullable because the 作息表 may not
        // know a 节 — a ClassReminder can only exist when the start time was known, so the
        // property is never null in practice.
        val plan = planOf(horizonDays = 400)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.startTime != null })
        assertTrue(plan.all { it.occurrence.isActive })
    }

    // ==================================================================
    // Courses and sessions the resolver drops
    // ==================================================================

    @Test
    fun `a hidden course never produces a reminder`() {
        val hidden = Course(id = 5, name = "隐藏课", teachingClassId = 2005, hidden = true)
        val t = timetableWith(
            courses = listOf(math, hidden),
            sessions = listOf(
                mathMonday,
                CourseSession(
                    id = 21, courseId = hidden.id, dayOfWeek = DayOfWeek.MONDAY,
                    startUnit = 3, endUnit = 4, weeks = everyWeek,
                ),
            ),
        )
        assertTrue(planOf(timetable = t).none { it.courseName == "隐藏课" })
        assertTrue(planOf(timetable = t).any { it.courseName == "高等数学" })
        assertTrue(ReminderPlanner.due(t, week1Mon.atTime(9, 45), 15).isEmpty())
    }

    @Test
    fun `a session with an unreadable week field never produces a reminder`() {
        val unparsed = Course(id = 6, name = "未解析周次", teachingClassId = 2006)
        val t = timetableWith(
            courses = listOf(unparsed),
            sessions = listOf(
                CourseSession(
                    id = 22, courseId = unparsed.id, dayOfWeek = DayOfWeek.MONDAY,
                    startUnit = 1, endUnit = 2, weeks = WeekPattern.EMPTY,
                ),
            ),
        )
        assertFalse(t.isEmpty)
        assertTrue(planOf(timetable = t, horizonDays = 28).isEmpty())
        assertNull(ReminderPlanner.next(t, mondayEarly, 15))
    }

    @Test
    fun `a session whose course is missing never produces a reminder`() {
        val t = timetableWith(
            courses = emptyList(),
            sessions = listOf(
                CourseSession(
                    id = 23, courseId = 99L, dayOfWeek = DayOfWeek.MONDAY,
                    startUnit = 1, endUnit = 2, weeks = everyWeek,
                ),
            ),
        )
        assertFalse(t.isEmpty)
        assertTrue(planOf(timetable = t, horizonDays = 28).isEmpty())
        assertNull(ReminderPlanner.next(t, mondayEarly, 15))
    }

    // ==================================================================
    // Consistency
    // ==================================================================

    @Test
    fun `every reminder points at a real course session and dated occurrence`() {
        for (reminder in planOf(horizonDays = 28)) {
            val occurrence = reminder.occurrence
            assertEquals(base.course(occurrence.session.courseId), occurrence.course)
            assertTrue(base.sessions.any { it.id == occurrence.session.id })
            assertEquals(term.weekOf(occurrence.date), occurrence.week)
            assertEquals(term.dateOf(occurrence.week, occurrence.session.dayOfWeek), occurrence.date)
        }
    }

    @Test
    fun `planning is idempotent and does not mutate the timetable`() {
        val first = planOf(horizonDays = 28)
        val second = planOf(horizonDays = 28)
        assertEquals(first, second)
        assertEquals(4, base.sessions.size)
        assertEquals(0, base.adjustments.size)
        assertFalse(base.isEmpty)
    }

    @Test
    fun `a reminder equals another built from the same occurrence and trigger`() {
        val reminder = planOf(horizonDays = 0).single()
        assertEquals(
            ClassReminder(reminder.occurrence, week1Mon.atTime(7, 45), 15),
            reminder,
        )
        assertFalse(reminder == reminder.copy(leadMinutes = 30))
        assertEquals(30, reminder.copy(leadMinutes = 30).leadMinutes)
    }
}
