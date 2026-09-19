package com.ranorac.tjtimetable.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ranorac.tjtimetable.calendar.DeviceCalendar
import com.ranorac.tjtimetable.data.prefs.AppSettings
import com.ranorac.tjtimetable.data.prefs.Credentials
import com.ranorac.tjtimetable.domain.AdjustmentSource
import com.ranorac.tjtimetable.domain.CalendarDayKind
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.DayAdjustment
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.WeekPattern
import com.ranorac.tjtimetable.ui.calendar.AdjustmentScreen
import com.ranorac.tjtimetable.ui.calendar.AdjustmentUiState
import com.ranorac.tjtimetable.ui.calendar.DayRow
import com.ranorac.tjtimetable.ui.calendar.WeekRow
import com.ranorac.tjtimetable.ui.components.AppBottomBar
import com.ranorac.tjtimetable.ui.components.BOTTOM_BAR_TAG
import com.ranorac.tjtimetable.ui.settings.SettingsScreen
import com.ranorac.tjtimetable.ui.settings.SettingsUiState
import com.ranorac.tjtimetable.ui.theme.TJTimetableTheme
import com.ranorac.tjtimetable.ui.timetable.CourseDetailSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Renders the remaining screens on the host JVM.
 *
 * Same motivation as [TimetableScreenRenderTest]: these screens were previously only
 * *compiled*, never executed, so a first-render crash would have reached the student's
 * phone before it reached CI. The settings screen is the riskiest of the three because
 * it registers ActivityResult launchers and reads notification state during
 * composition, which is exactly the kind of thing that only fails at runtime.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OtherScreensRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val monday = LocalDate.of(2026, 3, 2)

    private val term = TermCalendar(
        calendarId = "122",
        name = "2025-2026学年度第2学期",
        year = 2025,
        term = 2,
        firstWeekStart = monday,
        endDate = monday.plusWeeks(17),
        totalWeeks = 18,
    )

    // ------------------------------------------------------------- settings

    private fun renderSettings(
        state: SettingsUiState = SettingsUiState(
            credentials = Credentials(clientId = "demo", userId = "1*****6"),
            settings = AppSettings(),
        ),
    ) {
        compose.setContent {
            TJTimetableTheme {
                SettingsScreen(
                    state = state,
                    onSaveCredentials = { _, _, _, _ -> },
                    onImport = {},
                    onRegisterCalendar = {},
                    onThemeChange = {},
                    onShowOddEven = {},
                    onDimInactive = {},
                    onShowWeekend = {},
                    onStartOnToday = {},
                    onRemindersEnabled = {},
                    onReminderLead = {},
                    onImportIcs = {},
                    onExportIcs = {},
                    onOpenLogin = {},
                    onImportPasted = {},
                    onSignOut = {},
                    onOpenCalendarPicker = {},
                    onDismissCalendarPicker = {},
                    onChooseCalendar = {},
                    onCalendarScope = {},
                    onRemoveFromCalendar = {},
                    onConsumeMessage = {},
                )
            }
        }
    }

    @Test
    fun `settings screen renders every section`() {
        renderSettings()

        // One assertion per section: a section that fails to compose fails here.
        // The import section leads the screen because it is the route almost every student
        // can actually use; the open-platform one is kept but demoted to the very bottom.
        compose.onNodeWithText("从教务系统导入").assertExists()
        compose.onNodeWithText("开放平台账号（需机构授权）").assertExists()
        compose.onNodeWithText("课表文件（.ics）").assertExists()
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithText("关于").assertExists()
        // All three pages carry a title AND a subtitle, so no page looks like the odd one out.
        compose.onNodeWithText("功能与配置").assertExists()

        // 校历与调休 moved to the 调休 page, so its controls must NOT be reachable here.
        compose.onNodeWithText("校历与调休").assertDoesNotExist()
        compose.onNodeWithText("粘贴通知原文").assertDoesNotExist()
    }

    @Test
    fun `settings screen renders its action buttons and text fields`() {
        renderSettings()

        compose.onNodeWithText("打开教务系统登录").assertExists()
        compose.onNodeWithText("从 .ics 导入").assertExists()
        compose.onNodeWithText("导出为 .ics").assertExists()
        compose.onNodeWithText("注册到系统日历").assertExists()
        // Seeded from the supplied credentials.
        compose.onNodeWithText("demo").assertExists()
    }

    @Test
    fun `settings screen offers a calendar to register into`() {
        renderSettings()

        // An app cannot create a calendar (only sync adapters may write account_name), so
        // the screen must offer the device's own calendars rather than a hidden default.
        compose.onNodeWithText("目标日历").assertExists()
        compose.onNodeWithText("选择日历").assertExists()
        compose.onNodeWithText("还没有选择。请挑一个已有的日历，课表会写进去").assertExists()
        // 写入范围 defaults to 仅本周, so the button does not flood a personal calendar with
        // a whole semester on the first tap.
        compose.onNodeWithText("写入范围").assertExists()
        compose.onNodeWithText("仅本周").assertExists()
        compose.onNodeWithText("整学期").assertExists()
        // And there is a way back out that does not involve deleting twenty rows by hand.
        compose.onNodeWithText("从日历中移除课表").assertExists()
    }

    @Test
    fun `the calendar chooser lists the device's calendars`() {
        renderSettings(
            SettingsUiState(
                credentials = Credentials(),
                // The stored target deliberately differs from every option, so the
                // assertion below can only be satisfied by the dialog's own rows.
                settings = AppSettings(calendarId = 1, calendarName = "之前的日历"),
                pickingCalendar = true,
                calendarOptions = listOf(
                    DeviceCalendar(42, "我的日历", "me@example.com", "com.google", isPrimary = true),
                    DeviceCalendar(7, "本地日历", null, "LOCAL", isPrimary = false),
                ),
            ),
        )

        compose.onNodeWithText("注册到哪个日历").assertExists()
        compose.onNodeWithText("我的日历").assertExists()
        compose.onNodeWithText("本地日历").assertExists()
        compose.onNodeWithText("账户：me@example.com").assertExists()
    }

    // ------------------------------------------------------------ bottom bar

    @Test
    fun `bottom bar shows the current page as a name and the others as icons`() {
        compose.setContent {
            TJTimetableTheme { AppBottomBar(selected = 0, onSelect = {}) }
        }

        // The current destination is the text; the other two are icons that carry the same
        // word as their content description, so nothing is hidden from TalkBack either.
        compose.onNodeWithText("课表").assertExists()
        compose.onNodeWithContentDescription("调休").assertExists()
        compose.onNodeWithContentDescription("设置").assertExists()
        // One line of content instead of Material3 NavigationBar's icon-over-label stack:
        // 44dp, which is what this gives back to the timetable while staying within 4dp of
        // Android's 48dp minimum touch target.
        compose.onNodeWithTag(BOTTOM_BAR_TAG).assertHeightIsEqualTo(44.dp)
    }

    @Test
    fun `settings screen renders the reminders section with settings applied`() {
        renderSettings(
            SettingsUiState(
                credentials = Credentials(),
                settings = AppSettings(
                    remindersEnabled = true,
                    reminderLeadMinutes = 10,
                    themeMode = "dark",
                    showWeekend = false,
                ),
            ),
        )

        compose.onNodeWithText("上课提醒").assertExists()
        compose.onNodeWithText("提前提醒时间").assertExists()
        compose.onNodeWithText("10 分").assertExists()
    }

    // ----------------------------------------------------------- 调休 editor

    private fun adjustmentState(): AdjustmentUiState {
        val holidays = ScheduleAdjustmentSet.fromApiCalendar(
            mapOf(
                "2026-03-02" to "1", // Monday of week 1: 节假日
                "2026-03-07" to "2", // Saturday: a 补课 workday
            ),
        ).with(
            DayAdjustment(
                date = LocalDate.of(2026, 3, 7),
                kind = CalendarDayKind.WORKDAY,
                followsWeekday = DayOfWeek.WEDNESDAY,
                source = AdjustmentSource.INFERRED,
                note = "推测补 3/4（周三）的课",
            ),
        )

        val week1 = WeekRow(
            week = 1,
            label = "3.2 – 3.8",
            days = (1..7).map { i ->
                val date = monday.plusDays((i - 1).toLong())
                DayRow(
                    date = date,
                    adjustment = holidays.get(date),
                    effectiveWeekday = holidays.effectiveWeekday(date),
                )
            },
        )
        return AdjustmentUiState(
            loading = false,
            term = term,
            weeks = listOf(week1),
            manualCount = holidays.all.count { it.source == AdjustmentSource.MANUAL },
            inferredCount = holidays.all.count { it.source == AdjustmentSource.INFERRED },
            offCount = week1.days.count { !it.isTeaching },
        )
    }

    private fun renderAdjustments(state: AdjustmentUiState = adjustmentState()) {
        compose.setContent {
            TJTimetableTheme {
                AdjustmentScreen(
                    state = state,
                    onSetNormal = {},
                    onSetNoClasses = {},
                    onSetFollows = { _, _ -> },
                    onResetToAuto = {},
                    onRefresh = {},
                    onApplyNotice = {},
                    onConsumeMessage = {},
                )
            }
        }
    }

    @Test
    fun `adjustment screen renders the term, summary and week rows`() {
        renderAdjustments()

        compose.onNodeWithText("调休与校历").assertIsDisplayed()
        compose.onNodeWithText("2025-2026学年度第2学期").assertExists()
        compose.onNodeWithText("教学周").assertExists()
        compose.onNodeWithText("停课日").assertExists()
        // The day list is a LazyColumn, so a week further down the term is only composed
        // once it is scrolled to — the same thing the student does.
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("第 1 周"))
        compose.onNodeWithText("第 1 周").assertExists()
    }

    @Test
    fun `adjustment screen owns the 校历与调休 controls that used to live in settings`() {
        renderAdjustments()

        // The refresh button and the notice parser moved here from 设置; both are the
        // controls a student needs while looking at a wrong 推测补课 row.
        compose.onNodeWithText("校历与调休").assertExists()
        compose.onNodeWithText("刷新校历与调休").assertExists()
        // The paste box is collapsed by default so the day rows stay reachable, and the
        // toggle has to actually reveal it.
        compose.onNodeWithText("粘贴通知原文").assertDoesNotExist()
        compose.onNodeWithText("粘贴通知").performClick()
        compose.onNodeWithText("粘贴通知原文").assertExists()
        compose.onNodeWithText("识别并应用").assertExists()
    }

    @Test
    fun `adjustment screen marks an inferred makeup day`() {
        renderAdjustments()

        // The summary must tell the student these are guesses, because a wrong 补课
        // silently produces a wrong timetable. Asserted first: it lives in the first
        // LazyColumn item, which scrolls out of composition once the list moves.
        compose.onNodeWithText("有 1 天是自动推测的补课，建议核对教务处通知").assertExists()

        // The day row composes to ONE merged semantics node carrying the date, what it
        // inferred and the source badge — "3/7 周六, 按周三课表上课, 推测". Asserting the
        // description is the strongest single proof that an INFERRED 补课 was handled
        // correctly, since that text is derived from the adjustment's own
        // followsWeekday rather than echoed from the input.
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("按周三课表上课", substring = true))
        compose.onNodeWithText("按周三课表上课", substring = true).assertExists()
    }

    @Test
    fun `adjustment screen prompts for an import when there is no term`() {
        renderAdjustments(AdjustmentUiState(loading = false, term = null))

        compose.onNodeWithText("请先在设置中导入课表，以获得学期信息").assertIsDisplayed()
    }

    // -------------------------------------------------------- course detail

    @Test
    fun `course detail sheet renders the course and its actions`() {
        val course = Course(
            id = 1,
            name = "数据结构",
            courseCode = "101019",
            classCode = "10016502",
            className = "01班",
            teacher = "张老师",
            credits = 4.0,
            campus = "四平路校区",
        )
        val session = CourseSession(
            id = 1,
            courseId = 1,
            dayOfWeek = DayOfWeek.MONDAY,
            startUnit = 5,
            endUnit = 6,
            weeks = WeekPattern.parse("[1-17单]"),
            room = "南101",
        )
        val occurrence = ClassOccurrence(
            course = course,
            session = session,
            date = monday,
            week = 1,
            startTime = LocalTime.of(13, 30),
            endTime = LocalTime.of(15, 5),
        )

        compose.setContent {
            TJTimetableTheme {
                CourseDetailSheet(
                    course = course,
                    occurrence = occurrence,
                    sessions = listOf(session),
                    onDismiss = {},
                    onSetColor = {},
                    onSetHidden = {},
                    onSetNote = {},
                )
            }
        }

        compose.onNodeWithText("数据结构").assertExists()
        compose.onNodeWithText("张老师 · 01班 · 四平路校区").assertExists()
        compose.onNodeWithText("课程信息").assertExists()
        compose.onNodeWithText("总周").assertDoesNotExist() // guards against stray labels
        compose.onNodeWithText("隐藏这门课").assertExists()
    }

    @Test
    fun `course detail sheet renders a hidden course as restorable`() {
        val course = Course(id = 9, name = "被隐藏的课", hidden = true)
        val timetable = Timetable(term, listOf(course), emptyList(), ScheduleAdjustmentSet.EMPTY)
        check(timetable.course(9) != null)

        compose.setContent {
            TJTimetableTheme {
                CourseDetailSheet(
                    course = course,
                    occurrence = null,
                    sessions = emptyList(),
                    onDismiss = {},
                    onSetColor = {},
                    onSetHidden = {},
                    onSetNote = {},
                )
            }
        }

        // With no occurrence the sheet must still compose (the "this week" block is
        // conditional, which is exactly where a null assumption would crash).
        compose.onNodeWithText("被隐藏的课").assertExists()
        compose.onNodeWithText("恢复显示这门课").assertExists()
    }
}
