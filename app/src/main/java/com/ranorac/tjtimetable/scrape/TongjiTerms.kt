package com.ranorac.tjtimetable.scrape

/** Port of `TongjiTerms.cs`'s `TermPreset(CalendarId, Name, Year, TermNo, StartDate, TotalWeeks)`. */
data class TermPreset(
    val calendarId: String,
    val name: String,
    val year: Int,
    val termNo: Int,
    val startDate: String,
    val totalWeeks: Int,
)

/**
 * 同济学期表（内置快照）。
 *
 * Ported from `gzy31007/TJDesktopTimetable`'s `dotnet/TjtCore/TongjiTerms.cs`, including its
 * reason for existing: 个人课表响应里只带 `calendarId` / `calendarName`，没有开学日期，而
 * 「当前第几周」必须要它；校历接口需要复杂参数（实测 POST 返回「系统繁忙」）。所以把已知学期
 * 做成内置表：命中即用，未命中则退化为「16 周 + 内置节次、不显示周次」。
 *
 * 数据来源：1 系统校历接口响应快照（`dotnet/fixtures/tongji-school-calendar.json`）。
 *
 * **Nothing here is inferred**: the two presets (122 / 124) are the reference's, verbatim.
 * When 教务 adds a semester this table — and the reference's own — must be extended.
 */
object TongjiTerms {

    val PRESETS: List<TermPreset> = listOf(
        TermPreset("122", "2026-2027学年第1学期", 2026, 1, "2026-09-14", 16),
        TermPreset("124", "2027-2028学年第1学期", 2027, 1, "2027-09-06", 16),
    )

    /** 按 calendarId 找内置学期；接受字符串（教务两种都出现过）。 */
    fun findPreset(calendarId: String?): TermPreset? {
        if (calendarId.isNullOrEmpty()) return null
        val wanted = calendarId.trim()
        return PRESETS.firstOrNull { it.calendarId == wanted }
    }

    /** 按 calendarId 找内置学期（数字形态的重载）。 */
    fun findPreset(calendarId: Int?): TermPreset? =
        calendarId?.let { findPreset(it.toString()) }

    /** 由内置学期表构造 [Term]（节次时间用同济默认表）。 */
    fun termFromPreset(preset: TermPreset): Term = Term(
        id = preset.calendarId,
        name = preset.name,
        year = preset.year,
        termNo = preset.termNo,
        totalWeeks = preset.totalWeeks,
        slots = TimetableDefaults.TONGJI_SLOTS.toList(),
        startDate = preset.startDate,
    )
}
