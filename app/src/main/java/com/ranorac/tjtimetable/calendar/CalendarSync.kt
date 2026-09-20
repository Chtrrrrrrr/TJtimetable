package com.ranorac.tjtimetable.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import androidx.core.content.ContextCompat
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.PeriodSchedule
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.WeekPattern
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of a 日历注册 (calendar registration) run.
 *
 * The operation is deliberately total: a missing permission or a misbehaving
 * calendar provider is reported as a value rather than thrown, because the only
 * caller is a settings toggle whose whole job is to explain to the student why
 * their classes did not show up in the calendar app.
 */
sealed interface CalendarSyncResult {

    /**
     * @param calendarId the calendar every event was written to, so the UI can offer
     *   "在日历中查看" without re-querying the provider.
     * @param eventsInserted number of event rows written, recurring and single alike.
     * @param skippedHolidays how many class slots were dropped because the date is a
     *   节假日 / 寒暑假 / 停课日. Surfaced to the student so a calendar that is visibly
     *   sparser than the timetable grid does not look like a bug.
     */
    data class Success(
        val calendarId: Long,
        val eventsInserted: Int,
        val skippedHolidays: Int,
    ) : CalendarSyncResult

    /** `READ_CALENDAR` and/or `WRITE_CALENDAR` is not granted. */
    data object NoPermission : CalendarSyncResult

    /** Anything else: no providers, rows rejected, permission revoked mid-run. */
    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : CalendarSyncResult
}

/**
 * How much of the term a 日历注册 writes.
 *
 * [CURRENT_WEEK] is the default: the app itself is the timetable, and the calendar copy is
 * for the lock screen and for reminders on the days at hand. Writing the whole term instead
 * puts several hundred rows (or, worse, one recurring rule per class with an exclusion for
 * every week the class does not run) into a student's personal calendar on the first tap.
 */
enum class CalendarScope {
    /** Only the teaching week that contains today, as plain single events. */
    CURRENT_WEEK,

    /** The whole term, as weekly recurrences with `EXDATE`s for the weeks that do not run. */
    WHOLE_TERM,
}

/**
 * The dates a 日历注册 covers, given [scope].
 *
 * Pure and separate from the provider work, because "which days does 本周 actually mean" is
 * exactly the arithmetic that can silently write the wrong week. When today is not inside
 * the term (a break before or after it, or a long holiday) [CalendarScope.CURRENT_WEEK]
 * falls back to the term's first week, so the button always has something to do rather than
 * failing with nothing to say.
 */
fun registrationRange(
    term: TermCalendar,
    scope: CalendarScope,
    today: LocalDate = LocalDate.now(),
): Pair<LocalDate, LocalDate> = when (scope) {
    CalendarScope.WHOLE_TERM -> term.firstWeekStart to term.endDate
    CalendarScope.CURRENT_WEEK -> {
        val week = term.weekOf(today) ?: 1
        term.weekStart(week) to term.weekEnd(week)
    }
}

/** `yyyyMMdd'T'HHmmss'Z'`, the RFC 5545 / `CalendarContract` `EXDATE` form. */
private val EXDATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"))

/**
 * Calendar colours for [Course.effectiveColorIndex], matching the hue order of the
 * UI's `CourseHues`.
 *
 * Duplicated as plain ARGB rather than read from `ui.theme`, because that palette is
 * built from Compose `Color`s and the calendar layer must stay free of UI dependencies.
 * The saturated values are used instead of the UI's pastel containers: a calendar app
 * draws the event title over its own background, where a pale hint colour would be
 * unreadable.
 */
private val COURSE_COLORS = intArrayOf(
    0xFF0969DA.toInt(), // blue
    0xFF1A7F37.toInt(), // green
    0xFF8250DF.toInt(), // purple
    0xFFBC4C00.toInt(), // orange
    0xFFCF222E.toInt(), // red
    0xFF1B7C83.toInt(), // teal
    0xFF9A6700.toInt(), // yellow
    0xFFBF3989.toInt(), // pink
    0xFF4A5FC1.toInt(), // indigo
    0xFF5C7C00.toInt(), // lime
)

/** The ARGB value for a course's hue index, clamped so a bad index cannot crash. */
private fun courseColor(index: Int): Int = COURSE_COLORS[index.coerceIn(0, COURSE_COLORS.lastIndex)]

// ---------------------------------------------------------------- pure logic

/**
 * Enumerates every class that actually happens between [fromDate] and [toDate].
 *
 * This is a pure function on purpose — 调休串休 is the part of 同济's calendar that
 * goes wrong most often, so the date maths must be testable without an emulator, a
 * database or a system calendar provider.
 *
 * It walks **dates**, not sessions, because a 补课日 breaks the simplifying
 * assumption that "week W of 周X happens on week W's X". On a 补课日 the date's own
 * weekday is irrelevant: the school runs a different weekday's timetable on it
 * (see [ScheduleAdjustmentSet.effectiveWeekday]), which can pull a session into a
 * week it does not own and push it out of the week it does.
 *
 * Two consequences that callers must handle, and that [CalendarSync] does:
 *  - an occurrence sitting on a weekday other than [CourseSession.dayOfWeek] cannot
 *    come from that session's weekly recurrence rule and needs its own single event;
 *  - a slot the recurrence *would* produce but that has no class needs an `EXDATE`.
 *
 * @param sessions sessions to consider; ones whose [CourseSession.courseId] matches no
 *   entry in [courses] are skipped rather than treated as an error, so a partially
 *   imported term still registers the classes that are complete.
 * @param fromDate inclusive lower bound, normally [TermCalendar.firstWeekStart].
 * @param toDate inclusive upper bound, normally [TermCalendar.endDate].
 * @return occurrences ordered by date, then by 节, then by course name.
 */
