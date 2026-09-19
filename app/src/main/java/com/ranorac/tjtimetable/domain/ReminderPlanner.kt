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
     * skip a class, which is the one failure a reminder must never have.
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
        windowMinutes: Long = 5,
    ): List<ClassReminder> {
        if (timetable.isEmpty) return emptyList()
        val lead = leadMinutes.coerceIn(0, MAX_LEAD_MINUTES)
        val window = windowMinutes.coerceAtLeast(0)
        val today = now.toLocalDate()

        return TimetableResolver.occurrencesBetween(timetable, today, today.plusDays(1))
            .mapNotNull { occurrence ->
                val start = occurrence.startTime ?: return@mapNotNull null
                val trigger = LocalDateTime.of(occurrence.date, start).minusMinutes(lead.toLong())
                val elapsed = Duration.between(trigger, now).toMinutes()
                // `toMinutes()` truncates toward zero, so this also accepts a trigger
                // up to 59 seconds in the FUTURE (elapsed == 0). That is deliberate:
                // firing marginally early is harmless, whereas the alternative —
                // demanding elapsed >= 1 — would drop a reminder whenever the worker
                // happened to run a second before the trigger.
                if (elapsed < 0 || elapsed > window) return@mapNotNull null
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
