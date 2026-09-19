package com.ranorac.tjtimetable.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置学期表验收 — port of `TongjiTerms.cs` (there is no dedicated reference test file for it;
 * the expectations come from `TongjiReportFormatTests.cs` / `E2ETimetableTests.cs`, which assert
 * on the term that the built-in table produces).
 */
class TongjiTermsTest {

    @Test
    fun theBuiltInPresetsAreTheReferencesVerbatim() {
        assertEquals(2, TongjiTerms.PRESETS.size)

        val p122 = TongjiTerms.PRESETS.first { it.calendarId == "122" }
        assertEquals("2026-2027学年第1学期", p122.name)
        assertEquals(2026, p122.year)
        assertEquals(1, p122.termNo)
        assertEquals("2026-09-14", p122.startDate)
        assertEquals(16, p122.totalWeeks)

        val p124 = TongjiTerms.PRESETS.first { it.calendarId == "124" }
        assertEquals("2027-2028学年第1学期", p124.name)
        assertEquals("2027-09-06", p124.startDate)
    }

    @Test
    fun presetsAreFoundByStringAndByNumber() {
        assertSame(TongjiTerms.PRESETS[0], TongjiTerms.findPreset("122"))
        // Whitespace is trimmed, like the reference's `calendarId.Trim()`.
        assertEquals("122", TongjiTerms.findPreset(" 122 ")!!.calendarId)
        assertEquals("122", TongjiTerms.findPreset(122)!!.calendarId)
        assertNull(TongjiTerms.findPreset("999"))
        assertNull(TongjiTerms.findPreset(""))
        assertNull(TongjiTerms.findPreset(null as String?))
        assertNull(TongjiTerms.findPreset(null as Int?))
    }

    @Test
    fun aPresetBecomesATermWithTheDefaultTongjiSlots() {
        val term = TongjiTerms.termFromPreset(TongjiTerms.findPreset("122")!!)

        assertEquals("122", term.id)
        assertEquals("2026-2027学年第1学期", term.name)
        assertEquals(2026, term.year)
        assertEquals(1, term.termNo)
        assertEquals(16, term.totalWeeks)
        assertEquals("2026-09-14", term.startDate)
        assertEquals(11, term.slots.size)
        assertEquals("08:00", term.slotBegin(1))
        assertEquals("08:45", term.slotEnd(1))
        assertEquals("", term.slotBegin(99))
        assertEquals("2026-2027学年第1学期", term.label)
    }

    @Test
    fun theLabelFallsBackToTheYearAndTerm() {
        val term = Term("7", "", 2027, 2, 16, TimetableDefaults.makeDefaultSlots())
        assertEquals("2027-2028学年第2学期", term.label)
        assertEquals("未知学期", Term("x", "", 0, 0, 16, emptyList()).label)
    }

    @Test
    fun thePresetTermConvertsToTheAppsCalendar() {
        val calendar = TongjiTerms.termFromPreset(TongjiTerms.findPreset("122")!!).toTermCalendar()

        assertTrue(calendar != null)
        assertEquals("122", calendar!!.calendarId)
        assertEquals(16, calendar.totalWeeks)
        assertEquals(16, calendar.weeks.size)
        // 第 1 周周三 = 2026-09-16, the date the reference's own E2E test uses as "today".
        assertEquals("2026-09-16", calendar.dateOf(1, java.time.DayOfWeek.WEDNESDAY).toString())
        // 16 teaching weeks from 2026-09-14: the last week starts 2026-12-28.
        assertEquals("2026-12-28", calendar.weekStart(16).toString())
        assertEquals("2027-01-03", calendar.weekEnd(16).toString())
    }
}