fun resolveOccurrences(
    courses: List<Course>,
    sessions: List<CourseSession>,
    term: TermCalendar,
    schedule: PeriodSchedule,
    adjustments: ScheduleAdjustmentSet,
    fromDate: LocalDate,
    toDate: LocalDate,
): List<ClassOccurrence> {
    if (sessions.isEmpty() || fromDate > toDate) return emptyList()
    val byId = courses.associateBy { it.id }

    // courseId -> sessions, so the date walk only touches sessions that could
    // possibly run that day instead of re-scanning the whole term for every date.
    val byCourseId = sessions.filter { byId.containsKey(it.courseId) }.groupBy { it.courseId }
    if (byCourseId.isEmpty()) return emptyList()

    val out = ArrayList<ClassOccurrence>()
    val seen = HashSet<Long>()

    var date = fromDate
    while (!date.isAfter(toDate)) {
        // The effective weekday is the timetable that runs here, or null when nothing
        // runs at all (节假日 / 寒暑假 / 停课日). Checked *before* the session's own
        // weekday, so a session whose weekday matches a holiday does not run.
        val weekday = adjustments.effectiveWeekday(date)
        if (weekday == null) {
            date = date.plusDays(1)
            continue
        }

        val week = term.weekOf(date)
        if (week != null) {
            for ((courseId, courseSessions) in byCourseId) {
                val course = byId[courseId] ?: continue
                if (course.hidden) continue
                for (session in courseSessions) {
                    // Which weekday's timetable runs must equal the session's weekday,
                    // and the date must fall in a week the session is actually taught.
                    // Outside the teaching weeks a 补课日 produces nothing, matching how
                    // the grid draws it.
                    if (weekday != session.dayOfWeek) continue
                    if (!session.runsIn(week)) continue
                    val key = session.id * 1_000_000L + date.toEpochDay()
                    if (!seen.add(key)) continue
                    out.add(occurrenceOf(course, session, date, week, schedule, adjustments))
                }
            }
        }
        date = date.plusDays(1)
    }

    return out.sortedWith(compareBy({ it.date }, { it.startUnit }, { it.course.name }))
}

/**
 * Analyses the term for the UI summary, without touching any provider.
 *
 * The skipped-slot count is computed over the *same* walk rules as
 * [resolveOccurrences]: a session counts as skipped when its own weekday falls on a
 * date that is inside the term but on which nothing runs. A date that runs some
 * other weekday's timetable is a 补课日, not a holiday, and is never counted here.
 */
private fun analyzeSchedule(
    courses: List<Course>,
    sessions: List<CourseSession>,
    term: TermCalendar,
    schedule: PeriodSchedule,
    adjustments: ScheduleAdjustmentSet,
    fromDate: LocalDate,
    toDate: LocalDate,
): Pair<List<ClassOccurrence>, Int> {
    val known = courses.associateBy { it.id }

    var skipped = 0
    for (session in sessions) {
        if (!known.containsKey(session.courseId)) continue
        for (week in effectiveWeeks(session, term).weekNumbers) {
            val date = term.dateOf(week, session.dayOfWeek)
            if (date < fromDate || date > toDate) continue
            // Only dates the session would have taught, cancelled by the calendar.
            if (adjustments.isTeachingDay(date)) continue
            skipped++
        }
    }

    return resolveOccurrences(
        courses = courses,
        sessions = sessions,
        term = term,
        schedule = schedule,
        adjustments = adjustments,
        fromDate = fromDate,
        toDate = toDate,
    ) to skipped
}

/** Builds one [ClassOccurrence] from an already-validated date/week pair. */
private fun occurrenceOf(
    course: Course,
    session: CourseSession,
    date: LocalDate,
    week: Int,
    schedule: PeriodSchedule,
    adjustments: ScheduleAdjustmentSet,
): ClassOccurrence {
    val span = session.span(schedule)
    return ClassOccurrence(
        course = course,
        session = session,
        date = date,
        week = week,
        startUnit = session.startUnit,
        endUnit = session.endUnit,
        startTime = span?.first,
        endTime = span?.second,
        room = session.room,
        // The date runs a weekday other than its own, so this class exists only
        // because the school moved it here — which is exactly what 补课 means.
        isMakeup = adjustments.effectiveWeekday(date) != date.dayOfWeek,
    )
}

/**
 * Builds the `RRULE` string for one maximal run of [session]'s active weeks, or null
 * when that run teaches nothing inside [term].
 *
 * Exposed as a small pure function because it is the highest-risk piece of the whole
 * integration: a wrong `COUNT` silently drops or duplicates a course for an entire
 * semester, and that must be catchable by a unit test rather than by a student.
 *
 * @param run an ascending maximal contiguous run from `session.weeks.ranges()`.
 * @param adjustments used to pick a `DTSTART` that is a real teaching day; pass
 *   [ScheduleAdjustmentSet.EMPTY] when holidays are not known yet.
 */
fun buildRrule(
    session: CourseSession,
    run: IntRange,
    term: TermCalendar,
    adjustments: ScheduleAdjustmentSet = ScheduleAdjustmentSet.EMPTY,
): String? = buildRecurrence(session, run, term, adjustments)?.rrule

/** A weekly rule plus the concrete dates and weeks it expands to. */
internal data class RecurrenceSpec(
    val rrule: String,
    val dtStart: LocalDate,
    val count: Int,
    /** Every week the rule generates, paired with the date it lands on. */
    val generatedDates: List<Pair<Int, LocalDate>>,
)

