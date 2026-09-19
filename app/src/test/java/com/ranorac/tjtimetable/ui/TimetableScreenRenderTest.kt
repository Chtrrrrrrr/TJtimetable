package com.ranorac.tjtimetable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ranorac.tjtimetable.data.prefs.AppSettings
import com.ranorac.tjtimetable.domain.Course
import com.ranorac.tjtimetable.domain.CourseSession
import com.ranorac.tjtimetable.domain.ScheduleAdjustmentSet
import com.ranorac.tjtimetable.domain.TermCalendar
import com.ranorac.tjtimetable.domain.Timetable
import com.ranorac.tjtimetable.domain.TimetableResolver
import com.ranorac.tjtimetable.domain.WeekPattern
import com.ranorac.tjtimetable.ui.theme.TJTimetableTheme
import com.ranorac.tjtimetable.ui.timetable.TimetableScreen
import com.ranorac.tjtimetable.ui.timetable.TimetableUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Renders the timetable on the host JVM and asserts what a student would see.
 *
 * This exists because until now **no composable had ever been executed**: the whole
 * UI was verified only by compiling. A first-render crash — a null dereference, a
 * bad layout modifier, a missing string — would therefore have surfaced for the
 * first time on the student's phone. Robolectric executes the real Compose
 * pipeline, so these assertions catch that class of failure in CI instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimetableScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

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

    /** Two courses: one every week, one 单周-only so a parity badge must appear. */
    private fun timetable(): Timetable {
        val everyWeek = Course(id = 1, name = "数据结构", teacher = "张老师", classCode = "10016502")
        val oddOnly = Course(id = 2, name = "大学英语", teacher = "李老师")

        val sessions = listOf(
            CourseSession(
                id = 1,
                courseId = 1,
                dayOfWeek = DayOfWeek.MONDAY,
                startUnit = 1,
                endUnit = 2,
                weeks = WeekPattern.parse("1-17"),
                room = "南101",
            ),
            CourseSession(
                id = 2,
                courseId = 2,
                dayOfWeek = DayOfWeek.WEDNESDAY,
                startUnit = 5,
                endUnit = 7,
                weeks = WeekPattern.parse("[1-17单]"),
                room = "北203",
            ),
        )
        return Timetable(
            term = term,
            courses = listOf(everyWeek, oddOnly),
            sessions = sessions,
            adjustments = ScheduleAdjustmentSet.EMPTY,
        )
    }

    private fun state(
        timetable: Timetable,
        week: Int = 1,
        settings: AppSettings = AppSettings(),
    ) = TimetableUiState(
        loading = false,
        timetable = timetable,
        week = week,
        settings = settings,
    )

    private fun render(state: TimetableUiState) {
        compose.setContent {
            TJTimetableTheme {
                // The bottom bar is part of the real window, so the grid's available height
                // is only representative if something occupies those 48dp. Standing one in
                // here keeps this test honest about what a student's phone looks like.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { TimetableScreenBody(state) }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    @Composable
    private fun TimetableScreenBody(state: TimetableUiState) {
        TimetableScreen(
            state = state,
            onStepWeek = {},
            onJumpToToday = {},
            onImport = {},
            onConsumeMessage = {},
            onCourseClick = {},
        )
    }

    @Test
    fun `renders the term, the week and every weekday column`() {
        render(state(timetable()))

        compose.onNodeWithText("2025-2026学年度第2学期").assertIsDisplayed()
        compose.onNodeWithText("第 1 周").assertIsDisplayed()
        // Monday..Sunday headers, so a layout regression that drops a column fails here.
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            compose.onNodeWithText(day).assertExists()
        }
    }

    @Test
    fun `renders a course block with its name and room`() {
        render(state(timetable()))

        compose.onNodeWithText("数据结构").assertIsDisplayed()
        compose.onNodeWithText("南101").assertIsDisplayed()
    }

    @Test
    fun `a multi-节 block also carries the teacher`() {
        render(state(timetable()))

        // 数据结构 is a 2-节 block, so there is room for the teacher under the 教室 —
        // 大学英语 likewise. A single-节 block keeps it in the detail sheet instead.
        compose.onNodeWithText("张老师").assertExists()
        compose.onNodeWithText("李老师").assertExists()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun `the gutter shows each 节's start and end time`() {
        render(state(timetable()))

        // 节 1 runs 08:00–08:45 in 同济's 作息; both ends are expected on a phone-sized row.
        compose.onNodeWithText("08:00").assertIsDisplayed()
        compose.onNodeWithText("08:45").assertIsDisplayed()
        compose.onNodeWithText("20:10").assertIsDisplayed()
        compose.onNodeWithText("20:55").assertIsDisplayed()
    }

    @Test
    fun `shows the odd-week badge for a 单周 course`() {
        render(state(timetable()))

        // 大学英语 is 1-17单 and spans 3 节, so the badge is rendered.
        compose.onNodeWithText("大学英语").assertExists()
        compose.onNodeWithText("单周").assertExists()
    }

    @Test
    fun `ghosts an off-week course instead of dropping it`() {
        // Week 2 is an even week, so the 单周 course does not run: it must still be
        // present (dimmed) rather than silently disappearing from the grid.
        render(state(timetable(), week = 2))

        compose.onNodeWithText("第 2 周").assertIsDisplayed()
        compose.onNodeWithText("数据结构").assertExists()
        compose.onNodeWithText("大学英语").assertExists()
    }

    @Test
    fun `renders the empty state before any import`() {
        render(TimetableUiState(loading = false, timetable = null))

        compose.onNodeWithText("还没有课表").assertIsDisplayed()
        // The button goes straight into the 教务 login browser, so it must not be labelled
        // in a way that implies a settings detour.
        compose.onNodeWithText("登录教务系统").assertIsDisplayed()
    }

    @Test
    fun `hiding the weekend drops those columns`() {
        render(state(timetable(), settings = AppSettings(showWeekend = false)))

        compose.onNodeWithText("五").assertExists()
        // Saturday and Sunday headers must be gone from the grid.
        compose.onNodeWithText("六").assertDoesNotExist()
        compose.onNodeWithText("日").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun `all eleven 节 fit on a phone without scrolling`() {
        render(state(timetable()))

        // The grid divides the height left after the week row and the weekday header by the
        // number of 节, so the LAST 节 and its start time have to be on screen. While the row
        // height was a fixed 58dp this row sat below the fold on a phone and had to be
        // scrolled to — which is exactly what this asserts is no longer true.
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("20:10").assertIsDisplayed()
    }

    @Test
    fun `offers no return-to-this-week control while already on this week`() {
        render(state(timetable(), week = 1))

        compose.onNodeWithContentDescription("回到本周").assertDoesNotExist()
    }

    @Test
    fun `browsing another week offers a return to this week`() {
        // The control is a back arrow, not a calendar: it *returns* the student to the
        // current week, so the icon must read as navigation rather than as a date picker.
        render(state(timetable(), week = 3).copy(isBrowsingOtherWeek = true))

        compose.onNodeWithContentDescription("回到本周").assertIsDisplayed()
        compose.onNodeWithText("第 3 周").assertIsDisplayed()
    }
}
