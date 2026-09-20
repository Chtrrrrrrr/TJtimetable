package com.ranorac.tjtimetable.domain

import java.time.Duration
import java.time.LocalDateTime

/** A class the student should be reminded about, and when the reminder should fire. */
data class ClassReminder(
    val occurrence: ClassOccurrence,
    /** Wall-clock instant the notification should be posted. */
    val triggerAt: LocalDateTime,
    val leadMinutes: Int,
) {
    val courseName: String get() = occurrence.course.name

    val room: String? get() = occurrence.room

    val startTime: java.time.LocalTime? get() = occurrence.startTime
}

/**
 * Decides when to remind the student about upcoming classes.
 *
 * Deliberately pure and clock-free — the caller passes `now` — so the entire
 * scheduling policy is unit-testable without WorkManager, a device, or a real
 * clock. What remains untestable is then only the thin plumbing that hands these
 * results to WorkManager.
 *
 * Reminders are derived from [TimetableResolver], not from raw sessions, so
 * holidays, 补课日 and 单双周 are all respected for free: a class that does not
 * actually run on a date can never produce a reminder.
 */
object ReminderPlanner {

    /**
     * How far ahead to plan.
     *
     * The timetable is re-planned whenever it changes, so a long horizon buys
     * little; a bounded one keeps the plan cheap to rebuild.
     */
    const val DEFAULT_HORIZON_DAYS = 14L

    /**
     * Cap on returned reminders. A term holds hundreds of meetings, and there is
     * no value in queueing far-future ones when any import or 调休 edit re-plans
     * from scratch.
     */
    const val DEFAULT_MAX_REMINDERS = 40

    /** Longest lead time accepted, so a bad setting cannot schedule days ahead. */
    const val MAX_LEAD_MINUTES = 24 * 60

    /**
     * How long past its trigger a reminder may still be posted, in minutes.
     *
     * This has to be measured against what actually defers a worker, not against the
     * precision of a scheduler. WorkManager's own minimum interval is 15 minutes and it
     * batches and defers freely under Doze or app standby, so a window of a few minutes
     * is not "tolerance", it is a coin flip: the worker runs late, [due] finds nothing,
     * and — because the worker then arms the *next* reminder and [plan] never returns a
     * past trigger — that class can never be reminded about again. Silently losing one
     * reminder is the single failure mode this feature must not have.
     *
     * 30 minutes is comfortably longer than a realistic deferral and still short enough
     * that a reminder never refers to a class the student has already missed: [due] also
     * refuses to fire once the class itself has started, whichever bound is tighter.
     */
    const val DEFAULT_DUE_WINDOW_MINUTES = 30L

    /**
     * Upcoming reminders after [now], ordered by trigger time.
     *
     * Occurrences with no known start time are skipped: without a 作息 entry there
     * is no instant to fire at, and guessing one would produce a reminder at the
     * wrong time.
     */
    fun plan(
        timetable: Timetable,
        now: LocalDateTime,
        leadMinutes: Int,
        horizonDays: Long = DEFAULT_HORIZON_DAYS,
        maxReminders: Int = DEFAULT_MAX_REMINDERS,
    ): List<ClassReminder> {
        if (timetable.isEmpty) return emptyList()
        val lead = leadMinutes.coerceIn(0, MAX_LEAD_MINUTES)
        val from = now.toLocalDate()
        val to = from.plusDays(horizonDays.coerceAtLeast(0))

        return TimetableResolver.occurrencesBetween(timetable, from, to)
            .mapNotNull { reminderFor(it, lead, now) }
            .sortedBy { it.triggerAt }
            // `coerceAtLeast(0)` and not `(1)`: a cap has to be able to express
            // "none", otherwise a caller asking for zero silently gets one.
            .take(maxReminders.coerceAtLeast(0))
    }

    /** The single next reminder — all the scheduler needs in order to arm itself. */
    fun next(
        timetable: Timetable,
        now: LocalDateTime,
        leadMinutes: Int,
    ): ClassReminder? = plan(timetable, now, leadMinutes, maxReminders = 1).firstOrNull()

    /**
     * Reminders whose trigger time has arrived at [now], within a tolerance window.
     *
     * WorkManager runs late (Doze, batching), so the worker looks for a *window*
     * rather than an exact instant. Without the window a delayed run would silently
     * skip a class, which is the one failure a reminder must never have — hence
     * [DEFAULT_DUE_WINDOW_MINUTES], not a token few minutes.
     *
     * The window is additionally capped at the class's own start: a notification that
     * arrives after the lecture began is a report rather than a reminder. Whichever bound is
     * tighter wins, so with any real lead the class start is what binds — the window only has
     * to be long enough not to bind first, which is exactly what the old 5-minute default got
     * wrong.
     *
     * Both today and tomorrow are scanned because a large lead time can push a
     * next-morning class's trigger into today.
     *
     * Note the asymmetry with [plan]: a trigger exactly at [now] is *excluded* there
     * (it requires `isAfter`) but *included* here, which is correct — this method
     * asks "is it time to fire?", and it is.
     */
    fun due(
        timetable: Timetable,
        now: LocalDateTime,
        leadMinutes: Int,
        windowMinutes: Long = DEFAULT_DUE_WINDOW_MINUTES,
    ): List<ClassReminder> {
        if (timetable.isEmpty) return emptyList()
        val lead = leadMinutes.coerceIn(0, MAX_LEAD_MINUTES)
        val window = windowMinutes.coerceAtLeast(0)
        val today = now.toLocalDate()

        return TimetableResolver.occurrencesBetween(timetable, today, today.plusDays(1))
            .mapNotNull { occurrence ->
                val start = occurrence.startTime ?: return@mapNotNull null
                val classStart = LocalDateTime.of(occurrence.date, start)
                val trigger = classStart.minusMinutes(lead.toLong())
                val elapsed = Duration.between(trigger, now).toMinutes()
                // Two bounds, and the second one is what the fix is about. `elapsed` is
                // measured from the trigger, which already sits `lead` minutes before the
                // class, so the class-start bound caps lateness at the lead *and* the window
                // rule caps it at `window` — whichever is tighter wins.
                //
                // Capping at the class start rather than at a fixed few minutes is the whole
                // point: a reminder may be posted at any point between its trigger and the
                // class itself, so a worker deferred by Doze still delivers it. The old
                // 5-minute window ended the window while the student still had 10 minutes of
                // warning left, and the class could never be reminded about again because the
                // worker arms the *next* reminder and `plan` never returns a past trigger.
                val beforeClass = !now.isAfter(classStart)
                // `toMinutes()` truncates toward zero, so this also accepts a trigger
                // up to 59 seconds in the FUTURE (elapsed == 0). That is deliberate:
                // firing marginally early is harmless, whereas the alternative —
                // demanding elapsed >= 1 — would drop a reminder whenever the worker
                // happened to run a second before the trigger.
                if (elapsed < 0 || elapsed > window || !beforeClass) return@mapNotNull null
                ClassReminder(occurrence, trigger, lead)
            }
            .sortedBy { it.triggerAt }
    }

    private fun reminderFor(
        occurrence: ClassOccurrence,
        lead: Int,
        now: LocalDateTime,
    ): ClassReminder? {
        val start = occurrence.startTime ?: return null
        val trigger = LocalDateTime.of(occurrence.date, start).minusMinutes(lead.toLong())
        // A trigger in the past is useless, and a class that has already begun
        // must not produce a late notification.
        if (!trigger.isAfter(now)) return null
        return ClassReminder(occurrence, trigger, lead)
    }
}