/**
 * Weekly recurrence for one maximal run of active weeks.
 *
 * RFC 5545 has no notion of a "teaching week", so 单双周 is expressed through the
 * *shape* of the rule rather than through week numbers:
 *
 *  - a run that genuinely alternates every other week inside the session's own
 *    pattern (weeks 1,3,5… or 2,4,6…) becomes `INTERVAL=2`, anchored on its first
 *    active week — that is how 单周 / 双周 is told to a calendar app;
 *  - every other run becomes a plain weekly rule whose `COUNT` is the run's length,
 *    including a run consisting of a single week, which must never be labelled 单周.
 *
 * `DTSTART` is the first *teaching* date of the run, not the run's first week: when
 * week 1 is a 节假日 the rule has to start at week 2, otherwise the calendar app shows
 * a class the timetable grid hides. A candidate week skipped this way is one the rule
 * never generates, so it needs no `EXDATE`.
 *
 * Weeks after a mid-run holiday are still generated by the rule and are cancelled by
 * the caller with `EXDATE`, because shrinking `COUNT` would silently lose them.
 */
private fun buildRecurrence(
    session: CourseSession,
    run: IntRange,
    term: TermCalendar,
    adjustments: ScheduleAdjustmentSet,
): RecurrenceSpec? {
    if (run.first > run.last) return null
    val runWeeks = run.toList()
    if (runWeeks.isEmpty()) return null

    val step = if (alternatesEveryOtherWeek(session.weeks, runWeeks)) 2 else 1

    var anchorIndex = -1
    var anchorDate: LocalDate? = null
    var i = 0
    while (i < runWeeks.size) {
        val candidate = term.dateOf(runWeeks[i], session.dayOfWeek)
        if (adjustments.isTeachingDay(candidate)) {
            anchorIndex = i
            anchorDate = candidate
            break
        }
        i += step
    }
    val first = anchorIndex.takeIf { it >= 0 } ?: return null
    val dtStart = anchorDate ?: return null

    val generated = ArrayList<Pair<Int, LocalDate>>()
    var index = first
    while (index < runWeeks.size) {
        val week = runWeeks[index]
        generated.add(week to term.dateOf(week, session.dayOfWeek))
        index += step
    }
    if (generated.isEmpty()) return null

    val rrule = buildString {
        append("FREQ=WEEKLY;")
        if (step == 2) append("INTERVAL=2;")
        append("BYDAY=").append(rfcWeekday(session.dayOfWeek)).append(';')
        append("COUNT=").append(generated.size)
    }

    return RecurrenceSpec(
        rrule = rrule,
        dtStart = dtStart,
        count = generated.size,
        generatedDates = generated,
    )
}

/**
 * True when [run] alternates every other week *as the session itself defines it*.
 *
 * Every gap between consecutive weeks must be exactly 2 and every week skipped that
 * way must be absent from [pattern] as well. The second half of the test matters for
 * precision: weeks 1,3,5 of a session that also meets in week 2 is not a 单周 session,
 * and weeks 2,4,6 of a session that also meets in week 3 is not a 双周 one.
 *
 * A single week never alternates, even an odd one: a course that only meets in week 3
 * is not 单周, and calling it that would drain the 单周 badge of meaning.
 */
private fun alternatesEveryOtherWeek(pattern: WeekPattern, run: List<Int>): Boolean {
    if (run.size < 2) return false
    for (i in 1 until run.size) {
        if (run[i] - run[i - 1] != 2) return false
        if (pattern.contains(run[i] - 1)) return false
    }
    return true
}

/** [session]'s weeks clipped to the weeks [term] actually has. */
private fun effectiveWeeks(session: CourseSession, term: TermCalendar): WeekPattern =
    session.weeks intersect WeekPattern.range(1, term.totalWeeks.coerceIn(1, WeekPattern.MAX_WEEK))

/** The session's 节 span as wall-clock times, when the 作息表 defines both ends. */
private fun CourseSession.span(schedule: PeriodSchedule): Pair<LocalTime, LocalTime>? =
    schedule.spanOf(startUnit, endUnit)

// ------------------------------------------------------- event-rows planning

/**
 * One row destined for [Events], already flattened to provider primitives.
 *
 * Keeping this separate from the writing is what makes [buildEventSpecs] pure: all the
 * interesting decisions — the shape of the rule, the `EXDATE` set, which occurrences
 * need a standalone event — are made without touching a [ContentResolver], so they can
 * be unit-tested.
 */
internal data class CalendarEventSpec(
    val title: String,
    val description: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val eventTimeZone: String,
    /** `FREQ=WEEKLY;…`, or null for a standalone (补课) occurrence. */
    val rrule: String?,
    /**
     * `EXDATE` values for slots the rule would otherwise draw, or null when nothing
     * needs cancelling. Null rather than an empty list so no `EXDATE` key is written
     * at all, which some providers treat as a malformed recurrence set.
     */
    val exdates: List<String>?,
    val reminderMinutes: Int,
    val colorIndex: Int,
)

/**
 * Turns one session's resolved occurrences into provider rows.
 *
 * The strategy, and why it reproduces 单双周 faithfully:
 *
 *  1. For each maximal contiguous run of the session's weeks, emit one recurring
 *     event. An odd/even-only session gets `INTERVAL=2` anchored on its first active
 *     week — the only way a calendar app can be told "单周".
 *
 *     NOTE (measured, not assumed): `WeekPattern.ranges()` splits an odd/even-only
 *     pattern into per-week *singletons*, so a 单周 course currently becomes one
 *     single-occurrence event per active week rather than a single `INTERVAL=2` rule —
 *     9 events for weeks 1-17 odd, not 1. The **dates are identical either way**, which
 *     `CalendarSyncLogicTest` pins by asserting the exact date set, so this is a
 *     verbosity issue rather than a correctness one. Collapsing parity patterns into one
 *     `INTERVAL=2` rule is a worthwhile optimisation, not a behavioural fix.
 *  2. Dates the session does not actually teach — 节假日, 寒暑假, 停课 — become `EXDATE`s.
 *     The calendar app expands the rule on its own and would otherwise resurrect every
 *     class the grid hides.
 *  3. With that bookkeeping the recurring events reproduce the occurrence list exactly, so
 *     every occurrence left over is one that landed on a different date — a 补课 — and is
 *     written as its own single event. Matching is by DATE rather than by week, so a 补课
 *     inside a week whose nominal date is *still* taught produces both the rule and the
 *     standalone row. Keying by week used to lose that class from the calendar entirely.
 *
 * @param occurrences occurrences belonging to [session] only; the caller groups them.
 * @param reminderMinutes lead time for the reminder, or negative to write none.
 * @return the rows to insert, possibly empty when the 作息表 does not define the span.
 */
