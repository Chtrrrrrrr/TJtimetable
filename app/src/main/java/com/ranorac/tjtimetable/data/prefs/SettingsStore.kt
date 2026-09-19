package com.ranorac.tjtimetable.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tj_settings",
)

/** Where the app reads settings from; see [SettingsStore] for the typed API. */
enum class WeekStart { MONDAY, SUNDAY }

/**
 * Non-secret user settings.
 *
 * Credentials and tokens deliberately live elsewhere (a separate DataStore
 * excluded from backup) so that backing up preferences can never leak a token.
 */
data class AppSettings(
    val themeMode: String = "system",
    val weekStart: WeekStart = WeekStart.MONDAY,
    /** Show 单双周 badges and split odd/even weeks. */
    val showOddEvenBadge: Boolean = true,
    /** Dim courses that do not run in the currently viewed week. */
    val dimInactiveCourses: Boolean = true,
    /** Start the grid on today's weekday rather than Monday. */
    val startOnToday: Boolean = false,
    /** Minutes before a class starts that the reminder fires. */
    val reminderLeadMinutes: Int = 15,
    val remindersEnabled: Boolean = true,
    /** Show the weekend columns. */
    val showWeekend: Boolean = true,
    /**
     * The system calendar the timetable is registered into, or null when the student has
     * not picked one yet.
     *
     * An app cannot create a calendar, so this always names one that already exists on the
     * device; [calendarName] is kept only so the settings row can show what was chosen
     * without querying the provider on every recomposition.
     */
    val calendarId: Long? = null,
    val calendarName: String? = null,
    /**
     * How much of the term 日历注册 writes: `"week"` (default) or `"term"`.
     *
     * Stored as a string rather than the enum so a value written by a newer build that this
     * one does not know still degrades to the default instead of crashing on `valueOf`.
     */
    val calendarScope: String = "week",
)

/**
 * Typed wrapper over DataStore for [AppSettings].
 *
 * Every write goes through [DataStore.edit] so updates are atomic, and reads are
 * exposed as a cold [Flow] that the UI collects with `collectAsStateWithLifecycle`.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[KEY_THEME_MODE] ?: "system",
            weekStart = runCatching {
                WeekStart.valueOf(prefs[KEY_WEEK_START] ?: WeekStart.MONDAY.name)
            }.getOrDefault(WeekStart.MONDAY),
            showOddEvenBadge = prefs[KEY_SHOW_ODD_EVEN] ?: true,
            dimInactiveCourses = prefs[KEY_DIM_INACTIVE] ?: true,
            startOnToday = prefs[KEY_START_ON_TODAY] ?: false,
            reminderLeadMinutes = prefs[KEY_REMINDER_LEAD] ?: 15,
            remindersEnabled = prefs[KEY_REMINDERS] ?: true,
            showWeekend = prefs[KEY_SHOW_WEEKEND] ?: true,
            calendarId = prefs[KEY_CALENDAR_ID],
            calendarName = prefs[KEY_CALENDAR_NAME],
            calendarScope = prefs[KEY_CALENDAR_SCOPE] ?: "week",
        )
    }

    suspend fun setThemeMode(mode: String) = edit { it[KEY_THEME_MODE] = mode }

    suspend fun setWeekStart(start: WeekStart) = edit { it[KEY_WEEK_START] = start.name }

    suspend fun setShowOddEvenBadge(enabled: Boolean) = edit { it[KEY_SHOW_ODD_EVEN] = enabled }

    suspend fun setDimInactiveCourses(enabled: Boolean) = edit { it[KEY_DIM_INACTIVE] = enabled }

    suspend fun setStartOnToday(enabled: Boolean) = edit { it[KEY_START_ON_TODAY] = enabled }

    suspend fun setReminderLeadMinutes(minutes: Int) =
        edit { it[KEY_REMINDER_LEAD] = minutes.coerceIn(0, 120) }

    suspend fun setRemindersEnabled(enabled: Boolean) = edit { it[KEY_REMINDERS] = enabled }

    suspend fun setShowWeekend(enabled: Boolean) = edit { it[KEY_SHOW_WEEKEND] = enabled }

    /** Remembers the calendar the student chose for 日历注册. */
    suspend fun setCalendarTarget(id: Long, name: String) = edit {
        it[KEY_CALENDAR_ID] = id
        it[KEY_CALENDAR_NAME] = name
    }

    /** Remembers how much of the term 日历注册 writes (`"week"` or `"term"`). */
    suspend fun setCalendarScope(scope: String) = edit {
        it[KEY_CALENDAR_SCOPE] = if (scope == "term") "term" else "week"
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_WEEK_START = stringPreferencesKey("week_start")
        val KEY_SHOW_ODD_EVEN = booleanPreferencesKey("show_odd_even_badge")
        val KEY_DIM_INACTIVE = booleanPreferencesKey("dim_inactive_courses")
        val KEY_START_ON_TODAY = booleanPreferencesKey("start_on_today")
        val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val KEY_REMINDERS = booleanPreferencesKey("reminders_enabled")
        val KEY_SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val KEY_CALENDAR_ID = longPreferencesKey("calendar_id")
        val KEY_CALENDAR_NAME = stringPreferencesKey("calendar_name")
        val KEY_CALENDAR_SCOPE = stringPreferencesKey("calendar_scope")
    }
}
