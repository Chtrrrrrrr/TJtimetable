package com.ranorac.tjtimetable.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ranorac.tjtimetable.calendar.CalendarScope
import com.ranorac.tjtimetable.calendar.DeviceCalendar
import com.ranorac.tjtimetable.data.prefs.AppSettings
import com.ranorac.tjtimetable.data.prefs.Credentials
import com.ranorac.tjtimetable.data.prefs.SettingsStore
import com.ranorac.tjtimetable.data.repo.CalendarRegistration
import com.ranorac.tjtimetable.data.repo.IcsImport
import com.ranorac.tjtimetable.data.repo.ImportOutcome
import com.ranorac.tjtimetable.data.repo.ScrapeImport
import com.ranorac.tjtimetable.data.repo.TimetableRepository
import com.ranorac.tjtimetable.notify.ReminderCoordinator
import com.ranorac.tjtimetable.notify.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    /**
     * The stored credentials, or `null` while they have not been read yet.
     *
     * Nullable on purpose, and the nullability is load-bearing: the initial value of
     * [SettingsViewModel.uiState] is emitted *before* DataStore has produced anything, so a
     * non-null default would be indistinguishable from "the student has saved nothing".
     * The settings screen seeds its text fields from this value, and if it seeded from an
     * empty default it would latch the empty strings — rendering blank fields over saved
     * credentials and, if 保存 was then pressed, overwriting them with "".
     */
    val credentials: Credentials? = null,
    val settings: AppSettings = AppSettings(),
    val busy: Boolean = false,
    /** The device calendars offered by the picker; empty until it has been opened. */
    val calendarOptions: List<DeviceCalendar> = emptyList(),
    /** True while the calendar chooser is on screen. */
    val pickingCalendar: Boolean = false,
    val message: String? = null,
)

/**
 * Backs the settings screen.
 *
 * Credentials and preferences are two different stores with two different
 * lifetimes — the token is ephemeral and excluded from backup, the preferences
 * are not — so they stay separate here and are combined only for display.
 */