internal fun buildEventSpecs(
    course: Course,
    session: CourseSession,
    occurrences: List<ClassOccurrence>,
    term: TermCalendar,
    schedule: PeriodSchedule,
    adjustments: ScheduleAdjustmentSet,
    zone: ZoneId,
    reminderMinutes: Int,
): List<CalendarEventSpec> {
    if (occurrences.isEmpty()) return emptyList()

    val span = session.span(schedule)
    val startTime = occurrences.firstNotNullOfOrNull { it.startTime } ?: span?.first ?: return emptyList()
    val endTime = occurrences.firstNotNullOfOrNull { it.endTime } ?: span?.second ?: return emptyList()

    val weeks = effectiveWeeks(session, term)
    if (weeks.isEmpty) return emptyList()

    // Keyed by DATE, not by week. Two independent defects came from keying by week:
    //
    //  1. A 补课日 puts two occurrences in one teaching week — the nominal one and the day it
    //     was actually moved to — and `associateBy { it.week }` silently kept only one of them.
    //  2. Worse, when the *makeup* one survived, the loop below marked its date as accounted
    //     for by the rule (so the standalone pass skipped it) and simultaneously EXDATE'd the
    //     nominal date. The class then existed in neither form: on the timetable grid but
    //     nowhere in the student's calendar. Real case: 放假 5月1–5日 with 5月9日（周六）补周二的课
    //     — 5月5日 is a holiday and 5月9日 is in the same Mon–Sun week.
    val occurrenceByDate = occurrences.associateBy { it.date }
    // Dates the recurring rules have already accounted for, so the standalone pass below emits
    // only what no rule covers.
    val coveredDates = HashSet<LocalDate>(occurrences.size)
    val out = ArrayList<CalendarEventSpec>()

    for (run in weeks.ranges()) {
        val rec = buildRecurrence(session, run, term, adjustments) ?: continue

        // The rule generates one slot per week of the run; look each up by its DATE. A week the
        // session does not teach has no occurrence, so the calendar app would draw a class the
        // grid hides and it has to be excluded by name.
        val cancelled = ArrayList<LocalDate>()
        for ((_, ruleDate) in rec.generatedDates) {
            if (occurrenceByDate[ruleDate] == null) {
                cancelled.add(ruleDate)
            } else {
                coveredDates.add(ruleDate)
            }
        }

        // Both instants live on the rule's own start date: the clamp only ever shortens
        // the span within that day, so it can never move the end onto the next one.
        val (clampedStart, clampedEnd) = clampToSameDay(rec.dtStart, startTime, endTime)

        out += CalendarEventSpec(
            title = courseTitle(course, makeup = false),
            description = recurringDescription(course, session, rec),
            location = locationOf(session),
            startMillis = epochMillis(rec.dtStart, clampedStart, zone),
            endMillis = epochMillis(rec.dtStart, clampedEnd, zone),
            eventTimeZone = zone.id,
            rrule = rec.rrule,
            // A rule with nothing to exclude must leave the column absent, not empty:
            // some providers treat an empty EXDATE as a malformed recurrence set.
            exdates = if (cancelled.isEmpty()) null else cancelled.map { toExdate(it, startTime, zone) },
            reminderMinutes = reminderMinutes,
            colorIndex = course.effectiveColorIndex,
        )
    }

    // Everything no rule accounted for: a 补课 on another weekday, or a class the school moved
    // into a week the runs do not cover. Deliberately OUTSIDE the run loop — inside it, an
    // occurrence could be emitted once per run.
    for (occ in occurrences) {
        if (occ.date in coveredDates) continue
        out += standaloneSpec(occ, startTime, endTime, zone, reminderMinutes, course, session)
    }
    return out
}

/**
 * One plain event per class in [occurrences] — the [CalendarScope.CURRENT_WEEK] writer.
 *
 * Deliberately the same row shape as a 补课 row: an exact date, no recurrence. A single week
 * of classes needs no rule, and a rule covering the whole term (with an exclusion for every
 * other week) is not what "只写本周" means.
 */
private fun weekSpecs(
    occurrences: List<ClassOccurrence>,
    schedule: PeriodSchedule,
    zone: ZoneId,
    reminderMinutes: Int,
): List<CalendarEventSpec> = occurrences.mapNotNull { occ ->
    // The occurrence's own clock times when 调休 moved it; otherwise the session's 作息 span.
    val span = occ.session.span(schedule)
    val start = occ.startTime ?: span?.first ?: return@mapNotNull null
    val end = occ.endTime ?: span?.second ?: return@mapNotNull null
    standaloneSpec(occ, start, end, zone, reminderMinutes, occ.course, occ.session)
}

/** A 补课 occurrence: one exact date, no rule, no excuse not to be shown. */
private fun standaloneSpec(
    occ: ClassOccurrence,
    startTime: LocalTime,
    endTime: LocalTime,
    zone: ZoneId,
    reminderMinutes: Int,
    course: Course,
    session: CourseSession,
): CalendarEventSpec {
    val (start, end) = clampToSameDay(occ.date, startTime, endTime)
    return CalendarEventSpec(
        title = courseTitle(course, makeup = occ.isMakeup),
        description = occurrenceDescription(course, session, occ),
        location = locationOf(session),
        startMillis = epochMillis(occ.date, start, zone),
        endMillis = epochMillis(occ.date, end, zone),
        eventTimeZone = zone.id,
        rrule = null,
        exdates = null,
        reminderMinutes = reminderMinutes,
        colorIndex = course.effectiveColorIndex,
    )
}

