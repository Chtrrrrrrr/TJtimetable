package com.ranorac.tjtimetable.scrape

import com.ranorac.tjtimetable.domain.WeekPattern

/** Port of `Weeks.cs`'s `WeekFilter`. */
enum class WeekFilter { ALL, ODD, EVEN }

/**
 * 周次位掩码工具 — port of `gzy31007/TJDesktopTimetable`'s `dotnet/TjtCore/Weeks.cs`,
 * reconciled onto this app's [WeekPattern].
 *
 * 约定：bit0 = 第 1 周（与同济教务 `weekState` 一致，实测 65535 = 第 1-16 周），
 * 这正是 [WeekPattern] 的约定，所以两边的掩码可以互相搬运。
 *
 * ### 为什么仍然单独移植一份
 * [WeekPattern] 是领域类型，服务的是「这门课第几周上」；`Weeks` 是适配器侧的**取值约定**
 * （32 位上限、`max(totalWeeks, 最高位)` 的展示范围、与既有 Python `fmt_weeks` 逐字一致的
 * `1-16` / `2, 4, 6` 标签）。把这些搬进领域类型会把参考实现的限制（`uint` 只有 32 位）
 * 固化到整个应用里，所以它们留在这里。差异逐条记在 [formatLabel] / [toWeeks] / [isAll] 上。
 */
object Weeks {

    /**
     * 第 1..32 周的合法掩码上限（国内高校一学期 16–23 周，32 足够）。
     *
     * This is the reference's `uint` width, not a fact about 同济: [WeekPattern] itself
     * holds 64 weeks, so [fromWeeks] drops 33..64 **only** to stay bit-for-bit with the
     * reference. Use [WeekPattern.of] directly when the caller wants all 64.
     */
    const val MAX_WEEKS = 32

    /** 由周次列表构造掩码。非法周次（越界）被忽略。 */
    fun fromWeeks(weeks: Iterable<Int>): WeekPattern =
        WeekPattern.of(weeks.filter { it in 1..MAX_WEEKS })

    /** 掩码里出现过的最大周次（空掩码为 0）。 */
    fun highest(mask: WeekPattern): Int {
        for (week in MAX_WEEKS downTo 1) {
            if (mask.contains(week)) return week
        }
        return 0
    }

    /**
     * 掩码 → 周次列表。展示范围取 `max(totalWeeks, 掩码最高位)`，
     * 这样掩码里出现第 17 周时不会因为校历写 16 周而丢数据。
     */
    fun toWeeks(mask: WeekPattern, totalWeeks: Int = 16): List<Int> {
        val limit = maxOf(totalWeeks, highest(mask))
        if (limit <= 0) return emptyList()
        return (1..limit).filter { mask.contains(it) }
    }

    /**
     * 掩码 → 人类可读标签，例如 `1-16`、`2, 4, 6`、`11-14`。
     *
     * 输出格式必须与既有 Python 实现 `fmt_weeks` 完全一致（区间用 `-`、分隔用 `, `），
     * 这是与既有数据做黄金对比的前提。**注意它与 [WeekPattern.display] 不同**：
     * `Weeks.formatLabel` 给 `1-16`，`WeekPattern.display` 给 `1-16周` / `第3周` / `1-5,7-9周(单)`，
     * 空掩码这里是 `-`、那里是 `无`。两者服务不同场合，谁也不要替换谁。
     */
    fun formatLabel(mask: WeekPattern, totalWeeks: Int = 16): String {
        val weeks = toWeeks(mask, totalWeeks)
        if (weeks.isEmpty()) return "-"

        val parts = mutableListOf<String>()
        var start = weeks[0]
        var prev = weeks[0]
        for (i in 1 until weeks.size) {
            val week = weeks[i]
            if (week == prev + 1) {
                prev = week
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = week
            prev = week
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(", ")
    }

    /** 学期全周掩码，例如 16 周 → 0xFFFF。 */
    fun fullMask(totalWeeks: Int = 16): WeekPattern = fromWeeks(1..totalWeeks)

    fun oddMask(totalWeeks: Int = 16): WeekPattern =
        fromWeeks((1..totalWeeks).filter { it % 2 == 1 })

    fun evenMask(totalWeeks: Int = 16): WeekPattern =
        fromWeeks((1..totalWeeks).filter { it % 2 == 0 })

    /** 周次过滤器 → 掩码；[WeekFilter.ALL] 返回 `null` 表示不过滤。 */
    fun resolveFilter(filter: WeekFilter, totalWeeks: Int = 16): WeekPattern? = when (filter) {
        WeekFilter.ODD -> oddMask(totalWeeks)
        WeekFilter.EVEN -> evenMask(totalWeeks)
        WeekFilter.ALL -> null
    }

    /** 两个掩码是否有交集（判断同一格的两门课在周次上是否真的撞车）。 */
    fun overlap(a: WeekPattern, b: WeekPattern): Boolean = (a.bits and b.bits) != 0L

    /** 掩码是否覆盖整个学期（例如 16 周课表的 0xFFFF）——「全周上课」判定。 */
    fun isAll(mask: WeekPattern, totalWeeks: Int = 16): Boolean =
        mask.isNotEmpty && mask == fullMask(totalWeeks)

    /**
     * 掩码里的周数。The reference counts weeks 1..[MAX_WEEKS] only, so a pattern carrying
     * weeks 33..64 (which [WeekPattern] can express but the reference cannot) counts as if
     * those weeks were absent; [WeekPattern.count] counts them.
     */
    fun count(mask: WeekPattern): Int = (1..MAX_WEEKS).count { mask.contains(it) }
}
