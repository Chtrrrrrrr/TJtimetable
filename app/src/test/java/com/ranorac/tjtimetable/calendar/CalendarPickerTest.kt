package com.ranorac.tjtimetable.calendar

import com.ranorac.tjtimetable.domain.TermCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins which calendars the chooser offers, and in what order.
 *
 * This decides where a student's whole semester is written, and it is the part of the
 * calendar feature that can be tested without a device — the `ContentResolver` round trip
 * around it is not where the bugs were.
 */
class CalendarPickerTest {

    private fun row(
        id: Long,
        name: String? = "日历$id",
        account: String? = "me@example.com",
        type: String? = "com.google",
        access: Int = ACCESS_CONTRIBUTOR,
        primary: Boolean = false,
        visible: Boolean = true,
        deleted: Boolean = false,
    ) = CalendarRow(
        id = id,
        displayName = name,
        accountName = account,
        accountType = type,
        accessLevel = access,
        isPrimary = primary,
        visible = visible,
        deleted = deleted,
    )

    @Test
    fun `read-only calendars are not offered`() {
        // A subscribed 校历 cannot receive events; offering it would only produce a failure
        // two taps later.
        val picked = pickerCalendars(
            listOf(
                row(id = 1, name = "校历", access = 200),
                row(id = 2, name = "我的日历", access = ACCESS_CONTRIBUTOR),
            ),
        )

        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun `deleted calendars are not offered`() {
        val picked = pickerCalendars(
            listOf(
                row(id = 1, name = "已删除", deleted = true),
                row(id = 2, name = "在用的"),
            ),
        )

        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun `the primary calendar comes first`() {
        val picked = pickerCalendars(
            listOf(
                row(id = 1, name = "A 日历", primary = false),
                row(id = 2, name = "Z 日历", primary = true),
            ),
        )

        assertEquals(listOf(2L, 1L), picked.map { it.id })
    }

    @Test
    fun `visible calendars come before hidden ones, then by name`() {
        val picked = pickerCalendars(
            listOf(
                row(id = 1, name = "隐藏的", visible = false),
                row(id = 2, name = "Beta"),
                row(id = 3, name = "alpha"),
            ),
        )

        assertEquals(listOf(3L, 2L, 1L), picked.map { it.id })
    }

    @Test
    fun `when no calendar is graded writable the full list is offered instead of nothing`() {
        // Some OEM providers report access level 0 for a calendar the app can in fact write
        // to. Offering something that might fail beats offering an empty dialog.
        val picked = pickerCalendars(
            listOf(
                row(id = 1, name = "本地日历", account = null, type = LOCAL_ACCOUNT_TYPE, access = 0),
            ),
        )

        assertEquals(listOf(1L), picked.map { it.id })
    }

    @Test
    fun `an unnamed calendar still gets a label`() {
        val picked = pickerCalendars(listOf(row(id = 7, name = "   ")))

        assertEquals("未命名日历", picked.single().displayName)
    }

    @Test
    fun `the account line names the account, and falls back to the type for a local calendar`() {
        val account = pickerCalendars(listOf(row(id = 1, name = "工作", account = "work@corp.com")))
        assertEquals("work@corp.com", account.single().accountLabel)

        // A local calendar has no account name, and "LOCAL" means nothing to a student, so
        // no account line is shown (the picker's own fallback is 本地日历).
        val local = pickerCalendars(
            listOf(row(id = 2, name = "本地日历", account = null, type = LOCAL_ACCOUNT_TYPE)),
        )
        assertNull(local.single().accountLabel)
    }

    @Test
    fun `an account that merely repeats the display name is not shown twice`() {
        val picked = pickerCalendars(
            listOf(row(id = 1, name = "我的日历", account = "我的日历", type = "com.google")),
        )

        // Falling through to the type is more informative than repeating the name.
        assertEquals("com.google", picked.single().accountLabel)
    }

    @Test
    fun `an empty provider answer yields no rows rather than a crash`() {
        assertTrue(pickerCalendars(emptyList()).isEmpty())
    }
}

/**
 * Pins what "仅本周" covers.
 *
 * The default scope now writes only the current teaching week, so this arithmetic decides
 * how much of a student's calendar gets filled — and an off-by-one week here would silently
 * register the wrong dates.
 */
class RegistrationRangeTest {

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

    @Test
    fun `the current week covers Monday to Sunday of the week today is in`() {
        // Wednesday of week 2.
        val (from, to) = registrationRange(term, CalendarScope.CURRENT_WEEK, monday.plusDays(9))

        assertEquals(monday.plusWeeks(1), from)
        assertEquals(monday.plusWeeks(1).plusDays(6), to)
    }

    @Test
    fun `the first day of a week still belongs to that week`() {
        val (from, to) = registrationRange(term, CalendarScope.CURRENT_WEEK, monday.plusWeeks(3))

        assertEquals(monday.plusWeeks(3), from)
        assertEquals(monday.plusWeeks(3).plusDays(6), to)
    }

    @Test
    fun `the last day of a week still belongs to that week`() {
        val (from, to) = registrationRange(term, CalendarScope.CURRENT_WEEK, monday.plusDays(6))

        assertEquals(monday, from)
        assertEquals(monday.plusDays(6), to)
    }

    @Test
    fun `a day outside the term falls back to the first week instead of failing`() {
        val (from, to) = registrationRange(term, CalendarScope.CURRENT_WEEK, LocalDate.of(2026, 8, 1))

        assertEquals(monday, from)
        assertEquals(monday.plusDays(6), to)
    }

    @Test
    fun `the whole term scope keeps the term's own bounds`() {
        val (from, to) = registrationRange(term, CalendarScope.WHOLE_TERM, monday.plusDays(9))

        assertEquals(term.firstWeekStart, from)
        assertEquals(term.endDate, to)
    }
}