// ------------------------------------------------------------ row formatting

/**
 * A class that starts at 23:30 and ends at 00:20 would yield a negative duration and
 * a row the provider rejects or misplaces, so an end that is not after the start is
 * pulled to the end of the same day. 同济's 作息表 never does this; the clamp exists so
 * a hand-edited 作息 cannot corrupt the student's calendar.
 */
private fun clampToSameDay(
    date: LocalDate,
    start: LocalTime,
    end: LocalTime,
): Pair<LocalTime, LocalTime> {
    if (end.isAfter(start)) return start to end
    val endOfDay = LocalTime.of(23, 59, 59)
    return start to if (start < endOfDay) endOfDay else start
}

private fun epochMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

/**
 * `EXDATE` entry cancelling one occurrence.
 *
 * Rendered in UTC to match the `DTSTART`/`DTEND` columns, which are epoch millis, and
 * because the provider parses a `Z`-suffixed stamp unambiguously. The instant is the
 * same either way, so the cancellation always lands on the intended day.
 */
private fun toExdate(date: LocalDate, start: LocalTime, zone: ZoneId): String =
    EXDATE_FORMAT.format(date.atTime(start).atZone(zone).withZoneSameInstant(ZoneId.of("UTC")))

/** `MONDAY` -> `MO`, the RFC 5545 day codes used by `BYDAY`. */
private fun rfcWeekday(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "MO"
    DayOfWeek.TUESDAY -> "TU"
    DayOfWeek.WEDNESDAY -> "WE"
    DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"
    DayOfWeek.SATURDAY -> "SA"
    DayOfWeek.SUNDAY -> "SU"
}

/** `高等数学` -> `高等数学 · 补课`. */
private fun courseTitle(course: Course, makeup: Boolean): String =
    if (makeup) "${course.name} · 补课" else course.name

/** `四平路校区 教学南楼 305`, or null when the session carries no place at all. */
private fun locationOf(session: CourseSession): String? =
    listOfNotNull(session.campus, session.building, session.room)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")
        .ifEmpty { null }

/**
 * Notes for a recurring event: 课程 · 教学班 · 教师, then 周次 · 节次 · 教室.
 *
 * Worth the bytes: the calendar app is what the student actually looks at when they
 * are already running late, so the room has to be visible without opening the app.
 */
private fun recurringDescription(
    course: Course,
    session: CourseSession,
    rec: RecurrenceSpec,
): String = buildEventDescription(
    course = course,
    session = session,
    weeks = rec.generatedDates.map { it.first },
)

/** Notes for a standalone 补课 event, which covers exactly one week. */
private fun occurrenceDescription(
    course: Course,
    session: CourseSession,
    occ: ClassOccurrence,
): String = buildEventDescription(
    course = course,
    session = session,
    weeks = listOf(occ.week),
    // The student needs to know this one is not on the usual weekday.
    makeup = occ.isMakeup,
)

/**
 * Renders the description text.
 *
 * Weeks are printed as the span the rule covers, even when it alternates, because the
 * parity suffix carries the rest: `第1-17周（单周）` is how a student reads their own
 * timetable, whereas listing the nine weeks that actually meet would look like a
 * different course.
 */
private fun buildEventDescription(
    course: Course,
    session: CourseSession,
    weeks: List<Int>,
    makeup: Boolean = false,
): String = buildString {
    append(course.name)
    // 教学班编号 and 课程代码 live on Course; a session only carries its own 教室/教师.
    course.classCode?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    // The session's teacher wins over the course's: a 教学班 can be split across teachers.
    (session.teacher ?: course.teacher)?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    if (weeks.isNotEmpty()) {
        val from = weeks.min()
        val to = weeks.max()
        append('\n')
        if (from == to) append("第").append(from).append("周")
        else append("第").append(from).append('-').append(to).append("周")
        session.weeks.parityLabel?.let { append("（").append(it).append("）") }
    }
    append(" · ").append(session.startUnit).append('-').append(session.endUnit).append("节")
    locationOf(session)?.let { append(" · ").append(it) }
    if (makeup) append("\n调休补课，上课时间以教务通知为准")
}

// ------------------------------------------------------------- provider work

/**
 * One calendar the student can register their timetable into.
 *
 * Only calendars that already exist on the device are ever listed: an ordinary app cannot
 * create one, and pretending otherwise is exactly what the previous implementation got
 * wrong (see [CalendarSync]).
 */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
    val isPrimary: Boolean,
) {
    /** Second line of the picker row: which account this calendar belongs to. */
    val accountLabel: String?
        get() = accountName
            ?.takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
            ?: accountType?.takeIf { it.isNotBlank() && it != LOCAL_ACCOUNT_TYPE }
}

/**
 * A calendar row as read from the provider, before [pickerCalendars] decides what the
 * student may actually write to. Separate from [DeviceCalendar] so the filtering and the
 * ordering can be unit-tested without a `ContentProvider`.
 */
data class CalendarRow(
    val id: Long,
    val displayName: String?,
    val accountName: String?,
    val accountType: String?,
    val accessLevel: Int,
    val isPrimary: Boolean,
    val visible: Boolean,
    val deleted: Boolean,
)

/** `Calendars.ACCOUNT_TYPE_LOCAL`, spelled out to keep this decision testable off-device. */
const val LOCAL_ACCOUNT_TYPE = "LOCAL"

