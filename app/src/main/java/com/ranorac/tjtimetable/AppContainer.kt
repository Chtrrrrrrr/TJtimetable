package com.ranorac.tjtimetable

import android.content.Context
import com.ranorac.tjtimetable.calendar.CalendarSync
import com.ranorac.tjtimetable.data.db.TimetableDatabase
import com.ranorac.tjtimetable.data.prefs.CredentialsStore
import com.ranorac.tjtimetable.data.prefs.SettingsStore
import com.ranorac.tjtimetable.data.remote.TongjiApi
import com.ranorac.tjtimetable.data.repo.TimetableRepository
import com.ranorac.tjtimetable.notify.ReminderCoordinator
import com.ranorac.tjtimetable.widget.TimetableWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled service locator.
 *
 * Held by [TjApplication] and reached from `ViewModelProvider.Factory` in the UI
 * layer. Everything here is either stateless or backed by DataStore/Room, so
 * construction is cheap and can stay lazy. A DI framework would add build time
 * and indirection without buying anything at this size.
 */
class AppContainer(context: Context) {

    /** Application context; safe to hand to a ViewModel for ContentResolver work. */
    val appContext: Context = context.applicationContext

    /**
     * Application-lifetime scope for work that must outlive a screen but must not
     * block whoever triggered it — refreshing the widget and re-arming reminders
     * are both fire-and-forget consequences of a database write.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TimetableDatabase by lazy { TimetableDatabase.build(appContext) }

    val api: TongjiApi by lazy { TongjiApi() }

    val credentialsStore: CredentialsStore by lazy { CredentialsStore(appContext) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val calendarSync: CalendarSync by lazy { CalendarSync(appContext) }

    /**
     * Lazy and referenced only from callbacks, so building it never races with the
     * repository it depends on: the callback below runs long after construction.
     */
    val reminderCoordinator: ReminderCoordinator by lazy {
        ReminderCoordinator(appContext, repository, settingsStore)
    }

    val repository: TimetableRepository by lazy {
        TimetableRepository(database, api, credentialsStore, calendarSync) {
            // Nudge the home-screen widget so an import, a 调休 edit or a colour
            // change shows up on the launcher immediately, instead of waiting for
            // the 30-minute updatePeriodMillis tick.
            TimetableWidget.refresh(appContext)
            // Re-arm reminders: the next class may have changed entirely.
            refreshReminders()
        }
    }

    /** Re-arms class reminders; safe to call at any time. */
    fun refreshReminders() {
        appScope.launch {
            // A scheduling failure must never surface as a crash in a write path.
            runCatching { reminderCoordinator.refresh() }
        }
    }
}
