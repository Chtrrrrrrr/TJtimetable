package com.ranorac.tjtimetable.data.remote

import com.ranorac.tjtimetable.domain.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the open-platform field mapping, which is the one import path with no device and no
 * network in it.
 *
 * The `weeks` / `weekNum` / `weekstr` trio is the reason this file exists. The server returns
 * up to three representations of the same fact and any of them can be the only one present,
 * so the fallback order is load-bearing: getting it wrong either *widens a course to the whole
 * term* (wrong weeks drawn, spurious reminders) or drops weeks it does have. The resolution
 * itself lives in [WeekPattern.fromApiFields]; what is pinned here is that this mapper actually
 * hands it all three fields.
 */
class TongjiImportTest {

    private val fullTerm = WeekPattern.range(1, 18)

    private fun entry(
        weekNum: String? = null,
        weekstr: String? = null,
        weeks: List<Int>? = null,
    ) = TimeTableEntryDto(
        dayOfWeek = 3,
        timeStart = 3,
        timeEnd = 4,
        weekNum = weekNum,
        weekstr = weekstr,
        weeks = weeks,
    )

    private fun dto(entry: TimeTableEntryDto) = StudentCourseDto(
        teachingClassId = 2001,
        courseName = "线性代数",
        timeTableList = listOf(entry),
    )

    private fun importedWeek(entry: TimeTableEntryDto): WeekPattern =
        TongjiImport.fromRealtime(listOf(dto(entry)), fullTerm)
            .sessionsByCourse
            .getValue(0)
            .single()
            .weeks

    @Test
    fun `the resolved weeks array wins over both strings`() {
        val weeks = importedWeek(entry(weekNum = "[1-17]", weekstr = "[1-17]", weeks = listOf(3, 5)))
        assertEquals(listOf(3, 5), weeks.weekNumbers)
    }

    @Test
    fun `weekNum is parsed when the array is missing`() {
        assertEquals(WeekPattern.of(1, 3, 5), importedWeek(entry(weekNum = "[1-5单]")))
    }

    @Test
    fun `weekstr is parsed when weekNum is missing`() {
        // FIXED: the third field used to be passed as `null`, so a payload whose only week
        // field is `weekstr` was silently widened to the whole term by the fallback below.
        val weeks = importedWeek(entry(weekstr = "[1-5单]"))
        assertEquals(WeekPattern.of(1, 3, 5), weeks)
        assertTrue("must not be widened to the term", weeks != fullTerm)
    }

    @Test
    fun `weekstr is parsed when weekNum is unparseable`() {
        // An empty-looking `weekNum` is exactly how the server spells "look at weekstr".
        val weeks = importedWeek(entry(weekNum = "[]", weekstr = "1-4"))
        assertEquals(WeekPattern.of(1, 2, 3, 4), weeks)
        assertTrue(weeks != fullTerm)
    }

    @Test
    fun `an unreadable week field widens to the term and says so`() {
        // The documented last resort: never drop a class because a week string was odd. What
        // matters here is that the widening is still reported rather than silent, and that it
        // only happens once every representation has been tried.
        val imported = TongjiImport.fromRealtime(listOf(dto(entry(weekNum = "???", weekstr = "???"))), fullTerm)
        val session = imported.sessionsByCourse.getValue(0).single()

        assertEquals(fullTerm, session.weeks)
        assertEquals("???", session.rawWeeks)
        assertTrue(
            "the guess must be auditable in the warnings: ${imported.warnings}",
            imported.warnings.any { it.contains("???") },
        )
    }

    @Test
    fun `a slot with an incomplete time span is skipped rather than guessed`() {
        val broken = StudentCourseDto(
            teachingClassId = 2002,
            courseName = "大学物理",
            timeTableList = listOf(TimeTableEntryDto(dayOfWeek = 3, timeStart = 5, timeEnd = null)),
        )
        val imported = TongjiImport.fromRealtime(listOf(broken), fullTerm)

        assertTrue(imported.sessionsByCourse.getValue(0).isEmpty())
        assertTrue(imported.warnings.any { it.contains("时间不完整") })
    }
}