/** `Calendars.CAL_ACCESS_CONTRIBUTOR`: the lowest access level that may insert events. */
const val ACCESS_CONTRIBUTOR = 500

/**
 * Decides what the calendar chooser shows, and in what order.
 *
 * Writing an event needs at least [ACCESS_CONTRIBUTOR], so read-only calendars (a
 * subscribed 校历, a calendar shared read-only) are dropped rather than offered and then
 * failing. If that filter leaves nothing — some OEM providers report access level 0 for a
 * calendar the app can in fact write to — the full list is shown instead: offering
 * something that might fail beats offering nothing at all.
 *
 * Pure on purpose: this decides which calendar a whole semester lands in, so it is tested
 * without an emulator.
 */
fun pickerCalendars(rows: List<CalendarRow>): List<DeviceCalendar> {
    val alive = rows.filter { !it.deleted }
    val writable = alive.filter { it.accessLevel >= ACCESS_CONTRIBUTOR }
    val usable = writable.ifEmpty { alive }

    return usable
        .sortedWith(
            // Primary first, then the ones the student can actually see, then by name: the
            // calendar they look at every day should be the one at the top of the list.
            compareByDescending<CalendarRow> { it.isPrimary }
                .thenByDescending { it.visible }
                .thenBy { it.displayName.orEmpty().lowercase() },
        )
        .map { row ->
            DeviceCalendar(
                id = row.id,
                displayName = row.displayName?.takeIf { it.isNotBlank() } ?: "未命名日历",
                accountName = row.accountName,
                accountType = row.accountType,
                isPrimary = row.isPrimary,
            )
        }
}

/**
 * Writes the student's timetable into a calendar the student picked.
 *
 * **Why the student picks the calendar.** The previous implementation tried to *create* a
 * calendar of its own by inserting into `Calendars` with `ACCOUNT_NAME` / `OWNER_ACCOUNT`.
 * Only sync adapters may write those columns, so every attempt died with
 * `only sync adapters may write to account_name` and the feature never worked on a real
 * phone. There is no supported way for an ordinary app to create a calendar, so the honest
 * design is the one every other 课程表 app uses: list the device's calendars, let the
 * student choose one, and write events into it by `CALENDAR_ID`.
 *
 * Idempotency comes from `CUSTOM_APP_URI` rather than from owning the calendar: every row
 * this app writes carries [APP_URI], so a re-sync deletes exactly those rows — wherever
 * they are, including in a calendar the student has since switched away from — and never
 * touches an event the student created by hand.
 *
 * **Why the provider and not a `.ics` export.** Students expect a reminder that
 * actually rings and events that survive a reboot without the app running;
 * `CalendarContract` gives both, a shared file gives neither.
 *
 * All provider work runs on [Dispatchers.IO]. Instances hold only a [Context], so the
 * class is cheap to construct and safe to keep in `AppContainer`.
 */
