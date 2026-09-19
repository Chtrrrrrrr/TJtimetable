package com.ranorac.tjtimetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Everything needed to draw one semester: courses, their meeting slots, the term
 * boundaries, the 作息表, and the resolved 调休串休 state.
 *
 * This is the aggregate the repository emits and the UI, the widget and the
 * calendar writer all consume, so they cannot disagree about the schedule.
 */
data class Timetable(
    val term: TermCalendar,
    val courses: List<Course>,
    val sessions: List<CourseSession>,
    val adjustments: ScheduleAdjustmentSet,
    val periodSchedule: PeriodSchedule = PeriodSchedule.TONGJI,
) {
    private val byId: Map<Long, Course> by lazy { courses.associateBy { it.id } }

    fun course(courseId: Long): Course? = byId[courseId]

    fun sessionsOf(courseId: Long): List<CourseSession> = sessions.filter { it.courseId == courseId }

    /** Sessions that are active in [week], ignoring 调休. */
    fun sessionsIn(week: Int): List<CourseSession> = sessions.filter { it.runsIn(week) }

    val isEmpty: Boolean get() = sessions.isEmpty()

    companion object {
        fun empty(term: TermCalendar) = Timetable(term, emptyList(), emptyList(), ScheduleAdjustmentSet.EMPTY)
    }
}

/**
 * Turns a [Timetable] into concrete dated occurrences.
 *
 * The interesting part is 调休串休. Rather than asking "which sessions are on
 * Wednesday?", this asks "which weekday's schedule does this *date* run?" and
 * then takes that weekday's sessions. That single inversion makes holiday
 * suppression and 补课 fall out for free:
 *
 *  - a 节假日 has no effective weekday, so its sessions simply disappear;
 *  - a 补课日 resolves to a different weekday, so those sessions appear on it;
 *  - and a genuine Saturday class still shows, because a weekend date's effective
 *    weekday is Saturday.
 */
object TimetableResolver {

    /** All occurrences in teaching week [week]. */
    fun occurrencesForWeek(timetable: Timetable, week: Int): List<ClassOccurrence> =
        weekGrid(timetable, week, includeInactive = false)

    /**
     * The grid contents for [week].
     *
     * @param includeInactive when true, sessions that do not run this week are
     *   still emitted with `isActive = false`, so the UI can ghost them. This is
     *   what makes 单双周 legible at a glance instead of leaving holes.
     */
    fun weekGrid(
        timetable: Timetable,
        week: Int,
        includeInactive: Boolean,
    ): List<ClassOccurrence> {
        val term = timetable.term
        if (week !in 1..term.totalWeeks) return emptyList()
        val out = ArrayList<ClassOccurrence>()
        for (offset in 0L..6L) {
            val date = term.weekStart(week).plusDays(offset)
            out += occurrencesOn(timetable, date, week, includeInactive)
        }
        return out.sortedWith(OCCURRENCE_ORDER)
    }

    /** All occurrences on a concrete date, or an empty list when it is not a teaching day. */
    fun occurrencesForDate(timetable: Timetable, date: LocalDate): List<ClassOccurrence> {
        val week = timetable.term.weekOf(date) ?: return emptyList()
        // Sorting is load-bearing here, not cosmetic: the widget renders this list
        // and then truncates it, so import order would both display classes out of
        // sequence and drop the EARLIEST ones from the truncated window.
        return occurrencesOn(timetable, date, week).sortedWith(OCCURRENCE_ORDER)
    }

    /**
     * Occurrences in `[from, to]`, which is what the calendar writer and the
     * reminder scheduler want.
     */
    fun occurrencesBetween(timetable: Timetable, from: LocalDate, to: LocalDate): List<ClassOccurrence> {
        val out = ArrayList<ClassOccurrence>()
        var date = from
        while (!date.isAfter(to)) {
            out += occurrencesForDate(timetable, date)
            date = date.plusDays(1)
        }
        return out.sortedWith(OCCURRENCE_ORDER)
    }

    private fun occurrencesOn(
        timetable: Timetable,
        date: LocalDate,
        week: Int,
        includeInactive: Boolean = false,
    ): List<ClassOccurrence> {
        // null means the date is a holiday, a break, or explicitly cancelled.
        val effective = timetable.adjustments.effectiveWeekday(date) ?: return emptyList()
        val makeUp = timetable.adjustments.get(date)?.isMakeup == true
        val schedule = timetable.periodSchedule

        return timetable.sessions
            .asSequence()
            .filter { it.dayOfWeek == effective }
            .mapNotNull { session ->
                val runs = session.runsIn(week)
                if (!runs && !includeInactive) return@mapNotNull null
                val course = timetable.course(session.courseId) ?: return@mapNotNull null
                if (course.hidden) return@mapNotNull null
                ClassOccurrence(
                    course = course,
                    session = session,
                    date = date,
                    week = week,
                    startTime = schedule.startOf(session.startUnit),
                    endTime = schedule.endOf(session.endUnit),
                    isMakeup = makeUp,
                    isActive = runs,
                )
            }
            .toList()
    }

    /** Chronological, then by 节 — the order the grid and 今日 view expect. */
    private val OCCURRENCE_ORDER: Comparator<ClassOccurrence> =
        compareBy<ClassOccurrence> { it.date }
            .thenBy { it.startUnit }
            .thenBy { it.course.name }

    /** The weekday a session nominally sits on, for grouping in the course list. */
    fun weeklySlots(timetable: Timetable): Map<DayOfWeek, List<CourseSession>> =
        timetable.sessions.groupBy { it.dayOfWeek }

    /** Start time of the next occurrence after [now], used by the widget and reminders. */
    fun nextOccurrence(timetable: Timetable, from: LocalDate, now: LocalTime): ClassOccurrence? {
        val horizon = from.plusDays(21)
        return occurrencesBetween(timetable, from, horizon).firstOrNull { occ ->
            occ.date.isAfter(from) || (occ.startTime != null && occ.startTime!!.isAfter(now))
        }
    }
}
