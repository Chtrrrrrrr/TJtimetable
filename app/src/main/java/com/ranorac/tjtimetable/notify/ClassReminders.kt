package com.ranorac.tjtimetable.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ranorac.tjtimetable.MainActivity
import com.ranorac.tjtimetable.R
import com.ranorac.tjtimetable.TjApplication
import com.ranorac.tjtimetable.data.prefs.SettingsStore
import com.ranorac.tjtimetable.data.repo.TimetableRepository
import com.ranorac.tjtimetable.domain.ClassReminder
import com.ranorac.tjtimetable.domain.ReminderPlanner
import com.ranorac.tjtimetable.domain.Timetable
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * In-app class reminders.
 *
 * This complements the system-calendar route (see `CalendarSync`): the calendar
 * only reminds the student if they actually use a calendar app, whereas these are
 * notifications from TJ课表 itself.
 *
 * The scheduling *policy* lives in [ReminderPlanner] as pure logic, so what is
 * left here is only plumbing: WorkManager only accepts a delay, not a cron, so the
 * app arms exactly one one-time work item for the next reminder and each run arms
 * the following one. That keeps the queue at one item regardless of term length,
 * and means a delayed run (Doze, batching) cannot desynchronise a long chain.
 */
object ReminderScheduler {

    private const val UNIQUE_WORK = "tj_class_reminder"

    const val CHANNEL_ID = "class_reminders"

    /**
     * Creates the notification channel; safe to call repeatedly.
     *
     * No `SDK_INT >= O` guard: `minSdk` is 26, which *is* O, so the check can never
     * be false and would only suggest the app still supports pre-channel Android.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                // HIGH so it can appear as a heads-up banner: a reminder that only
                // shows in the shade after class has started is worthless.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            },
        )
    }

    /** True when notifications can actually be posted, so callers can explain why not. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Arms the next reminder, replacing any previously armed one.
     *
     * [ExistingWorkPolicy.REPLACE] is what makes this idempotent: called after every
     * import, 调休 edit or settings change, it always leaves exactly one pending item
     * pointing at the current next reminder.
     */
    fun reschedule(
        context: Context,
        timetable: Timetable,
        leadMinutes: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val workManager = WorkManager.getInstance(context)
        val next = ReminderPlanner.next(timetable, now, leadMinutes)
        if (next == null) {
            // Nothing left in the horizon; the worker re-arms on the next import.
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val delay = Duration.between(now, next.triggerAt).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ClassReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    /** Posts one notification per reminder. */
    fun notify(context: Context, reminders: List<ClassReminder>, leadMinutes: Int): Int {
        if (reminders.isEmpty() || !canNotify(context)) return 0
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            // IMMUTABLE is required from API 31; a mutable intent here would throw.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        var posted = 0
        for (reminder in reminders) {
            val title = if (leadMinutes <= 0) {
                context.getString(R.string.reminder_now, reminder.courseName)
            } else {
                context.getString(R.string.reminder_soon, leadMinutes, reminder.courseName)
            }
            val body = buildString {
                append(reminder.startTime?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "—")
                reminder.room?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                append(" · ").append(reminder.occurrence.unitLabel)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()

            // A stable id per (date, 节, course) so a re-run replaces rather than duplicates,
            // while two different courses in the same 节 on the same date do not collide.
            // The course name is mixed in because that is the only part of the identity
            // guaranteed to differ between them: two courses can share a date and a 节 (a
            // 补课日 clash, or simply two classes the student takes in parallel) and the
            // second notification used to overwrite the first.
            val id = notificationId(reminder.occurrence.date, reminder.occurrence.startUnit, reminder.courseName)

            // Checked again here, not just in canNotify() above: lint's MissingPermission
            // analysis cannot see through a helper, and on API 33+ notify() genuinely
            // throws without POST_NOTIFICATIONS. Losing a reminder is far better than
            // crashing the worker, so both an explicit check and an explicit catch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                break
            }
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
                posted++
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call.
                break
            }
        }
        return posted
    }
}

/**
 * A stable notification id for one class meeting.
 *
 * It has to be stable so that a re-run REPLACES its notification instead of stacking a second
 * copy, and it has to be distinct per meeting so that two classes in the same 节 on the same
 * date do not collide — the previous `(epochDay % 100_000) * 100 + startUnit` did exactly that,
 * and the second course silently replaced the first. [courseName] is the discriminator because
 * it is the only part of a meeting's identity a student can tell apart; a stable string hash
 * rather than `hashCode()` keeps the id identical across processes.
 *
 * Kept `internal` and pure so the collision can be asserted in a plain JVM test.
 */
internal fun notificationId(date: LocalDate, startUnit: Int, courseName: String): Int {
    val day = (date.toEpochDay() % 100_000L).toInt()
    var hash = FNV_OFFSET
    for (ch in courseName) {
        hash = hash xor ch.code
        hash *= FNV_PRIME
    }
    // Fold the full 31 bits of the hash in, not just one byte: two courses must differ even
    // when their names agree except in a late character, and `Int` ids have room for it.
    return ((day * 31 + startUnit) and 0x3FFF_FFFF) * 31 + (hash and 0x1FFF_FFFF) and 0x7FFF_FFFF
}

private const val FNV_OFFSET = -2_128_831_035
private const val FNV_PRIME = 16_777_619

/**
 * Fires at one reminder's trigger time, posts anything due, then arms the next.
 *
 * Re-planning from the database on every run (rather than trusting a precomputed
 * list) is what makes an import or a 调休 edit take effect immediately instead of
 * waiting for the previously armed item to drain.
 */
class ClassReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? TjApplication)?.container
            ?: return Result.success()
        val settings = container.settingsStore.settings.first()
        if (!settings.remindersEnabled) return Result.success()

        val timetable = container.repository.observeTimetable().first()
        if (timetable == null || timetable.isEmpty) return Result.success()

        val now = LocalDateTime.now()
        val due = ReminderPlanner.due(timetable, now, settings.reminderLeadMinutes)
        ReminderScheduler.notify(applicationContext, due, settings.reminderLeadMinutes)

        // Arm whatever comes next; the timetable may have changed since we were queued.
        ReminderScheduler.reschedule(
            applicationContext,
            timetable,
            settings.reminderLeadMinutes,
            now,
        )
        return Result.success()
    }
}

/**
 * Re-arms reminders after anything that changes the timetable or the settings.
 *
 * Kept as its own class so the repository does not have to know about WorkManager:
 * the repository exposes a plain "something changed" callback and this decides what
 * that means for scheduling.
 */
class ReminderCoordinator(
    private val context: Context,
    private val repository: TimetableRepository,
    private val settingsStore: SettingsStore,
) {

    /** Cancels reminders when they are off or there is nothing to remind about. */
    suspend fun refresh() {
        val settings = settingsStore.settings.first()
        val timetable = repository.observeTimetable().first()
        if (!settings.remindersEnabled || timetable == null || timetable.isEmpty) {
            ReminderScheduler.cancel(context)
            return
        }
        ReminderScheduler.reschedule(context, timetable, settings.reminderLeadMinutes)
    }
}