class CalendarSync(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Every calendar on the device the student could register into, best candidate first.
     *
     * Returns an empty list when the calendar permission is missing; the UI says so instead
     * of claiming the phone has no calendars.
     */
    suspend fun availableCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext emptyList()

        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
            Calendars.VISIBLE,
            Calendars.DELETED,
        )
        val rows = ArrayList<CalendarRow>()
        runCatching {
            resolver.query(Calendars.CONTENT_URI, projection, null, null, null)
        }.getOrNull()?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Calendars._ID)
            val name = cursor.getColumnIndex(Calendars.CALENDAR_DISPLAY_NAME)
            val account = cursor.getColumnIndex(Calendars.ACCOUNT_NAME)
            val type = cursor.getColumnIndex(Calendars.ACCOUNT_TYPE)
            val access = cursor.getColumnIndex(Calendars.CALENDAR_ACCESS_LEVEL)
            val primary = cursor.getColumnIndex(Calendars.IS_PRIMARY)
            val visible = cursor.getColumnIndex(Calendars.VISIBLE)
            val deleted = cursor.getColumnIndex(Calendars.DELETED)
            while (cursor.moveToNext()) {
                rows += CalendarRow(
                    id = cursor.getLong(id),
                    displayName = name.takeIf { it >= 0 }?.let { cursor.getString(it) },
                    accountName = account.takeIf { it >= 0 }?.let { cursor.getString(it) },
                    accountType = type.takeIf { it >= 0 }?.let { cursor.getString(it) },
                    // An absent access level means "the provider does not grade calendars";
                    // treat it as writable rather than hiding every row.
                    accessLevel = access.takeIf { it >= 0 }?.let { cursor.getInt(it) }
                        ?: ACCESS_CONTRIBUTOR,
                    isPrimary = primary.takeIf { it >= 0 }?.let { cursor.getInt(it) == 1 } ?: false,
                    visible = visible.takeIf { it >= 0 }?.let { cursor.getInt(it) == 1 } ?: true,
                    deleted = deleted.takeIf { it >= 0 }?.let { cursor.getInt(it) == 1 } ?: false,
                )
            }
        }
        pickerCalendars(rows)
    }

    /**
     * Registers [sessions] in [calendarId], replacing whatever this app wrote previously.
     *
     * @param calendarId the calendar the student chose. It must still exist and be
     *   writable, otherwise the run fails with a message the student can act on.
     * @param courses courses the sessions belong to; sessions whose `courseId` is
     *   unknown are ignored instead of failing the whole sync.
     * @param fromDate first date to materialise, inclusive.
     * @param toDate last date to materialise, inclusive.
     * @param reminderMinutes lead time for the calendar reminder, or negative to
     *   register the classes without any reminder.
     */
    suspend fun sync(
        courses: List<Course>,
        sessions: List<CourseSession>,
        term: TermCalendar,
        schedule: PeriodSchedule,
        adjustments: ScheduleAdjustmentSet,
        calendarId: Long,
        scope: CalendarScope = CalendarScope.WHOLE_TERM,
        fromDate: LocalDate = term.firstWeekStart,
        toDate: LocalDate = term.endDate,
        reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
    ): CalendarSyncResult = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext CalendarSyncResult.NoPermission

        // 本周 narrows the analysis to those seven days; 整学期 keeps the caller's range.
        val (rangeStart, rangeEnd) = when (scope) {
            CalendarScope.WHOLE_TERM -> fromDate to toDate
            CalendarScope.CURRENT_WEEK -> registrationRange(term, scope)
        }

        val (occurrences, skippedHolidays) = try {
            analyzeSchedule(courses, sessions, term, schedule, adjustments, rangeStart, rangeEnd)
        } catch (t: Throwable) {
            return@withContext CalendarSyncResult.Failed("解析课表失败", t)
        }

        try {
            // The student may have deleted the calendar (or the whole account) since they
            // picked it. Checking up front turns "nothing appeared" into a sentence that
            // says why, instead of a provider exception.
            if (!isWritableCalendar(calendarId)) {
                return@withContext CalendarSyncResult.Failed(
                    "所选日历已不存在或不可写入，请重新选择",
                )
            }

            val zone = ZoneId.systemDefault()

            // Idempotency: this app's previous rows go first, so a re-sync after an
            // import change never leaves a stale 教室 or a course that was dropped. Scoped
            // by CUSTOM_APP_URI and NOT by calendar, so switching the target calendar
            // cannot leave a duplicate semester behind in the old one.
            clearAppEvents()

            val rows = when (scope) {
                // One plain event per class, no rule at all. Reusing the recurrence builder
                // here would describe the whole term: `buildEventSpecs` derives its RRULE from
                // the session's full week set, so a one-week analysis would come out as a
                // semester-long rule with an EXDATE for every week outside it.
                CalendarScope.CURRENT_WEEK -> weekSpecs(occurrences, schedule, zone, reminderMinutes)

                CalendarScope.WHOLE_TERM -> {
                    val byCourse = courses.associateBy { it.id }
                    val specs = ArrayList<CalendarEventSpec>(occurrences.size)
                    for (sessionOccurrences in occurrences.groupBy { it.session.id }.values) {
                        // Every occurrence of a group carries the same session, so the first
                        // is representative. The course is re-read from the import by id
                        // rather than trusted from the occurrence, so a renamed course is
                        // written under its current name.
                        val first = sessionOccurrences.first()
                        val course = byCourse[first.course.id] ?: continue
                        specs += buildEventSpecs(
                            course = course,
                            session = first.session,
                            occurrences = sessionOccurrences,
                            term = term,
                            schedule = schedule,
                            adjustments = adjustments,
                            zone = zone,
                            reminderMinutes = reminderMinutes,
                        )
                    }
                    specs
                }
            }

            val eventIds = writeEvents(calendarId, rows, reminderMinutes)

            // Nudge other calendar apps to re-read, so the classes appear without
            // waiting for their next periodic refresh. Best effort by design: some
            // providers do not implement this call, and that is not an error.
            runCatching { resolver.call(CalendarContract.CONTENT_URI, "sync", null, null) }

            CalendarSyncResult.Success(
                calendarId = calendarId,
                eventsInserted = eventIds.size,
                skippedHolidays = skippedHolidays,
            )
        } catch (se: SecurityException) {
            // The permission can be revoked between the check above and the write.
            CalendarSyncResult.NoPermission
        } catch (oae: OperationApplicationException) {
            // The provider refused the batch; usually a malformed RRULE or EXDATE.
            CalendarSyncResult.Failed(oae.message ?: "系统日历拒绝了课表数据", oae)
        } catch (t: Throwable) {
            CalendarSyncResult.Failed(t.message ?: "写入系统日历失败", t)
        }
    }

    /**
     * True when [calendarId] still exists and can be written to.
     *
     * Uses the same access-level rule as the picker, so "it was offered" and "it works"
     * cannot disagree.
     */
    private fun isWritableCalendar(calendarId: Long): Boolean {
        val cursor = runCatching {
            resolver.query(
                ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
                arrayOf(
                    Calendars.CALENDAR_ACCESS_LEVEL,
                    Calendars.DELETED,
                ),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return false

        return cursor.use {
            if (!it.moveToFirst()) {
                false
            } else {
                val access = it.getColumnIndex(Calendars.CALENDAR_ACCESS_LEVEL)
                val deleted = it.getColumnIndex(Calendars.DELETED)
                val level = access.takeIf { index -> index >= 0 }?.let { index -> it.getInt(index) }
                    ?: ACCESS_CONTRIBUTOR
                val gone = deleted.takeIf { index -> index >= 0 }?.let { index -> it.getInt(index) == 1 }
                    ?: false
                !gone && level >= ACCESS_CONTRIBUTOR
            }
        }
    }

    /**
     * Deletes every event this app has written, wherever it is, and reports how many went.
     *
     * Exposed to the settings screen as 从日历中移除课表. Without it the only way out for a
     * student who no longer wants the classes in their calendar is deleting them one by one
     * by hand — twenty taps for a single week, hundreds for a semester.
     */
    suspend fun removeWrittenEvents(): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext 0
        val removed = runCatching { clearAppEvents() }.getOrDefault(0)
        // Best effort, same as after a write: let other calendar apps notice the deletion.
        runCatching { resolver.call(CalendarContract.CONTENT_URI, "sync", null, null) }
        removed
    }

    /**
     * Deletes every event this app previously wrote, in any calendar, and returns the count.
     *
     * Deliberately narrow in *what* it matches and deliberately wide in *where*: rows are
     * matched on `CUSTOM_APP_URI`, so even if the student copies their own events into the
     * target calendar they survive, while the previous semester written to a different
     * calendar is still cleaned up instead of duplicating the new one. That safety property
     * is what makes an automatic re-sync acceptable at all.
     */
    private fun clearAppEvents(): Int {
        var removed = 0
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events.CUSTOM_APP_URI} = ?",
            arrayOf(APP_URI),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Events._ID)
            val ids = ArrayList<Long>(cursor.count)
            while (cursor.moveToNext()) ids.add(cursor.getLong(idColumn))
            for (id in ids) {
                // A provider is free to refuse a row (already gone, read-only calendar), so
                // the count comes from what was actually deleted rather than from what was
                // found.
                removed += runCatching {
                    resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
                }.getOrDefault(0)
            }
        }
        return removed
    }

    /**
     * Inserts [specs] and then their reminders.
     *
     * A single [ContentResolver.applyBatch] is used instead of one insert per row because
     * a semester is several hundred rows, and a per-row round trip through the provider is
     * the difference between "instant" and "why is this toggle spinning". The batch is
     * also the only way to get the generated ids back: `bulkInsert` reports just a row
     * count, and each reminder row needs the event id it belongs to.
     *
     * The whole batch runs in one provider transaction, so the id list is guaranteed to
     * line up with [specs] one-for-one — including when it comes back empty.
     *
     * @return the ids of the events actually created, in [specs] order.
     * @throws android.content.OperationApplicationException when the provider rejects the
     *   batch; the caller turns that into a [CalendarSyncResult.Failed].
     */
    private fun writeEvents(
        calendarId: Long,
        specs: List<CalendarEventSpec>,
        reminderMinutes: Int,
    ): List<Long> {
        if (specs.isEmpty()) return emptyList()

        // applyBatch demands this exact parameter type, so it cannot be a List.
        val operations = ArrayList<ContentProviderOperation>(specs.size)
        for (spec in specs) {
            operations += ContentProviderOperation
                .newInsert(Events.CONTENT_URI)
                .withValues(eventValues(calendarId, spec, reminderMinutes))
                .build()
        }

        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val eventIds = ArrayList<Long>(results.size)
        for (result in results) {
            // Only an insert yields a uri; a provider is free to report null for a row it
            // declined to create, and such a row simply gets no reminder.
            val uri = result.uri
            if (uri == null) continue
            val eventId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: continue
            eventIds.add(eventId)
        }

        if (reminderMinutes >= 0 && eventIds.isNotEmpty()) {
            insertReminders(eventIds, reminderMinutes)
        }
        return eventIds
    }

    /**
     * Attaches one reminder to each event.
     *
     * Failure here is swallowed once logged: the classes themselves are already in the
     * calendar by this point, and losing 提醒 is a far smaller harm than rolling the whole
     * registration back into an error the student cannot act on.
     */
    private fun insertReminders(eventIds: List<Long>, reminderMinutes: Int) {
        val operations = ArrayList<ContentProviderOperation>(eventIds.size)
        for (eventId in eventIds) {
            val values = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, reminderMinutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            operations += ContentProviderOperation
                .newInsert(Reminders.CONTENT_URI)
                .withValues(values)
                .build()
        }
        try {
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (t: Exception) {
            Log.w(TAG, "写入上课提醒失败，课程已注册但不会有提醒", t)
        }
    }

    private fun eventValues(
        calendarId: Long,
        spec: CalendarEventSpec,
        reminderMinutes: Int,
    ): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, spec.title)
        put(Events.DESCRIPTION, spec.description)
        spec.location?.let { put(Events.EVENT_LOCATION, it) }
        put(Events.DTSTART, spec.startMillis)
        put(Events.EVENT_TIMEZONE, spec.eventTimeZone)
        put(Events.ALL_DAY, 0)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        // Same colour the grid draws the course in, so the two views agree at a glance.
        put(Events.EVENT_COLOR, courseColor(spec.colorIndex))
        // The marker is what makes the next sync able to find exactly its own rows.
        put(Events.CUSTOM_APP_URI, APP_URI)
        put(Events.CUSTOM_APP_PACKAGE, context.packageName)

        // Exactly one of DTEND / DURATION, never both: a row carrying both is rejected,
        // and which one to use differs by kind. A recurring row needs DURATION because a
        // single DTEND cannot describe the end of each expanded occurrence, while a
        // standalone 补课 row can simply state its own DTEND.
        if (spec.rrule != null) {
            put(Events.RRULE, spec.rrule)
            put(Events.DURATION, durationString(spec.startMillis, spec.endMillis))
            spec.exdates?.takeIf { it.isNotEmpty() }?.let { exdates ->
                put(Events.EXDATE, exdates.joinToString(","))
            }
        } else {
            put(Events.DTEND, spec.endMillis)
        }

        // Written explicitly in both directions: the column defaults to 1 on some
        // providers, which would leave a silent phantom reminder on every class.
        put(Events.HAS_ALARM, if (reminderMinutes >= 0) 1 else 0)
    }

    /** RFC 5545 duration, e.g. `PT1H35M`. */
    private fun durationString(startMillis: Long, endMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes((endMillis - startMillis).coerceAtLeast(0L))
        if (minutes <= 0L) return "PT0M"
        val hours = minutes / 60
        val rest = minutes % 60
        return buildString {
            append("PT")
            if (hours > 0) append(hours).append('H')
            if (rest > 0) append(rest).append('M')
        }
    }

    private fun hasCalendarPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /**
         * Value stored in `CUSTOM_APP_URI`. It identifies the rows this app owns, so it
         * must stay stable across versions — changing it would orphan every event
         * written by an older build and leave them duplicating the new ones.
         */
        const val APP_URI = "com.ranorac.tjtimetable.calendar"

        /** Matches 极简课程表's default: a quarter of an hour before class. */
        const val DEFAULT_REMINDER_MINUTES = 15

        const val TAG = "CalendarSync"
    }
}