class SettingsViewModel(
    private val appContext: android.content.Context,
    private val repository: TimetableRepository,
    private val settingsStore: SettingsStore,
    private val reminderCoordinator: ReminderCoordinator,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    /** The calendar picker's transient state: what it shows, and whether it is showing. */
    private data class PickerState(
        val options: List<DeviceCalendar> = emptyList(),
        val open: Boolean = false,
    )

    private val picker = MutableStateFlow(PickerState())

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.observeCredentials(),
        settingsStore.settings,
        busy,
        message,
        picker,
    ) { credentials, settings, isBusy, msg, pickerState ->
        SettingsUiState(
            credentials = credentials,
            settings = settings,
            busy = isBusy,
            calendarOptions = pickerState.options,
            pickingCalendar = pickerState.open,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun saveCredentials(clientId: String, clientSecret: String, userId: String, userName: String) {
        viewModelScope.launch {
            repository.saveClient(clientId, clientSecret)
            repository.saveStudent(userId, userName.ifBlank { null })
            message.value = "已保存"
        }
    }

    fun importNow() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = when (val outcome = repository.importCurrentSemester()) {
                // Built with buildString rather than "a" + if (...) ... : a line
                // starting with '+' is parsed by Kotlin as a NEW statement
                // (unary plus), not as a continuation of the previous line.
                is ImportOutcome.Success -> buildString {
                    append("已导入 ${outcome.courseCount} 门课程、${outcome.sessionCount} 个时段")
                    if (outcome.warnings.isNotEmpty()) {
                        append("（${outcome.warnings.size} 条提示）")
                    }
                }
                ImportOutcome.NotConfigured -> "请先填写 client_id 与学号并保存"
                ImportOutcome.Unauthorized -> "登录已失效，请重新授权"
                is ImportOutcome.Failed -> outcome.message
            }
            busy.value = false
        }
    }

    /**
     * Registers the timetable into the chosen system calendar.
     *
     * With no calendar chosen yet this opens the picker instead of guessing: an app cannot
     * create a calendar of its own, so the student has to name a target once.
     */
    fun registerToCalendar() {
        if (busy.value) return
        val target = uiState.value.settings.calendarId
        val name = uiState.value.settings.calendarName
        if (target == null) {
            openCalendarPicker()
            return
        }
        viewModelScope.launch {
            writeToCalendar(target, name, uiState.value.settings.calendarScope.toScope())
        }
    }

    /** Remembers how much of the term a registration writes. */
    fun setCalendarScope(scope: String) {
        viewModelScope.launch { settingsStore.setCalendarScope(scope) }
    }

    /**
     * Deletes everything this app wrote to the calendar, without writing anything new.
     *
     * The counterpart to 注册：a student who only wanted this week's classes on their lock
     * screen should not have to remove twenty events by hand when they are done with them.
     * Only rows carrying this app's marker are touched, so their own events are never at risk.
     */
    fun removeFromCalendar() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val removed = repository.removeTimetableFromCalendar()
            busy.value = false
            message.value = if (removed > 0) {
                "已从系统日历移除 $removed 个日程"
            } else {
                "日历里没有本应用写入的日程"
            }
        }
    }

    /** Loads the device's calendars and shows the chooser. */
    fun openCalendarPicker() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val calendars = repository.availableCalendars()
            busy.value = false
            if (calendars.isEmpty()) {
                message.value = "没有找到可写入的日历。请先在系统日历应用里添加一个账户（或本地日历）"
                return@launch
            }
            picker.value = PickerState(options = calendars, open = true)
        }
    }

    fun dismissCalendarPicker() {
        picker.value = picker.value.copy(open = false)
    }

    /**
     * Remembers the student's choice and registers into it straight away.
     *
     * The chosen id is passed to [writeToCalendar] explicitly rather than read back from
     * settings: the DataStore write is a suspend call whose new value reaches the UI state
     * asynchronously, so reading it here could still see null and re-open the picker.
     */
    fun chooseCalendar(calendar: DeviceCalendar) {
        viewModelScope.launch {
            settingsStore.setCalendarTarget(calendar.id, calendar.displayName)
            picker.value = picker.value.copy(open = false)
            writeToCalendar(calendar.id, calendar.displayName, uiState.value.settings.calendarScope.toScope())
        }
    }

    private suspend fun writeToCalendar(
        calendarId: Long,
        calendarName: String?,
        scope: CalendarScope,
    ) {
        if (busy.value) return
        busy.value = true
        val scopeLabel = if (scope == CalendarScope.CURRENT_WEEK) "本周" else "整学期"
        message.value = when (val result = repository.registerCurrentTermToCalendar(calendarId, scope)) {
            is CalendarRegistration.Done ->
                "已写入「${calendarName ?: "所选日历"}」$scopeLabel：${result.events} 个日程" +
                    if (result.skippedHolidays > 0) "（跳过 ${result.skippedHolidays} 个节假日时段）" else ""
            CalendarRegistration.NoPermission -> "需要日历读写权限才能注册"
            CalendarRegistration.NoTimetable -> "请先导入课表"
            is CalendarRegistration.Failed -> "日历注册失败：${result.message}"
        }
        busy.value = false
    }

    private fun String.toScope(): CalendarScope =
        if (this == "term") CalendarScope.WHOLE_TERM else CalendarScope.CURRENT_WEEK

    // ---------------------------------------------------- 教务系统抓取 / 粘贴

    /**
     * Imports a course-table response the student copied out of a desktop browser's
     * DevTools (F12 → Network → the timetable request → Copy response).
     *
     * This is the no-WebView, no-credential path: it needs nothing but a text blob, so
     * it works even where the in-app browser cannot capture, and it is the only route
     * that can be exercised without a device.
     */
    fun importPastedResponse(text: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = when (val r = repository.importCapturedResponse(url = null, body = text)) {
                is ScrapeImport.Success -> buildString {
                    append("已导入 ${r.courseCount} 门课程、${r.sessionCount} 个时段")
                    if (r.warnings.isNotEmpty()) append("（${r.warnings.size} 条提示）")
                }
                ScrapeImport.NotTimetable ->
                    "这段内容里没有识别到课表数据，请确认复制的是课表请求的响应体"
                is ScrapeImport.Failed -> r.message
            }
            busy.value = false
        }
    }

    /** Imports a response captured by the in-app login browser. */
    fun importCaptured(url: String?, body: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = when (val r = repository.importCapturedResponse(url, body)) {
                is ScrapeImport.Success -> buildString {
                    append("已从教务系统导入 ${r.courseCount} 门课程、${r.sessionCount} 个时段")
                    if (r.warnings.isNotEmpty()) append("（${r.warnings.size} 条提示）")
                }
                ScrapeImport.NotTimetable -> "读到的内容不是课表数据，请重试或在课表页刷新"
                is ScrapeImport.Failed -> r.message
            }
            busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
            message.value = "已退出登录，本地课表仍保留"
        }
    }

    // ---------------------------------------------------- 日历文件导入导出

    /**
     * Writes the current timetable to a document the student picked via SAF.
     *
     * A ViewModel doing I/O through ContentResolver is deliberate here: the
     * alternative is threading a `Uri` and a suspending write back into the
     * composable, and the Application context makes this leak-free.
     */
    fun exportIcsTo(uri: android.net.Uri) {
        viewModelScope.launch {
            busy.value = true
            val ics = repository.exportIcs()
            message.value = if (ics == null) {
                "当前没有课表可导出，请先导入或手动添加课程"
            } else {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(ics.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }.fold(
                    onSuccess = { "已导出日历文件，可导入手机自带日历" },
                    onFailure = { "导出失败：${it.message ?: it::class.java.simpleName}" },
                )
            }
            busy.value = false
        }
    }

    /** Reads a student-picked `.ics` file and replaces the local timetable with it. */
    fun importIcsFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching {
                val text = appContext.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")

                when (val result = repository.importIcs(text)) {
                    is IcsImport.Success -> buildString {
                        append("已导入 ${result.courseCount} 门课程、${result.sessionCount} 个时段")
                        if (result.warnings.isNotEmpty()) {
                            append("（${result.warnings.size} 条提示）")
                        }
                    }
                    is IcsImport.Failed -> result.message
                }
            }.getOrElse { "导入失败：${it.message ?: it::class.java.simpleName}" }
            busy.value = false
        }
    }

    // ---- preference writes ----

    fun setTheme(mode: String) = viewModelScope.launch { settingsStore.setThemeMode(mode) }

    fun setShowOddEvenBadge(v: Boolean) = viewModelScope.launch { settingsStore.setShowOddEvenBadge(v) }

    fun setDimInactive(v: Boolean) = viewModelScope.launch { settingsStore.setDimInactiveCourses(v) }

    fun setShowWeekend(v: Boolean) = viewModelScope.launch { settingsStore.setShowWeekend(v) }

    fun setStartOnToday(v: Boolean) = viewModelScope.launch { settingsStore.setStartOnToday(v) }

    fun setRemindersEnabled(v: Boolean) {
        viewModelScope.launch {
            settingsStore.setRemindersEnabled(v)
            // Re-arm (or cancel) immediately rather than at the next app start.
            reminderCoordinator.refresh()
        }
    }

    fun setReminderLead(minutes: Int) {
        viewModelScope.launch {
            settingsStore.setReminderLeadMinutes(minutes)
            // The next reminder's trigger time just moved.
            reminderCoordinator.refresh()
        }
    }

    /**
     * Whether the OS will actually deliver reminders.
     *
     * Exposed so the screen can say "reminders are on but blocked" instead of
     * showing an enabled switch that silently does nothing.
     */
    fun notificationsAllowed(): Boolean = ReminderScheduler.canNotify(appContext)

    fun consumeMessage() {
        message.value = null
    }

    class Factory(
        private val appContext: android.content.Context,
        private val repository: TimetableRepository,
        private val settingsStore: SettingsStore,
        private val reminderCoordinator: ReminderCoordinator,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(
                appContext,
                repository,
                settingsStore,
                reminderCoordinator,
            ) as T
        }
    }
}
